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

// =============================================================================
// FILE: PermissionAdminService.java
//
// WHAT THIS FILE IS
//   The read-only catalogue of permissions for the administration screens. It
//   lists every permission code that exists in the database, and, in the second
//   method, which roles currently hold each one.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  PermissionController, on GET /api/admin/permissions. One single
//     endpoint serves both methods: the query parameter ?withRoles=true picks
//     findAllWithRoles(), and its absence picks findAll(). The Angular
//     "Permissions" admin page calls it to draw its table.
//   Calls:      PermissionRepository (the catalogue rows) and RoleRepository
//     (the roles, whose permission sets are read to build the reverse view).
//   Produces:   PermissionResponse and PermissionWithRolesResponse -- small
//     flat objects (DTOs) built only to travel to the browser as JSON.
//
//   Sister service in this same folder: RoleAdminService. This class SHOWS the
//   catalogue; RoleAdminService is the one that CHANGES which role holds what.
//   Both are guarded by the same permission, MANAGE_ROLES, because they are two
//   halves of the same screen.
//
// WHY IT EXISTS
//   Delete it and the administrator has no way to see the list of codes they
//   are allowed to tick when editing a role. They would have to read the Flyway
//   migrations to know that "MANAGE_DI" exists.
//
// WHY THERE IS NO create / update / delete HERE -- the point a jury will ask
//   The permission catalogue is DEFINED BY THE CODE. A permission only does
//   something if some @PreAuthorize in the Java source names it. If an
//   administrator could invent the code "SUPER_ACCESS", the row would appear on
//   screen and look meaningful, could be ticked on a role, and would change
//   absolutely nothing -- the worst kind of security illusion. So the rows are
//   created only by Flyway migrations (V2 seeds them, V12/V13/V25 add more),
//   and administration happens by ASSIGNING existing codes to roles, which is
//   RoleAdminService's job. This is exactly the dynamic RBAC of ADR-001: the
//   vocabulary is fixed by the code, the matrix is data.
//   Traceability note kept from the original French comment: V20 removed
//   MANAGE_ROLES from the catalogue because nothing enforced it at that time;
//   V25 put it back once these role-administration endpoints existed to be
//   protected by it.
// =============================================================================

/**
 * Read-only view of the permission catalogue (dynamic RBAC -- ADR-001),
 * guarded by {@code MANAGE_ROLES}.
 *
 * <p>Why both methods return a fresh list instead of caching one: the
 * catalogue is about twenty rows, it is read only by administrators, and a
 * cache would have to be invalidated by every future migration. The cost is
 * one small SELECT.
 */
