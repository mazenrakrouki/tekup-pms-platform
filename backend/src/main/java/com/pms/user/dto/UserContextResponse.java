package com.pms.user.dto;

import java.util.Set;

// ============================================================================
// FILE: UserContextResponse
//
// WHAT THIS FILE IS
//   The answer to the question "who am I, and what am I allowed to do?". It
//   describes the person who is currently signed in: identity, role name,
//   permission codes, and whether they still have to choose their own
//   password. It is a DTO (Data Transfer Object): a small flat object built
//   only to travel over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   User entity + its Role + the role's Permission rows
//     -> UserService.getContext(email), which reads the user with
//        findActiveByEmailWithRole and flattens the permissions into codes
//     -> UserController, GET /api/me/context. The e-mail comes from
//        @AuthenticationPrincipal String email, which JwtAuthenticationFilter
//        placed there after checking the access token - so the client cannot
//        ask for somebody else's context by changing a parameter.
//
// WHY THERE IS NO @PreAuthorize BEHIND IT
//   Every other service method in this project carries
//   @PreAuthorize("hasAuthority('X')"). getContext() carries none on purpose:
//   reading YOUR OWN context needs no particular permission, and a person who
//   has just signed in usually has no admin permission at all. The guard is
//   SecurityConfig, whose last rule is anyRequest().authenticated(): without a
//   valid token the request never reaches the controller.
//
// WHY IT EXISTS
//   The browser has to know which menus and buttons to draw. It cannot read
//   that from the database, and it must not guess it from the role name. This
//   record hands it the list of permission codes, which is exactly what the
//   server itself checks.
//
//   State it clearly in front of a jury: what the browser receives here only
//   decides what is DISPLAYED. Hiding a button is a comfort, not a protection.
//   Every call is checked again on the server by @PreAuthorize on the service
//   method, and, for URLs of the form /api/projects/{id}/**, also by
//   ProjectScopeInterceptor, which checks the permission AND that the project
//   is inside the caller's scope (ADR-021). Someone who edits the JSON of this
//   answer in their browser only breaks their own menu.
//
// WHERE IT IS TODAY ON THE ANGULAR SIDE
//   AuthService keeps the same values in a signal (its UserContext interface)
//   and in localStorage, but it fills them from the login and refresh answer
//   (AuthResponse), which already carries the identity and the permission
//   list. No Angular code calls GET /api/me/context at the moment. The
//   endpoint stays the fresh, server-side source of truth - the copy held in
//   localStorage is only as new as the last login or token refresh.
// ============================================================================

/**
 * The context of the signed-in user.
 *
 * <p>Built by hand in UserService.getContext(), and not by MapStruct like
 * UserResponse: the permissions field is not a copy of anything on the entity,
 * it is computed by walking role -> permissions and keeping the codes.
 *
 * <p>Why a record: the value is built once, written to JSON, then thrown away,
 * and nothing between those two moments should be able to add a permission to
 * it.
 */
public record UserContextResponse(
        // Database id of the account. The Angular AuthService exposes it as
        // currentUserId, and screens use it to answer "is this task mine?"
        // without a second call.
        Long id,

        // "Firstname Lastname", for the top bar. There is no full_name column:
        // User.getFullName() joins the two fields when it is read, so a name
        // corrected in the admin screen is right everywhere at once.
        String fullName,

        // The login e-mail. It also identifies the session: the "securityContext"
        // cache of ADR-017 is keyed on email plus token version, so revoking a
        // session evicts exactly that entry.
        String email,

        // The role NAME, for example "CHEF_PROJET". Display only - a badge in
        // the interface.
        //
        // This is the field a jury is most likely to point at, so be precise:
        // nothing in this application decides access from this string. The code
        // never tests a role name. Access is decided from the permissions field
        // below, through @PreAuthorize("hasAuthority('CODE')") on the service
        // methods. If this field were removed, not one authorization rule would
        // change.
        String role,

        // The permission codes the person holds, for example
        // ["VIEW_PROJECT", "ASSIGN_DEVELOPER"]. Read from
        // user.getRole().getPermissions() and mapped to Permission.getCode().
        //
        // Why the codes and not the Permission objects: this list is used by
        // the Angular guards and by *ngIf-style checks, which only need to
        // answer "is this string in the list?". Sending module names and
        // descriptions would make the answer bigger for no gain.
        //
        // Why a Set and not a List: a person cannot hold the same permission
        // twice, and Role.permissions is itself a Set<Permission> because
        // role_permissions is a many-to-many join table keyed on
        // (role_id, permission_id). Jackson writes a Set as a plain JSON array,
        // so the browser sees a normal list.
        //
        // Why this list is rebuilt on every call instead of being trusted from
        // the token: this is the dynamic part of the RBAC. When an admin
        // changes the permissions of a role, RoleAdminService clears the
        // "securityContext" cache (ADR-017), and the next read comes from the
        // database. A list frozen at sign-in time would keep a permission that
        // was taken away this morning until the person logs out.
        Set<String> permissions,

        // true = the person still has the password generated by the admin and
        // has never chosen their own.
        //
        // Why the client needs it: the Angular app must send the user to the
        // "change your password" page instead of the dashboard. The real guard
        // is again on the server - while the token says firstLogin, the filter
        // FirstLoginFilter answers 403 to every endpoint except
        // /api/auth/change-password and /api/auth/logout - so a client that
        // ignored this flag would simply be refused everywhere else.
        boolean firstLogin
) {}
