package com.pms.user.repository;

import com.pms.user.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Database access for the permissions table (one row = one testable right, e.g.
 * MANAGE_USERS, VIEW_KPI). Read by PermissionAdminService (the read-only catalogue) and by
 * RoleAdminService.resolvePermissions (turning ids sent by the browser into rows to attach
 * to a role). Part of the ADR-001 chain User -> Role -> Permission -> Spring Security
 * authority -> @PreAuthorize; the code never tests a role name, only permission codes.
 *
 * <p>No create/update/delete here on purpose: the catalogue of codes is defined by the code
 * itself (a @PreAuthorize has to name a code for it to mean anything), so it arrives only
 * through Flyway migrations. Administrators change the ASSIGNMENT of codes to roles, not
 * the catalogue — that's RoleAdminService's job.
 *
 * <p>Security note: @PreAuthorize("hasAuthority('MANAGE_ROLES')") sits on the calling
 * services, never here — a method of this file is not safe on its own.
 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    // Derived query (checked against the entity at start-up). Returns an Optional so callers
    // handle "no such code" instead of NPE-ing on a null.
    // Deliberately NOT "...AndDeletedFalse": uk_permissions_code (V1) is an absolute unique
    // constraint that still counts deleted rows, so filtering here would make a retired code
    // look free and then fail with a duplicate-key error on insert.
    // No caller in the running application — used only by integration test fixtures
    // (TestFixtures.perm, AgileControllerTest) via findByCode(...).orElseGet(() -> save(...)).
    Optional<Permission> findByCode(String code);
}
