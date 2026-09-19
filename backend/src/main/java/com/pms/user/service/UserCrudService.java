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

// =============================================================================
// FILE: UserCrudService.java
//
// WHAT THIS FILE IS
//   The administration service for user accounts: list them, search them,
//   create one, edit one, reset its password, switch it off, switch it back on,
//   and soft-delete it. Everything an administrator does to SOMEBODY ELSE's
//   account goes through this class.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  UserController, on /api/users (list, search, create, update,
//     reset, deactivate, reactivate, delete) and /api/users/assignable.
//   Calls:      UserRepository (the accounts), RoleRepository (to attach the
//     chosen role), UserMapper (entity -> UserResponse, the MapStruct mapper of
//     ADR-018), PasswordEncoder (BCrypt hashing) and the Spring CacheManager
//     (to drop a revoked session from the security-context cache).
//   Produces:   UserResponse, and UserCreateResult when a generated password
//     has to travel back once.
//
// WHY IT EXISTS
//   Without it there is no way to create an account. The only accounts would be
//   the ones seeded by Flyway, and nobody could ever be added, moved to another
//   role, or locked out after leaving the company.
//
// THE TWO THINGS TO UNDERSTAND BEFORE READING THE METHODS
//
//   1. SOFT DELETE. This project never really removes a user row. delete()
//      sets the "deleted" flag of BaseEntity and the row stays in the table.
//      That is why every query here filters on deleted = false, and why the
//      unique rule on the e-mail column is a PARTIAL index (V18:
//      UNIQUE ... WHERE deleted = FALSE). Without the partial index, deleting
//      jean@s2i.tn and re-creating the same person later would be refused with
//      a duplicate-key error while the admin sees no such account on screen.
//
//   2. REVOKING A SESSION (ADR-017). The access token is a short-lived JWT and
//      there is nothing to delete server-side. Each token carries the
//      tokenVersion the user had when it was signed;
//      JwtAuthenticationFilter compares it with the value in the database and
//      refuses the request when they differ. So user.revokeAllTokens(), which
//      simply does tokenVersion++, kills every token that user holds at once.
//      On top of that, the filter caches the authority list under the key
//      "email:tokenVersion" (Caffeine, expireAfterWrite=5m in
//      application.yml). That entry has to be dropped too, which is what
//      evictFromCache(...) does at the bottom of this file. Doing only one of
//      the two would leave the old session working for up to five minutes.
//
// SECURITY NOTE FOR THE JURY
//   Every method here is guarded by @PreAuthorize on the SERVICE, never on the
//   controller, and every guard names a PERMISSION (MANAGE_USERS), never a role
//   name. An administrator can move MANAGE_USERS to another role from the admin
//   screen and this file does not change (ADR-001).
// =============================================================================

