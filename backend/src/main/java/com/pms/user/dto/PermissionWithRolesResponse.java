package com.pms.user.dto;

import java.util.List;

/** Une permission enrichie de la liste des rôles qui la détiennent (vue transverse RBAC). */
public record PermissionWithRolesResponse(
        Long id,
        String code,
        String module,
        String description,
        List<String> roleNames
) {}
