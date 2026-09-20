package com.pms.user.repository;

import com.pms.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Database access for the roles table — a named bundle of permissions (ADMIN, DIRECTEUR,
 * CHEF_PROJET, DEVELOPPEUR, plus any role an administrator creates). Read/written through
 * RoleAdminService (@PreAuthorize MANAGE_ROLES); also used by UserCrudService (resolve
 * roleId on account create/update), PermissionAdminService.findAllWithRoles, and the
 * bootstrap/demo loaders (DataInitializer, DemoDataSeeder, EnterpriseDataSeeder) which look
 * roles up by name.
 *
 * <p>Part of the ADR-001 chain User -> Role -> Permission -> Spring Security authority ->
 * @PreAuthorize. The role NAME is a display label only; no @PreAuthorize or if-statement in
 * this project branches on it, which is what makes the permission matrix editable at
 * runtime without a redeploy.
 *
 * <p>Unlike most tables here, roles are HARD-deleted: RoleAdminService.delete() really
 * removes the row (role_permissions cascades via V1's ON DELETE CASCADE), guarded by two
 * checks — a system role (is_system=true) can never be deleted, and neither can one still
 * carried by a live user (UserRepository.countByRoleIdAndDeletedFalse).
 *
 * <p>Security note: @PreAuthorize("hasAuthority('MANAGE_ROLES')") lives on RoleAdminService,
 * never here.
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

    // Derived query, checked against the entity at start-up. Used by DataInitializer to
    // detect "does ADMIN exist yet?" (empty box = fresh database, seed it) and by the demo
    // loaders' role(name) helper. Not an authorization decision — ADR-001 forbids branching
    // on the name; these callers only create data.
    // No "AndDeletedFalse": uk_roles_name (V1) is an absolute unique constraint and roles
    // are hard-deleted anyway, so no row ever carries deleted=true in practice.
    Optional<Role> findByName(String name);

    // Presence-only check (avoids loading the whole role and its EAGER permission set).
    // Not the real duplicate protection — that's the uk_roles_name DB constraint; this just
    // lets RoleAdminService.create/update give a readable message instead of a raw DB error.
    // update() only calls it when the name actually changes, or a role would collide with itself.
    boolean existsByName(String name);
}
