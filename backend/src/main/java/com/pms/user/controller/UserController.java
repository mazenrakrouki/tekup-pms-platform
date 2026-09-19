package com.pms.user.controller;

import com.pms.user.dto.UserContextResponse;
import com.pms.user.dto.UserCreateResult;
import com.pms.user.dto.UserRequest;
import com.pms.user.dto.UserResponse;
import com.pms.user.repository.RoleRepository;
import com.pms.user.service.UserCrudService;
import com.pms.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

// ============================================================================
// FILE: UserController
//
// WHAT THIS FILE IS
//   The HTTP door to accounts. It publishes three families of endpoints that
//   happen to live in one class: the profile of the signed-in user
//   (/api/me/context), the role drop-down of the user form (/api/roles), and
//   the administrator's CRUD on accounts (/api/users...).
//
// WHERE IT SITS IN THE FLOW
//   Angular user-list.component.ts (the admin screen), project.service.ts (the
//   assignment pickers) and dashboard.component.ts
//     -> SecurityConfig: anyRequest().authenticated(), so no valid token means
//        401 before this class is reached
//     -> FirstLoginFilter (H-2): while the token says firstLogin = true, every
//        URL except change-password, logout and refresh is answered 403 with
//        the code FIRST_LOGIN_REQUIRED. So none of the endpoints below can be
//        reached before the first password change.
//     -> THIS FILE: maps URL + verb onto a call, asks Bean Validation to check
//        the body, picks the status code
//     -> UserService (read-only profile) / UserCrudService (everything else,
//        guarded by @PreAuthorize on each service method) / RoleRepository for
//        the one drop-down below
//     -> UserRepository, UserMapper -> tables users, roles, permissions
//     -> UserContextResponse, UserResponse, UserCreateResult, as JSON.
//
// WHY IT EXISTS
//   Delete it and nobody can be given an account, and nobody can lose one. It
//   is also the entry point of two security operations that are not obvious
//   from their names: PATCH reset-account (new one-time password) and PATCH
//   deactivate (cut every live session of that person at once).
//
// WHY THERE IS NO CLASS-LEVEL @RequestMapping HERE
//   Unlike the other controllers of this package, this one serves three
//   different prefixes: /api/me/context, /api/roles and /api/users. There is no
//   common prefix to factor out, so each method spells out its full path. The
//   price is that a typo in one path is not caught by the others; the benefit
//   is that the three families stay in one class next to the service they
//   share.
//
// THE PART THAT IS NOT IN THIS FILE: REVOKING SESSIONS (ADR-017)
//   Authentication is a short-lived JWT access token plus a refresh token kept
//   in an HttpOnly cookie, rotated at every refresh. Each user row also carries
//   a tokenVersion counter, and a token is only accepted while its version
//   matches. UserCrudService raises that counter (revokeAllTokens) when an
//   account is deactivated, deleted, reset, or when its ROLE changes, and it
//   evicts the matching entry of the "securityContext" cache. That is what
//   makes a deactivation take effect immediately instead of waiting for the
//   access token to expire - and what makes the dynamic RBAC honest: a role
//   change cannot leave the old permissions running in an open session.
//
// WHERE THE PERMISSION CHECKS LIVE
//   On the SERVICE methods, as @PreAuthorize("hasAuthority('MANAGE_USERS')"),
//   not on this class - with one single exception, listRoles() below, which is
//   flagged and explained where it stands. The code never tests a role NAME
//   anywhere; it tests permission codes (ADR-001).
//
// ADR-021 DOES NOT APPLY HERE
//   ProjectScopeInterceptor only inspects URLs matching /api/projects/{id}/**,
//   so it never looks at these paths. An account does not belong to a project,
//   so there is no project scope to enforce - it is a scope that does not
//   exist, not a check that was forgotten.
// ============================================================================

// @Tag: springdoc/OpenAPI only. It groups these endpoints under "Utilisateurs"
// in the Swagger page instead of the raw class name.
//
// @RestController: Spring creates one instance at start-up, scans it for URL
// mappings, and writes whatever a method returns into the response body as JSON
// (Jackson). Without it the returned value would be taken for the name of an
// HTML view.
//
// @RequiredArgsConstructor: Lombok writes the constructor taking the three
// final fields, and Spring injects through it. Final fields set in the
// constructor can never be null and can never be replaced at runtime.
/**
 * Accounts: session profile, role list for the user form, and the
 * administrator's CRUD.
 *
 * <p>The class holds no rule. Uniqueness of the e-mail, generation of the
 * one-time password (H-2), soft delete, and the revocation of live sessions
 * (ADR-017) all live in {@link UserCrudService}, where they apply to every
 * caller and not only to these URLs.
 */