@Service
@RequiredArgsConstructor
public class UserCrudService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    // MapStruct-generated mapper (ADR-018). It copies a User into a
    // UserResponse and, crucially, does NOT copy passwordHash or tokenVersion,
    // because UserResponse simply does not declare them.
    private final UserMapper userMapper;
    // The BCrypt encoder configured in SecurityConfig. BCrypt is a one-way
    // password function: you can check a password against the stored hash, but
    // you cannot read the password back out of it.
    private final PasswordEncoder passwordEncoder;
    // Handle on the Caffeine caches of application.yml; used only to drop
    // entries of the "securityContext" cache when a session is revoked.
    private final CacheManager cacheManager;

    /**
     * Returns every active user, as a plain list.
     *
     * <p>Kept next to the paged version below because a few screens need the
     * whole list at once (selection widgets), while the admin table pages.
     */
    // The guard that protects the whole user administration.
    // WHAT: Spring refuses the call unless the caller's authority list holds
    // the exact string "MANAGE_USERS".
    // WHY here and not on the controller: it then protects every caller,
    // including another service or a test calling the method directly.
    // WITHOUT IT: any signed-in person could read the full list of accounts of
    // the company, which is a ready-made list of targets.
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        // findAllActive does "JOIN FETCH u.role ... WHERE u.deleted = false".
        // The JOIN FETCH is what lets UserMapper read role.name afterwards:
        // application.yml sets open-in-view: false, so the database session is
        // closed by the time the mapper runs, and a lazy role would throw
        // LazyInitializationException on this very screen.
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    /**
     * Same list, but one page at a time.
     *
     * <p>Gives back a Spring Data Page: the rows of the page plus the total
     * count, which is what the Angular paginator needs to draw its buttons.
     *
     * <p>Why an overload rather than a differently named method: the caller
     * chooses by passing a Pageable or not, and both answer the same question.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(Pageable pageable) {
        // Page.map applies the mapper to each row of the page and keeps the
        // paging information (total elements, page number) untouched.
        // Doing the conversion by hand would mean rebuilding a PageImpl and
        // risking a wrong total, which makes the last page unreachable.
        return userRepository.findAllActivePaged(pageable).map(userMapper::toResponse);
    }

    /**
     * Paged search with optional filters: free text, role, and active status.
     *
     * <p>Gives back a Page of UserResponse. Any of the three filters may be
     * null, which means "do not filter on this one" -- the JPQL query tests
     * each parameter for null before applying it.
     *
     * <p>Why filtering in the query and not in Java: the table is paged. If the
     * filtering happened after loading, page 1 could come back with three rows
     * and the total count would be wrong.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public Page<UserResponse> search(String search, Long roleId, Boolean active, Pageable pageable) {
        // Original French comment, translated and expanded.
        // WHAT: the text filter is normalised to an empty string, never null.
        // WHY: the query uses this parameter inside LOWER(CONCAT('%', :search,
        // '%')). When a null is sent, the PostgreSQL driver cannot tell what
        // type the parameter is and guesses "bytea" (raw bytes), so the
        // database refuses the call with an error such as "function lower(bytea)
        // does not exist". The query then compares ":search = ''" to decide
        // whether to filter at all, so an empty string means "no text filter".
        // WITHOUT IT: the user list screen breaks with a 500 as soon as the
        // search box is left empty -- that is, on the very first page load.
        // trim() also means that typing only spaces is treated as no search.
        String normalized = (search == null) ? "" : search.trim();
        return userRepository.searchActive(normalized, roleId, active, pageable).map(userMapper::toResponse);
    }

    /**
     * List of active users who can be assigned to something (project manager,
     * team member).
     *
     * <p>Translated from the original French note: this is reachable by anyone
     * who is allowed to ASSIGN people, without requiring MANAGE_USERS, which
     * stays an administrator-only right. Otherwise the Director or a project
     * manager could not fill the drop-down of the assignment screen.
     *
     * <p>Why a separate method instead of reusing findAll(): the two differ
     * only by their guard, and that is exactly the point. Relaxing the guard of
     * findAll() would open the whole administration list to project managers.
     */
    // hasAnyAuthority means "at least one of these three".
    // WHY three codes: ASSIGN_CHEF_PROJET is held by the Director,
    // ASSIGN_DEVELOPER by the Director and the project managers, and
    // MANAGE_USERS by the administrator -- who must obviously see the list too.
    // WITHOUT this method (or with MANAGE_USERS alone), a project manager
    // opening "add a developer to my team" would get an empty drop-down and a
    // 403 in the browser console, with nothing explaining why.
    // Note the data that travels: UserResponse carries no password hash and no
    // token version, so widening the audience here does not widen what leaks.
    @PreAuthorize("hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS')")
    @Transactional(readOnly = true)
    public List<UserResponse> findAssignable() {
        return userMapper.toResponseList(userRepository.findAllActive());
    }

    /**
     * Returns one account by its id.
     *
     * <p>Throws NotFoundException (HTTP 404) when the id is unknown or points
     * at a soft-deleted row -- see loadUser at the bottom of the file.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return userMapper.toResponse(loadUser(id));
    }

    /**
     * Creates a new account and generates its first password.
     *
     * <p>Gives back a UserCreateResult, which is the created UserResponse PLUS
     * the generated password in clear text. This is the ONLY moment of the
     * whole application where a password exists in readable form outside the
     * person's head: the administrator reads it once on screen and passes it
     * on. It is never stored in clear and never returned again -- only the
     * BCrypt hash goes to the database.
     *
     * <p>The new account starts with firstLogin = true, so FirstLoginFilter
     * blocks every endpoint except the change-password one until the person
     * chooses their own password.
     *
     * <p>Throws IllegalArgumentException (HTTP 409 Conflict) when the e-mail is
     * already taken by an active account, or when the role id does not exist.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    // @Transactional (no readOnly) makes the duplicate check, the role lookup
    // and the INSERT one single database unit of work.
    // Why it is needed: if the save failed after the password was generated,
    // the administrator would already have been shown a password that belongs
    // to no account. Inside one transaction, either the row exists or nothing
    // happened at all.
    @Transactional
    public UserCreateResult create(UserRequest request) {
        // "AndDeletedFalse" is the important half of this method name: the
        // e-mail of a soft-deleted account must NOT block a new one. That
        // matches the partial unique index of V18 (unique only WHERE deleted =
        // FALSE).
        // The check is done here to produce a readable message; the index in
        // the database is the real guarantee if two administrators save at the
        // same instant.
        // IllegalArgumentException is mapped to 409 Conflict by
        // GlobalExceptionHandler. French text: "e-mail already in use".
        if (userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        // The role is loaded, not just referenced by id, because the entity
        // needs the object and because an unknown id must be reported now.
        // Without this check the INSERT would fail later on the foreign key
        // fk_users_role, and the browser would receive an unreadable database
        // error instead of "role not found".
        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // H-2 (audit reference -- do not remove): the first password is drawn
        // at random for every account. Never a fixed default such as
        // "Passw0rd!".
        // WHY: a fixed default is public knowledge the day one person learns
        // it. Anyone could then sign in as any freshly created colleague before
        // that colleague's first login.
        String initialPassword = generateSecurePassword();

        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                // encode() runs BCrypt on the generated password. Only the
                // resulting hash is stored. If the database were ever copied,
                // the accounts still could not be used.
                .passwordHash(passwordEncoder.encode(initialPassword))
                // Written explicitly even though the entity defaults it: a new
                // colleague must be able to sign in straight away, and reading
                // it here saves the reader a trip to the entity.
                .active(true)
                // Forces the change-password screen on the first connection, so
                // the password the administrator saw stops being valid as soon
                // as the person connects.
                .firstLogin(true)
                .role(role)
                .build();

        // The mapper strips the hash and the token version on the way out;
        // UserResponse does not declare them, so they cannot travel.
        UserResponse created = userMapper.toResponse(userRepository.save(user));
        // The clear password is attached to the answer here, and only here.
        return new UserCreateResult(created, initialPassword);
    }

    /**
     * Generates a 12-character random password containing at least one upper
     * case letter, one lower case letter, one digit and one special character.
     *
     * <p>Gives back the password in clear text. The caller hashes it
     * immediately and shows it once to the administrator.
     *
     * <p>Why it is written here rather than taken from a library: the rule is
     * four lines long, and it has to match the password policy the
     * change-password screen enforces. A mismatch would produce an initial
     * password the person cannot re-enter.
     *
     * <p>static because it uses no field of the service -- it depends on
     * nothing but its own random generator.
     */
    private static String generateSecurePassword() {
        // SecureRandom, NOT java.util.Random.
        // WHY: Random is a predictable sequence. Knowing one value produced by
        // it is enough to compute the following ones, so an attacker who
        // created an account of their own could guess the password generated
        // for the next colleague. SecureRandom draws from the operating
        // system's cryptographic source and cannot be replayed that way.
        SecureRandom rng = new SecureRandom();
        String upper   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower   = "abcdefghijklmnopqrstuvwxyz";
        String digits  = "0123456789";
        // A deliberately small special set. A wider one would include
        // characters that are painful to type on a French keyboard or that get
        // mangled when the password is copied into an e-mail.
        String special = "@#$%!";
        String all     = upper + lower + digits + special;

        // A list of characters is used rather than a StringBuilder because the
        // four guaranteed characters have to be SHUFFLED afterwards, and a
        // StringBuilder cannot be shuffled.
        List<Character> chars = new ArrayList<>(12);
        // Guarantee complexity requirements: one character taken from each
        // family, so the result always satisfies the policy.
        // WITHOUT these four lines, a purely random draw could produce
        // "abcdefghijkl", which the change-password screen would then refuse --
        // the person could not even sign in to change it.
        chars.add(upper  .charAt(rng.nextInt(upper.length())));
        chars.add(lower  .charAt(rng.nextInt(lower.length())));
        chars.add(digits .charAt(rng.nextInt(digits.length())));
        chars.add(special.charAt(rng.nextInt(special.length())));
        // The eight remaining characters are drawn from all families at once.
        // The loop starts at 4 because the four guaranteed ones are already in.
        for (int i = 4; i < 12; i++) {
            chars.add(all.charAt(rng.nextInt(all.length())));
        }
        // THE line that makes the four guaranteed characters useful.
        // WITHOUT IT, every generated password would look like
        // "Aa1@xxxxxxxx": upper case first, then lower case, then a digit,
        // then a special character. An attacker who knew that shape would only
        // have to search the eight free characters instead of twelve.
        // The shuffle is fed with the same SecureRandom so the order is
        // unpredictable too.
        Collections.shuffle(chars, rng);
        StringBuilder sb = new StringBuilder(12);
        chars.forEach(sb::append);
        return sb.toString();
    }

    /**
     * Updates the name, the e-mail and the role of an account.
     *
     * <p>Gives back the saved account as a UserResponse. The password is never
     * touched here -- that is resetAccount's job.
     *
     * <p>THE IMPORTANT PART: when the ROLE changes, the person's existing
     * session is killed straight away. Without that, somebody moved down from
     * CHEF_PROJET to DEVELOPPEUR would keep the old permissions inside their
     * already-signed token until it expired.
     *
     * <p>Throws IllegalArgumentException (HTTP 409) when the new e-mail belongs
     * to another active account or the role id is unknown, and
     * NotFoundException (HTTP 404) when the account does not exist.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    // One unit of work for the checks, the UPDATE and the version bump. Without
    // it, a crash between "revoke the tokens" and "save" would leave the person
    // with the new role and the old, still-valid session.
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = loadUser(id);

        // The duplicate test runs only when the e-mail really changes.
        // WHY the first half of the condition is needed: without it, saving a
        // user without touching their e-mail would find their OWN row and
        // refuse the save with "e-mail already in use".
        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmailAndDeletedFalse(request.email())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.email());
        }

        var role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable : " + request.roleId()));

        // Original French note, translated: dynamic RBAC mandate -- changing
        // the role invalidates the existing session immediately.
        // The comparison is on the id and not on the object, because Role does
        // not override equals(): two objects loaded separately would never be
        // "equal" even when they are the same row, and the code would then
        // revoke the session on every single save.
        boolean roleChanged = !user.getRole().getId().equals(request.roleId());
        // The e-mail is captured BEFORE setEmail() runs, because the cache key
        // of the session that must be dropped was built with the OLD e-mail
        // ("oldEmail:oldVersion"). Reading it after the setter would evict a
        // key that never existed and leave the real entry in place.
        String oldEmail = user.getEmail();   // capture before setEmail() — cache key uses old email
        // Same reasoning for the version: revokeAllTokens() below increases it,
        // and the entry to drop is the one keyed with the value the live tokens
        // still carry.
        int oldVersion = user.getTokenVersion();

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole(role);
        if (roleChanged) {
            // tokenVersion++ (ADR-017). Every token already signed carries the
            // old number; JwtAuthenticationFilter compares it with this one and
            // now refuses them all. The person has to sign in again and
            // receives the new permissions.
            user.revokeAllTokens();
        }

        User saved = userRepository.save(user);

        if (roleChanged) {
            // Second half of the revocation. The filter caches the authority
            // list under "email:tokenVersion" for five minutes, so the token
            // check above is not enough on its own: the cached entry would
            // answer with the OLD permission list until it expired. Dropping it
            // makes the new matrix apply on the very next request.
            evictFromCache(oldEmail, oldVersion);
        }

        return userMapper.toResponse(saved);
    }

    /**
     * Resets an account: draws a new password, forces the change-password
     * screen, and kills every session the person had.
     *
     * <p>Gives back a UserCreateResult, that is the account plus the new
     * password in clear text -- shown once to the administrator, exactly like
     * on creation.
     *
     * <p>Used when somebody forgets their password. Why the administrator does
     * it rather than a "forgot my password" e-mail link: this application sends
     * no e-mail, so there is no mailbox to send a reset link to.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public UserCreateResult resetAccount(Long id) {
        User user = loadUser(id);
        // Captured before revokeAllTokens() increases it: this is the version
        // the still-live tokens carry, and therefore the cache key to drop.
        int oldVersion = user.getTokenVersion();
        String newPassword = generateSecurePassword();
        // Only the BCrypt hash is stored; newPassword stays in memory just long
        // enough to travel back in the answer.
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // force password change on next login: FirstLoginFilter will block
        // every endpoint except the change-password one. Without this line the
        // password the administrator read on screen would stay valid forever.
        user.setFirstLogin(true);
        // invalidate all existing sessions immediately (ADR-017). Needed
        // because the usual reason for a reset is "I think someone else knows
        // my password" -- leaving the old sessions alive would defeat the whole
        // point of the reset.
        user.revokeAllTokens();
        User saved = userRepository.save(user);
        // The e-mail did not change here, so user.getEmail() is already the
        // right half of the key; only the version had to be captured earlier.
        evictFromCache(user.getEmail(), oldVersion);
        return new UserCreateResult(userMapper.toResponse(saved), newPassword);
    }

    /**
     * Switches an account off.
     *
     * <p>Returns nothing. The row stays, the person simply cannot sign in any
     * more: UserDetailsServiceImpl passes accountLocked(!active) to Spring
     * Security, and the JWT filter stops accepting the revoked tokens.
     *
     * <p>Why deactivate and not delete: the person's name must keep appearing
     * on the projects, the workloads and the cost history they were part of.
     * This is the normal action when somebody leaves the company.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void deactivate(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setActive(false);
        // Without this, setting active = false would only block the NEXT sign
        // in. The person's current tab would keep working with its JWT until
        // the token expired, because the filter does not re-read the "active"
        // column on every request.
        user.revokeAllTokens();
        userRepository.save(user);
        // And without this, the cached authority list of that session would
        // keep answering for up to five minutes even after the version bump.
        evictFromCache(user.getEmail(), oldVersion);
    }

    /**
     * Switches an account back on.
     *
     * <p>Returns nothing. The person can sign in again with the password they
     * had before, because deactivate() never touched it.
     *
     * <p>Why there is no revokeAllTokens() and no cache eviction here, unlike
     * every other write method of this class: those two calls exist to REMOVE
     * access. Re-activating gives access back, and every token that existed
     * before was already revoked by deactivate(). There is nothing left to
     * invalidate.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void reactivate(Long id) {
        User user = loadUser(id);
        user.setActive(true);
        userRepository.save(user);
    }

    /**
     * Soft-deletes an account.
     *
     * <p>Returns nothing. The row is NOT removed from the table: only the
     * "deleted" flag of BaseEntity is set, which every query of this project
     * filters on.
     *
     * <p>Why soft and not a real DELETE: the user id is referenced by projects,
     * team assignments, workload entries and cost history. A real DELETE would
     * either be refused by the foreign keys or destroy that history. The
     * partial unique index of V18 is what allows the same e-mail to be used
     * again for a new account afterwards.
     */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    @Transactional
    public void delete(Long id) {
        User user = loadUser(id);
        int oldVersion = user.getTokenVersion();
        user.setDeleted(true);
        // Same pair as in deactivate(): the flag alone would not stop a session
        // that is already open, because the JWT filter trusts the token until
        // its version no longer matches.
        user.revokeAllTokens();
        userRepository.save(user);
        evictFromCache(user.getEmail(), oldVersion);
    }

    /**
     * Loads one account by id, or throws NotFoundException (HTTP 404).
     *
     * <p>Why this helper exists: the soft-delete filter below has to be applied
     * every single time. Written once, the seven public methods above cannot
     * forget it.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                // findById is the plain Spring Data method and knows nothing
                // about the "deleted" flag. Without this filter, an account
                // that was removed from the screen could still be edited,
                // reset, or re-activated by its id.
                // filter() on an Optional turns "found but deleted" into
                // "empty", which the next line reports as a clean 404.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }

    /**
     * Drops the security-context cache entry of one revoked session (ADR-017).
     * Translated from the original French comment.
     *
     * <p>Returns nothing. Takes the e-mail and the token version the session
     * was cached under.
     *
     * <p>Why one precise key here, while RoleAdminService clears the whole
     * cache: changing a role affects an unknown number of people, but the
     * actions of this class affect exactly one account. Removing one entry
     * leaves every other open session untouched, so nobody else pays for an
     * extra database read.
     */
    private void evictFromCache(String email, int tokenVersion) {
        var cache = cacheManager.getCache("securityContext");
        // getCache returns null when no cache with that name is configured,
        // which happens in tests that start the context without the Caffeine
        // settings of application.yml. The check keeps those tests from failing
        // with NullPointerException on a line unrelated to what they test.
        if (cache != null) {
            // The key must be built exactly as JwtAuthenticationFilter builds
            // it: email, a colon, then the version. Any other shape would
            // silently evict nothing, and the revoked session would keep
            // working until the five-minute expiry.
            cache.evict(email + ":" + tokenVersion);
        }
    }
}