@Service
// Lombok writes the constructor over the two final fields; Spring injects the
// repositories through it. Final fields mean the service can never exist in a
// half-built state.
@RequiredArgsConstructor
public class PermissionAdminService {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    /**
     * Lists every permission that exists, sorted by module then by code.
     *
     * <p>Gives back a list of PermissionResponse: id, code, module,
     * description. The id is included because the admin screen sends those ids
     * back to RoleAdminService when saving a role; the code and the description
     * are what the administrator reads.
     *
     * <p>Why it returns DTOs and not the Permission entities: an entity
     * serialised straight to JSON would drag in the audit columns of
     * BaseEntity, and any future relation added to Permission would start
     * leaking into the API without anyone noticing.
     */
    // THE security check, and the reason it is HERE and not on the controller.
    // WHAT: Spring refuses the call unless the caller's authority list contains
    // the exact string "MANAGE_ROLES". That list was built from the user's role
    // permissions (see UserDetailsServiceImpl / JwtAuthenticationFilter).
    // WHY on the service and not on the controller: the guard then protects
    // EVERY caller. If tomorrow a scheduled job, a test, or another service
    // calls findAll() directly, it is still checked. A guard on the controller
    // only protects the HTTP door.
    // WHY a permission and never a role name: an administrator can move
    // MANAGE_ROLES to another role from the admin screen without touching the
    // Java code (ADR-001). hasRole('ADMIN') would freeze that decision in the
    // source.
    // WITHOUT IT: any authenticated person, including a developer account,
    // could read the full list of security codes of the application.
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    // One read-only unit of work: nothing is written, and Hibernate is told not
    // to check the loaded objects for changes at the end.
    @Transactional(readOnly = true)
    public List<PermissionResponse> findAll() {
        return permissionRepository.findAll().stream()
                // This project never really deletes rows: BaseEntity carries a
                // "deleted" flag and the row stays in the table (soft delete).
                // findAll() is the plain Spring Data method, so it brings back
                // deleted rows too and they have to be dropped here.
                // Without this line, a permission that was retired by a
                // migration would still be offered as a tick box on the
                // role-editing screen.
                .filter(p -> !p.isDeleted())
                // Sorts by module first ("ADMIN", "KPI", "PROJET"...), then by
                // code inside each module.
                // Comparator.comparing(...).thenComparing(...) builds that
                // two-level order; the fully-qualified Permission name is used
                // because this file does not import the entity.
                // WHY: about twenty codes in one flat, unordered list is
                // unreadable, and the order returned by the database is not
                // guaranteed to be stable. Grouping by module lets the screen
                // draw one section per functional family.
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                // Copies the four fields the screen needs into the response
                // record. Four values, chosen explicitly -- the audit columns
                // and anything added to the entity later stay behind.
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getDescription()))
                .toList();
    }

    /**
     * Same catalogue, but each permission also carries the names of the roles
     * that currently hold it.
     *
     * <p>Gives back a list of PermissionWithRolesResponse: the four fields
     * above plus a sorted list of role names, for example
     * ["ADMIN", "DIRECTEUR"].
     *
     * <p>Why this reverse view is computed here in Java instead of being a
     * field on the Permission entity: the link between roles and permissions is
     * mapped on the Role side only, which owns the join table
     * role_permissions. A mirrored collection on Permission would be loaded on
     * every single login -- it would drag the roles, and through them their
     * users, into memory each time somebody signs in. Here the cost is paid
     * only on one administration screen.
     *
     * <p>Why the answer is useful: before moving MANAGE_DI away from a role, an
     * administrator needs to see who else holds it. Without this screen they
     * would have to open every role one by one.
     */
    @PreAuthorize("hasAuthority('MANAGE_ROLES')")
    @Transactional(readOnly = true)
    public List<PermissionWithRolesResponse> findAllWithRoles() {
        // The roles are loaded ONCE, before the loop below.
        // WHY that matters: the search for holders is done in memory against
        // this list. Querying the roles inside the loop instead would fire one
        // SELECT per permission (the classic "N+1 queries" problem): twenty
        // permissions would mean twenty round trips to the database for one
        // page.
        // Role.permissions is mapped EAGER, so each role already arrives with
        // its permission set attached -- no extra query is fired when the set
        // is read further down.
        List<Role> roles = roleRepository.findAll().stream()
                // Same soft-delete filter as above: a retired role must not be
                // shown as a holder of a permission.
                .filter(r -> !r.isDeleted())
                .toList();

        return permissionRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .sorted(Comparator.comparing(com.pms.user.entity.Permission::getModule)
                        .thenComparing(com.pms.user.entity.Permission::getCode))
                .map(p -> {
                    // For this one permission, keep the roles whose permission
                    // set contains it, then keep only their names.
                    // anyMatch stops at the first match instead of testing the
                    // whole set, which is why it is used rather than a count.
                    // The comparison is on the database id, not on the object:
                    // Permission does not override equals(), so two objects
                    // loaded in two different places would not be "equal" even
                    // when they are the same row. Comparing ids makes the
                    // answer correct whatever Hibernate did with its cache.
                    // Without .sorted() the role names would come out in
                    // whatever order the database returned, and the same page
                    // reloaded twice could show them differently.
                    List<String> holders = roles.stream()
                            .filter(r -> r.getPermissions().stream().anyMatch(rp -> rp.getId().equals(p.getId())))
                            .map(Role::getName)
                            .sorted()
                            .toList();
                    // Only the role NAMES travel, not the Role objects. Sending
                    // whole roles would mean sending their entire permission
                    // set again for every line of the table.
                    return new PermissionWithRolesResponse(
                            p.getId(), p.getCode(), p.getModule(), p.getDescription(), holders);
                })
                .toList();
    }
}
