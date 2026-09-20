package com.pms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

// Body for creating/updating a role: name, description, and the permission ids it must end up
// with. The only door through which a role is written, so the input rules live here.

/**
 * Creating or updating a role. {@code name} follows the UPPERCASE_SNAKE convention (same shape as
 * the built-in roles, and what the seeding code looks up by). {@code permissionIds} replaces the
 * role's permission set completely.
 */
public record RoleRequest(
        // @NotBlank rejects null/""/spaces-only, so the admin list can't end up with an
        // unidentifiable blank row. Message stays in French like the rest of the UI.
        @NotBlank(message = "Le nom du rôle est requis.")
        // Caps at the column width (roles.name VARCHAR(50)) so an oversized name fails as a
        // clear 400 instead of a vague DB 409.
        @Size(max = 50, message = "Le nom du rôle ne peut dépasser 50 caractères.")
        // Uppercase letters, digits and "_" only, starting with a letter (e.g. CHEF_PROJET).
        // Role names are matched as plain strings by the start-up seeders and SQL migrations —
        // this is about seeding/display only, no authorization decision tests a role name —
        // so without this rule "Admin" and "ADMIN" could coexist as two rows a seeder can't tell
        // apart.
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                 message = "Le nom doit être en MAJUSCULES_SNAKE (ex. CHEF_PROJET).")
        String name,

        // Optional free text; the service turns blank into null (blankToNull). 255 matches
        // roles.description VARCHAR(255).
        @Size(max = 255, message = "La description ne peut dépasser 255 caractères.")
        String description,

        // The ids of the permissions this role must end up with. This REPLACES the whole set
        // (RoleAdminService.update() overwrites, never merges), so an empty/null list strips
        // every permission — safe because the Angular editor always sends the full ticked list.
        // Set, not List: duplicates are meaningless and Role.permissions is itself a Set.
        Set<Long> permissionIds
) {}
