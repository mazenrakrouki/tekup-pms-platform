package com.pms.user.dto;

import java.util.List;

// One permission plus the names of the roles that currently hold it — the "who can do this?"
// view. Kept separate from PermissionResponse because building the reverse role list costs a
// pass over every role, so it stays out of the cheap record used by the role editor.

/** One permission plus the roles that hold it (cross-cutting RBAC view). */
public record PermissionWithRolesResponse(
        // Same meaning as in PermissionResponse; the two records are kept field-for-field
        // identical so the Angular interface can simply extend the other one.
        Long id,
        String code,
        String module,
        String description,

        // Role names holding this permission, sorted alphabetically by the service so badges
        // stay stable. Names only (not full RoleResponse) to avoid a circular, bloated payload.
        List<String> roleNames
) {}
