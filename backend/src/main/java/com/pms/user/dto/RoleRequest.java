package com.pms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

// ============================================================================
// FILE: RoleRequest
//
// WHAT THIS FILE IS
//   The shape of the JSON body the admin sends to create or update a role.
//   It is the only door through which a role is written, so it is also where
//   the input rules live.
//
// WHERE IT SITS IN THE FLOW
//   Angular role-list.component (it posts { name, description, permissionIds })
//     -> RoleController, POST /api/admin/roles and PUT /api/admin/roles/{id},
//        where @Valid @RequestBody triggers the checks written below
//     -> RoleAdminService.create() / update(), both guarded by
//        @PreAuthorize("hasAuthority('MANAGE_ROLES')") - the permission check
//        is on the service, not on the controller
//     -> Role entity, and the role_permissions join table
//     -> the answer comes back as a RoleResponse.
//
// WHY IT EXISTS
//   Delete it and the controller would have to accept the Role entity itself.
//   A client could then send "system": false on a built-in role, or push an
//   id, or set the audit columns - it would be writing straight into the
//   security model. This record accepts three fields and nothing else.
//
// WHY THE RULES ARE HERE AND NOT ONLY IN THE SERVICE
//   Bean Validation runs before any service code, and
//   GlobalExceptionHandler turns the resulting MethodArgumentNotValidException
//   into a 400 response that names each bad field. Checking the same things by
//   hand inside the service would mean writing that plumbing again in every
//   endpoint.
// ============================================================================

/**
 * Creating or updating a role. {@code name} follows the UPPERCASE_SNAKE
 * convention (the same shape as the built-in roles, and as the lookups by name
 * done by the seeding code). {@code permissionIds} replaces the role's
 * permission set completely.
 */
public record RoleRequest(
        // @NotBlank rejects null, "" and a value made only of spaces.
        // Why: the role name is the label shown everywhere and the value the
        // seeders look up. Without it, "   " would be accepted, stored, and the
        // admin list would show an empty row nobody can identify.
        // The message is what the user reads, so it stays in French like the
        // rest of the interface.
        @NotBlank(message = "Le nom du rôle est requis.")
        // @Size caps the length at the column width (roles.name is VARCHAR(50)).
        // Without it a 200-character name would pass Java and be refused by
        // PostgreSQL, which surfaces as a 409 with a vague message instead of a
        // clear "this field is too long".
        @Size(max = 50, message = "Le nom du rôle ne peut dépasser 50 caractères.")
        // Regular expression, read left to right:
        //   ^        start of the text (nothing may come before)
        //   [A-Z]    the first character must be an uppercase letter A to Z
        //   [A-Z0-9_]*  then any number of uppercase letters, digits or "_"
        //   $        end of the text (nothing may come after)
        // So "CHEF_PROJET" and "AUDIT2" pass; "chef projet", "Chef_Projet",
        // " ADMIN" and "CHEF-PROJET" are refused.
        // Why: role names are compared as plain strings by the start-up seeders
        // (DataInitializer does roleRepository.findByName("ADMIN")) and by the
        // SQL migrations. Note this is about seeding and display only - no
        // authorization decision anywhere tests a role name; access is always
        // decided on permission codes. Without this rule an admin could create
        // "Admin" next to "ADMIN": two rows that look the same to a human but
        // never to an exact string match, and the seeder would keep recreating
        // its own.
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                 message = "Le nom doit être en MAJUSCULES_SNAKE (ex. CHEF_PROJET).")
        String name,

        // Optional free text. No @NotBlank, so it may be absent; the service
        // turns an empty or blank string into null (blankToNull) so the column
        // holds either real text or nothing, never "  ".
        // 255 matches roles.description VARCHAR(255), same reason as above.
        @Size(max = 255, message = "La description ne peut dépasser 255 caractères.")
        String description,

        // The ids of the permissions this role must end up with.
        //
        // This is a REPLACEMENT, not an addition: RoleAdminService.update()
        // calls role.setPermissions(resolvePermissions(...)), which overwrites
        // the whole set. Sending null or an empty list therefore strips every
        // permission from the role. The Angular editor always sends the full
        // list of ticked boxes, which is what makes this safe.
        //
        // Set<Long> and not List<Long>: a role either has a permission or it
        // does not, so duplicates are meaningless, and a Set makes the same id
        // sent twice harmless. It mirrors Role.permissions, which is a
        // Set<Permission> because role_permissions is a many-to-many join
        // table with (role_id, permission_id) as its key.
        //
        // Ids and not codes: ids are stable, and the service resolves them all
        // at once with findAllById, then refuses the whole request if one is
        // missing - so a typo can never half-apply a permission change.
        Set<Long> permissionIds
) {}
