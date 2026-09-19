package com.pms.user.dto;

// ============================================================================
// FILE: UserResponse
//
// WHAT THIS FILE IS
//   The read-only shape sent to the browser for ONE user account. It is a DTO
//   (Data Transfer Object): a small flat object built only to travel over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   User entity (table "users")
//     -> UserMapper, in package com.pms.user.mapper, which MapStruct turns into
//        real code at compile time (ADR-018)
//     -> UserCrudService: findAll, search, findAssignable, findById, update,
//        and inside UserCreateResult for create and resetAccount
//     -> UserController: GET /api/users (a page of them),
//        GET /api/users/assignable, GET /api/users/{id}, PUT /api/users/{id}
//     -> Angular user-list.component and every assignment picker, which read it
//        through the "User" interface of core/models/user.model.ts.
//
// WHY IT EXISTS - THE SECURITY REASON FIRST
//   The User entity carries passwordHash and tokenVersion. This record does not
//   declare them, so MapStruct never copies them and they can never reach a
//   JSON answer. If the entity were returned directly, every user list in the
//   application would publish the BCrypt hash of everybody's password, and the
//   revocation counter of ADR-017 along with it.
//
// THE SECOND REASON: THE DATABASE SESSION IS ALREADY CLOSED
//   application.yml sets open-in-view: false, so the transaction ends inside
//   the service. If Jackson (the library that turns Java objects into JSON)
//   were given the entity, any field not yet loaded would throw
//   LazyInitializationException while writing the response. The mapper reads
//   everything it needs while the transaction is still open, so the object
//   written to JSON has no link to the database left.
//
// WHO IS ALLOWED TO SEE IT
//   Most of the methods above need MANAGE_USERS. findAssignable() is the
//   exception: it is annotated
//   @PreAuthorize("hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS')")
//   so a director or a project manager can fill the "who do I assign?" lists
//   without being given the full user administration permission. That is the
//   whole idea of permission-based authorization: the list is opened to a
//   precise action, not to a role name.
// ============================================================================

/**
 * One user account, as the client sees it.
 *
 * <p>Filled by UserMapper.toResponse(). Six of the seven fields are copied
 * because the names match on both sides; only roleName needs a rule, written in
 * the mapper as {@code @Mapping(target = "roleName", source = "role.name")}.
 *
 * <p>Why a record and not a class with getters and setters: a response is built
 * once, written to JSON, then thrown away. A record is immutable, so no code
 * between the mapper and the JSON writer can change a field by mistake - and
 * here that field could be somebody's e-mail or their active flag.
 */
public record UserResponse(
        // Primary key of the "users" row. The screens send it back in the URL
        // of PUT /api/users/{id} and of the PATCH endpoints (deactivate,
        // reactivate, reset-account), and the assignment screens send it as the
        // chosen user.
        Long id,

        // The two name parts are sent separately, not already joined.
        // Why: the list screen sorts on the last name (the default sort of
        // GET /api/users is sort = "lastName"), and the forms need to edit each
        // part on its own. A single joined string would have to be split back
        // apart, and splitting on a space breaks on a name in two words.
        // (The joined form does exist, User.getFullName(), and it is used where
        // only a label is needed - for example ResourceResponse.userFullName.)
        String firstName,
        String lastName,

        // The login e-mail. It identifies the account everywhere, so the admin
        // screen shows it as the real key of the row rather than the id.
        String email,

        // The role NAME, for example "CHEF_PROJET". A label for the table
        // column, nothing more.
        //
        // Be ready for this jury question: no authorization decision is taken
        // from this string. Access is always decided on permission codes, with
        // @PreAuthorize("hasAuthority('MANAGE_USERS')") on the methods of
        // UserCrudService. The name is here so a human can read the table.
        //
        // Note the whole Role object is deliberately NOT sent: it carries the
        // complete permission set, which would repeat itself on every row of
        // the user list for no purpose.
        String roleName,

        // false = the account is switched off. UserDetailsServiceImpl passes
        // accountLocked(!active) to Spring Security, so an inactive person is
        // refused at login even with the right password; and deactivating an
        // account also revokes its open sessions.
        // Why the client is told: the list shows the state and offers
        // "deactivate" or "reactivate" accordingly. The decision itself is
        // taken on the server, so hiding the button is only a comfort.
        boolean active,

        // true = the person still has the password the admin generated and has
        // never chosen their own. While this is true, FirstLoginFilter answers
        // 403 on every endpoint except /api/auth/change-password and
        // /api/auth/logout.
        // Why it is in the list: the admin can see at a glance who has never
        // signed in yet, which is exactly who may need their account reset.
        boolean firstLogin
) {}
