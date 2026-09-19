package com.pms.auth.service;

import com.pms.user.entity.Permission;
import com.pms.user.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

// =============================================================================
// FILE: JwtService.java
//
// WHAT THIS FILE IS
//   The only class in the project that signs a JWT, and the only one that opens
//   one. A JWT (JSON Web Token) is a short text made of three parts separated
//   by dots: who the token is about, a few named values called "claims", and a
//   signature computed with a secret key. Anybody can READ a JWT; only the
//   holder of the secret can produce one the server will accept.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  AuthService (to create the access token and the refresh token),
//     JwtAuthenticationFilter (to check the token of every incoming request and
//     read the e-mail and the token version out of it) and FirstLoginFilter (to
//     read the firstLogin claim).
//   Calls:      the jjwt library (Jwts, Keys) and nothing else. It touches no
//     database and keeps no state beyond the signing key, which is why one
//     single instance can serve every request at the same time.
//
// WHY IT EXISTS
//   Delete it and there is no way to prove, on request number two, that the
//   password was checked on request number one. The alternative would be a
//   server-side session table that every node of a deployment has to share.
//
// THE TWO TOKENS IT PRODUCES (ADR-017)
//   ACCESS  - 900 s. Carries the subject (e-mail), role, permissions,
//             tokenVersion, type="access" and firstLogin. Sent by the browser
//             in the Authorization header.
//   REFRESH - 604800 s, or 2592000 s with "remember me". Carries the subject,
//             tokenVersion, type="refresh" and rememberMe. Lives only inside
//             the HttpOnly cookie "pms_refresh".
//   The "type" claim is what keeps one from being used in the other's place:
//   both are signed with the SAME key, so the signature alone cannot tell them
//   apart. EXAMPLE of the hole it closes: without it, an access token read out
//   of JavaScript could be posted to /api/auth/refresh and exchanged for a
//   fresh long-lived pair.
//
// WHAT THIS CLASS DELIBERATELY DOES NOT DECIDE
//   It never grants anything. The permission list it writes into the access
//   token is there for the interface only; the authorities that
//   @PreAuthorize("hasAuthority('X')") tests on the service methods are rebuilt
//   from the DATABASE by JwtAuthenticationFilter on every request, through the
//   "securityContext" cache. And for URLs matching /api/projects/{id}/**,
//   ProjectScopeInterceptor additionally checks the project scope (ADR-021), so
//   the permission alone is not enough. A token therefore cannot widen anyone's
//   rights, even if its content is read or copied.
//
// WHY EVERY READ RE-VERIFIES THE SIGNATURE
//   All the extract... methods go through validateAndParse(), which calls
//   parseSignedClaims(). There is no "read without checking" path in this file,
//   on purpose. EXAMPLE of what that prevents: the middle part of a JWT is
//   ordinary base64 and is not secret at all, so a method that merely decoded
//   it would happily return any e-mail or any permission an attacker typed in
//   there.
// =============================================================================

/**
 * Creates and verifies the project's JSON Web Tokens.
 *
 * <p>Why a single class for both directions: the secret key is used to sign AND
 * to verify, and it should exist in exactly one place. Two classes would mean
 * two copies of the key, and a key that gets copied is a key that ends up in a
 * test file.
 *
 * <p>Why it is a singleton bean and still safe under load: after {@link #init()}
 * the object never changes again, and the jjwt builder and parser are created
 * fresh inside each method, so two requests never share mutable state.
 */
// @Service makes this a Spring bean, so AuthService and the two security filters
// can receive it. It is also what makes Spring fill the @Value fields below at
// startup; an object built by hand with "new JwtService()" would have a null
// secret and could sign nothing.
@Service
// Lombok adds the "log" object. The only thing written to the log in this file
// is the reason a token was rejected, at DEBUG level - never the token itself,
// because that string is a credential.
@Slf4j
public class JwtService {

    // @Value injects the value of pms.jwt.secret from application.yml, which
    // itself reads the JWT_SECRET environment variable. Note there is no
    // default after a colon: the application REFUSES TO START when the variable
    // is missing.
    // WHY no default: a hard-coded fallback secret would end up in Git, and
    // anybody who could read the repository would then be able to sign a token
    // claiming to be the administrator. Crashing at startup is the loud, safe
    // failure.
    // The text is used as raw UTF-8 bytes (see init()), not as base64, so it
    // must be at least 64 characters long for HMAC-SHA512 to be chosen.
    @Value("${pms.jwt.secret}")
    private String jwtSecret;

