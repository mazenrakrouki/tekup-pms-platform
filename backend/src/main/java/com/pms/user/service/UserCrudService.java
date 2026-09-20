package com.pms.user.service;

import com.pms.user.dto.UserCreateResult;
import com.pms.user.dto.UserRequest;
import com.pms.user.dto.UserResponse;
import com.pms.user.entity.User;
import com.pms.user.mapper.UserMapper;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import com.pms.shared.exception.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Administration service for user accounts: list, search, create, edit, reset password,
// (de)activate, soft-delete. Everything an administrator does to SOMEBODY ELSE's account
// goes through here.
//
// Two things to know before reading the methods:
// 1. Soft delete only — delete() sets BaseEntity's "deleted" flag, so every query filters
//    on it, and the email unique rule is a PARTIAL index (V18) so a freed email can be reused.
// 2. Revoking a session (ADR-017) needs two steps: user.revokeAllTokens() bumps
//    tokenVersion so JwtAuthenticationFilter rejects every already-issued token, and
//    evictFromCache(...) drops the matching "email:tokenVersion" cache entry — skipping
//    either half leaves the old session usable for up to five more minutes.
//
// Every method is guarded by @PreAuthorize on the service, naming MANAGE_USERS (a
// permission, never a role name — ADR-001).

@Service
@RequiredArgsConstructor
public class UserCrudService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    // MapStruct mapper (ADR-018); does not copy passwordHash or tokenVersion since
    // UserResponse never declares them.
    private final UserMapper userMapper;
    // BCrypt encoder from SecurityConfig — one-way, so a stored hash can be checked but
    // never turned back into the password.
    private final PasswordEncoder passwordEncoder;
    // Used only to drop entries of the "securityContext" cache when a session is revoked.
    private final CacheManager cacheManager;

    /**
     * Every active user as a plain list — kept next to the paged version for selection
     * widgets that need the whole set at once.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        // JOIN FETCH u.role in findAllActive is required: open-in-view is false, so a lazy
        // role would throw LazyInitializationException once UserMapper reads role.name.
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    /**
     * Same list, one page at a time. Page.map keeps the paging metadata (total elements,
     * page number) intact while converting rows — rebuilding a PageImpl by hand risks a
     * wrong total and an unreachable last page.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(Pageable pageable) {
        return userRepository.findAllActivePaged(pageable).map(userMapper::toResponse);
    }

    /**
     * Paged search with optional filters (free text, role, active status), each null
     * meaning "don't filter on this". Filtering happens in the query, not in Java, so the
     * page's total count stays correct.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> search(String search, Long roleId, Boolean active, Pageable pageable) {
        // Normalized to "" rather than null: the query embeds it in LOWER(CONCAT('%',
        // :search, '%')), and a null parameter there makes PostgreSQL guess "bytea" and
        // fail. trim() also treats whitespace-only input as no search.
        String normalized = (search == null) ? "" : search.trim();
        return userRepository.searchActive(normalized, roleId, active, pageable).map(userMapper::toResponse);
    }

    /**
     * Active users assignable to something (project manager, team member), reachable by
     * anyone who can ASSIGN people without requiring the administrator-only MANAGE_USERS.
     * A separate method from findAll() purely so its guard can be looser.
     */
    // Three codes: ASSIGN_CHEF_PROJET (Director), ASSIGN_DEVELOPER (Director + PMs),
    // MANAGE_USERS (administrator). UserResponse carries no sensitive fields, so widening
    // the audience here doesn't widen what leaks.
    @PreAuthorize("hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAssignable() {
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    /**
     * Returns one account by its id. Throws NotFoundException (404) when unknown or
     * soft-deleted — see loadUser at the bottom of the file.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return userMapper.toResponse(loadUser(id));
    }

    /**
     * Creates a new account and generates its first password, returned once in
     * UserCreateResult alongside the UserResponse — the only moment in the application
     * where a password exists in clear outside the person's head. Only the BCrypt hash is
     * stored. The account starts with firstLogin = true, so FirstLoginFilter blocks every
     * endpoint except change-password until the person sets their own.
     *
     * <p>Throws IllegalArgumentException (409) for an email already taken by an active
     * account, or an unknown role id.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    // Not readOnly: if save failed after the password was generated, the administrator
    // would have been shown a password belonging to no account.
    @Transactional
    public UserCreateResult create(UserRequest request) {
        // "AndDeletedFalse" matters: a soft-deleted account's email must not block a new
        // one (matches the V18 partial unique index). Checked here for a clear message;
        // the index is the real guarantee against a race between two administrators.
        if (userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        // Loaded, not just referenced by id, so an unknown role id is reported now instead
        // of failing later on the fk_users_role foreign key with an unreadable DB error.
        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // H-2: always a random password, never a fixed default — a fixed one becomes public
        // knowledge the moment one person learns it.
        String initialPassword = generateSecurePassword();

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                // Only the BCrypt hash is stored.
                .passwordHash(passwordEncoder.encode(initialPassword))
                .active(true)
                // Forces the change-password screen on first connection.
                .firstLogin(true)
                .role(role)
                .build();

        UserResponse created = userMapper.toResponse(userRepository.save(user));
        return new UserCreateResult(created, initialPassword);
    }

    /**
     * Generates a 12-character random password with at least one upper case letter, one
     * lower case letter, one digit and one special character. Written here rather than
     * pulled from a library so it stays in lockstep with the change-password screen's
     * policy — a mismatch would create a password the person couldn't re-enter.
     */
    private static String generateSecurePassword() {
        // SecureRandom, not java.util.Random: Random's sequence is predictable from one
        // observed value, which would let an attacker guess the next colleague's password.
        SecureRandom rng = new SecureRandom();
        String upper   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower   = "abcdefghijklmnopqrstuvwxyz";
        String digits  = "0123456789";
        // Deliberately small: a wider set risks characters awkward on a French keyboard.
        String special = "@#$%!";
        String all     = upper + lower + digits + special;

        // A List, not a StringBuilder, because the four guaranteed characters below are
        // shuffled afterward.
        List<Character> chars = new ArrayList<>(12);
        // One character from each family guarantees the policy is met — a purely random
        // draw could otherwise produce an all-lowercase password the screen would reject.
        chars.add(upper  .charAt(rng.nextInt(upper.length())));
        chars.add(lower  .charAt(rng.nextInt(lower.length())));
        chars.add(digits .charAt(rng.nextInt(digits.length())));
        chars.add(special.charAt(rng.nextInt(special.length())));
        for (int i = 4; i < 12; i++) {
            chars.add(all.charAt(rng.nextInt(all.length())));
        }
        // Without shuffling, every password would follow the same
        // upper-lower-digit-special shape, halving the effective search space for an attacker.
        Collections.shuffle(chars, rng);
        StringBuilder sb = new StringBuilder(12);
        chars.forEach(sb::append);
        return sb.toString();
    }

    /**
     * Updates an account's name, e-mail and role. Password untouched — that's
     * resetAccount's job. When the ROLE changes, the existing session is killed
     * immediately, or someone moved from CHEF_PROJET to DEVELOPPEUR would keep the old
     * permissions until their token expired.
     *
     * <p>Throws IllegalArgumentException (409) for an email taken by another active
     * account or an unknown role id, NotFoundException (404) when the account is missing.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    // One unit of work: a crash between revoking tokens and saving would leave the new role
    // active alongside the old, still-valid session.
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = loadUser(id);

        // Only checked when the email actually changes, or a user would collide with itself.
        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // Compared by id, not object: Role doesn't override equals(), so two separately
        // loaded instances of the same row would never be "equal".
        boolean roleChanged = !user.getRole().getId().equals(request.roleId());
        // Captured before setEmail()/revokeAllTokens(): the cache key to drop is
        // "oldEmail:oldVersion", built from the values live tokens still carry.
        String oldEmail = user.getEmail();
        int oldVersion = user.getTokenVersion();

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole(role);
        if (roleChanged) {
            // ADR-017: bumps tokenVersion so every already-signed token is refused.
            user.revokeAllTokens();
        }

        User saved = userRepository.save(user);

        if (roleChanged) {
            // Second half of the revocation — the cached authority list would otherwise
            // keep answering with the old permissions until its 5-minute expiry.
            evictFromCache(oldEmail, oldVersion);
        }

        return userMapper.toResponse(saved);
    }

    /**
     * Resets an account: draws a new password, forces the change-password screen, and
     * kills every existing session — the usual "someone else may know my password" case.
     * Done by the administrator rather than an email link because this application sends
     * no email.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserCreateResult resetAccount(Long id) {
        User user = loadUser(id);
        // Captured before revokeAllTokens() bumps it.
        int oldVersion = user.getTokenVersion();
        String newPassword = generateSecurePassword();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Forces the change-password screen on next login.
        user.setFirstLogin(true);
        user.revokeAllTokens();
        User saved = userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
        return new UserCreateResult(userMapper.toResponse(saved), newPassword);
    }

    /**
     * Switches an account off. The row stays; the person just can't sign in
     * (UserDetailsServiceImpl / accountLocked). Used instead of delete when someone leaves,
     * so their name keeps appearing on past projects, workloads and cost history.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void deactivate(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setActive(false);
        // Without this, active=false would only block the NEXT sign-in; the current tab's
        // JWT would keep working until it expired.
        user.revokeAllTokens();
        userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
    }

    /**
     * Switches an account back on. No revokeAllTokens()/cache eviction here, unlike every
     * other write method — those exist to REMOVE access, and deactivate() already revoked
     * everything there was to revoke.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void reactivate(Long id) {
        User user = loadUser(id);
        user.setActive(true);
        userRepository.save(user);
    }

    /**
     * Soft-deletes an account: only BaseEntity's "deleted" flag is set. Never a real
     * DELETE — the id is referenced by projects, team assignments, workload entries and
     * cost history. The V18 partial unique index is what lets the freed email be reused.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void delete(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setDeleted(true);
        // Same pair as deactivate(): the flag alone wouldn't stop an already-open session.
        user.revokeAllTokens();
        userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
    }

    /**
     * Loads one account by id, or throws NotFoundException (404). Centralizes the
     * soft-delete filter so the seven public methods above can't forget it.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }

    /**
     * Drops the security-context cache entry of one revoked session (ADR-017). One precise
     * key rather than the whole cache (as RoleAdminService does), since these actions
     * affect exactly one account.
     */
    private void evictFromCache(String email, int tokenVersion) {
        var cache = cacheManager.getCache("securityContext");
        // Null when no such cache is configured, e.g. a test context without Caffeine settings.
        if (cache != null) {
            // Key shape must match JwtAuthenticationFilter's exactly, or nothing is evicted.
            cache.evict(email + ":" + tokenVersion);
        }
    }
}
