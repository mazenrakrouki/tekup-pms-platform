package com.pms.user.service;

import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.dto.PermissionResponse;
import com.pms.user.dto.RoleRequest;
import com.pms.user.dto.RoleResponse;
import com.pms.user.entity.Permission;
import com.pms.user.entity.Role;
import com.pms.user.repository.PermissionRepository;
import com.pms.user.repository.RoleRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Administration des rôles (RBAC dynamique — ADR-001), gardée par {@code MANAGE_ROLES}.
 *
 * <p>Règles :
 * <ul>
 *   <li>les rôles <b>système</b> (ADMIN/DIRECTEUR/CHEF_PROJET/DEVELOPPEUR) ne peuvent être
 *       ni renommés ni supprimés — leur nom est référencé en dur par le code et les migrations ;
 *       leurs permissions restent modifiables (c'est l'objet même du RBAC dynamique) ;</li>
 *   <li>un rôle affecté à au moins un utilisateur ne peut être supprimé ;</li>
 *   <li>toute modification des permissions d'un rôle purge le cache de contexte de sécurité
 *       afin que les sessions actives reconstruisent leurs autorités depuis la base (ADR-017).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final CacheManager cacheManager;

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<RoleResponse> findAll() {
        return roleRepository.findAll().stream()
                .filter(r -> !r.isDeleted())
                .sorted(Comparator.comparing(Role::getName))
                .map(this::toResponse)
                .toList();
    }

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public RoleResponse findById(Long id) {
        return toResponse(loadRole(id));
    }

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public RoleResponse create(RoleRequest request) {
        String name = request.name().trim();
        if (roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }
        Role role = Role.builder()
                .name(name)
                .description(blankToNull(request.description()))
                .system(false)
                .permissions(resolvePermissions(request.permissionIds()))
                .build();
        return toResponse(roleRepository.save(role));
    }

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        Role role = loadRole(id);
        String name = request.name().trim();

        if (role.isSystem() && !role.getName().equals(name)) {
            throw new BusinessRuleException("Un rôle système ne peut pas être renommé.");
        }
        if (!role.getName().equals(name) && roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }

        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        role.setPermissions(resolvePermissions(request.permissionIds()));

        RoleResponse saved = toResponse(roleRepository.save(role));
        evictSecurityContextCache();   // les sessions actives reconstruiront leurs autorités
        return saved;
    }

    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public void delete(Long id) {
        Role role = loadRole(id);
        if (role.isSystem()) {
            throw new BusinessRuleException("Un rôle système ne peut pas être supprimé.");
        }
        long users = userRepository.countByRoleIdAndDeletedFalse(id);
        if (users > 0) {
            throw new BusinessRuleException(
                    "Ce rôle est affecté à " + users + " utilisateur(s) — réaffectez-les avant suppression.");
        }
        roleRepository.delete(role);   // role_permissions purgé par ON DELETE CASCADE (V1)
        evictSecurityContextCache();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private Set<Permission> resolvePermissions(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        Set<Permission> found = new HashSet<>(permissionRepository.findAllById(ids));
        if (found.size() != ids.size()) {
            throw new NotFoundException("Une ou plusieurs permissions sont introuvables.");
        }
        return found;
    }

    private Role loadRole(Long id) {
        return roleRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Rôle introuvable : " + id));
    }

    private RoleResponse toResponse(Role role) {
        List<PermissionResponse> perms = role.getPermissions().stream()
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
        long userCount = userRepository.countByRoleIdAndDeletedFalse(role.getId());
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(),
                role.isSystem(), userCount, perms);
    }

    private void evictSecurityContextCache() {
        Cache cache = cacheManager.getCache("securityContext");
        if (cache != null) cache.clear();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
