package com.pms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Création / mise à jour d'un rôle. {@code name} suit la convention MAJUSCULES_SNAKE
 * (cohérente avec les rôles intégrés et les recherches par nom côté code).
 * {@code permissionIds} remplace intégralement le jeu de permissions du rôle.
 */
public record RoleRequest(
        @NotBlank(message = "Le nom du rôle est requis.")
        @Size(max = 50, message = "Le nom du rôle ne peut dépasser 50 caractères.")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                 message = "Le nom doit être en MAJUSCULES_SNAKE (ex. CHEF_PROJET).")
        String name,

        @Size(max = 255, message = "La description ne peut dépasser 255 caractères.")
        String description,

        Set<Long> permissionIds
) {}
