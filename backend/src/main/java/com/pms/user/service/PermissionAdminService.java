package com.pms.user.service;

import com.pms.user.dto.PermissionResponse;
import com.pms.user.dto.PermissionWithRolesResponse;
import com.pms.user.entity.Role;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

// Read-only permission catalogue for the admin screens: every code that exists, and (second
// method) which roles hold each one. Sister to RoleAdminService, which changes assignments;
// this class only shows them — codes are seeded by Flyway migrations, never by an admin
// screen, since an invented code would guard nothing (ADR-001: vocabulary is code, matrix is data).

/**
 * Read-only view of the permission catalogue (dynamic RBAC -- ADR-001),
 * guarded by {@code MANAGE_ROLES}.
 */
@Service
@RequiredArgsConstructor
public class PermissionAdminService {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    /**
     * Lists every permission, sorted by module then code, as PermissionResponse (id, code,
     * module, description) — never the entity itself, to keep audit columns off the API.
     */
    // Guard lives on the service, not the controller, so it protects every caller
    // (scheduled job, test, another service), and names a permission rather than a role so
    // MANAGE_ROLES can be moved to another role without a code change (ADR-001).
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<PermissionResponse> findAll() {
        return permissionRepository.findAll().stream()
                // findAll() ignores soft delete; a retired permission must not appear as a
                // tick box on the role-editing screen.
                .filter(p -> !p.isDeleted())
                // Grouped by module so the screen can draw one section per functional family.
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
    }

    /**
     * Same catalogue, each permission also carrying the sorted names of the roles that
     * currently hold it — lets an administrator see who else holds a right before moving it.
     *
     * <p>Computed here rather than as a field on Permission: the join table role_permissions
     * is owned by the Role side, and a mirrored collection would load on every login.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<PermissionWithRolesResponse> findAllWithRoles() {
        // Loaded once so the holder search below runs in memory, not as one SELECT per
        // permission (Role.permissions is EAGER, so no extra query fires either).
        List<Role> roles = roleRepository.findAll().stream()
                .filter(r -> !r.isDeleted())
                .toList();

        return permissionRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                .map(p -> {
                    // ids are compared, not objects: Permission doesn't override equals(), so
                    // two instances of the same row would otherwise never be "equal".
                    List<String> holders = roles.stream()
                            .filter(r -> r.getPermissions().stream().anyMatch(rp -> rp.getId().equals(p.getId())))
                            .map(Role::getName)
                            .sorted()
                            .toList();
                    // Only role NAMES travel — sending whole Role objects would resend their
                    // full permission set for every line of the table.
                    return new PermissionWithRolesResponse(
                            p.getId(), p.getCode(), p.getModule(), p.getDescription(), holders);
                })
                .toList();
    }
}