    // Lifetime of the access token, in seconds (900 = 15 minutes in
    // application.yml).
    // WHY so short: this is the token JavaScript can read, since the Angular
    // interceptor has to put it into the Authorization header. Keeping it short
    // bounds the damage of a leak. EXAMPLE: a token copied by an injected
    // script stops working within a quarter of an hour, and it cannot be
    // renewed without the HttpOnly cookie that same script cannot read.
    @Value("${pms.jwt.access-token-expiration}")
    private long accessTokenExpirySeconds;

    // Lifetime of an ordinary refresh token, in seconds (604800 = 7 days).
    // WHY it is allowed to be long: this one never reaches JavaScript. It lives
    // in a cookie marked HttpOnly and scoped to Path=/api/auth/refresh, so the
    // browser only ever sends it to the renewal endpoint. It is also single-use:
    // AuthService.refresh() increases tokenVersion at every renewal (audit item
    // H-1), so a copy that is replayed is refused.
    @Value("${pms.jwt.refresh-token-expiration}")
    private long refreshTokenExpirySeconds;

    /**
     * Lifetime of a "remember me" session, in seconds. 2592000 = 30 days.
     *
     * <p>The value must match the wording shown in the sign-in form: promising
     * the user thirty days and expiring after seven would look like a bug to
     * them.
     *
     * <p>Why this one has a default (:2592000) while pms.jwt.secret has none: a
     * missing duration is a comfort setting with an obvious sane value, while a
     * missing secret is a security hole.
     */
    @Value("${pms.jwt.remember-me-expiration:2592000}")
    private long rememberMeExpirySeconds;

    // The key used both to sign and to verify. Built once in init() and never
    // exposed by a getter. Why a SecretKey object and not the raw String: the
    // jjwt API works with keys, and rebuilding one from the text on every call
    // would waste work and spread the secret over more of the code.
    private SecretKey signingKey;

