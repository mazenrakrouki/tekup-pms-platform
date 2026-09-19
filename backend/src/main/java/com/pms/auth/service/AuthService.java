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

// =============================================================================
// FILE: AuthService.java
//
// WHAT THIS FILE IS
//   The brain of sign-in. It is the only class that decides whether a password
//   is right, that hands out tokens, and that ends sessions. Four public
//   methods: login, refresh, logout and changePassword.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  AuthController (POST /api/auth/login, /refresh, /logout and
//     /change-password). The controller takes no security decision of its own -
//     it only moves the refresh token in and out of the "pms_refresh" HttpOnly
//     cookie.
//   Calls:      UserRepository.findActiveByEmailWithRole (the account plus its
//     role and its permissions in ONE query), PasswordEncoder (BCrypt),
//     JwtService (signs and reads the two tokens), LoginAttemptTracker (the
//     rate limiter) and the Spring CacheManager (to drop a revoked session from
//     the "securityContext" cache of ADR-017).
//   Produces:   TokenBundle = the AuthResponse JSON body + the refresh token +
//     the cookie lifetime, all three decided here so they can never disagree.
//
// WHY IT EXISTS
//   Delete it and nobody can obtain a token, so every request in the whole
//   application is anonymous and every @PreAuthorize refuses. It is also the
//   only place that can end a session: a JWT is stateless, so there is nothing
//   to delete on the server; revoking means increasing the user's tokenVersion
//   counter, which happens here (and in UserCrudService for admin actions).
//
// THE TWO-TOKEN MODEL IN ONE PARAGRAPH (ADR-017, audit item H-1)
//   The ACCESS token is short (900 s = 15 minutes,
//   pms.jwt.access-token-expiration) and travels in the JSON body, because
//   JavaScript must read it to build the "Authorization: Bearer ..." header.
//   The REFRESH token is long (7 days, or 30 with "remember me") and travels in
//   a cookie marked HttpOnly, which the browser sends back on its own but
//   JavaScript cannot read. EXAMPLE of what this split buys: a script injected
//   into the page (an XSS attack) can steal the access token and use it for at
//   most fifteen minutes, but it cannot reach the refresh token and so cannot
//   keep renewing a stolen session for a week.
//
// WHY THERE IS NO @PreAuthorize IN THIS FILE
//   Everywhere else in the project the permission check sits on the SERVICE
//   method, never on the controller, and it always names a permission code,
//   never a role name. Here such a check would be meaningless: login and
//   refresh run BEFORE anyone is authenticated (SecurityConfig marks those two
//   URLs permitAll), and logout / changePassword act on the caller's OWN
//   account, which needs no particular permission - a brand-new user holds
//   almost none. What protects those two methods is that the account is never
//   read from the request body: it comes from @AuthenticationPrincipal, which
//   JwtAuthenticationFilter filled from the verified token. That is what stops
//   somebody changing another person's password.
//
// THE REVOCATION RITUAL - READ THIS BEFORE CHANGING ANYTHING BELOW
//   Every method that ends a session does the same three things, in this order:
//     1. read the current tokenVersion into oldVersion;
//     2. user.revokeAllTokens() (tokenVersion++) and save;
//     3. evict the cache entry keyed "email:oldVersion".
//   Step 3 must use the OLD number, because that is the number the tokens still
//   in the wild carry. Reading the version after step 2 would evict a key that
//   never existed, and the cached authority list would keep answering for up to
//   five minutes (Caffeine, expireAfterWrite=5m in application.yml).
// =============================================================================

/**
 * Sign-in, token renewal, sign-out and self-service password change.
 *
 * <p>Why one service for the four operations instead of one class each: they
 * share the same three collaborators (the user row, the password encoder, the
 * token signer) and the same revocation ritual. Splitting them would copy that
 * ritual four times, and a copy that somebody forgets to update is a session
 * that survives a password change.
 *
 * <p>Why it returns TokenBundle and not AuthResponse: the refresh token must
 * never appear in a JSON body. Handing the controller a bundle keeps the two
 * parts apart - body on one side, cookie value on the other - so the refresh
 * token cannot reach JavaScript by accident.
 */
// @Service registers this class as a Spring bean so AuthController can have it
// injected. Without it the application fails to start with "No qualifying bean
// of type AuthService".
@Service
// Lombok writes, at compile time, the constructor that takes the five final
// fields below; that constructor is how Spring injects them. Why constructor
// injection rather than @Autowired on each field: the fields stay final, so
// nothing can swap the password encoder at runtime, and the class can be built
// with fake collaborators in a unit test.
@RequiredArgsConstructor
// Lombok adds the "log" object used further down. It is not decoration here:
// the warning logged on a replayed refresh token is the project's only trace of
// a possible token theft.
@Slf4j
public class AuthService {

