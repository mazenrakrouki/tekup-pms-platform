package com.pms.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// ============================================================================
// FILE: LoginRequest
//
// WHAT THIS FILE IS
//   The JSON body sent to sign in: e-mail, password, and whether the user
//   ticked "remember me". It is a DTO (Data Transfer Object): a small flat
//   object built only to carry values over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   login.component.ts calls
//   auth.login({ email, password, rememberMe }) -> POST /api/auth/login
//     -> AuthController.login(@Valid @RequestBody LoginRequest request, ...)
//     -> AuthService.login(request), which
//          1. asks LoginAttemptTracker whether this e-mail is blocked,
//          2. looks the user up, compares the password with bcrypt,
//          3. returns a TokenBundle built by buildBundle(user, rememberMe)
//     -> the controller sends AuthResponse as the body and puts the refresh
//        token in the HttpOnly "pms_refresh" cookie.
//
// WHY IT EXISTS
//   It is the only entry point into the whole application. Deleting it would
//   leave no way to obtain an access token, and since SecurityConfig ends
//   with anyRequest().authenticated(), every other endpoint would answer 401
//   forever.
//
// WHY THIS ENDPOINT IS OPEN TO EVERYONE
//   /api/auth/login is one of the two paths listed as permitAll() in
//   SecurityConfig (the other is /api/auth/refresh). It has to be: the caller
//   has no token yet. That is exactly why the brute-force protection of audit
//   item H-2 lives right behind it - LoginAttemptTracker allows 5 failures
//   per e-mail in a 15-minute sliding window, then answers 429, and
//   AuthService always runs one bcrypt comparison even when the e-mail is
//   unknown so that the response time cannot be used to find out which
//   addresses exist.
//
// A WORD ON THE CLEAR-TEXT PASSWORD IT HOLDS
//   The password field holds the real password, in clear text, in memory, for
//   the few milliseconds of the call. Bcrypt cannot compare a password
//   without seeing it. What matters is that this object is never logged,
//   never stored and never echoed back; only the stored bcrypt hash is ever
//   compared against it.
// ============================================================================

/**
 * Credentials for POST /api/auth/login.
 *
 * <p>Why a record: the three values are read once by AuthService and then
 * dropped. Making them final means no filter or interceptor can change the
 * e-mail between the rate-limit check and the password check - which would be
 * a way to make the limiter count failures against a different account than
 * the one really being attacked.
 *
 * <p>Why this one is NOT produced by MapStruct (ADR-018): a mapper copies an
 * entity into a DTO. This DTO comes from the outside world and matches no
 * entity: there is no "password" column, only "password_hash".
 */
public record LoginRequest(
        // The account e-mail.
        //
        // @NotBlank rejects null, "" and values made only of spaces.
        // @Email checks that what is left looks like an address
        // (something@something).
        //
        // Why BOTH are needed, and not @Email alone: @Email deliberately
        // accepts null and the empty string, because saying "this field is
        // required" is @NotBlank's job. Example of what would go wrong with
        // @Email alone: a body with "email": "" would pass validation, reach
        // AuthService, and be used as the key of the rate limiter and of the
        // user lookup - so every anonymous attacker in the world would share
        // one single rate-limit bucket.
        //
        // Both are enforced on the SERVER, triggered by the @Valid annotation
        // on the controller parameter; a violation is turned into 400 Bad
        // Request by GlobalExceptionHandler before any service code runs.
        // The Angular form checks the same thing, but that check can be
        // skipped by anyone posting with curl.
        //
        // This value is also the key of three other things: the sliding
        // window of LoginAttemptTracker, the "sub" (subject) claim of both
        // JWTs, and the "securityContext" cache key of ADR-017.
        @NotBlank @Email String email,

        // The password typed by the user, in clear text.
        //
        // @NotBlank only. There is deliberately no @Size here, unlike
        // ChangePasswordRequest: this is a CHECK against an existing hash, not
        // a choice of a new password. Example of what would go wrong with a
        // minimum length here: an account created before a stricter rule
        // would become impossible to sign into, instead of simply failing the
        // password comparison.
        @NotBlank String password,

        /**
         * Long session asked for by the user ("remember me").
         *
         * <p>Primitive boolean: when the field is absent from the JSON it
         * stays false, so a client that does not send it keeps the current
         * behaviour with no change on its side. A boxed Boolean would arrive
         * as null instead and every reader would have to guard against it.
         *
         * <p>What it changes downstream: AuthService passes it to
         * buildBundle, JwtService.generateRefreshToken writes it into the
         * refresh token as a "rememberMe" claim and picks the long lifetime
         * (pms.jwt.remember-me-expiration) instead of the short one, and
         * JwtService.refreshMaxAge gives the controller the matching cookie
         * Max-Age. Because the flag lives inside the token itself, the
         * rotation performed on every refresh can read it back and keep the
         * long session alive; without that claim the first refresh would
         * silently drop the user back to the short duration.
         */
        boolean rememberMe
) {
    /**
     * Sign-in without a long session. Keeps callers that were written with
     * two arguments compiling, and makes it explicit that a missing flag
     * means {@code false}.
     *
     * <p>Who uses it in practice: the integration tests. AuthControllerTest,
     * ProjectControllerTest, TeamControllerTest and the others build their
     * login body with {@code new LoginRequest(email, password)} because
     * "remember me" is irrelevant to what they check. Without this
     * constructor every one of those lines would have to end in
     * {@code , false}, which says nothing to the reader.
     *
     * <p>Note for the JSON side: this shorter form is a convenience for Java
     * code only. A body arriving over HTTP is always built through the full
     * three-component form of the record.
     */
    public LoginRequest(String email, String password) {
        this(email, password, false);
    }
}
