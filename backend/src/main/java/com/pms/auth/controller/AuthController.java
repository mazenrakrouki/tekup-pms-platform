package com.pms.auth.controller;

import com.pms.auth.dto.AuthResponse;
import com.pms.auth.dto.ChangePasswordRequest;
import com.pms.auth.dto.LoginRequest;
import com.pms.auth.dto.TokenBundle;
import com.pms.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

// The HTTP door for signing in: login, refresh, logout, change-password. Splits AuthService's
// TokenBundle into a JSON body (access token) and a Set-Cookie header (refresh token, HttpOnly)
// so the two never travel the same way (H-1). No @PreAuthorize here: /login and /refresh are
// permitAll, and /logout and /change-password only need "somebody is logged in".

/**
 * REST endpoints published under /api/auth.
 *
 * <p>The refresh token never rides in the JSON body — it goes in an HttpOnly cookie that
 * JavaScript can't read, so an XSS payload can't turn a stolen access token into a
 * weeks-long session.
 */
@Tag(name = "Authentification", description = "Login, refresh token, logout, changement de mot de passe")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    // Name of the cookie carrying the refresh token; public so tests reuse the same text.
    public static final String REFRESH_COOKIE = "pms_refresh";

    private final AuthService authService;

    // pms.security.cookie-secure: true in production (HTTPS only). Left false by default so the
    // cookie isn't silently dropped by the browser on local http://localhost.
    @Value("${pms.security.cookie-secure:false}")
    private boolean cookieSecure;

    /**
     * Signs a user in and opens a session.
     *
     * <p>200 with the access token and user context, plus a Set-Cookie header holding the
     * refresh token. 401 on wrong password, 403 disabled account, 429 too many tries.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        TokenBundle bundle = authService.login(request);
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        return ResponseEntity.ok(bundle.body());
    }

    /**
     * Gives a new access token to a browser holding a valid refresh cookie, and replaces that
     * cookie with a fresh one.
     *
     * <p>200 with a new AuthResponse, or 401 when there is no cookie, it expired, or it was
     * already used once (rotation: every refresh token is single-use).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            // required = false: a missing/expired cookie is a normal case here, not a 400.
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenBundle bundle = authService.refresh(refreshToken);
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        return ResponseEntity.ok(bundle.body());
    }

    /**
     * Closes the caller's session: revokes his tokens and clears the refresh cookie.
     *
     * <p>The email comes from the security context, never the request body, so nobody can log
     * another user out on purpose.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal String email,
            HttpServletResponse response) {
        authService.logout(email);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lets the logged-in user replace his own password.
     *
     * <p>204 on success, 401 when the current password is wrong. Stays reachable while
     * firstLogin is true (FirstLoginFilter's allow list) — otherwise a forced password change
     * would have no page to happen on.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(email, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Writes the Set-Cookie header carrying the refresh token. Used by login and refresh.
     */
    private void addRefreshCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, value);
        // HttpOnly: JavaScript can't read this cookie, so an XSS payload can't harvest it.
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        // Scoped to the refresh endpoint only, so it isn't attached to every API call.
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(maxAgeSeconds);
        // SameSite=Strict via header (Servlet Cookie API doesn't support SameSite directly)
        response.addCookie(cookie);
        // Override with SameSite attribute.
        // Rebuilds the cookie as one header line so SameSite=Strict can be added — CSRF
        // protection is off elsewhere (stateless Bearer API), so this is what stops a
        // cross-site page from silently POSTing to /api/auth/refresh with the cookie attached.
        // setHeader (not addHeader) replaces the one addCookie just wrote.
        String header = String.format(
                "%s=%s; Path=/api/auth/refresh; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE, value, maxAgeSeconds,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }

    /**
     * Tells the browser to delete the refresh cookie. Used by logout.
     *
     * <p>Path/HttpOnly/Secure/SameSite are repeated exactly as in addRefreshCookie: the browser
     * only deletes a cookie whose name AND path match the one it holds.
     */
    private void clearRefreshCookie(HttpServletResponse response) {
        String header = String.format(
                "%s=; Path=/api/auth/refresh; Max-Age=0; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }
}
