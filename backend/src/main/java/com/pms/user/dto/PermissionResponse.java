package com.pms.user.dto;

/** Une permission du référentiel RBAC (ADR-001). */
public record PermissionResponse(
        Long id,
        String code,
        String module,
        String description
) {}
