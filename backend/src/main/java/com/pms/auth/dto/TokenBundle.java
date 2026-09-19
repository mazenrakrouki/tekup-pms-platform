package com.pms.auth.dto;

// ============================================================================
// FILE: TokenBundle
//
// WHAT THIS FILE IS
//   The internal carrier that AuthService hands back to AuthController after
//   a sign-in or a token refresh. It holds three things that must be decided
//   together: the JSON body, the refresh token, and how long the cookie that
//   will hold that refresh token may live.
//
// WHERE IT SITS IN THE FLOW
//   AuthService.buildBundle(user, rememberMe) creates it - it is the return
//   type of both AuthService.login(request) and AuthService.refresh(token).
//     -> AuthController.login() / refresh() take it apart:
//          bundle.body()                 -> ResponseEntity.ok(...), the JSON
//          bundle.refreshToken()         -> the "pms_refresh" cookie value
//          bundle.refreshMaxAgeSeconds() -> that cookie's Max-Age
//   It never leaves the server. It is not serialized to JSON, and the browser
//   never sees this shape.
//
// WHY IT EXISTS: A LAYER BOUNDARY
//   A Java method can only return one value, and the service has to produce
//   two very different ones - a body and a cookie. The obvious alternatives
//   are both worse:
//     - put the refresh token inside AuthResponse: then it would be in the
//       JSON, readable by any script running on the page, and the whole point
//       of the HttpOnly cookie (audit item H-1) would be lost;
//     - give AuthService the HttpServletResponse and let it write the cookie
//       itself: then the service would depend on the servlet API, could no
//       longer be unit-tested without a fake HTTP response, and would break
//       the rule that HTTP concerns stay in the controller layer (ADR-016).
//   This record is the small third option: the service decides the values,
//   the controller decides how they are transported.
//
// WHY THERE IS NO PERMISSION CHECK AROUND IT
//   Nothing here is guarded by @PreAuthorize, because signing in and
//   refreshing happen before the caller has any authority at all: both paths
//   are permitAll() in SecurityConfig. What protects them is different -
//   the password, the rate limiter (H-2) for login, and for refresh the
//   signature check plus the tokenVersion comparison (ADR-017).
// ============================================================================

/**
 * Internal carrier: HTTP body plus the refresh token meant for the HttpOnly cookie.
 *
 * <p>{@code refreshMaxAgeSeconds} travels with the token so that the lifetime
 * of the cookie and the lifetime of the JWT are decided in the same place.
 * Both come from {@code JwtService.refreshMaxAge(rememberMe)}, the same method
 * that chose the token's own expiry. Separating them would let a cookie
 * outlive its content: the user would still look "signed in" while the token
 * inside is already expired.
 *
 * <p>Concrete example of that mismatch. Suppose the cookie were hardcoded to
 * 30 days while a normal refresh token lives 7 days. On day 8 the browser
 * still holds the cookie and still sends it, so the application believes a
 * session exists and calls /api/auth/refresh - which answers 401, because the
 * JWT inside expired the day before. The user is thrown back to the login
 * page for no reason they can see. The opposite mistake is just as bad: a
 * cookie shorter than the token silently ends a session that the server would
 * have accepted, and a user who ticked "remember me" is asked to sign in
 * again long before the promised 30 days.
 *
 * <p>Why a record rather than three parameters or a mutable holder: the three
 * values only make sense together and are read once, immediately, by the
 * controller. Making them final removes any chance of a later line replacing
 * the token while leaving the old duration in place.
 *
 * @param body                 what becomes the JSON answer: identity, access
 *                             token, first-login flag, role name for display,
 *                             and the permission codes the interface uses to
 *                             build its menu.
 * @param refreshToken         the long-lived JWT. The controller writes it
 *                             into the "pms_refresh" cookie with HttpOnly
 *                             (JavaScript cannot read it), SameSite=Strict
 *                             (the browser does not attach it to requests
 *                             started by another site, which blocks CSRF on
 *                             the refresh endpoint), Path=/api/auth/refresh
 *                             (it is not even sent to any other endpoint, so
 *                             it cannot leak through an unrelated call), and
 *                             Secure when pms.security.cookie-secure is true.
 *                             It is single-use: every refresh increments the
 *                             user's tokenVersion, so replaying the previous
 *                             one is detected and rejected (audit item H-1).
 * @param refreshMaxAgeSeconds the cookie's Max-Age. Seconds, as an int and not
 *                             a long, because that is what
 *                             jakarta.servlet.http.Cookie.setMaxAge expects;
 *                             JwtService.refreshMaxAge already casts the
 *                             configured value down.
 */
public record TokenBundle(AuthResponse body, String refreshToken, int refreshMaxAgeSeconds) {}
