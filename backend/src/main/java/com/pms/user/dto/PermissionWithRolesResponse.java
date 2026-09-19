package com.pms.user.dto;

import java.util.List;

// ============================================================================
// FILE: PermissionWithRolesResponse
//
// WHAT THIS FILE IS
//   The same four fields as PermissionResponse, plus the names of the roles
//   that currently hold this permission. It answers the question an auditor
//   asks first: "who can do this?".
//
// WHERE IT SITS IN THE FLOW
//   PermissionAdminService.findAllWithRoles() builds it: it loads every
//   non-deleted role once, then for each permission keeps the names of the
//   roles whose permission set contains it
//     -> PermissionController, GET /api/admin/permissions?withRoles=true
//     -> Angular permission-list.component, which prints one badge per role
//        name and also searches on them (interface PermissionWithRoles in
//        core/models/rbac.model.ts).
//
// WHY IT EXISTS - AND WHY IT IS A SEPARATE RECORD
//   The role/permission link is a many-to-many table (role_permissions), so the
//   reverse direction "permission -> roles" is not a field on the Permission
//   entity; it has to be computed. Building it costs a pass over every role, so
//   it is kept out of the cheap PermissionResponse used by the role editor.
//   Adding a nullable roleNames field to PermissionResponse instead would mean
//   that every caller has to ask "is this list filled in or not?", and the list
//   would be meaningless where PermissionResponse is nested inside RoleResponse
//   (a role listing its permissions, each permission listing its roles).
// ============================================================================

/** One permission plus the roles that hold it (cross-cutting RBAC view). */
public record PermissionWithRolesResponse(
        // Same meaning as in PermissionResponse; the two records are kept field
        // for field identical so the Angular interface can simply extend the
        // other one.
        Long id,
        String code,
        String module,
        String description,

        // Names of the roles holding this permission, for example
        // ["ADMIN", "DIRECTEUR"]. The service sorts them alphabetically, so the
        // badges never jump around between two page loads.
        //
        // The generic type is List<String> and not List<RoleResponse> on
        // purpose: the screen only prints labels. Sending whole roles would
        // repeat the full permission set of every role inside every permission
        // row - a response of a few kilobytes would become hundreds - and the
        // structure would be circular (role -> permissions -> roles).
        //
        // It is a List and not a Set because the order matters here and is
        // decided by the server; a Set gives no ordering guarantee, so the
        // badges could come out in a different order on each call.
        List<String> roleNames
) {}
