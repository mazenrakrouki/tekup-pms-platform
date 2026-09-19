package com.pms.auth.dto;

import java.util.Set;

// ============================================================================
// FILE: AuthResponse
//
// WHAT THIS FILE IS
//   The JSON body the browser receives after a successful sign-in, and again
//   after every token refresh. It is a DTO (Data Transfer Object): a small
//   flat object built only to travel over HTTP, with no behaviour of its own.
//
// WHERE IT SITS IN THE FLOW
//   AuthService.buildBundle(user, rememberMe) is the ONLY place that creates
//   it. It reads the User entity, asks JwtService for a fresh access token,
//   and flattens role -> permissions into codes.
//     -> wrapped in TokenBundle (same package) together with the refresh token
//     -> AuthController.login() and refresh() return bundle.body() through
//        ResponseEntity.ok(...), so only THIS record becomes JSON
//     -> Angular AuthService.storeSession() reads it, keeps the access token,
//        and fills its UserContext signal (userId, email, fullName, roles,
//        permissions).
//
// WHAT IS DELIBERATELY NOT IN HERE: THE REFRESH TOKEN
//   This is the most important thing to say about this file. The refresh
//   token is NOT a field of this record. AuthController puts it in a separate
//   cookie named "pms_refresh" marked HttpOnly (see TokenBundle). HttpOnly
//   means the browser sends the cookie back by itself but JavaScript cannot
//   read it. Example of what would go wrong otherwise: if one injected script
//   ran on the page (an XSS attack), it could read a refresh token that lived
//   in this JSON and keep making new sessions for weeks. Stealing the access
//   token instead only buys the few minutes left before it expires. This
//   split is audit item H-1.
//
// WHY IT EXISTS
//   Without it the browser would know nothing after signing in: no token to
//   put in the Authorization header, no name for the top bar, no permission
//   list to build the menu from, and no way to know the user must still
//   change their password. Each of those would need its own extra call.
//
// THE SENTENCE TO SAY TO A JURY
//   Everything in this record only decides what the interface DISPLAYS.
//   Hiding a button is comfort, not protection. On the server the authorities
//   used by @PreAuthorize("hasAuthority('X')") are not read from this answer,
//   and not read from the token either: JwtAuthenticationFilter loads them
//   from the database (through the "securityContext" cache, ADR-017) on every
//   request. For URLs like /api/projects/{id}/**, ProjectScopeInterceptor
//   then also checks that the project is inside the caller's own scope
//   (ADR-021): the permission alone is not enough. Someone who edits this
//   JSON in their browser only breaks their own menu.
// ============================================================================

/**
 * What the client gets back from POST /api/auth/login and POST /api/auth/refresh.
 *
 * <p>Why a record and not a class with setters: the value is built once in
 * AuthService, written to JSON, then thrown away. A record makes every field
 * final, so no layer between those two moments can quietly add a permission
 * to the list that is about to be sent.
 *
 * <p>Why it is built by hand and not by MapStruct like most DTOs of the
 * project (ADR-018): this is not a copy of one entity. Three of its fields
 * (accessToken, role name, permission codes) are computed - the token does
 * not exist before the call, and the permissions come from walking
 * user -> role -> permissions.
 */
public record AuthResponse(
        // Database id of the account that just signed in. Angular keeps it as
        // currentUserId, and screens use it to answer "is this task mine?"
        // without asking the server again.
        //
        // Why the boxed Long and not the primitive long: this is the type of
        // User.getId(), which JPA leaves null until the row is saved. Here the
        // user always comes from the database, so it is never null in
        // practice; keeping the same type simply avoids an unboxing step that
        // could throw a NullPointerException.
        Long userId,

        // The short-lived JWT the client must send back as
        // "Authorization: Bearer <accessToken>" on every later call.
        //
        // Why it travels in the body and not in a cookie, unlike the refresh
        // token: JavaScript has to read it to build that header, so a cookie
        // it cannot read would be useless. The risk is accepted because the
        // token dies quickly (pms.jwt.access-token-expiration) and because it
        // can be killed before that: it carries a tokenVersion claim (a claim
        // is one named value stored inside the token), and every revocation
        // event - logout, password change, deactivation, role change -
        // increments that counter on the user row, after which
        // JwtAuthenticationFilter refuses the token. Example of what this
        // saves: an administrator deactivates someone at 10:00 and the very
        // next request from that person is rejected, instead of being served
        // until the token expires on its own.
        String accessToken,

        // true = this person still has the password the administrator
        // generated for them and has never chosen their own.
        //
        // What Angular does with it: send the user to the change-password page
        // instead of the dashboard.
        //
        // Why the client is not trusted to obey: the same flag is written into
        // the access token, and FirstLoginFilter answers 403
        // FIRST_LOGIN_REQUIRED to every endpoint except change-password,
        // logout and refresh while it is true (audit item H-2). Example of
        // what would go wrong without that server-side twin: the user could
        // skip the redirect with the browser back button, or call the API
        // directly, and keep working forever with the password that is still
        // written on the administrator's screen.
        boolean firstLogin,

        // The login e-mail. It also identifies the session: the
        // "securityContext" cache of ADR-017 is keyed on e-mail plus token
        // version, so revoking a session evicts exactly that one entry.
        String email,

        // "Firstname Lastname", for the top bar and for greetings.
        //
        // There is no full_name column in the database: User.getFullName()
        // joins the two columns when it is read. So a first name corrected in
        // the admin screen is right here too, with no second place to update.
        String fullName,

        // The role NAME, for example "CHEF_PROJET". Display only - a badge in
        // the interface.
        //
        // A jury will most likely point at this line, so be precise: no
        // authorization decision in this application is taken from this
        // string. The code never tests a role name. Access is decided from the
        // permission codes in the next field, through
        // @PreAuthorize("hasAuthority('CODE')") on the service methods. If
        // this field disappeared, not one authorization rule would change -
        // only the badge would.
        String role,

        // The permission codes the person holds, for example
        // ["VIEW_PROJECT", "ASSIGN_DEVELOPER"]. AuthService builds them with
        // user.getRole().getPermissions().stream().map(Permission::getCode).
        //
        // Why only the codes and not whole Permission objects: the Angular
        // guards and the permission directive only need to answer "is this
        // string in the list?". Sending module names and descriptions would
        // make every login answer bigger for no gain.
        //
        // Why Set<String> and not List<String>: the generic parameter says the
        // collection holds plain code strings, and the Set says a code cannot
        // appear twice. Role.permissions is itself a Set because
        // role_permissions is a many-to-many join table keyed on
        // (role_id, permission_id). Jackson, the library that turns this
        // object into JSON, writes a Set as an ordinary JSON array, so the
        // browser simply sees a list.
        //
        // Why this list is rebuilt at every login AND at every refresh instead
        // of being frozen once: this is the dynamic half of the RBAC. When an
        // administrator adds a permission to a role, the next refresh hands
        // the browser the new list and the new menu appears with zero code
        // change. Example of what would go wrong if it were frozen: a
        // permission taken away this morning would keep lighting up its menu
        // entry until the person logged out.
        Set<String> permissions
) {}