    /**
     * Turns the configured secret text into the HMAC key every method of this
     * class uses.
     *
     * <p>Returns nothing; it only fills {@link #signingKey}.
     *
     * <p>Why here and not in a constructor: Spring fills {@code @Value} fields
     * AFTER the object has been built, so the secret is still null while a
     * constructor runs. A constructor version would fail at startup with a
     * NullPointerException.
     *
     * <p>Keys.hmacShaKeyFor also refuses a key that is too short (it throws
     * WeakKeyException below 32 bytes), and it is the key LENGTH that decides
     * which HMAC-SHA variant jjwt will use. So "is the secret strong enough?"
     * is answered once, at startup, instead of being discovered during the
     * first sign-in.
     */
    // @PostConstruct: Spring calls this exactly once, after injection and before
    // the bean serves anything. Without it signingKey would stay null and the
    // very first token operation would throw.
    @PostConstruct
    void init() {
        signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the short-lived access token the browser sends on every call.
     *
     * <p>Gives back the compact token string - three base64 parts separated by
     * dots - ready to be placed in an "Authorization: Bearer ..." header.
     *
     * <p>Why the role and the permissions are written inside it even though the
     * server does not trust them: the Angular side reads them to build the menu
     * and to hide buttons, and carrying them here saves an extra HTTP call after
     * every renewal. The authorization decision itself is taken elsewhere, from
     * the database - see the file header.
     *
     * <p>Why the e-mail and not the numeric id is used as the subject: every
     * consumer of the token (the two filters, the "securityContext" cache key,
     * the repository query findActiveByEmailWithRole) works from the e-mail, so
     * one identifier is enough and no extra lookup is needed.
     */
    public String generateAccessToken(User user) {
        // Flatten role -> permissions into plain codes such as "VIEW_PROJECT".
        // .toList() gives back an unmodifiable list, which is all jjwt needs in
        // order to write the claim; nothing is supposed to add to it afterwards.
        // Why a List here while AuthResponse uses a Set: this one is only
        // serialised into the token, while the other is meant to be searched by
        // the client.
        List<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .toList();

        // Each .claim(...) writes one named value into the token's payload.
        //   subject(email)     - who the token is about. extractEmail() reads it
        //                        back, and it becomes the Spring Security
        //                        principal, which is why controllers can simply
        //                        write @AuthenticationPrincipal String email.
        //   role, permissions  - for the interface only, see the javadoc above.
        //   tokenVersion       - the revocation counter of ADR-017.
        //                        JwtAuthenticationFilter compares it with the
        //                        value stored on the user row and refuses the
        //                        request when the two differ. WITHOUT it, a
        //                        user who has signed out or been deactivated
        //                        would keep working until the token expired on
        //                        its own.
        //   type = "access"    - lets AuthService.refresh() tell the two token
        //                        families apart; they carry the same signature
        //                        otherwise.
        //   firstLogin         - read by FirstLoginFilter, which then answers
        //                        403 FIRST_LOGIN_REQUIRED everywhere except the
        //                        change-password, logout and refresh endpoints
        //                        (audit item H-2). WITHOUT it the check would
        //                        live only in the Angular router, and a user
        //                        could skip it by calling the API directly and
        //                        keep for ever the password the administrator
        //                        generated for them.
        //   expiration         - computed from Instant.now(). After that moment
        //                        parseSignedClaims() throws and isTokenValid()
        //                        answers false.
        //   signWith(key)      - the signature. WITHOUT it anybody could edit
        //                        the permissions claim by hand and send the
        //                        token back.
        //   compact()          - renders the three parts as the final string.
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().getName())
                .claim("permissions", permissions)
                .claim("tokenVersion", user.getTokenVersion())
                .claim("type", "access")
                .claim("firstLogin", user.isFirstLogin())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(accessTokenExpirySeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Builds the long-lived refresh token that lives in the HttpOnly cookie.
     *
     * <p>Gives back the compact token string. AuthController puts it into the
     * "pms_refresh" cookie; it is never part of any JSON body.
     *
     * <p>The {@code rememberMe} flag is written INSIDE the token. That is what
     * lets rotation preserve it: without this claim, the token issued at the
     * first renewal would fall back to the short lifetime and the long session
     * would disappear without the user understanding why.
     *
     * <p>Why it carries far fewer claims than the access token - no role, no
     * permissions: this token is only ever exchanged for a new pair, and
     * AuthService.refresh() re-reads the account from the database anyway.
     * Every claim that is not needed is one more thing that would go stale
     * inside a token that may live thirty days.
     */
    public String generateRefreshToken(User user, boolean rememberMe) {
        // One flag, two configured lifetimes. Keeping the choice in a single
        // expression means the value used for the JWT just below and the one
        // refreshMaxAge() gives to the cookie can never be computed differently.
        long ttl = rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds;
        // The same builder as the access token, minus role and permissions.
        //   type = "refresh" is what AuthService.refresh() checks before
        //     accepting the token; without it an access token could be presented
        //     there and exchanged for a fresh long-lived pair.
        //   tokenVersion is what makes this token single-use: refresh()
        //     increases the counter, so this exact string is refused the next
        //     time it shows up (audit item H-1, reuse detection).
        //   rememberMe is the flag explained in the javadoc above.
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("tokenVersion", user.getTokenVersion())
                .claim("type", "refresh")
                .claim("rememberMe", rememberMe)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(ttl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Gives back, in seconds, the lifetime the refresh cookie must be given.
     *
     * <p>It is the very same number used as the token's expiry just above, and
     * that is the whole point of the method: the cookie and the token inside it
     * are decided in one place and cannot drift apart. If the cookie outlived
     * its content, the user would look signed in while every renewal answered
     * 401.
     *
     * <p>The cast to int is required by the Servlet cookie API, whose Max-Age
     * is an int. The largest value in play is 2592000, far below the limit of an
     * int, so nothing is lost.
     */
    public int refreshMaxAge(boolean rememberMe) {
        return (int) (rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds);
    }

    /**
     * Reads the "remember me" flag back out of a refresh token.
     *
     * <p>Gives back false when the claim is absent, which is what a token issued
     * before this feature existed looks like. Boolean.TRUE.equals(flag) handles
     * that in one step: it is false for null as well as for false, so unboxing
     * the Boolean can never throw a NullPointerException.
     */
    public boolean extractRememberMe(String token) {
        Boolean flag = validateAndParse(token).get("rememberMe", Boolean.class);
        return Boolean.TRUE.equals(flag);
    }

    /**
     * Verifies a token's signature and expiry date, then gives back its claims.
     *
     * <p>This is the single door every other read in this class goes through.
     * It returns the payload as a Claims map, and throws a JwtException - or an
     * IllegalArgumentException for an empty string - when the token is forged,
     * altered or expired.
     *
     * <p>verifyWith(signingKey) is what makes the content trustworthy:
     * parseSignedClaims() recomputes the signature and refuses the token when it
     * does not match. EXAMPLE of what a non-verifying parse would allow: the
     * middle part of a JWT is ordinary base64, so anybody could turn
     * "permissions":["VIEW_PROJECT"] into ["MANAGE_USERS"], and a decode-only
     * reader would believe it.
     *
     * <p>Why a new parser is built on each call instead of keeping one in a
     * field: the builder object is not meant to be shared between threads, and
     * building it is cheap next to the HMAC computation that follows.
     */
    public Claims validateAndParse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Gives back the e-mail the token was issued for - its "subject".
     *
     * <p>It goes through validateAndParse, so the value can be trusted: it is
     * the string this server itself signed, not something the caller typed.
     * JwtAuthenticationFilter uses it as the Spring Security principal, which is
     * why controllers can write {@code @AuthenticationPrincipal String email}
     * and be sure of who is calling.
     */
    public String extractEmail(String token) {
        return validateAndParse(token).getSubject();
    }

    /**
     * Gives back the revocation counter stored in the token (ADR-017).
     *
     * <p>Callers compare it with User.getTokenVersion() in the database and
     * refuse the request when the two differ. That is how a sign-out, a password
     * change, a deactivation or a role change end every existing session at
     * once, even though a JWT itself cannot be deleted.
     *
     * <p>The claim is read as an Integer and returned as an int, so the value is
     * unboxed. That is safe here because every token this application issues
     * writes the claim, and a token without it would be one this server never
     * signed - in which case validateAndParse would already have rejected it on
     * the signature.
     */
    public int extractTokenVersion(String token) {
        return validateAndParse(token).get("tokenVersion", Integer.class);
    }

    /**
     * Gives back "access" or "refresh", the family the token belongs to.
     *
     * <p>Both families are signed with the same key, so this claim is the only
     * thing that keeps them apart. AuthService.refresh() and
     * JwtAuthenticationFilter both check it before doing anything else.
     *
     * <p>It may return null for a token carrying no type claim, which is why
     * callers compare with the literal first, as in "refresh".equals(...).
     */
    public String extractType(String token) {
        return validateAndParse(token).get("type", String.class);
    }

    /**
     * Returns true if the token carries firstLogin=true (user must change password).
     *
     * <p>Read by FirstLoginFilter, which then answers 403 FIRST_LOGIN_REQUIRED
     * on every endpoint except change-password, logout and refresh (audit item
     * H-2). Boolean.TRUE.equals(...) makes a missing claim behave as false
     * instead of throwing while the Boolean is unboxed.
     *
     * <p>Why the flag is read from the token here and not from the database:
     * this runs on every single request, and the whole point of the
     * "securityContext" cache is to avoid a database round trip. The token is
     * re-issued at each renewal, so the flag follows the account within at most
     * one access-token lifetime.
     */
    public boolean extractFirstLogin(String token) {
        Boolean val = validateAndParse(token).get("firstLogin", Boolean.class);
        return Boolean.TRUE.equals(val);
    }

    /**
     * Answers true when the token is genuine and still valid, false otherwise.
     *
     * <p>Why a boolean instead of letting the exception travel: the callers -
     * JwtAuthenticationFilter on every request, AuthService.refresh() - treat
     * "no valid token" as an ordinary outcome, not as an error worth a stack
     * trace. Otherwise an anonymous visitor reaching a public URL would fill
     * the log with exceptions.
     */
    public boolean isTokenValid(String token) {
        // The try/catch is what turns a bad token into a plain "false" instead of
        // an exception travelling up the filter chain.
        // JwtException covers a wrong signature, an altered payload and an
        // expired token; IllegalArgumentException covers a null or empty string,
        // which jjwt refuses before even looking at it. Catching both means a
        // missing or malformed Authorization header cannot break the request.
        // The reason is logged at DEBUG and the token itself is NEVER logged: a
        // token is a credential, and a log file is often the least protected
        // place in a deployment.
        try {
            validateAndParse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalide : {}", e.getMessage());
            return false;
        }
    }
}
