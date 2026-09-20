package com.pms.user.controller;

import com.pms.user.dto.PermissionResponse;
import com.pms.user.dto.PermissionWithRolesResponse;
import com.pms.user.service.PermissionAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// HTTP door to the RBAC permission catalogue: one read-only endpoint listing every permission
// code the application knows about. No POST/PUT/DELETE on purpose — a permission only means
// something if Java code names it (@PreAuthorize), so the catalogue is owned by code and Flyway
// migrations; administering RBAC means attaching permissions to roles (RoleController's job).

/**
 * Read-only HTTP endpoint for the RBAC permission catalogue (ADR-001). Holds no logic: reads
 * one query parameter, delegates to the service, returns what it gets. The permission check
 * itself lives on the service method, not here, so it still runs for any other caller.
 */
@Tag(name = "Permissions", description = "Consultation du référentiel de permissions RBAC")
@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionAdminService permissionAdminService;

    /**
     * Lists the permissions. {@code ?withRoles=true} adds to each permission the list of roles
     * that hold it — one endpoint with a flag rather than two URLs, since the expensive branch
     * (a pass over every role) should stay opt-in for the role editor's frequent cheap calls.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "withRoles", defaultValue = "false") boolean withRoles) {
        if (withRoles) {
            List<PermissionWithRolesResponse> body = permissionAdminService.findAllWithRoles();
            return ResponseEntity.ok(body);
        }
        List<PermissionResponse> body = permissionAdminService.findAll();
        return ResponseEntity.ok(body);
    }
}
