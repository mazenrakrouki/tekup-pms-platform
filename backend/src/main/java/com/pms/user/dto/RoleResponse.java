package com.pms.user.dto;

import java.util.List;

// Read-only view of one role for the admin screens: name, permissions, and two extra flags
// (system, userCount) the UI needs to decide what it may do with it. Built by hand in
// RoleAdminService.toResponse() rather than mapped, since userCount is a separate query.

/**
 * One role and the permissions attached to it.
 *
 * <p>Permissions are sorted by module then code so the checkbox list keeps a stable order between
 * page loads instead of following a HashSet's random order.
 */
public record RoleResponse(
        // Primary key, reused in the URL of PUT/DELETE.
        Long id,

        // The role name (UPPERCASE_SNAKE). Display only — no authorization decision in this
        // project compares a role name; access is always decided on permission codes.
        String name,

        // Free help text; may be null for roles created before V25 added the column.
        String description,

        // true = one of the four built-in roles, which cannot be renamed or deleted (the real
        // guard is server-side in RoleAdminService). Column is is_system; field is "system"
        // because SYSTEM is a reserved word in several databases. Permissions inside a system
        // role stay editable — that's the whole point of dynamic RBAC.
        boolean system,

        // Non-deleted users currently carrying this role, counted separately since it isn't a
        // column. Lets the admin see up front how many accounts a delete would orphan.
        long userCount,

        // Permissions this role carries, sorted by module then code, as full PermissionResponse
        // objects (not just ids) so the screen can print code + description without a second call.
        List<PermissionResponse> permissions
) {}
