package com.pms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// ============================================================================
// FILE: ChangePasswordRequest
//
// WHAT THIS FILE IS
//   The JSON body a signed-in user sends to change their own password. It is
//   a DTO (Data Transfer Object): a small flat object that only carries values
//   over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   Angular AuthService.changePassword(current, next) posts
//   { currentPassword, newPassword } to POST /api/auth/change-password
//     -> AuthController.changePassword(@AuthenticationPrincipal String email,
//        @Valid @RequestBody ChangePasswordRequest request)
//     -> AuthService.changePassword(email, request), which re-checks the old
//        password with the PasswordEncoder, stores the bcrypt hash of the new
//        one, sets firstLogin to false, and calls user.revokeAllTokens()
//     -> the controller answers 204 No Content (nothing to send back).
//
// THE FIELD THAT IS NOT HERE, AND WHY IT MATTERS MOST
//   There is no e-mail field and no user id. The account being changed comes
//   from @AuthenticationPrincipal, which JwtAuthenticationFilter filled from
//   the verified access token. Example of what would go wrong if the id were
//   a field of this record: anyone could post
//   { "userId": 1, "newPassword": "..." } and take over the administrator
//   account. Because the target is taken from the token, a user can only ever
//   change their own password, and no ownership check is needed in the
//   service.
//
// WHY THERE IS NO @PreAuthorize BEHIND IT
//   Every business operation in this project carries
//   @PreAuthorize("hasAuthority('X')") on its service method.
//   AuthService.changePassword carries none on purpose: changing YOUR OWN
//   password needs no particular permission, and a brand-new user who has
//   just signed in for the first time usually holds almost none. The guard is
//   SecurityConfig: /api/auth/change-password is not in the permitAll list,
//   so the last rule anyRequest().authenticated() applies and the request
//   never reaches the controller without a valid token.
//
// WHY IT EXISTS
//   Without it there would be no way to satisfy the first-login rule (audit
//   item H-2): FirstLoginFilter blocks every endpoint except this one, logout
//   and refresh while firstLogin is true, so this is literally the only door
//   out of that state. Deleting it would lock every new account out of the
//   application.
//
// A WORD ON THE CLEAR-TEXT PASSWORDS IT HOLDS
//   Both fields hold the real password in clear text, in memory, for the few
//   milliseconds of the call. That is unavoidable: bcrypt has to see the
//   clear text to compare or to hash it. What matters is what the project
//   does NOT do with it - it is never written to a log, never stored, and
//   never sent back. Only the bcrypt hash reaches the database. Bcrypt is a
//   hashing function built to be slow on purpose (strength 12 here, see
//   SecurityConfig), so that guessing millions of passwords from a stolen
//   database dump stays too expensive to be worth it.
// ============================================================================

/**
 * Old password plus new password, for POST /api/auth/change-password.
 *
 * <p>Why a record: the two values are read once by the service and then
 * dropped. Nothing should be able to swap them in between.
 *
 * <p>Why the old password is asked for at all, when the caller is already
 * authenticated: it proves the person in front of the keyboard is the owner
 * of the account, not someone who found an unlocked laptop or reused a stolen
 * access token. The service checks it with passwordEncoder.matches(...) and
 * answers 401 through BadCredentialsException when it does not match.
 *
 * <p>What happens after a successful change, and why it is worth saying to a
 * jury: AuthService calls user.revokeAllTokens(), which increments the
 * tokenVersion counter on the user row (ADR-017), and evicts the matching
 * "securityContext" cache entry. Every session opened with the old password -
 * the thief's session included - stops working on its very next request. A
 * password change that left old sessions alive would protect nothing.
 */
public record ChangePasswordRequest(
        // The password the user is using today.
        //
        // @NotBlank rejects null, "" and a value made only of spaces. It is
        // checked by Jakarta Bean Validation, triggered by the @Valid
        // annotation on the controller parameter, so a bad body is answered
        // with 400 Bad Request before any service code runs.
        // Why it is needed: without it, a body with "currentPassword": " "
        // would travel all the way down and waste a bcrypt comparison - which
        // is deliberately slow - on something that can never be right.
        @NotBlank String currentPassword,

        // The password the user wants from now on.
        //
        // @NotBlank: same rejection of empty and whitespace-only values.
        // @Size(min = 8): at least 8 characters. Both are checked on the
        // SERVER. Why that matters even though the Angular form already checks
        // it: the browser form can be bypassed by anyone posting to the API
        // with curl or Postman. Example of what would go wrong without
        // @Size(min = 8): a user could set their password to "a", and the
        // account would be guessed in seconds - while the application would
        // still look perfectly secure from the outside.
        //
        // Note the annotations sit on the record component, so Bean Validation
        // sees them on the constructor parameter. They must stay glued to the
        // parameter they describe; a comment inserted between an annotation
        // and its target is a common way to break this kind of file.
        @NotBlank @Size(min = 8) String newPassword
) {}
