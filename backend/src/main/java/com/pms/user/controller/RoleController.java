package com.pms.user.controller;

import com.pms.user.dto.RoleRequest;
import com.pms.user.dto.RoleResponse;
import com.pms.user.service.RoleAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

// HTTP door to role administration (/api/admin/roles): where the authorization matrix itself is
// edited — ADR-001 is permission-based, never role-name-based, so granting a right is one row in
// role_permissions via PUT here, no redeploy. RoleAdminService.update()/delete() also clear the
// security-context cache (ADR-017) so a revoked permission takes effect for already-signed-in
// users immediately. ADR-021's ProjectScopeInterceptor does not apply: a role isn't project-scoped.

/**
 * Administration of roles and the permissions attached to them (ADR-001). No rule here: maps
 * HTTP onto {@link RoleAdminService}, where built-in-role protection, the duplicate-name check
 * and the cache clear all live, so they apply to every caller, not just these five URLs.
 */
@Tag(name = "Rôles", description = "Administration des rôles et affectation des permissions (RBAC dynamique)")
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleAdminService roleAdminService;

    // userCount travels with each role so the admin list can grey out delete and the service can
    // refuse it, from the same number — the button and the server can't disagree.
    @GetMapping
    public ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleAdminService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(roleAdminService.findById(id));
    }

    /**
     * Creates a role -> 201. Always created with system = false: a built-in role can only come
     * from a migration, never from this endpoint.
     */
    @PostMapping
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        RoleResponse created = roleAdminService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces a role's full state, including its permission set (role.setPermissions(...) is
     * a full replace, not a merge) -> 200. An empty permissionIds strips every permission from
     * the role; the Angular editor always sends the complete ticked list, which is what makes
     * this safe.
     */
    @PutMapping("/{id}")
    public ResponseEntity<RoleResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody RoleRequest request) {
        return ResponseEntity.ok(roleAdminService.update(id, request));
    }

    /**
     * Deletes a role for real (not soft-deleted, unlike most tables) -> 204. Safe only because
     * the service already refuses to delete a built-in role or one still held by a live user —
     * users.role_id is NOT NULL, so either would otherwise leave accounts pointing at nothing.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        roleAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
