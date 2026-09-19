package com.pms.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// ============================================================================
// FILE: UserRequest
//
// WHAT THIS FILE IS
//   The shape of the JSON body an administrator sends to create or update a
//   user account. It is the only door through which an account is written, so
//   it is also where the input rules live. It is a DTO (Data Transfer Object):
//   a small flat object whose only job is to carry data from the browser to the
//   server.
//
// WHERE IT SITS IN THE FLOW
//   Angular user-list.component
//     -> UserController, POST /api/users and PUT /api/users/{id}, where
//        @Valid @RequestBody runs the four checks written below
//     -> UserCrudService.create() / update(), both guarded by
//        @PreAuthorize("hasAuthority('MANAGE_USERS')") - the permission is
//        tested on the SERVICE method, never on the controller
//     -> User entity, table "users"
//     -> the answer comes back as a UserCreateResult (create) or a
//        UserResponse (update).
//
// WHY IT EXISTS - THIS IS THE IMPORTANT ONE
//   Without it the controller would accept the User entity itself, and a client
//   could send whatever that class contains. Three fields make that dangerous:
//     - passwordHash: anyone could set their own hash and choose the password
//       of any account;
//     - tokenVersion: pushing it back down would resurrect tokens that had been
//       revoked (ADR-017);
//     - active / deleted: an account could be switched on again from a normal
//       update call.
//   This record declares four values, so those fields simply have nowhere to
//   arrive from. Jackson (the library that reads JSON into Java objects) cannot
//   fill what is not declared.
//
// WHY THERE IS NO PASSWORD FIELD
//   The client never chooses the first password. UserCrudService generates a
//   random one with SecureRandom (marker H-2 in that file), stores only its
//   BCrypt hash, and returns the clear value once inside UserCreateResult.
//   Later, the person changes it themselves through /api/auth/change-password.
//   So no password ever travels in this direction.
//
// WHY THE RULES ARE HERE AND NOT ONLY IN THE SERVICE
//   Bean Validation runs before any service code, and GlobalExceptionHandler
//   turns the resulting MethodArgumentNotValidException into a 400 answer that
//   names each bad field. Writing the same checks by hand inside the service
//   would mean rewriting that plumbing in every endpoint.
// ============================================================================

/**
 * Creating or updating a user account.
 *
 * <p>The same record is used for both, because the two operations write the
 * same four values. What differs is what the SERVICE does around them, and it
 * is worth knowing before reading the fields:
 * <ul>
 *   <li>create: refuses an e-mail already used by an active account, then
 *       generates the first password;</li>
 *   <li>update: refuses an e-mail already used by SOMEONE ELSE, and - if the
 *       role changed - calls revokeAllTokens() and evicts the "securityContext"
 *       cache entry, so the person's open sessions stop immediately instead of
 *       keeping their old permissions (ADR-017, dynamic RBAC).</li>
 * </ul>
 *
 * <p>Why a record and not a class with setters: the body is read once,
 * validated, then used. Nothing should be able to change an e-mail between the
 * uniqueness check and the write.
 */
public record UserRequest(
        // @NotBlank refuses null, "" and a value made only of spaces - which is
        // more than @NotNull does. Why it matters: "   " would otherwise be
        // stored, and the admin list would show a row with an invisible name
        // that nobody can identify or search for.
        // @Size(max = 100) matches the column users.first_name VARCHAR(100).
        // Without it, a 300-character value would pass Java and be refused by
        // PostgreSQL, which reaches the user as a flat 409 "conflict" instead
        // of "this field is too long".
        @NotBlank @Size(max = 100) String firstName,

        // Same two rules, same column width (users.last_name VARCHAR(100)).
        // Note that the default sort of GET /api/users is on lastName, so an
        // empty value here would also put the row in a strange place in the
        // list.
        @NotBlank @Size(max = 100) String lastName,

        // The login e-mail. It is the identity of the account everywhere:
        // Spring Security signs the JWT with it as subject, and the
        // "securityContext" cache is keyed on email plus token version.
        //
        // @Email checks the shape of the address (something@something). It is
        // deliberately a loose check - it does not prove the address exists -
        // but it catches the common typo of a missing "@", which would create
        // an account nobody can ever sign in to, since the password is sent by
        // e-mail.
        // @Size(max = 255) matches users.email VARCHAR(255).
        //
        // Uniqueness is NOT checked here: a field annotation cannot query the
        // database. UserCrudService does it with existsByEmailAndDeletedFalse
        // and answers 409 with a clear message. The final guarantee is the
        // database index uk_users_email, which V18__soft_delete_unique_indexes
        // .sql turned into a PARTIAL unique index - UNIQUE (email) WHERE
        // deleted = FALSE - so an address freed by a soft-deleted account can
        // be used again.
        @NotBlank @Email @Size(max = 255) String email,

        // Which role the account carries. Only the id travels; the service
        // loads the real Role and refuses an unknown id.
        //
        // @NotNull and not @NotBlank because this is a number, not a text. It
        // is required because users.role_id is NOT NULL: an account with no
        // role would produce an empty authority list, and the person would be
        // refused on every screen with no explanation, instead of being refused
        // at creation time.
        //
        // Long (the object) and not long (the primitive) exactly so that a
        // missing value arrives as null and @NotNull can report it. A primitive
        // would silently become 0, and the service would then complain about a
        // missing role with id 0.
        //
        // Why an id and not the role name: names are labels that an
        // administrator may change, ids do not move. It also keeps the rule of
        // the project intact - nothing here decides anything from a role name.
        // Changing this value on an existing account is what triggers
        // revokeAllTokens() in UserCrudService.update(): without that, a person
        // demoted from DIRECTEUR to DEVELOPPEUR would keep the old permissions
        // in their current session until their token expired.
        @NotNull Long roleId
) {}
