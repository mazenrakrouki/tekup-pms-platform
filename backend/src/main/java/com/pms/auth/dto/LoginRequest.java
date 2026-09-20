package com.pms.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// JSON body sent to sign in: e-mail, password, "remember me". Open endpoint (permitAll); the
// brute-force protection is LoginAttemptTracker, and AuthService always runs one bcrypt
// comparison even for an unknown e-mail so response time can't reveal which addresses exist.

/**
 * Credentials for POST /api/auth/login.
 *
 * <p>Not produced by MapStruct (ADR-018): this DTO matches no entity — there is no "password"
 * column, only "password_hash".
 */
public record LoginRequest(
        // Key of the rate limiter, of both JWTs' "sub" claim, and of the securityContext cache
        // (ADR-017). @NotBlank is required alongside @Email because @Email alone accepts "".
        @NotBlank @Email String email,

        // No @Size here unlike ChangePasswordRequest: this checks against an existing hash, it
        // doesn't choose a new password.
        @NotBlank String password,

        /**
         * Long session asked for by the user ("remember me").
         *
         * <p>Primitive boolean so an absent field defaults to false instead of null. Written
         * into the refresh token as a claim so rotation on refresh can preserve the long
         * lifetime instead of silently dropping back to the short one.
         */
        boolean rememberMe
) {
    /**
     * Sign-in without a long session. Kept for the integration tests, which build their login
     * body as {@code new LoginRequest(email, password)} since "remember me" is irrelevant there.
     */
    public LoginRequest(String email, String password) {
        this(email, password, false);
    }
}
