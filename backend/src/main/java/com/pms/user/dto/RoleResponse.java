package com.pms.user.dto;

import java.util.List;

/**
 * Un rôle et ses permissions affectées. {@code system} = rôle métier intégré
 * (ADMIN/DIRECTEUR/CHEF_PROJET/DEVELOPPEUR) protégé contre le renommage et la suppression.
 * {@code userCount} = nombre d'utilisateurs actifs portant ce rôle (garde-fou de suppression).
 */
public record RoleResponse(
        Long id,
        String name,
        String description,
        boolean system,
        long userCount,
        List<PermissionResponse> permissions
) {}
