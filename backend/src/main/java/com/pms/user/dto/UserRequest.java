package com.pms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Body for creating/updating a user account: first/last name, email, role id. The only door
// through which an account is written, so nothing here can arrive as passwordHash, tokenVersion,
// or active/deleted — fields the User entity has but this record deliberately doesn't declare.

/**
 * Creating or updating a user account.
 *
 * <p>Same record for both operations; what differs is what the SERVICE does around them: create
 * refuses an e-mail already used by an active account then generates the first password; update
 * refuses an e-mail used by someone else, and — if the role changed — revokes tokens and evicts
 * the "securityContext" cache entry so open sessions stop using the old permissions (ADR-017).
 */
public record UserRequest(
        // @NotBlank (stronger than @NotNull) keeps a spaces-only name out of the admin list.
        // @Size(max = 100) matches users.first_name VARCHAR(100).
        @NotBlank @Size(max = 100) String firstName,

        // Same rules, users.last_name VARCHAR(100). GET /api/users sorts on this by default.
        @NotBlank @Size(max = 100) String lastName,

        // The login e-mail — the account's identity everywhere (JWT subject, cache key).
        // @Email is a loose shape check, mainly to catch a missing "@" that would create an
        // account nobody could ever sign into. Uniqueness is checked in the service
        // (existsByEmailAndDeletedFalse -> 409), backed by the partial unique index
        // uk_users_email (active rows only, so a freed address can be reused).
        @NotBlank @Email @Size(max = 255) String email,

        // Which role the account carries; only the id travels, the service loads the real Role.
        // Long (not long) so a missing value arrives as null for @NotNull to catch. Changing this
        // on an existing account triggers revokeAllTokens() in update(), so a demoted user loses
        // the old permissions immediately instead of keeping them until the token expires.
        @NotNull Long roleId
) {}
