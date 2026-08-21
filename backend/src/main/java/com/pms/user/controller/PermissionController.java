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

@Tag(name = "Permissions", description = "Consultation du référentiel de permissions RBAC")
@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionAdminService permissionAdminService;

    /**
     * Liste les permissions. {@code ?withRoles=true} enrichit chaque permission
     * de la liste des rôles qui la détiennent.
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
