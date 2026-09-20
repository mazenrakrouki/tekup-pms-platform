package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// One row of the "permissions" table: a single right a user may or may not hold. The last link of
// ADR-001's chain (User -> Role -> Permission -> authority string), which every
// @PreAuthorize("hasAuthority('X')") check ultimately reads. Rows are seeded by Flyway only —
// there's deliberately no create/update/delete endpoint, since a permission only does something
// if some @PreAuthorize in the code actually names it; admins can only change which roles hold
// which existing permissions.

/**
 * One permission of the RBAC catalogue.
 *
 * <p>No field points back to the roles that hold it — that link is mapped on the Role side only,
 * to avoid two collections disagreeing in memory. The one screen needing the reverse view builds
 * it in PermissionAdminService.findAllWithRoles by filtering roles instead.
 *
 * <p>Id, timestamps, author columns and the soft-delete flag come from BaseEntity.
 */
@Entity
// uk_permissions_code is the real constraint (from V1); repeating it here keeps the Java mapping
// and the database telling the same story.
@Table(name = "permissions",
       uniqueConstraints = @UniqueConstraint(name = "uk_permissions_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission extends BaseEntity {

    // THE key of the security system: the exact string @PreAuthorize compares against. Case- and
    // space-sensitive, so a seeding typo here would just make the check always refuse silently.
    @Column(nullable = false, unique = true, length = 100)
    private String code;    // ex. "ASSIGN_DEVELOPER"

    // Functional family (e.g. "PROJET", "ADMIN"), display grouping only — has no effect on
    // security. PermissionAdminService sorts by module then code.
    @Column(nullable = false, length = 50)
    private String module;  // ex. "PROJET"

    // Plain-language sentence shown in the admin UI. Nullable: a permission from a future
    // migration that forgets it must still load.
    @Column(length = 255)
    private String description;
}
