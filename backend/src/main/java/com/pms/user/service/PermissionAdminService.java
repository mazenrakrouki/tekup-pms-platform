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

/**
 * Consultation du référentiel de permissions (RBAC dynamique — ADR-001), gardée par {@code MANAGE_ROLES}.
 *
 * <p>Le catalogue de permissions est <b>défini par le code</b> : une permission n'a d'effet que si un
 * {@code @PreAuthorize} la référence. L'administration se fait donc par <b>affectation aux rôles</b>
 * (voir {@link RoleAdminService}), pas par création de codes arbitraires — d'où l'absence volontaire
 * de CRUD de permissions (décision cohérente avec le nettoyage V20).
 */
@Service
@RequiredArgsConstructor
public class PermissionAdminService {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<PermissionResponse> findAll() {
        return permissionRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
    }

    /** Chaque permission enrichie des rôles qui la détiennent (vue transverse). */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<PermissionWithRolesResponse> findAllWithRoles() {
        List<Role> roles = roleRepository.findAll().stream()
                .filter(r -> !r.isDeleted())
                .toList();

        return permissionRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                .map(p -> {
                    List<String> holders = roles.stream()
                            .filter(r -> r.getPermissions().stream().anyMatch(rp -> rp.getId().equals(p.getId())))
                            .map(Role::getName)
                            .sorted()
                            .toList();
                    return new PermissionWithRolesResponse(
                            p.getId(), p.getCode(), p.getModule(), p.getDescription(), holders);
                })
                .toList();
    }
}
