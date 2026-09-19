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

/*
 * ---------------------------------------------------------------------------
 * WHAT THIS FILE IS
 * The one HTTP door for everything about signing in: log in, renew the short
 * access token, log out, and change your own password.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular AuthService (frontend .../core/services/auth.service.ts)
 *     -> Spring Security filter chain (SecurityConfig: JwtAuthenticationFilter,
 *        then FirstLoginFilter)
 *     -> THIS controller
 *     -> AuthService (com.pms.auth.service.AuthService), which itself uses
 *        JwtService, UserRepository and LoginAttemptTracker.
 * The answer goes back as JSON (an AuthResponse record) and, for login and
 * refresh, one extra "Set-Cookie" header that carries the refresh token.
 *
 * WHY IT EXISTS
 * AuthService gives back a TokenBundle, which holds two things that must NOT
 * travel the same way: the short access token (JSON body, read by JavaScript)
 * and the long refresh token (HttpOnly cookie, never readable by JavaScript).
 * This class is the only place that splits them and turns them into an HTTP
 * answer (H-1). Delete it and the Angular app can never obtain a token, so
 * every other endpoint answers 401 and the whole application is dead.
 *
 * WHY THERE IS NO @PreAuthorize IN THIS FILE
 * In PMS, permission checks sit on SERVICE methods, never on controllers, and
 * the code never tests a role NAME, only a permission code. Here there is
 * nothing left to check: /login and /refresh are opened to everybody in
 * SecurityConfig (permitAll), and /logout and /change-password only need
 * "somebody is logged in", which the filter chain already guarantees. Note
 * also that ADR-021 (ProjectScopeInterceptor) does not apply here: it only
 * guards URLs that look like /api/projects/{id}/**.
 * ---------------------------------------------------------------------------
 */

/**
 * REST endpoints published under /api/auth.
 *
 * <p>What it gives back: JSON for login and refresh (an {@link AuthResponse}:
 * access token + who the user is + his permission codes), and an empty 204 for
 * logout and for a password change.
 *
 * <p>Why written this way rather than the obvious alternative: the easy version
 * would return the refresh token inside the JSON body too, and let the Angular
 * code keep it in localStorage. We do not do that. Any injected script (XSS)
 * can read localStorage; it would then hold a long-lived token and could stay
 * connected as that user for weeks. Putting the refresh token in an HttpOnly
 * cookie means JavaScript cannot read it at all, so the controller has to build
 * that cookie by hand - that is the price of the two private helpers at the
 * bottom of this file.
 */
// @Tag only feeds the Swagger / OpenAPI documentation page: it groups these
// four endpoints under one heading with this description.
// Why: without it the generated page lists the routes under a machine name
// such as "auth-controller", and a reader of the API documentation has to
// guess what the group is about.
@Tag(name = "Authentification", description = "Login, refresh token, logout, changement de mot de passe")
// @RestController = @Controller + @ResponseBody: whatever a method returns is
// written straight into the response as JSON.
// Why: with a plain @Controller, Spring reads the returned value as the NAME OF
// AN HTML PAGE to display. Login would then fail with "view not found" instead
// of sending the token back.
@RestController
// Common prefix of every route below, so each method only declares its own last
// part ("/login", "/refresh", ...).
// Why: the prefix is written once. Repeated four times, a single typo such as
// "/api/atuh/logout" would silently create a route that nobody calls.
@RequestMapping("/api/auth")
// Lombok annotation: at build time it writes a constructor that takes every
// final field of the class. Spring then uses that constructor to hand over the
// beans (this is constructor injection).
// Why: without it nothing fills authService, and the very first login call ends
// in a NullPointerException.
@RequiredArgsConstructor
public class AuthController {

    // Name of the cookie that carries the refresh token, kept in one single
    // place and public so that tests and other classes reuse the same text.
    // Why: login writes this cookie and refresh reads it. If one side said
    // "pms_refresh" and the other "pms-refresh", the browser would send nothing
    // back, and every session would die as soon as the access token expires.
    public static final String REFRESH_COOKIE = "pms_refresh";

    // All the real work (password check, rate limiting, token rotation) lives in
    // this service. The controller only translates HTTP in and HTTP out.
    private final AuthService authService;

    // @Value reads the setting pms.security.cookie-secure from application.yml
    // (or from an environment variable) when the application starts. The
    // ":false" part is the value used when the setting is absent.
    // "Secure" means: the browser only sends this cookie over HTTPS.
    // Why a setting and not a fixed true: in local development the app runs on
    // http://localhost, where a Secure cookie is simply dropped by the browser -
    // login would look fine but every refresh would answer 401. In production it
    // must be set to true, otherwise the refresh token can travel in clear text
    // and be read on the network.
    @Value("${pms.security.cookie-secure:false}")
    private boolean cookieSecure;

