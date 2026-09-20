package com.pms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// JSON body a signed-in user posts to change their own password. No e-mail/userId field on
// purpose: the target account comes from @AuthenticationPrincipal (the verified token), so a
// caller can only ever change their own password.

/**
 * Old password plus new password, for POST /api/auth/change-password.
 *
 * <p>The current password is required even though the caller is authenticated, to prove the
 * person at the keyboard owns the account. After a successful change, AuthService revokes every
 * existing session (tokenVersion bump) so a stolen session doesn't survive the change.
 */
public record ChangePasswordRequest(
        // Checked with passwordEncoder.matches(...); a mismatch throws BadCredentialsException (401).
        @NotBlank String currentPassword,

        // @Size(min = 8) is enforced server-side because the Angular form check can be bypassed
        // with curl/Postman.
        @NotBlank @Size(min = 8) String newPassword
) {}