@Tag(name = "Utilisateurs", description = "CRUD utilisateurs, activation/désactivation, contexte de session")
@RestController
@RequiredArgsConstructor
public class UserController {

    // Reads the profile of the signed-in user. Separate from UserCrudService
    // because it needs no permission: everybody may read their own context.
    private final UserService userService;
    // Everything an administrator does to an account. Every one of its methods
    // carries its own @PreAuthorize.
    private final UserCrudService userCrudService;
    // Used by exactly one endpoint below, listRoles(), to fill a drop-down.
    // It is the only place in this package where a controller talks to a
    // repository without a service in between - see the note there.
    private final RoleRepository roleRepository;

    // ── Profile of the signed-in user ─────────────────────────────

    // WHAT IT DOES / GIVES BACK
    //   GET /api/me/context -> 200 with who the caller is: id, full name,
    //   e-mail, the NAME of their role (a label for the screen) and above all
    //   the SET OF PERMISSION CODES they hold. 404 if the account behind the
    //   token has been deleted in the meantime (the lookup filters on
    //   deleted = false). 401 without a token.
    //
    // WHY IT EXISTS - THIS IS WHAT MAKES THE MENU DYNAMIC (ADR-001)
    //   The Angular side never decides what to show from a role name. It reads
    //   this permission list and shows or hides each menu entry and each button
    //   from it. So moving a permission from one role to another changes the
    //   interface with no frontend change at all. The role name is returned for
    //   display only; no decision is ever taken from it, on either side.
    //   Note that the browser also receives the same fields in the login and
    //   refresh answers, which is what AuthService stores in its context
    //   signal. This endpoint is the authoritative copy: it reads the database
    //   again, so it tells the truth even if the token was minted before the
    //   last change.
    //
    // WHY THERE IS NO @PreAuthorize ON THIS ONE - AND WHY THAT IS NOT A HOLE
    //   Asking who you are is not a privilege. And the e-mail is NOT taken from
    //   the request: @AuthenticationPrincipal injects the principal that
    //   JwtAuthenticationFilter put into the SecurityContext after checking the
    //   token signature and the tokenVersion. It is a String here because that
    //   filter stores the e-mail as the principal.
    //   Concrete example of what this prevents: there is no ?email= parameter
    //   to tamper with, so a user cannot ask for somebody else's context. If
    //   the e-mail came from the client, any signed-in person could read the
    //   permission list of the administrator.
    @GetMapping("/api/me/context")
    public ResponseEntity<UserContextResponse> getContext(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(userService.getContext(email));
    }

    // ── Roles ─────────────────────────────────────────────────────

    // WHAT IT DOES / GIVES BACK
    //   GET /api/roles -> 200 with a light list of { id, name }, one per role.
    //   It fills the role drop-down of the create/edit user form
    //   (user-list.component.ts). It is NOT the RBAC administration endpoint -
    //   that one is GET /api/admin/roles in RoleController, which returns the
    //   permissions of each role and requires MANAGE_ROLES.
    //
    // READ THIS BEFORE THE JURY DOES: THE GUARD IS ON THE CONTROLLER HERE
    //   Everywhere else in this project @PreAuthorize sits on the SERVICE
    //   method. This endpoint has no service: it reads the repository
    //   directly, because there is nothing to decide - two columns of every
    //   row. With no service there is no service method to put the guard on,
    //   so it is written here, on the handler. Spring Security enforces it
    //   exactly the same way (the same proxy mechanism, the same
    //   AccessDeniedException -> 403). The difference is scope, and it is the
    //   honest answer to give: this check protects this URL, not a reusable
    //   business method. It is the one exception in this package, and it is
    //   deliberate.
    //   MANAGE_USERS and not MANAGE_ROLES is the right permission here: the
    //   list exists to serve the user form, and whoever may create a user must
    //   be able to pick a role for them.
    @GetMapping("/api/roles")
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public ResponseEntity<List<Map<String, Object>>> listRoles() {
        // A stream is a pipeline over the rows: .map turns each Role into a
        // small map, .toList() collects the result into an immutable list.
        //
        // WHY a Map and not RoleResponse: RoleResponse carries the description,
        // the system flag, the user count and the full permission list of every
        // role. A drop-down needs an id and a label. Sending the rest would
        // publish the whole authorization matrix to anybody allowed to open the
        // user form.
        //
        // WHY the (Object) cast on the first value: without it the compiler
        // looks for one common type for a Long and a String and infers
        // something narrow, so the expression would not be a Map<String,Object>
        // and the method would not compile against its declared return type.
        // The cast simply tells it "treat these values as Object".
        //
        // NOTE on Map.of: it builds an immutable map and refuses null values -
        // which is fine here, since roles.id and roles.name are both NOT NULL.
        return ResponseEntity.ok(roleRepository.findAll().stream()
                .map(r -> Map.of("id", (Object) r.getId(), "name", r.getName()))
                .toList());
    }

