package com.pms.auth.dto;

// Internal carrier AuthService hands to AuthController after login/refresh: the JSON body, the
// refresh token, and the cookie lifetime, decided together so they can never disagree. Never
// serialized — the browser never sees this shape. No @PreAuthorize: login/refresh run before
// the caller has any authority (both permitAll in SecurityConfig).

/**
 * Internal carrier: HTTP body plus the refresh token meant for the HttpOnly cookie.
 *
 * <p>{@code refreshMaxAgeSeconds} travels with the token so the cookie and the JWT inside it
 * always expire at the same moment — both come from {@code JwtService.refreshMaxAge(rememberMe)}.
 * A mismatched lifetime would either strand a "signed in"-looking user with an expired token, or
 * end a session the server would still have accepted.
 *
 * @param body                 the JSON answer: identity, access token, first-login flag, role
 *                             name for display, and permission codes for the menu.
 * @param refreshToken         the long-lived JWT, written by the controller into the
 *                             "pms_refresh" cookie (HttpOnly, SameSite=Strict,
 *                             Path=/api/auth/refresh, Secure when configured). Single-use:
 *                             every refresh bumps tokenVersion, so replay is detected (H-1).
 * @param refreshMaxAgeSeconds the cookie's Max-Age in seconds, as an int because that's what
 *                             jakarta.servlet.http.Cookie.setMaxAge expects.
 */
public record TokenBundle(AuthResponse body, String refreshToken, int refreshMaxAgeSeconds) {}
