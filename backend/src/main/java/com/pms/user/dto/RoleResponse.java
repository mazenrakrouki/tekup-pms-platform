package com.pms.user.dto;

import java.util.List;

// ============================================================================
// FILE: RoleResponse
//
// WHAT THIS FILE IS
//   The read-only shape sent to the browser for ONE role of the dynamic RBAC
//   model (ADR-001): its name, the permissions it carries, and two extra facts
//   the admin screen needs to decide what it may do with it. It is a DTO (Data
//   Transfer Object): a small flat object built only to travel over HTTP.
//
// WHERE IT SITS IN THE FLOW
//   Role entity (table "roles" + join table role_permissions)
//     -> built by hand in RoleAdminService.toResponse()
//     -> RoleController: GET /api/admin/roles, GET /api/admin/roles/{id}, and
//        the answer of POST and PUT /api/admin/roles/{id}
//     -> Angular RbacService.listRoles() and the role-list screen, which read
//        it through the "Role" interface of core/models/rbac.model.ts.
//   Every one of those service methods is guarded by
//   @PreAuthorize("hasAuthority('MANAGE_ROLES')") - the permission is checked
//   on the SERVICE, not on the controller.
//
// WHY IT EXISTS
//   Returning the Role entity itself would publish the audit fields it
//   inherits from BaseEntity (createdBy, updatedAt, deleted), and each
//   Permission inside it would be a full entity too. Worse, two of the fields
//   below simply do not exist on the entity: userCount is counted with a
//   separate query, and the permission list is sorted before being sent. This
//   record is the exact contract the admin screen needs, nothing more.
//
// WHY "DYNAMIC" MATTERS HERE
//   The permissions of a role are rows of role_permissions, not constants in
//   Java. That is why the admin screen can change them, and why this record
//   carries the whole permission list back: the screen ticks the boxes from it.
//   RoleAdminService.update() clears the "securityContext" cache right after
//   saving (ADR-017), so people already signed in rebuild their authority list
//   from the database instead of keeping the old one until they log out.
// ============================================================================

/**
 * One role and the permissions attached to it.
 *
 * <p>Built only by RoleAdminService.toResponse(). That method sorts the
 * permissions by module, then by code, so the checkbox list on screen keeps
 * the same order between two page loads instead of following the random order
 * of a HashSet.
 *
 * <p>Why a record and not a class with setters: a response is built once,
 * written to JSON, then thrown away. Nothing should be able to change it in
 * between, and a role is exactly the kind of object where a stray change would
 * be a security problem rather than a display bug.
 */
public record RoleResponse(
        // Primary key of the "roles" row. The screen sends it back in the URL
        // of PUT and DELETE /api/admin/roles/{id}.
        Long id,

        // The role name, in UPPERCASE_SNAKE: ADMIN, DIRECTEUR, CHEF_PROJET,
        // DEVELOPPEUR, or any role an admin has created.
        //
        // Important for a jury question: this is a LABEL. No authorization
        // decision anywhere in this project compares a role name; access is
        // always decided on permission codes, with
        // @PreAuthorize("hasAuthority('CODE')") on service methods. The name is
        // used for display, and by the start-up seeding code that looks roles
        // up by name - which is exactly why a system role may not be renamed.
        String name,

        // Free help text. May be null: roles.description was added by
        // V25__rbac_admin_module.sql with no NOT NULL, and RoleAdminService
        // stores null rather than an empty string (blankToNull). The Angular
        // interface declares it optional for that reason, and the screen must
        // not assume a text is there.
        String description,

        // true = one of the four built-in business roles
        // (ADMIN / DIRECTEUR / CHEF_PROJET / DEVELOPPEUR), which cannot be
        // renamed or deleted. The column is roles.is_system; the Java field is
        // called "system" because SYSTEM is a reserved word in several
        // databases, so V25 had to name the column differently.
        //
        // Why the client needs to know: the screen greys out the name field and
        // hides the delete button for those roles. The real guard is on the
        // server - RoleAdminService.update() and delete() throw a
        // BusinessRuleException (422) - but without this flag the user would
        // only discover the rule after being refused.
        //
        // What this flag does NOT protect: the permissions inside the role.
        // Those stay editable even for a system role, and that is the whole
        // point of dynamic RBAC.
        boolean system,

        // How many non-deleted users currently carry this role. It is not a
        // column: RoleAdminService counts it per role with
        // userRepository.countByRoleIdAndDeletedFalse(id).
        //
        // Why it is sent: deleting a role that people still use would leave
        // those accounts with no role at all, and users.role_id is NOT NULL.
        // The server refuses such a delete with a message naming the number of
        // users; this field lets the screen show the same number up front, so
        // the admin knows to move those people to another role first.
        //
        // "long" and not "int" only because count() queries return a long.
        long userCount,

        // The permissions this role carries, already sorted by module then by
        // code. Each one is a PermissionResponse - the same four-field record
        // used by the permission screens - so the front end has a single shape
        // to read everywhere.
        //
        // A List and not a Set: the order is decided by the server and must be
        // kept, and a Set gives no ordering guarantee. Role.permissions itself
        // is a Set<Permission>, because role_permissions is a many-to-many join
        // table whose key is the pair (role_id, permission_id).
        //
        // Why the full objects and not just the ids: the screen prints the code
        // and the description of every permission, grouped by module. Sending
        // ids only would force a second call and a lookup on every row.
        List<PermissionResponse> permissions
) {}
