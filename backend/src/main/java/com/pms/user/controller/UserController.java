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

// HTTP door to accounts: the signed-in user's profile (/api/me/context), the role drop-down for
// the user form (/api/roles), and the admin CRUD on accounts (/api/users...) — three prefixes,
// hence no class-level @RequestMapping. Also the entry point of two non-obvious security ops:
// reset-account (new one-time password) and deactivate (cuts every live session at once).
//
// Session revocation (ADR-017, not visible in this file): each user row carries a tokenVersion
// counter; UserCrudService bumps it and evicts the security-context cache on deactivate, delete,
// reset, or role change, so old permissions can't keep working in an already-open session.

/**
 * Accounts: session profile, role list for the user form, and the administrator's CRUD. No rule
 * here — email uniqueness, the one-time password (H-2), soft delete and session revocation
 * (ADR-017) all live in {@link UserCrudService}.
 */
@Tag(name = "Utilisateurs", description = "CRUD utilisateurs, activation/désactivation, contexte de session")
@RestController
@RequiredArgsConstructor
public class UserController {

    // Read-only profile lookup; separate from UserCrudService because reading your own context needs no permission.
    private final UserService userService;
    private final UserCrudService userCrudService;
    // Used only by listRoles() below — the one place in this package where a controller reads a repository directly.
    private final RoleRepository roleRepository;

    /**
     * Who the caller is: id, name, email, role label, and — this is what makes the menu dynamic
     * (ADR-001) — the full set of permission codes. The frontend decides what to show from these
     * codes, never from the role name. Email comes from @AuthenticationPrincipal (set by
     * JwtAuthenticationFilter after verifying the token), not a request parameter, so nobody can
     * ask for someone else's context.
     */
    @GetMapping("/api/me/context")
    public ResponseEntity<UserContextResponse> getContext(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(userService.getContext(email));
    }

    /**
     * Light {id, name} list for the user form's role drop-down — not the RBAC admin endpoint
     * (that's GET /api/admin/roles, which needs MANAGE_ROLES and returns full permissions).
     *
     * <p>The one place in this package where @PreAuthorize sits on the controller instead of a
     * service method: there is no service here, just a direct repository read, so there's no
     * service method to guard. MANAGE_USERS, not MANAGE_ROLES, because this serves the user form.
     */
    @GetMapping("/api/roles")
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public ResponseEntity<List<Map<String, Object>>> listRoles() {
        // Map, not RoleResponse: a drop-down needs only id + name, not the whole permission matrix.
        return ResponseEntity.ok(roleRepository.findAll().stream()
                .map(r -> Map.of("id", (Object) r.getId(), "name", r.getName()))
                .toList());
    }

    /**
     * Paginated account list with optional free-text/role/active filters, combined. Sorted by
     * default (lastName ASC) so paging stays stable — an unsorted page order lets rows repeat
     * or vanish across pages.
     */
    @GetMapping("/api/users")
    public ResponseEntity<Page<UserResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(userCrudService.search(search, roleId, active, pageable));
    }

    /**
     * Every live account, unpaginated, for the "who manages this project" / "who joins this
     * team" pickers. A separate endpoint from GET /api/users because it's guarded differently:
     * hasAnyAuthority('ASSIGN_CHEF_PROJET','ASSIGN_DEVELOPER','MANAGE_USERS') lets whoever may
     * assign people read the people list without needing full account-administration rights.
     */
    @GetMapping("/api/users/assignable")
    public ResponseEntity<List<UserResponse>> assignable() {
        return ResponseEntity.ok(userCrudService.findAssignable());
    }

    // UserResponse never carries passwordHash or tokenVersion — the entity itself is never serialized.
    @GetMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userCrudService.findById(id));
    }

    /**
     * Creates an account -> 201 with a UserCreateResult (the saved user plus a one-time random
     * password — H-2 forbids a fixed default like a guessable convention). The password is
     * returned only here, in its own record rather than a UserResponse field, since UserResponse
     * is returned by several other endpoints where a password field would eventually be
     * populated by mistake. firstLogin is set true, so FirstLoginFilter forces a change at once.
     */
    @PostMapping("/api/users")
    public ResponseEntity<UserCreateResult> create(@Valid @RequestBody UserRequest request) {
        UserCreateResult result = userCrudService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.user().id()).toUri();
        return ResponseEntity.created(location).body(result);
    }

    /**
     * Updates an account, including its role. A role change is a security operation, not a
     * field update: the service bumps tokenVersion and evicts the security-context cache
     * (ADR-017) so every access token that person holds stops working immediately, instead of
     * staying valid with the old permissions until it expires. Password is untouched here.
     */
    @PutMapping("/api/users/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userCrudService.update(id, request));
    }

    /**
     * Generates a new one-time password ("forgot password" button) -> 200. Also bumps
     * tokenVersion and clears the cache, cutting every open session — the part that matters
     * most when the reset reason is a stolen device. No request body: the password is always
     * random, never administrator-chosen. Not idempotent — a second call invalidates the first
     * password.
     */
    @PatchMapping("/api/users/{id}/reset-account")
    public ResponseEntity<UserCreateResult> resetAccount(@PathVariable Long id) {
        return ResponseEntity.ok(userCrudService.resetAccount(id));
    }

    /**
     * Disables sign-in and cuts every live session (tokenVersion bump + cache evict) -> 204.
     * Soft, not deleted: the account still fed real history (projects managed, teams joined,
     * past quotes) that must stay explainable. The action lives in a named sub-path rather than
     * a body field so it can't be triggered by a stray field in an ordinary edit payload.
     */
    @PatchMapping("/api/users/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userCrudService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    // Deliberately does NOT touch tokenVersion: deactivation already revoked every token, so
    // there's nothing left to revoke, and the person just signs in again with their old password.
    @PatchMapping("/api/users/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable Long id) {
        userCrudService.reactivate(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft delete -> 204. The row stays (so project-manager/team-assignment/audit references
     * stay valid); uniqueness is checked with existsByEmailAndDeletedFalse, so the email can be
     * reused by a new account even though it's not freed from this row.
     */
    @DeleteMapping("/api/users/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userCrudService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