    /**
     * Signs a user in and opens a session.
     *
     * <p>Gives back 200 with the access token and the user context (id, email,
     * full name, role name, permission codes), plus a Set-Cookie header holding
     * the refresh token. When the password is wrong the service throws and the
     * answer becomes 401; a disabled account gives 403; too many tries give 429.
     *
     * <p>Why it is written this way: the method asks for the raw
     * HttpServletResponse as a parameter instead of only returning an object.
     * That is the only way to add our own Set-Cookie header, and the refresh
     * token must never appear in the JSON body.
     */
    // @PostMapping = this method answers POST /api/auth/login. The route is open
    // to anonymous callers (SecurityConfig: permitAll).
    // Why POST and not GET: a GET carries its data in the URL. The password
    // would then land in the browser history, in proxy logs and in the server
    // access log, readable by anyone who can open those files.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            // @Valid switches on Bean Validation for the incoming object. The
            // rules live on LoginRequest itself (@NotBlank, @Email).
            // Why: without it an empty or malformed email reaches the service,
            // which then runs a bcrypt comparison (bcrypt = a deliberately slow
            // password hashing function) for nothing. With it, Spring stops the
            // call before the method body and the caller gets a clean 400 that
            // names the bad fields.
            // @RequestBody turns the JSON sent by the browser into the record.
            @Valid @RequestBody LoginRequest request,
            // The raw servlet response, asked for only so that we can write the
            // Set-Cookie header ourselves a few lines below.
            HttpServletResponse response) {
        // The service checks the rate limit, verifies the password and builds
        // both tokens. TokenBundle is the small carrier that keeps the JSON body
        // and the refresh token together with the exact lifetime of that token.
        TokenBundle bundle = authService.login(request);
        // The refresh token leaves through the cookie, never through the body.
        // The lifetime comes from the bundle, so the cookie and the token inside
        // it always expire at the same moment.
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        // 200 with only the body part of the bundle. bundle.refreshToken() is on
        // purpose not part of what gets serialized here.
        return ResponseEntity.ok(bundle.body());
    }

    /**
     * Gives a new short access token to a browser that still holds a valid
     * refresh cookie, and replaces that cookie with a fresh one.
     *
     * <p>Gives back 200 with a new AuthResponse, or 401 when there is no cookie,
     * when the token has expired, or when it was already used once (H-1
     * rotation: every refresh token can be used one time only).
     *
     * <p>Why a new cookie is written on every call and not only at login: the
     * service raises the user tokenVersion counter at each refresh, which kills
     * the token the browser just sent. Without overwriting the cookie here, the
     * browser would keep that dead token and the next refresh would answer
     * "Token revoke" - the user would be thrown out a few minutes after signing
     * in.
     */
    // Open route (SecurityConfig: permitAll), because the caller has no valid
    // access token any more - that is exactly why he is here. His proof of
    // identity is the cookie, which the service checks.
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            // @CookieValue pulls one named cookie out of the request.
            // required = false means "do not fail when the cookie is missing".
            // Why: with the default (required = true) Spring answers 400 Bad
            // Request for a perfectly normal case - a visitor who never logged
            // in, or whose cookie has expired. We prefer to answer a clean 401
            // ourselves, which is what the Angular interceptor knows how to
            // handle.
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        // Guard against both "no cookie at all" and "cookie present but empty".
        // Why: an empty string is not a JWT, so the parser inside the service
        // would throw a low-level parsing error. Stopping here keeps the normal
        // "not logged in" case cheap and keeps the logs quiet.
        if (refreshToken == null || refreshToken.isBlank()) {
            // 401 with no body: there is nothing useful to tell an anonymous
            // caller, and a detailed message would only help someone who is
            // probing the API.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // The service checks the signature, the expiry, that the token type is
        // "refresh" and not "access", and that the tokenVersion written inside
        // the token still matches the one stored on the user (H-1 reuse
        // detection). Anything wrong throws, and the global exception handler
        // turns it into a 401.
        TokenBundle bundle = authService.refresh(refreshToken);
        // Rotation: this is a brand new refresh token. It has to replace the old
        // one inside the browser, see the note above the method.
        addRefreshCookie(response, bundle.refreshToken(), bundle.refreshMaxAgeSeconds());
        return ResponseEntity.ok(bundle.body());
    }

    /**
     * Closes the session of the caller: the server revokes his tokens and the
     * browser is told to drop the refresh cookie.
     *
     * <p>Gives back 204 (No Content), because there is nothing to show after a
     * logout.
     *
     * <p>Why the email is taken from the security context and not from the
     * request body: if the client could send the email to log out, anybody could
     * post somebody else address and disconnect that person on purpose.
     */
    // This route is not in the permitAll list of SecurityConfig, so the filter
    // chain has already refused the call when no valid access token was sent.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            // @AuthenticationPrincipal injects the "principal" that
            // JwtAuthenticationFilter placed in the SecurityContext after
            // reading the access token. In PMS that principal is simply the
            // email String, not a UserDetails object.
            // Why it can never be null here: the route requires authentication,
            // so Spring Security stopped the request before this method when
            // nobody was logged in.
            @AuthenticationPrincipal String email,
            HttpServletResponse response) {
        // Server side: the service raises the tokenVersion counter of the user.
        // Every token signed with the old number stops working at once, so ALL
        // the sessions of this user die together - including one left open on
        // another computer.
        authService.logout(email);
        // Browser side: without this, the cookie stays in the browser until its
        // own expiry date. It would be useless (revoked just above), but the
        // browser would keep sending it and each automatic refresh would answer
        // 401 instead of a clean "you are logged out".
        clearRefreshCookie(response);
        // 204: it worked, and on purpose there is no body.
        return ResponseEntity.noContent().build();
    }

    /**
     * Lets the logged-in user replace his own password.
     *
     * <p>Gives back 204 when it worked, 401 when the current password is wrong.
     *
     * <p>Why the current password is asked again although the user is already
     * authenticated: without that check, somebody who walks up to an unlocked
     * computer, or who stole an access token, could set a new password and keep
     * the account for good.
     *
     * <p>Note: this endpoint stays reachable for a user whose firstLogin flag is
     * still true. FirstLoginFilter keeps /api/auth/change-password in its short
     * allow list; otherwise a user forced to change his password would be
     * blocked from the only page that lets him do it.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            // Same idea as logout: the identity comes from the verified token,
            // never from the payload. Otherwise the body could name another user
            // and this would become a "change anybody password" endpoint.
            @AuthenticationPrincipal String email,
            // @Valid applies the rules written on ChangePasswordRequest: both
            // fields @NotBlank, and the new password @Size(min = 8).
            // Why here and not only in the Angular form: the browser check can
            // be skipped by calling the API directly with curl or Postman.
            @Valid @RequestBody ChangePasswordRequest request) {
        // The service verifies the old password, stores the bcrypt hash of the
        // new one (so the database never holds a readable password), clears the
        // firstLogin flag, and revokes every existing token of that user.
        authService.changePassword(email, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Writes the "Set-Cookie" header that carries the refresh token to the
     * browser. Used by both login and refresh.
     *
     * <p>Gives back nothing: it only adds a header to the response being built.
     *
     * <p>Why the header is finally written by hand instead of using the Cookie
     * object alone: the Cookie class has no setter for the SameSite attribute,
     * and SameSite=Strict is exactly the protection needed here (see below). So
     * the Cookie object is built for its flags, and then the complete header
     * text replaces it.
     */
    private void addRefreshCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, value);
        // HttpOnly: JavaScript running in the page cannot read this cookie
        // through document.cookie.
        // Why: if a script injected into the page (XSS) could read it, the
        // attacker would hold a long-lived refresh token and could keep making
        // himself new access tokens for days, even after the user closed the tab.
        cookie.setHttpOnly(true);
        // Secure: the browser only sends the cookie over HTTPS. Driven by the
        // setting read at the top of this class, see the comment there.
        cookie.setSecure(cookieSecure);
        // Path: the browser attaches this cookie ONLY to requests whose URL
        // starts with /api/auth/refresh.
        // Why: a cookie with Path=/ would be glued to every single API call of
        // the application. The long-lived token would then cross the network
        // dozens of times per screen instead of once every few minutes, which
        // multiplies the chances of it leaking (proxy log, browser extension,
        // crash report).
        cookie.setPath("/api/auth/refresh");
        // The lifetime comes from the service, which already used the same
        // number when it signed the JWT: the two cannot drift apart and let a
        // cookie outlive the token it carries.
        cookie.setMaxAge(maxAgeSeconds);
        // SameSite=Strict via header (Servlet Cookie API doesn't support SameSite directly)
        response.addCookie(cookie);
        // Override with SameSite attribute.
        // The lines below build the same cookie again, as one text line, and add
        // the one piece the Cookie object cannot express: SameSite=Strict, which
        // tells the browser never to send this cookie on a request started by
        // another web site.
        // Why it matters: CSRF protection is switched off in SecurityConfig (the
        // API is stateless and uses Bearer tokens). Without SameSite=Strict, a
        // page on evil.com could POST to /api/auth/refresh in the background, the
        // browser would happily attach the cookie, and that site would receive a
        // valid access token for our user.
        // setHeader (and not addHeader) replaces the header that addCookie wrote
        // just above, so exactly one Set-Cookie leaves with this response.
        // In the format text, %s is a piece of text and %d a number; the
        // "; Secure" part is added only when the setting asks for it.
        String header = String.format(
                "%s=%s; Path=/api/auth/refresh; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE, value, maxAgeSeconds,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }

    /**
     * Tells the browser to delete the refresh cookie. Used by logout.
     *
     * <p>Gives back nothing: it only adds a header to the response.
     *
     * <p>Why an empty value plus Max-Age=0 instead of "removing" the cookie: a
     * server cannot reach inside the browser. The only way to erase a cookie is
     * to send the same cookie back with a lifetime that is already over, and
     * Max-Age=0 means exactly that - delete it now.
     *
     * <p>Why Path, HttpOnly, Secure and SameSite are repeated here: the browser
     * only deletes a stored cookie when the name AND the path match the ones it
     * keeps. A deletion sent with a different Path would create a second, empty
     * cookie and leave the real one in place.
     */
    private void clearRefreshCookie(HttpServletResponse response) {
        String header = String.format(
                "%s=; Path=/api/auth/refresh; Max-Age=0; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE,
                cookieSecure ? "; Secure" : "");
        response.setHeader("Set-Cookie", header);
    }
}
