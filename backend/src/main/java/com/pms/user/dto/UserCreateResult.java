package com.pms.user.dto;

// ============================================================================
// FILE: UserCreateResult
//
// WHAT THIS FILE IS
//   The only answer in the whole application that carries a password in clear
//   text: the account that was just created or reset, plus the one-time
//   password the server generated for it. It is a DTO (Data Transfer Object):
//   a small flat object built only to travel over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   UserCrudService.create()       -> UserController, POST  /api/users
//   UserCrudService.resetAccount() -> UserController, PATCH /api/users/{id}/reset-account
//   Both service methods are guarded by
//   @PreAuthorize("hasAuthority('MANAGE_USERS')") - the permission is checked
//   on the SERVICE, not on the controller - and both build this object the same
//   way: the saved user goes through UserMapper, and the generated password is
//   put beside it.
//     -> Angular user-list.component, which shows the password once, so the
//        administrator can pass it to the new colleague.
//
// WHY IT EXISTS
//   The password is never stored. UserCrudService saves only its BCrypt hash
//   (BCrypt is a one-way function: it can check a password, it cannot give it
//   back). So this one answer is the ONLY moment the clear value exists outside
//   the administrator's screen. If this record did not exist, the two endpoints
//   would have to return the password inside UserResponse, and every other
//   endpoint that returns a user - the list, the search, the update - would
//   then carry a password field. Keeping it in a separate record means the
//   clear password can only appear in the two answers that really need it.
//
// H-2 - WHY THE SERVER GENERATES THE PASSWORD
//   The marker H-2 in UserCrudService.create() refers to the project's own
//   audit: the initial password is drawn with SecureRandom for each account
//   instead of being a fixed default. With a fixed default, anybody who knew it
//   could sign in as any freshly created colleague until that person changed
//   it.
//
// WHAT HAPPENS NEXT TO THAT PASSWORD
//   The account is saved with firstLogin = true, so FirstLoginFilter answers
//   403 on every endpoint except change-password and logout until the person
//   chooses their own password. resetAccount() also calls revokeAllTokens(),
//   which moves the user's tokenVersion forward and therefore kills every
//   session that person still had open (ADR-017).
// ============================================================================

/**
 * The created (or reset) account, plus its one-time password.
 *
 * <p>Returned by POST /api/users and by PATCH /api/users/{id}/reset-account.
 * Nothing else in the application returns this record.
 *
 * <p>Two parts on purpose:
 * <ul>
 *   <li>{@code user} - the normal UserResponse, exactly the same shape every
 *       other user endpoint returns, so the Angular list can insert the new row
 *       without a second call;</li>
 *   <li>{@code initialPassword} - the clear text password, shown once and never
 *       retrievable again. Re-reading the user later gives only the hash-backed
 *       UserResponse, which has no password field at all. If the administrator
 *       loses it, the only way forward is to reset the account, which draws a
 *       new one.</li>
 * </ul>
 *
 * <p>Practical consequence to remember: because this value is in clear text,
 * these two answers must never be logged or cached. That is also why the record
 * is kept this small - there is nothing else in it to be tempted to log.
 */
public record UserCreateResult(UserResponse user, String initialPassword) {}