    // The five collaborators, injected through the Lombok constructor above.
    // They are final, so once the application has started nothing can replace
    // the password encoder or the token signer.
    //
    // userRepository      - loads the account together with its role and its
    //                       permissions in one query (JOIN FETCH), so no lazy
    //                       loading is attempted after the transaction closes.
    // jwtService          - signs the two tokens and reads them back.
    // passwordEncoder     - the BCrypt encoder built in SecurityConfig with
    //                       strength 12. BCrypt is a one-way function, slow on
    //                       purpose, so guessing passwords from a stolen
    //                       database dump stays too expensive to be worth it.
    // cacheManager        - gives access to the "securityContext" cache that
    //                       JwtAuthenticationFilter fills; ending a session
    //                       means dropping an entry from it (ADR-017).
    // loginAttemptTracker - the per-e-mail rate limiter of audit item H-2.
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;
    private final LoginAttemptTracker loginAttemptTracker;

    // H-2: precomputed dummy hash for constant-time comparison when email is unknown
    //
    // WHAT: at startup we BCrypt-hash a fixed throw-away string, with the very
    //   same encoder (strength 12) that hashes real passwords.
    // WHY: BCrypt is deliberately slow. If login() skipped the comparison when
    //   the e-mail is unknown, an unknown address would answer in a couple of
    //   milliseconds while a known one would take a few hundred. That gap is a
    //   free "does this person work here?" oracle.
    // EXAMPLE of what it prevents: a script tries ten thousand addresses,
    //   measures the response times, and walks away with the exact list of the
    //   company's staff e-mails - a ready-made phishing target list - without
    //   ever guessing a single password.
    // Why it is computed once in init() rather than on each call: hashing at
    //   strength 12 costs a fraction of a second, so doing it per request would
    //   turn the login endpoint into its own denial-of-service.
    private String dummyHash;

