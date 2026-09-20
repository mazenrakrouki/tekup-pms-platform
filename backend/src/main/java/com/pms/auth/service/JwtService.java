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

// The only class that signs or opens a JWT in this project. Used by AuthService (issuing both
// tokens) and by the two security filters (reading claims back). ADR-017: ACCESS tokens (900s)
// carry identity, role, permissions, tokenVersion, type="access", firstLogin, and travel in the
// Authorization header. REFRESH tokens (604800s, or 2592000s with "remember me") carry subject,
// tokenVersion, type="refresh", rememberMe, and live only in the HttpOnly "pms_refresh" cookie.
// The "type" claim is what keeps one from being used as the other, since both share the signing
// key. This class never grants anything itself — @PreAuthorize checks are rebuilt from the
// database on every request (see JwtAuthenticationFilter), so a copied/edited token can't widen
// anyone's rights. Every read goes through validateAndParse(), which re-verifies the signature —
// there is no decode-without-check path, since the JWT payload is plain base64, not secret.

/**
 * Creates and verifies the project's JSON Web Tokens.
 *
 * <p>One class for both directions so the signing key exists in exactly one place. Safe as a
 * shared singleton: after {@link #init()} the object never changes, and each method builds its
 * own jjwt builder/parser.
 */
@Service
@Slf4j
public class JwtService {

    // No default value after the colon: the app refuses to start without JWT_SECRET rather than
    // fall back to a hardcoded secret that would end up in Git. Used as raw UTF-8 bytes, so it
    // must be at least 64 characters for HMAC-SHA512.
    @Value("${pms.jwt.secret}")
    private String jwtSecret;

    // Access token lifetime in seconds (900 = 15 min). Short because this is the token
    // JavaScript can read; a leak is bounded to a quarter hour and can't be renewed without the
    // HttpOnly refresh cookie.
    @Value("${pms.jwt.access-token-expiration}")
    private long accessTokenExpirySeconds;

    // Refresh token lifetime in seconds (604800 = 7 days). Safe to be long: it never reaches
    // JavaScript, and it's single-use (AuthService.refresh() bumps tokenVersion at every
    // renewal, H-1), so a replayed copy is refused.
    @Value("${pms.jwt.refresh-token-expiration}")
    private long refreshTokenExpirySeconds;

    /**
     * Lifetime of a "remember me" session, in seconds (2592000 = 30 days). Has a default,
     * unlike pms.jwt.secret, because a missing duration is a comfort setting, not a security hole.
     */
    @Value("${pms.jwt.remember-me-expiration:2592000}")
    private long rememberMeExpirySeconds;

    private SecretKey signingKey;

    /**
     * Turns the configured secret text into the HMAC key every method here uses.
     *
     * <p>Runs in @PostConstruct rather than a constructor because Spring fills {@code @Value}
     * fields after construction. Keys.hmacShaKeyFor also rejects a key under 32 bytes, so a weak
     * secret fails fast at startup rather than during the first sign-in.
     */
    @PostConstruct
    void init() {
        signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the short-lived access token the browser sends on every call.
     *
     * <p>Role and permissions are embedded for the Angular UI (menu/buttons) only — the server
     * never trusts them; see the file header for where the real authorization decision happens.
     * The e-mail, not the numeric id, is the subject, since every consumer (both filters, the
     * cache key, findActiveByEmailWithRole) already works from the e-mail.
     */
    public String generateAccessToken(User user) {
        List<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .toList();

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
     * <p>{@code rememberMe} is written INSIDE the token so rotation can preserve it — otherwise
     * the first renewal would silently fall back to the short lifetime. Carries far fewer claims
     * than the access token (no role/permissions) since it's only ever exchanged for a fresh
     * pair, and AuthService.refresh() re-reads the account from the database anyway.
     */
    public String generateRefreshToken(User user, boolean rememberMe) {
        long ttl = rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds;
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
     * Gives back, in seconds, the lifetime the refresh cookie must be given — the very same
     * number used as the token's own expiry above, so the two can never drift apart.
     */
    public int refreshMaxAge(boolean rememberMe) {
        return (int) (rememberMe ? rememberMeExpirySeconds : refreshTokenExpirySeconds);
    }

    /**
     * Reads the "remember me" flag back out of a refresh token. False when the claim is absent
     * (a token issued before this feature existed).
     */
    public boolean extractRememberMe(String token) {
        Boolean flag = validateAndParse(token).get("rememberMe", Boolean.class);
        return Boolean.TRUE.equals(flag);
    }

    /**
     * Verifies a token's signature and expiry, then gives back its claims. Every other read in
     * this class goes through here — verifyWith(signingKey) is what makes the payload
     * trustworthy, since a decode-only reader would accept an edited claim.
     */
    public Claims validateAndParse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Gives back the e-mail the token was issued for. Verified, so it can be trusted as the
     * Spring Security principal.
     */
    public String extractEmail(String token) {
        return validateAndParse(token).getSubject();
    }

    /**
     * Gives back the revocation counter stored in the token (ADR-017), compared by callers
     * against User.getTokenVersion() to detect logout/password-change/deactivation/role-change.
     */
    public int extractTokenVersion(String token) {
        return validateAndParse(token).get("tokenVersion", Integer.class);
    }

    /**
     * Gives back "access" or "refresh" — the only thing that tells the two token families apart,
     * since both share a signing key. May be null for a token without the claim.
     */
    public String extractType(String token) {
        return validateAndParse(token).get("type", String.class);
    }

    /**
     * Returns true if the token carries firstLogin=true (user must change password). Read on
     * every request from the token itself rather than the database, to avoid defeating the
     * point of the securityContext cache.
     */
    public boolean extractFirstLogin(String token) {
        Boolean val = validateAndParse(token).get("firstLogin", Boolean.class);
        return Boolean.TRUE.equals(val);
    }

    /**
     * Answers true when the token is genuine and still valid, false otherwise — callers treat
     * "no valid token" as routine, not exception-worthy.
     */
    public boolean isTokenValid(String token) {
        // JwtException covers a bad signature/altered payload/expiry; IllegalArgumentException
        // covers a null or empty string. The reason is logged at DEBUG; the token itself never is.
        try {
            validateAndParse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalide : {}", e.getMessage());
            return false;
        }
    }
}