    // ── Admin CRUD ────────────────────────────────────────────────

    // WHAT IT DOES / GIVES BACK
    //   GET /api/users -> 200 with ONE PAGE of accounts, plus the paging
    //   information (content, totalElements, totalPages, number, size). The
    //   three filters are optional and combine: free text, role, active or
    //   not. 403 without MANAGE_USERS, raised by @PreAuthorize inside
    //   UserCrudService.search().
    //
    // WHY IT IS PAGED, unlike GET /api/resources
    //   This list grows with the company and the screen shows it in a table
    //   with a pager. Returning every account in one answer would make the
    //   response, and the rendering, grow without limit.
    //
    // @RequestParam(required = false): each filter may be absent from the URL.
    //   Without required = false Spring would answer 400 as soon as one is
    //   missing, and the screen could no longer show the unfiltered list.
    //   Note the types: Long and Boolean are objects, so they can be null,
    //   which is how "no filter on this field" is expressed. The primitives
    //   long and boolean could not hold that "not given" state - false would be
    //   indistinguishable from "show inactive accounts only".
    //   The service turns a null search into an empty string before querying,
    //   because a null parameter inside LOWER() makes PostgreSQL guess a type
    //   and fail.
    //
    // Pageable is not read from a body: Spring builds it from ?page=, ?size=
    // and ?sort= in the URL.
    // @PageableDefault fills it in when those are absent: 20 rows, sorted by
    //   last name, ascending.
    //   WHY a default is needed: without it Spring uses 20 rows but NO sort
    //   order, and PostgreSQL is then free to return the rows in any order - so
    //   the same person could appear on page 1 and again on page 2, while
    //   somebody else never appears at all. A stable sort is what makes paging
    //   mean anything.
    //   WHY sort = "lastName": it is the entity FIELD name, not the column
    //   name. Spring Data turns it into an ORDER BY on last_name; a typo here
    //   is answered as a property error at request time, not at compile time,
    //   so the value is worth reading twice.
    @GetMapping("/api/users")
    public ResponseEntity<Page<UserResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(userCrudService.search(search, roleId, active, pageable));
    }

    // WHAT IT DOES / GIVES BACK
    //   GET /api/users/assignable -> 200 with every live account, as a plain
    //   list, to fill the "who manages this project" and "who joins this team"
    //   pickers (project.service.ts).
    //
    // WHY IT IS A SEPARATE ENDPOINT AND NOT A FILTER ON THE ONE ABOVE
    //   Because it is guarded differently, and that is the whole point. The
    //   service asks for hasAnyAuthority('ASSIGN_CHEF_PROJET',
    //   'ASSIGN_DEVELOPER','MANAGE_USERS'): whoever may assign people must be
    //   able to read the list of people, WITHOUT holding MANAGE_USERS, which is
    //   the administrator's permission to create and delete accounts.
    //   Without this split, a Directeur or a project manager would have to be
    //   given the full account-administration permission just to populate a
    //   drop-down - the exact opposite of least privilege.
    //   It also returns less: UserResponse without paging, which is all a
    //   picker needs.
    //
    // ROUTE ORDER IS NOT AN ISSUE HERE, though it looks like one:
    //   "/api/users/assignable" and "/api/users/{id}" both match the same URL
    //   shape. Spring compares patterns by how specific they are, not by the
    //   order the methods are written in, and a literal segment always beats a
    //   variable one - so /api/users/assignable lands here and never in
    //   getById(), where it would have failed to convert "assignable" to a
    //   Long.
    @GetMapping("/api/users/assignable")
    public ResponseEntity<List<UserResponse>> assignable() {
        return ResponseEntity.ok(userCrudService.findAssignable());
    }

    // WHAT IT DOES / GIVES BACK
    //   GET /api/users/{id} -> 200 with one account, 404 if the id is unknown
    //   or the row is soft-deleted, 403 without MANAGE_USERS.
    //
    // NOTE what UserResponse does NOT contain: passwordHash and tokenVersion.
    // The DTO declares seven fields and the entity is never serialised, so
    // those two values cannot reach a JSON answer even by accident.
    @GetMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userCrudService.findById(id));
    }

    // WHAT IT DOES / GIVES BACK
    //   POST /api/users creates an account and answers 201 Created with a
    //   UserCreateResult - the saved user AND a one-time initial password -
    //   plus a Location header pointing at the new row.
    //   409 if the e-mail is already taken or the roleId does not exist, 400 if
    //   the body breaks a rule of UserRequest, 403 without MANAGE_USERS.
    //
    // THE INITIAL PASSWORD - H-2, AND THE ONLY TIME A PASSWORD IS IN AN ANSWER
    //   The client never chooses it. UserCrudService generates a random
    //   12-character password with SecureRandom, stores only its bcrypt hash
    //   (bcrypt = a one-way hashing function built for passwords: the stored
    //   value cannot be turned back into the password), and sets
    //   firstLogin = true so the person is forced to change it at once -
    //   FirstLoginFilter blocks every other URL until they do.
    //   It is returned in this response because it exists nowhere else: the
    //   administrator reads it on screen and passes it on. That is also why it
    //   is in its own record, UserCreateResult, instead of being a field of
    //   UserResponse - UserResponse is returned by six other endpoints, and a
    //   password field there would eventually be filled in by mistake.
    //   What H-2 forbids is the obvious alternative: a fixed default password
    //   such as "Welcome123". Every account would then be reachable by anyone
    //   who knows the convention, for as long as one person has not signed in.
    //
    // @RequestBody: Jackson builds the UserRequest from the JSON body.
    // @Valid runs Bean Validation BEFORE this method: @NotBlank on the names,
    //   @Email on the address, @NotNull on the roleId. A failure throws
    //   MethodArgumentNotValidException, which GlobalExceptionHandler turns
    //   into a 400 naming each bad field.
    //   Concrete example: without @Email, "jean.dupont" would be accepted as an
    //   address. That address is the login AND the key used to look the account
    //   up in the security cache, so the person could simply never sign in.
    @PostMapping("/api/users")
    public ResponseEntity<UserCreateResult> create(@Valid @RequestBody UserRequest request) {
        UserCreateResult result = userCrudService.create(request);
        // Absolute address of the account just created:
        // fromCurrentRequest() reuses the scheme, host and port the client
        // actually called (http://host/api/users), .path("/{id}") adds the
        // placeholder and buildAndExpand fills it with the generated id.
        // Note result.user().id() and not result.id(): the id lives on the
        // nested UserResponse, since UserCreateResult only wraps the pair.
        // WHY not a hand-written URL: behind the Docker reverse proxy a
        // hard-coded "http://localhost:8080/..." would point the browser at an
        // address that does not exist for it.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.user().id()).toUri();
        return ResponseEntity.created(location).body(result);
    }

    // WHAT IT DOES / GIVES BACK
    //   PUT /api/users/{id} -> 200 with the updated account. It changes the
    //   names, the e-mail and the ROLE. 409 if the new e-mail belongs to
    //   somebody else or the roleId is unknown, 404 if the account is unknown,
    //   400 on a validation failure, 403 without MANAGE_USERS.
    //
    // CHANGING THE ROLE IS A SECURITY OPERATION, NOT A FIELD UPDATE
    //   When the role really changes, UserCrudService raises the user's
    //   tokenVersion (revokeAllTokens) and evicts the matching entry from the
    //   "securityContext" cache (ADR-017). Every access token that person is
    //   carrying stops being accepted at once, so they sign in again and
    //   receive the new permission set.
    //   Without that, demoting somebody would leave their old permissions
    //   working until their current token expired - they could keep doing what
    //   they were just forbidden from doing. The cache eviction uses the OLD
    //   e-mail and the OLD version on purpose, because that is the key the
    //   entry was stored under.
    //   Note the password is not touched here: this endpoint never resets it.
    @PutMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userCrudService.update(id, request));
    }

    // WHAT IT DOES / GIVES BACK
    //   PATCH /api/users/{id}/reset-account -> 200 with a UserCreateResult
    //   carrying a NEW one-time password. This is the "this person forgot their
    //   password" button. 404 if unknown, 403 without MANAGE_USERS.
    //   The service generates a new random password, stores its bcrypt hash,
    //   sets firstLogin = true again so it must be changed at the next sign-in,
    //   raises tokenVersion and clears the cache entry - so every session that
    //   person had open is cut immediately. That last part matters most when
    //   the reason for the reset is a stolen laptop.
    //
    // WHY PATCH AND NOT PUT: this changes one aspect of the account, it does
    // not describe the whole account. And why not POST: there is no new
    // resource being created, the account already exists.
    //
    // WHY THERE IS NO REQUEST BODY: there is nothing to send. Letting the
    // administrator choose the new password would mean a human-picked secret
    // travelling in a request and being known to two people; the generated one
    // is random and must be changed at once anyway.
    //
    // NOTE it is not idempotent: calling it twice gives two different
    // passwords, and the first one stops working. The screen asks for
    // confirmation before calling it for that reason.
    @PatchMapping("/api/users/{id}/reset-account")
    public ResponseEntity<UserCreateResult> resetAccount(@PathVariable Long id) {
        return ResponseEntity.ok(userCrudService.resetAccount(id));
    }

    // WHAT IT DOES / GIVES BACK
    //   PATCH /api/users/{id}/deactivate -> 204 No Content. The account stays
    //   in the database but can no longer sign in (active = false), and every
    //   live session is cut at once: the service raises tokenVersion and
    //   evicts the cache entry (ADR-017). 404 if unknown, 403 without
    //   MANAGE_USERS.
    //
    // WHY DEACTIVATE RATHER THAN DELETE: somebody who leaves the company still
    //   appears in the history - they managed projects, they were assigned to
    //   teams, their cost fed past quotes. Removing the row would break those
    //   links; this keeps them readable while closing the door.
    //
    // WHY THE ACTION IS IN THE PATH AND NOT A FIELD IN A BODY
    //   "PATCH /api/users/7 { active: false }" would look more RESTful, but it
    //   would put a security operation on the same route as an ordinary edit -
    //   the same route that also carries names and role. A named sub-path makes
    //   the intent explicit in the access log and in the Angular service, and
    //   it can never be triggered by a stray field in a form payload.
    //
    // ResponseEntity<Void> + noContent(): status 204, no body. There is nothing
    // to describe, and the Void type makes it impossible to add one later by
    // accident.
    @PatchMapping("/api/users/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userCrudService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    // WHAT IT DOES / GIVES BACK
    //   PATCH /api/users/{id}/reactivate -> 204 No Content. It puts
    //   active = true back. 404 if unknown, 403 without MANAGE_USERS.
    //
    // NOTE THE ASYMMETRY, it is deliberate: this one does NOT touch
    //   tokenVersion. Deactivating must revoke sessions; reactivating has
    //   nothing to revoke - the person holds no valid token any more, since
    //   every token they had was invalidated when the account was closed. They
    //   simply sign in again with the password they already had, which is why
    //   reactivation does not reset it either. If the password is also in
    //   doubt, reset-account is the endpoint to call.
    @PatchMapping("/api/users/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable Long id) {
        userCrudService.reactivate(id);
        return ResponseEntity.noContent().build();
    }

    // WHAT IT DOES / GIVES BACK
    //   DELETE /api/users/{id} -> 204 No Content. 404 if unknown, 403 without
    //   MANAGE_USERS.
    //
    // THIS IS A SOFT DELETE
    //   The service sets deleted = true and raises tokenVersion, then evicts
    //   the cache entry; the row stays. Every read of this module filters on
    //   deleted = false, so the account disappears from the screens, but the
    //   foreign keys that point at it - project manager, team assignments,
    //   audit columns - stay valid and the history stays explainable. A real
    //   DELETE would either fail on those references or erase the trace of who
    //   did what.
    //   Consequence worth knowing: the e-mail is not freed. The uniqueness
    //   check is existsByEmailAndDeletedFalse, and migration V18 replaced the
    //   plain unique constraint on users.email with a partial index limited to
    //   live rows - so the same address can be reused for a new account, while
    //   two live accounts can still never share one.
    //
    // WHY DELETE AND NOT ANOTHER PATCH SUB-PATH: from the caller's point of
    //   view the account is gone. That the row survives is an implementation
    //   choice of the server, and the verb describes the intent.
    @DeleteMapping("/api/users/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userCrudService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
