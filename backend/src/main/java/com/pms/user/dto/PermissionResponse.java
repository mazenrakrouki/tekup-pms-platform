package com.pms.user.dto;

// ============================================================================
// FILE: PermissionResponse
//
// WHAT THIS FILE IS
//   The read-only shape sent to the browser for ONE permission of the RBAC
//   catalogue. It is a DTO (Data Transfer Object): a small object whose only
//   job is to carry data from the server to the client.
//
// WHERE IT SITS IN THE FLOW
//   Permission entity (table "permissions")
//     -> built by hand with "new PermissionResponse(...)" in
//        PermissionAdminService.findAll() and in RoleAdminService.toResponse()
//     -> PermissionController, GET /api/admin/permissions
//        and RoleController, GET /api/admin/roles (nested inside RoleResponse)
//     -> Angular admin screens (permission-list, role-list), which read it
//        through the "Permission" interface of core/models/rbac.model.ts.
//
// WHY IT EXISTS
//   Without it the controllers would return the Permission entity itself, and
//   two things would go wrong. First, Jackson (the library that turns Java
//   objects into JSON) would also publish the audit fields inherited from
//   BaseEntity - createdBy, updatedAt, deleted - which no client needs.
//   Second, an entity is tied to the open database session, so serialising it
//   after the transaction has closed is exactly how lazy-loading errors appear.
//   This record pins down four fields, so the JSON contract cannot drift when
//   a column is added to the table.
// ============================================================================

/**
 * One permission of the RBAC catalogue (ADR-001).
 *
 * <p>This is a Java "record": an immutable data carrier where the compiler
 * writes the constructor, the accessors (id(), code(), ...), equals and
 * hashCode for us. A record is used instead of a normal class with getters and
 * setters because a response is built once, written to JSON, then thrown away.
 * Nothing should be able to change it in between; a stray setter call on a
 * shared object is the kind of bug that takes days to find.
 */
public record PermissionResponse(
        // Database primary key. The Angular role editor sends these ids back
        // inside RoleRequest.permissionIds when the admin ticks the checkboxes.
        // Why the id and not only the code: if a code were ever renamed, a
        // client that remembered the old string would silently stop matching
        // and the role would look as if it had lost that permission.
        Long id,

        // The exact string the security checks compare against, for example
        // "ASSIGN_DEVELOPER" in @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
        // on a service method. This is the real key of the whole authorization
        // system: the code never tests a role name, it tests this code.
        String code,

        // Functional family, for example "PROJET". Used only to group the rows
        // on screen. Without it the admin page would be one flat list of dozens
        // of codes with no structure.
        String module,

        // Free help text shown next to the code in the admin UI. The column
        // permissions.description has no NOT NULL, so this can be null and the
        // client must cope with that - the Angular interface declares it
        // optional for that reason.
        String description
) {}