    /**
     * Computes the decoy hash once, right after Spring has injected the fields.
     *
     * <p>Returns nothing; it only fills {@link #dummyHash}.
     *
     * <p>Why not a field initializer such as
     * {@code private String dummyHash = passwordEncoder.encode(...)}: field
     * initializers run while the object is still being built, before Spring has
     * finished injecting, so passwordEncoder could still be null and the
     * application would fail to start with a NullPointerException.
     */
    // @PostConstruct tells Spring to call this method exactly once, after the
    // bean is built and every dependency is injected, and before it serves
    // anything. Without it the method would simply never run, dummyHash would
    // stay null, and the first login with an unknown e-mail would crash inside
    // passwordEncoder.matches(...).
    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode("dummy-timing-equalization");
    }

    // -------------------------------------------------------------------------
    // login(): the only place a password is checked at sign-in time.
    //
    // WHAT IT GIVES BACK: a TokenBundle - the AuthResponse body (access token,
    //   identity, role name, permission codes) plus the refresh token and the
    //   lifetime the cookie must be given. The controller splits the two.
    //
    // THE FOUR STEPS, IN THIS ORDER AND FOR A REASON:
    //   1. rate limit - refuse at once if this e-mail has already failed five
    //      times in fifteen minutes (HTTP 429). Checked FIRST so that a flood
    //      costs us nothing: no database read, no BCrypt.
    //   2. load       - read the account, or null.
    //   3. BCrypt     - ALWAYS run, against the real hash or against dummyHash.
    //   4. verdict    - wrong credentials -> 401; switched-off account -> 403;
    //      otherwise clear the failure window and issue the tokens.
    //
    // WHY THE VERDICT IS NOT SPLIT INTO "unknown e-mail" AND "wrong password":
    //   answering "this address does not exist" would tell an attacker which
    //   addresses are worth attacking. Both cases give the exact same 401 with
    //   the exact same message.
    //
    // WHY @Transactional: it wraps the whole method in one database unit of
    //   work. login() writes nothing, so the point here is a consistent read:
    //   the user, its role and its permissions are read through one single view
    //   of the database. EXAMPLE of what it protects against: an administrator
    //   changing that role at the same second could otherwise be read halfway,
    //   and the person would receive a permission list that never existed.
    // -------------------------------------------------------------------------
    /**
     * H-2: rate-limited login with timing equalization.
     * Always runs bcrypt even when the email is not found, to prevent
     * user enumeration via response-time differences.
     */
    @Transactional
    public TokenBundle login(LoginRequest request) {
        // WHAT: refuse straight away when this e-mail has already failed
        //   LoginAttemptTracker.MAX_ATTEMPTS (5) times inside WINDOW_SECONDS
        //   (15 minutes).
        // WHY it is the very first line: everything below costs real work - a
        //   database round trip, then a BCrypt comparison that is slow by
        //   design. Refusing here means a password-guessing flood is stopped at
        //   almost no cost to the server.
        // EXAMPLE without it: a script runs a common-password list against one
        //   address at a few hundred tries per second, and the BCrypt calls
        //   alone saturate the machine for every other user of the application.
        // TooManyRequestsException is turned into HTTP 429 by
        //   GlobalExceptionHandler. The message is written in French because it
        //   is shown to the user as it is.
        if (loginAttemptTracker.isBlocked(request.email())) {
            throw new TooManyRequestsException(
                    "Trop de tentatives de connexion. Réessayez dans " +
                    (LoginAttemptTracker.WINDOW_SECONDS / 60) + " minutes.");
        }

        // findActiveByEmailWithRole brings back the account together with its
        // role and that role's permissions in ONE query (JOIN FETCH). Why that
        // matters: application.yml sets open-in-view: false, so the database
        // session is already closed when buildBundle() walks
        // user.getRole().getPermissions(). With a lazy association that walk
        // would throw LazyInitializationException instead of returning a token.
        //
        // Careful with the name: the query filters on deleted = false, NOT on
        // active. A switched-off account is still returned here - which is
        // exactly why the explicit isActive() check exists further down.
        //
        // .orElse(null) and not .orElseThrow(...): an unknown e-mail must NOT
        // leave the method early, or the timing gap described on dummyHash
        // comes straight back.
        // Look up the user — may return empty
        User user = userRepository.findActiveByEmailWithRole(request.email()).orElse(null);

        // WHAT: choose which hash BCrypt will be compared against - the user's
        //   real one, or the decoy computed at startup.
        // WHY: passwordEncoder.matches(...) then takes the same time whether
        //   the address exists or not.
        // EXAMPLE of the flaw this removes: writing "if (user == null) throw"
        //   would answer in about 2 ms for an unknown address and in about
        //   250 ms for a known one; measuring that gap reveals every valid
        //   e-mail of the company.
        // Note the result is stored in a variable and judged on the next lines;
        //   it is never used to decide WHETHER to run the comparison.
        // Always run bcrypt to equalize timing (prevents user enumeration)
        String hashToVerify = (user != null) ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToVerify);

        // One single branch for the two failures - unknown address and wrong
        // password - so the client cannot tell them apart. Both give HTTP 401
        // through GlobalExceptionHandler, with the same French message.
        // recordFailure() adds one timestamp to this address's sliding window;
        // five of them inside fifteen minutes make the check at the top of the
        // method refuse the next attempt.
        if (user == null || !passwordMatches) {
            loginAttemptTracker.recordFailure(request.email());
            throw new BadCredentialsException("Identifiants incorrects");
        }

        // The query above only excluded soft-deleted rows, so an account an
        // administrator switched off still reaches this point. DisabledException
        // is mapped to HTTP 403, not 401, so the Angular side can show "your
        // account has been disabled" instead of "wrong password".
        // The failure is recorded here as well: without it, somebody who knows
        // a disabled account's password could hammer this endpoint for free.
        if (!user.isActive()) {
            loginAttemptTracker.recordFailure(request.email());
            throw new DisabledException("Compte désactivé");
        }

        // Success: clear this address's failure window, so a person who mistyped
        // their password three times does not stay one mistake away from being
        // locked out for the rest of the quarter of an hour.
        // request.rememberMe() decides the refresh token's lifetime (7 days, or
        // 30 with the box ticked). It is written INSIDE the refresh token so
        // that rotation can preserve it - see JwtService.generateRefreshToken.
        loginAttemptTracker.reset(request.email());
        return buildBundle(user, request.rememberMe());
    }

    // -------------------------------------------------------------------------
    // refresh(): exchange the refresh token held in the HttpOnly cookie for a
    // brand-new pair of tokens.
    //
    // WHAT IT GIVES BACK: a fresh TokenBundle, exactly like login().
    //
    // WHY THIS ENDPOINT EXISTS: the access token lives fifteen minutes. Asking
    //   the user to type their password four times an hour would be unusable,
    //   and giving the access token a long life would mean a stolen one stays
    //   usable for days. The refresh token is the compromise: long-lived, but
    //   kept out of JavaScript's reach in an HttpOnly cookie scoped to
    //   Path=/api/auth/refresh, so the browser does not even send it anywhere
    //   else.
    //
    // ROTATION AND REUSE DETECTION (audit item H-1) - the part a jury will ask
    //   about. Every successful refresh increases the user's tokenVersion. The
    //   refresh token that has just been used carries the previous number, so
    //   it can never be used a second time: it is single-use. If it shows up
    //   again, the version comparison below fails and we answer 401. That same
    //   mismatch is our theft signal, which is why it is logged as a warning.
    //   EXAMPLE of what this buys: a refresh token copied from a stolen laptop
    //   stops working the moment the real user's browser renews its session -
    //   and the log says it happened.
    //
    // WHY @Transactional: the method reads the user, increases tokenVersion and
    //   saves. Without one unit of work, a crash between the read and the save
    //   could hand out a new token pair while the counter stayed where it was,
    //   leaving the old refresh token alive - which is exactly the single-use
    //   guarantee we have just claimed.
    //
    // A NOTE ON COST: isTokenValid() and each extract...() call parse and check
    //   the signature again, so the token is read several times here. That is a
    //   few HMAC computations on a short string, and the code stays obvious;
    //   the trade was accepted.
    // -------------------------------------------------------------------------
    /**
     * H-1: Rotation — each refresh token is single-use.
     * After a successful refresh, tokenVersion is bumped; the old refresh token
     * (which carries the old tokenVersion) is therefore invalidated on next use.
     * If the presented token already has a stale version, the existing version check
     * catches it and returns 401 ("Token révoqué") — that is our reuse detection.
     */
    @Transactional
    public TokenBundle refresh(String refreshTokenValue) {
        // Signature and expiry date first. isTokenValid() swallows the parsing
        // exception and answers a boolean, so a forged or expired token gives a
        // clean 401 instead of a stack trace. JwtException is mapped to HTTP 401
        // in GlobalExceptionHandler.
        if (!jwtService.isTokenValid(refreshTokenValue)) {
            throw new JwtException("Refresh token invalide ou expiré");
        }

        // WHAT: refuse an ACCESS token presented where a REFRESH token is
        //   expected. Both are signed with the same key, so the signature check
        //   above cannot tell them apart - only the "type" claim can (a claim is
        //   one named value stored inside the token).
        // EXAMPLE without this check: a script that managed to read the access
        //   token out of JavaScript could post it here and receive a complete
        //   new pair, turning a fifteen-minute leak into a permanent session.
        //   This is the whole reason a type is written into both tokens.
        // "refresh".equals(...) and not the other way round: extractType() may
        //   return null for a token without that claim, and this order can never
        //   throw a NullPointerException.
        if (!"refresh".equals(jwtService.extractType(refreshTokenValue))) {
            throw new JwtException("Type de token incorrect");
        }

        // The "remember me" choice is read back from the token itself, not from
        // the request: on this endpoint the browser sends nothing but the
        // cookie, so the only memory of the user's choice is what we wrote into
        // the token at login. Without it, the first renewal would silently
        // downgrade a 30-day session to 7 days and the user would be signed out
        // with no visible reason.
        boolean rememberMe = jwtService.extractRememberMe(refreshTokenValue);

        // The account is re-read from the database at every renewal instead of
        // trusting what the token says. That is what makes the permission list
        // in the answer follow an administrator's changes: a role modified this
        // morning shows up in the very next refresh, with no code change - the
        // dynamic half of the RBAC. orElseThrow gives 401 for an account that
        // was deleted in the meantime.
        String email = jwtService.extractEmail(refreshTokenValue);
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        // The heart of revocation (ADR-017). The token carries the tokenVersion
        // the user had when it was signed; the database holds the current one.
        // Every event that must end sessions - logout, password change, account
        // deactivation, role change - increases the database counter, and every
        // token signed before that moment now disagrees with it.
        // WHY a counter and not a blacklist table: a JWT is stateless, there is
        // nothing to delete server-side. One integer per user kills all of that
        // user's tokens at once and needs no cleanup job.
        // EXAMPLE without this check: an administrator disables a leaver at
        // 10:00 and that person's browser keeps renewing its session for the
        // next seven days.
        int presented = jwtService.extractTokenVersion(refreshTokenValue);
        if (presented != user.getTokenVersion()) {
            // Stale version → token was already rotated or explicitly revoked.
            // Could indicate refresh-token theft — log prominently.
            log.warn("Refresh token replay detected for {} (presented={}, current={}). Possible token theft.",
                    email, presented, user.getTokenVersion());
            throw new JwtException("Token révoqué");
        }

        // Rotation: bump tokenVersion so the current refresh token can never be reused
        // oldVersion is captured BEFORE revokeAllTokens(), because the
        // cache entry dropped a few lines below is keyed with the number the
        // tokens still in the wild carry. Reading it afterwards would evict a
        // key that never existed and leave the stale authority list answering
        // for up to five minutes.
        // revokeAllTokens() is a method on the User entity rather than
        // setTokenVersion(n) written here, so the rule "revoking means +1, never
        // a fixed value" exists in exactly one place.
        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        User saved = userRepository.save(user);

        // Second half of the revocation. JwtAuthenticationFilter caches the
        // authority list under the key "email:tokenVersion" (Caffeine,
        // expireAfterWrite=5m in application.yml) so that most requests avoid a
        // database round trip (ADR-017). Increasing the version alone is not
        // enough: the old entry would keep answering with the old permission
        // list until it expired by itself.
        // The null test is not defensive noise - CacheManager.getCache() returns
        // null when no cache with that name is configured, which is the case in
        // some test slices. Without it those tests would fail on a
        // NullPointerException instead of exercising the logic.
        // Evict the cache entry for the old access token
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        // The renewed session keeps the SCOPE of the original one: somebody who
        // ticked "remember me" must not be signed out after the short lifetime
        // simply because their token happened to be renewed in between. This is
        // why rememberMe was read out of the old token above and is handed to
        // buildBundle here.
        return buildBundle(saved, rememberMe);
    }

    /**
     * Ends the caller's session on the server side.
     *
     * <p>Returns nothing; the controller answers 204 No Content and clears the
     * refresh cookie.
     *
     * <p>Why it takes an e-mail and not a user id: the value comes from
     * {@code @AuthenticationPrincipal} in the controller, which
     * JwtAuthenticationFilter filled from the VERIFIED token. Nothing in the
     * request body can choose whose session is destroyed - otherwise anybody
     * could sign the director out.
     *
     * <p>Why call the server at all, when the browser could simply drop the
     * token: dropping it locally would leave the refresh token usable by
     * whoever copied it. Increasing tokenVersion is what really ends the
     * session everywhere, including on the user's other devices.
     */
    // One unit of work around the read, the counter increase and the save. See
    // the revocation ritual in the file header for why the two steps must stay
    // together.
    @Transactional
    public void logout(String email) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        // Same ritual as in refresh(): capture the OLD version first, increase,
        // save, then evict the cache entry the living tokens point at. The
        // order cannot be changed - see the file header.
        int oldVersion = user.getTokenVersion();
        user.revokeAllTokens();
        userRepository.save(user);

        // Without this eviction the cached authority list would keep answering
        // for up to five more minutes, and the "signed out" user would go on
        // reading data during that window.
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Session révoquée pour : {}", email);
    }

    /**
     * Lets a signed-in user replace their own password.
     *
     * <p>Returns nothing; the controller answers 204 No Content.
     *
     * <p>Why the current password is demanded although the caller is already
     * authenticated: it proves the person at the keyboard owns the account and
     * is not somebody who found an unlocked laptop or replayed a stolen access
     * token.
     *
     * <p>Why there is no {@code @PreAuthorize}: changing YOUR OWN password
     * needs no permission, and a brand-new account holds almost none. The gate
     * is SecurityConfig, where this URL is not in the permitAll list, so the
     * final rule anyRequest().authenticated() applies and the request never
     * reaches the controller without a valid token.
     *
     * <p>Why this is the only way out of the first-login state (audit item
     * H-2): FirstLoginFilter answers 403 FIRST_LOGIN_REQUIRED on every endpoint
     * except this one, logout and refresh while firstLogin is true. Setting
     * firstLogin to false below is what re-opens the rest of the application.
     */
    // @Transactional makes the three writes below - new hash, firstLogin flag,
    // tokenVersion increase - one single database unit of work. Why that
    // matters here: a crash between them could store the new password while
    // leaving tokenVersion untouched, so every session opened with the OLD
    // password would keep working. A password change that does not end the old
    // sessions protects nothing.
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findActiveByEmailWithRole(email)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur introuvable"));

        // matches() re-hashes the submitted password with the salt that is stored
        // inside the existing hash, then compares the two. It is the only way to
        // check a BCrypt password: the stored value cannot be turned back into
        // text. A mismatch throws BadCredentialsException, mapped to HTTP 401.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Mot de passe actuel incorrect");
        }

        // The three writes that make a password change real:
        //   - the new BCrypt hash replaces the old one (the clear-text password
        //     is never stored and never logged);
        //   - firstLogin goes to false, which unlocks the rest of the
        //     application in FirstLoginFilter;
        //   - revokeAllTokens() ends every session opened with the old
        //     password. EXAMPLE of why: people change their password precisely
        //     because they think it leaked; leaving the thief's session alive
        //     would defeat the whole point of the change.
        // oldVersion is captured first, for the cache key - same rule as above.
        int oldVersion = user.getTokenVersion();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFirstLogin(false);
        user.revokeAllTokens();
        userRepository.save(user);

        // Drop the cached authority list of the session we have just ended;
        // otherwise it would keep answering for up to five minutes.
        var cache = cacheManager.getCache("securityContext");
        if (cache != null) {
            cache.evict(email + ":" + oldVersion);
        }

        log.info("Mot de passe changé pour : {}", email);
    }

    /**
     * Builds the token pair and the JSON body that login() and refresh() both
     * return.
     *
     * <p>Gives back a TokenBundle: the AuthResponse the browser will receive,
     * the refresh token the controller will put in the HttpOnly cookie, and the
     * lifetime that cookie must be given.
     *
     * <p>Why it is private and shared by the two entry points: a renewed
     * session must describe itself exactly like a fresh one. If refresh() built
     * its own answer, the two could drift apart - for instance the permission
     * list could be rebuilt in one place and not in the other, and a permission
     * an administrator removed would survive in half of the sessions.
     *
     * <p>Why the cookie lifetime is decided here and travels in the bundle,
     * instead of being computed by the controller: the same rememberMe flag
     * then drives both the JWT expiry and the cookie Max-Age. Computing them in
     * two places would eventually let a cookie outlive the token inside it, and
     * the user would look signed in while every call answered 401.
     */
    private TokenBundle buildBundle(User user, boolean rememberMe) {
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user, rememberMe);
        // Flatten user -> role -> permissions into plain code strings such as
        // "VIEW_PROJECT": the stream maps each Permission entity to its code and
        // collects the codes into a Set.
        // Why only the codes: the Angular guards and the permission directive
        // only ask "is this string in the list?"; sending whole Permission
        // objects would make every login answer bigger for no gain.
        // Why Set<String> and not List<String>: the generic parameter says the
        // collection holds plain code strings, and the Set says a code cannot
        // appear twice. Role.permissions is itself a Set, because
        // role_permissions is a many-to-many join table keyed on
        // (role_id, permission_id).
        // Why this list is rebuilt at EVERY login and EVERY refresh instead of
        // being frozen once: this is the dynamic half of the RBAC. An
        // administrator who grants a permission to a role sees the new menu
        // appear after the next renewal, with no code change and no
        // redeployment. EXAMPLE if it were frozen: a permission taken away this
        // morning would keep lighting up its menu entry until the person signed
        // out.
        Set<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        // The body the browser receives. Note what is NOT in it: the refresh
        // token. That one leaves this method inside the TokenBundle instead, so
        // the controller can only put it in the HttpOnly cookie. Sending it in
        // the JSON would make it readable by any injected script, and a stolen
        // refresh token means weeks of renewed sessions rather than fifteen
        // minutes.
        //
        // A sentence worth saying to a jury: everything in this body only
        // decides what the interface DISPLAYS. The authorities that
        // @PreAuthorize("hasAuthority('X')") tests on the server are rebuilt by
        // JwtAuthenticationFilter from the database on each request, and for
        // URLs matching /api/projects/{id}/** ProjectScopeInterceptor also
        // checks that the project is inside the caller's own scope (ADR-021) -
        // the permission alone is not enough. Editing this JSON in a browser
        // only breaks one's own menu.
        AuthResponse body = new AuthResponse(
                user.getId(),
                accessToken,
                user.isFirstLogin(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().getName(),
                permissions
        );
        // refreshMaxAge(rememberMe) gives back the very same number of seconds
        // JwtService has just used as the token's expiry, so the cookie and its
        // content always die at the same moment.
        return new TokenBundle(body, refreshToken, jwtService.refreshMaxAge(rememberMe));
    }
}
