package com.pms.auth.service;

import com.pms.auth.dto.AuthResponse;
import com.pms.auth.dto.TokenBundle;
import com.pms.auth.dto.ChangePasswordRequest;
import com.pms.auth.dto.LoginRequest;
import com.pms.shared.exception.TooManyRequestsException;
import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

// The brain of sign-in: login, refresh, logout, changePassword. Two-token model (ADR-017,
// H-1): the access token (15 min) travels in the JSON body since JavaScript must read it; the
// refresh token (7 or 30 days) travels only in the HttpOnly "pms_refresh" cookie, so an XSS
// payload can steal at most fifteen minutes, not a week.
//
// No @PreAuthorize in this file: login/refresh run before anyone is authenticated (permitAll),
// and logout/changePassword act on the caller's OWN account (from @AuthenticationPrincipal,
// never the request body), which needs no permission.
//
// Revocation ritual, followed by every method that ends a session: (1) read the current
// tokenVersion into oldVersion, (2) user.revokeAllTokens() + save, (3) evict the cache entry
// keyed "email:oldVersion". Step 3 must use the OLD number — that's what the live tokens still
// carry — or the stale cache entry keeps answering for up to five minutes.

/**
 * Sign-in, token renewal, sign-out and self-service password change.
 *
 * <p>One service for all four, since they share the same collaborators and the same revocation
 * ritual — splitting them would risk one copy drifting and a session surviving a password change.
 *
 * <p>Returns TokenBundle, not AuthResponse: keeps the refresh token out of any JSON body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    // userRepository      - loads the account with role + permissions in one query (JOIN FETCH).
    // jwtService          - signs and reads the two tokens.
    // passwordEncoder     - BCrypt, strength 12 (SecurityConfig), deliberately slow.
    // cacheManager        - access to the "securityContext" cache (ADR-017).
    // loginAttemptTracker - the per-e-mail rate limiter (H-2).
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;
    private final LoginAttemptTracker loginAttemptTracker;

    // H-2: precomputed dummy hash, compared against when the e-mail is unknown, so BCrypt
    // (deliberately slow) always runs and response time can't be used to enumerate valid
    // addresses. Computed once at startup rather than per call, since hashing at strength 12
    // costs real time.
    private String dummyHash;

    /**
     * Computes the decoy hash once, after Spring has injected the fields (a field initializer
     * would run too early, while passwordEncoder is still null).
     */
    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode("dummy-timing-equalization");
    }

    /**
     * H-2: rate-limited login with timing equalization.
     * Always runs bcrypt even when the email is not found, to prevent
     * user enumeration via response-time differences.
     */
    @Transactional
    public TokenBundle login(LoginRequest request) {
        // Checked first, before any DB read or BCrypt call, so a guessing flood costs almost nothing.
        if (loginAttemptTracker.isBlocked(request.email())) {
            throw new TooManyRequestsException(
                    "Trop de tentatives de connexion. Réessayez dans " +
                    (LoginAttemptTracker.WINDOW_SECONDS / 60) + " minutes.");
        }

        // Filters on deleted = false, NOT active — a disabled account still comes back here,
        // which is why the explicit isActive() check exists below. .orElse(null), not
        // orElseThrow: an unknown e-mail must not return early or the timing-equalization below
        // is defeated.
        User user = userRepository.findActiveByEmailWithRole(request.email()).orElse(null);

        // Always compare against a real hash if there is one, otherwise the dummy — so
        // passwordEncoder.matches(...) takes the same time whether the address exists or not.
        String hashToVerify = (user != null) ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToVerify);

        // One branch for both failures (unknown address, wrong password) so the client can't
        // tell them apart — same 401, same message.
        if (user == null || !passwordMatches) {
            loginAttemptTracker.recordFailure(request.email());
            throw new BadCredentialsException("Identifiants incorrects");
        }

        // 403, not 401, so the UI can say "account disabled" instead of "wrong password". The
        // failure is still recorded, so a disabled account's password can't be brute-forced for free.
        if (!user.isActive()) {
            loginAttemptTracker.recordFailure(request.email());
            throw new DisabledException("Compte désactivé");
        }

        loginAttemptTracker.reset(request.email());
        return buildBundle(user, request.rememberMe());
    }

    /**
     * H-1: Rotation — each refresh token is single-use.
     * After a successful refresh, tokenVersion is bumped; the old refresh token
     * (which carries the old tokenVersion) is therefore invalidated on next use.
     * If the presented token already has a stale version, the existing version check
     * catches it and returns 401 ("Token révoqué") — that is our reuse detection.
     */
    @Transactional
    public TokenBundle refresh(String refreshTokenValue) {
        if (!jwtService.isTokenValid(refreshTokenValue)) {
            throw new JwtException("Refresh token invalide ou expiré");
        }

        // Refuses an ACCESS token presented as a refresh token — both share a signature, only
        // "type" tells them apart. Without it, a leaked access token could be exchanged here
        // for a fresh long-lived pair.
        if (!"refresh".equals(jwtService.extractType(refreshTokenValue))) {
            throw new JwtException("Type de token incorrect");
        }

        // Read from the token, not the request: the browser sends nothing but the cookie here,
        // so this is the only record of the original "remember me" choice.
        boolean rememberMe = jwtService.extractRememberMe(refreshTokenValue);

        // Re-read from the database at every renewal (not trusted from the token) so a role
        // change this morning shows up in the very next refresh — the dynamic half of RBAC.
        String email = jwtService.extractEmail(refreshTokenValue);
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        // ADR-017 revocation check: logout, password change, deactivation and role change all
        // bump the DB counter, so a token signed before that moment no longer matches it.
        int presented = jwtService.extractTokenVersion(refreshTokenValue);
        if (presented != user.getTokenVersion()) {
            // Stale version → token was already rotated or explicitly revoked.
            // Could indicate refresh-token theft — log prominently.
            log.warn("Refresh token replay detected for {} (presented={}, current={}). Possible token theft.",
                    email, presented, user.getTokenVersion());
            throw new JwtException("Token révoqué");
        }

        // Rotation: bump tokenVersion so the current refresh token can never be reused.
        // oldVersion captured before the bump — the cache is keyed with the number the live
        // tokens still carry, not the new one.
        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        User saved = userRepository.save(user);

        // Evict the cache entry for the old access token
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        // Keeps the scope of the original session: a "remember me" user shouldn't get downgraded
        // to a short session just because their token happened to be renewed in between.
        return buildBundle(saved, rememberMe);
    }

    /**
     * Ends the caller's session on the server side.
     *
     * <p>Increasing tokenVersion, not just dropping the token client-side, is what kills the
     * session on every device the user is signed in on.
     */
    @Transactional
    public void logout(String email) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        userRepository.save(user);

        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Session révoquée pour : {}", email);
    }

    /**
     * Lets a signed-in user replace their own password.
     *
     * <p>The current password is demanded despite the caller being authenticated, to prove
     * ownership of the account rather than an unlocked laptop or a stolen access token. Setting
     * firstLogin to false here is the only way out of the first-login lock (H-2).
     */
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Mot de passe actuel incorrect");
        }

        // revokeAllTokens() ends every session opened with the old password — a password change
        // that leaves an old (possibly stolen) session alive protects nothing.
        int oldVersion = user.getTokenVersion();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFirstLogin(false);
        user.revokeAllTokens();
        userRepository.save(user);

        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Mot de passe changé pour : {}", email);
    }

    /**
     * Builds the token pair and JSON body shared by login() and refresh().
     *
     * <p>Kept private and shared so a renewed session describes itself exactly like a fresh one
     * — if refresh() built its own answer, the permission list could drift between the two paths.
     */
    private TokenBundle buildBundle(User user, boolean rememberMe) {
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user, rememberMe);
        // Flattened to plain code strings (e.g. "VIEW_PROJECT") since the Angular guards only
        // ask "is this string in the list?". Rebuilt on every login/refresh so a permission
        // change takes effect immediately — the dynamic half of RBAC.
        Set<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        // Note what is NOT here: the refresh token. It leaves via the TokenBundle instead, so
        // only AuthController can put it in the HttpOnly cookie.
        AuthResponse body = new AuthResponse(
                user.getId(),
                accessToken,
                user.isFirstLogin(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().getName(),
                permissions
        );
        return new TokenBundle(body, refreshToken, jwtService.refreshMaxAge(rememberMe));
    }
}
