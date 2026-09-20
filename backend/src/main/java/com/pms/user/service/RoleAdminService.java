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

// Lets an administrator manage roles: list, create, rename, change permissions, delete —
// the part of the app where the ADR-001 authorization matrix is actually edited. Sister to
// PermissionAdminService (which only shows the catalogue); both gated by MANAGE_ROLES.
// Three rules enforced here: (1) a system role (ADMIN/DIRECTEUR/CHEF_PROJET/DEVELOPPEUR)
// can't be renamed or deleted, though its permissions stay editable; (2) a role still
// carried by a user can't be deleted; (3) any permission change clears the security-context
// cache so open sessions (cached under "email:tokenVersion", 5 min TTL) pick up the new
// matrix on their very next request instead of waiting out the cache.

/**
 * Role administration (dynamic RBAC -- ADR-001), guarded by
 * {@code MANAGE_ROLES}.
 *
 * <p>Every method carries its own {@code @PreAuthorize} rather than one on the class, so a
 * method added later is visibly unprotected in review instead of silently inheriting a rule.
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    // Used only to COUNT the users of a role, for the screen and for delete()'s guard.
    private final UserRepository userRepository;
    // Handle on the "securityContext" cache that JwtAuthenticationFilter fills.
    private final CacheManager cacheManager;

    /**
     * Lists every role, sorted by name, each with its permissions and user count (one COUNT
     * query per role — fine given this table holds a handful of rows).
     */
    // Guard sits on the service (protects every caller, not just the HTTP door) and names a
    // permission, never a role name (ADR-001).
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<RoleResponse> findAll() {
        return roleRepository.findAll().stream()
                // findAll() ignores soft delete; without this a retired role would reappear.
                .filter(r -> !r.isDeleted())
                .sorted(Comparator.comparing(Role::getName))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns one role by id, with its permissions. Throws NotFoundException (404) when
     * unknown or soft-deleted — see loadRole below.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public RoleResponse findById(Long id) {
        return toResponse(loadRole(id));
    }

    /**
     * Creates a new role from the name, description and permission ids sent by the admin
     * screen. No cache eviction here, unlike update()/delete(): a brand-new role has no
     * user yet, so no open session is affected.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    // Not readOnly: the duplicate-name check and the INSERT must commit together, and a
    // thrown resolvePermissions() must leave nothing written.
    @Transactional
    public RoleResponse create(RoleRequest request) {
        // trim(): "  AUDITEUR " and "AUDITEUR" must collide, not pass as two different names.
        String name = request.name().trim();
        // Checked here for a clear message; uk_roles_name is the real guarantee against a
        // race between two administrators. 409 reserved for duplicates, 422 for broken rules.
        if (roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }
        Role role = Role.builder()
                .name(name)
                .description(blankToNull(request.description()))
                // Explicit, not left to the default: a created role must never come out
                // system=true, or it could never be renamed/deleted again.
                .system(false)
                .permissions(resolvePermissions(request.permissionIds()))
                .build();
        return toResponse(roleRepository.save(role));
    }

    /**
     * Updates a role's name, description and permission set. Permissions are REPLACED, not
     * merged — the screen always sends the complete ticked list, so merging would make it
     * impossible to untick a box.
     *
     * <p>Throws BusinessRuleException (422) renaming a system role, IllegalArgumentException
     * (409) when the new name is taken.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        Role role = loadRole(id);
        String name = request.name().trim();

        // Rule 1: system role names are protected because ADMIN/DIRECTEUR/CHEF_PROJET/
        // DEVELOPPEUR are referenced by Flyway seed data and demo data. Condition only
        // trips when the name actually changes, so editing a system role's permissions
        // alone still works.
        if (role.isSystem() && !role.getName().equals(name)) {
            throw new BusinessRuleException("Un rôle système ne peut pas être renommé.");
        }
        // Only checked when the name changes, or a role would collide with its own row.
        if (!role.getName().equals(name) && roleRepository.existsByName(name)) {
            throw new IllegalArgumentException("Un rôle porte déjà ce nom : " + name);
        }

        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        role.setPermissions(resolvePermissions(request.permissionIds()));

        RoleResponse saved = toResponse(roleRepository.save(role));
        // Rule 3 (ADR-017): without this, an administrator removing MANAGE_DI from
        // CHEF_PROJET would leave already-signed-in project managers using it for up to
        // five more minutes (the cache's expireAfterWrite).
        evictSecurityContextCache();
        return saved;
    }

    /**
     * Deletes a role. Refuses with BusinessRuleException (422) for a system role or one
     * still carried by at least one user — users.role_id is NOT NULL, so deleting a role in
     * use would either break that foreign key or leave people with no permissions at all.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional
    public void delete(Long id) {
        Role role = loadRole(id);
        if (role.isSystem()) {
            throw new BusinessRuleException("Un rôle système ne peut pas être supprimé.");
        }
        // Soft-deleted users are excluded so a removed account can't block cleanup.
        long users = userRepository.countByRoleIdAndDeletedFalse(id);
        if (users > 0) {
            throw new BusinessRuleException(
                    "Ce rôle est affecté à " + users + " utilisateur(s) — réaffectez-les avant suppression.");
        }
        // Real DELETE, not soft — safe because the guards above proved no user points at
        // this role; role_permissions rows are removed via ON DELETE CASCADE (V1).
        roleRepository.delete(role);
        evictSecurityContextCache();
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Turns permission ids sent by the screen into real Permission rows, refusing the whole
     * request if one is unknown — silently dropping it would give the role a set the
     * administrator never chose.
     */
    private Set<Permission> resolvePermissions(Set<Long> ids) {
        // Empty set is legitimate (a role with no permission is allowed); null guards an
        // optional JSON field.
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        // One query via findAllById instead of one per id.
        Set<Permission> found = new HashSet<>(permissionRepository.findAllById(ids));
        // findAllById silently skips ids it can't find; comparing sizes turns that silence
        // into a loud error (e.g. a permission retired by a migration while the screen was open).
        if (found.size() != ids.size()) {
            throw new NotFoundException("Une ou plusieurs permissions sont introuvables.");
        }
        return found;
    }

    /**
     * Loads one role by id, or throws NotFoundException (404). Centralizes the soft-delete
     * filter so the four public methods above can't forget it.
     */
    private Role loadRole(Long id) {
        return roleRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Rôle introuvable : " + id));
    }

    /**
     * Copies one Role into the RoleResponse record sent to the API. Hand-written rather
     * than MapStruct (ADR-018) because userCount is a separate COUNT query and the
     * permission list must be sorted before it travels.
     */
    private RoleResponse toResponse(Role role) {
        List<PermissionResponse> perms = role.getPermissions().stream()
                // Role.permissions is a HashSet with no natural order; without this the edit
                // screen's checkboxes would reshuffle after each save.
                .sorted(Comparator.comparing(Permission::getModule).thenComparing(Permission::getCode))
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
        // One COUNT per role, called from findAll()'s stream — accepted since roles are few
        // and this is an administrator-only page.
        long userCount = userRepository.countByRoleIdAndDeletedFalse(role.getId());
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(),
                role.isSystem(), userCount, perms);
    }

    /**
     * Empties the whole security-context cache (ADR-017), called after update()/delete().
     * Clears everything rather than one entry because a role change can affect an unknown
     * number of users; the cost is one extra DB read per active session on their next request.
     */
    private void evictSecurityContextCache() {
        Cache cache = cacheManager.getCache("securityContext");
        // Null when no such cache is configured, e.g. a test context without Caffeine settings.
        if (cache != null) cache.clear();
    }

    /**
     * Turns an empty/whitespace-only description into null and trims the rest, so the
     * column doesn't end up with a mix of null and "" for the same "no description" case.
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
