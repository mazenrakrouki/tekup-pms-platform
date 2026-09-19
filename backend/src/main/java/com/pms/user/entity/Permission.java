package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// =============================================================================
// FILE: Permission.java
//
// WHAT THIS FILE IS
//   One row of the "permissions" table: one single right that a user may or may
//   not hold, such as MANAGE_DI or ASSIGN_DEVELOPER. It is the last link of the
//   chain the whole authorization of this project is built on (ADR-001):
//       User  ->  Role  ->  Permission  ->  authority string
//
// WHERE IT SITS IN THE FLOW
//   Loaded by:  PermissionRepository (findByCode, findAll), and above all by
//     Role.permissions, which reads the join table role_permissions. That set
//     is EAGER, so every login brings back the permissions of the user's role
//     in the same trip.
//   Turned into authorities by:  UserDetailsServiceImpl and
//     JwtAuthenticationFilter. Both walk user.getRole().getPermissions() and
//     turn each "code" into a Spring Security authority. Those authorities are
//     exactly what @PreAuthorize("hasAuthority('X')") on the SERVICE methods
//     tests - never a role name (ADR-001).
//   Shown by:  PermissionAdminService.findAll and findAllWithRoles, both gated
//     by @PreAuthorize("hasAuthority('MANAGE_ROLES')"), which copy these rows
//     into PermissionResponse / PermissionWithRolesResponse for the admin
//     screens, and RoleAdminService, which attaches permissions to a role.
//
// WHY IT EXISTS
//   It is the vocabulary of the security system. Delete it and Role has nothing
//   to hold, the authority list of every user becomes empty, and every
//   @PreAuthorize in the application refuses every call.
//
// WHO CREATES THE ROWS - an important point for the jury
//   The rows are seeded by Flyway (V2__seed_rbac.sql, then V12, V13, V25) and
//   there is deliberately NO create/update/delete endpoint for permissions. The
//   reason: a permission only does something if some @PreAuthorize in the Java
//   code names it. Letting an administrator invent the code "SUPER_ACCESS" would
//   create a row that looks meaningful on screen and changes nothing at all.
//   What the administrator CAN change - and this is the whole point of the
//   dynamic matrix - is which roles hold which of these existing permissions.
//   History kept for traceability: V20 deleted MANAGE_ROLES and VIEW_AUDIT_LOG
//   because nothing enforced them at the time; V25 put MANAGE_ROLES back once
//   the role-administration endpoints existed to be protected by it.
// =============================================================================

/**
 * One permission of the RBAC catalogue.
 *
 * <p>Why this class has no field pointing back to the roles that hold it: the
 * link is mapped on one side only, in Role.permissions, which owns the join
 * table role_permissions. A second, mirrored collection here would be a
 * permanent risk of the two sides disagreeing in memory, and every load of a
 * permission would drag in its roles - and their users. The one screen that
 * needs the reverse view, "which roles hold this permission?", builds it in
 * PermissionAdminService.findAllWithRoles by reading the roles and filtering
 * them, which costs one query instead of a mapping that is always loaded.
 *
 * <p>The id, the created and updated dates, the author columns and the
 * soft-delete flag all come from BaseEntity, which is why only three fields
 * appear below.
 */
// @Entity maps the class to a table; without it Hibernate ignores it and the
// JOIN on role_permissions in Role fails at startup.
@Entity
// @Table pins the table name and repeats the unique rule on the code. Flyway
// owns the schema (ADR-019, ddl-auto: validate), so this @UniqueConstraint does
// not create anything: the real constraint uk_permissions_code comes from
// V1__schema_auth.sql. Writing it here keeps the Java mapping and the database
// telling the same story, and gives the reader the constraint name that will
// appear in the error message if a duplicate is ever inserted.
// Why the rule matters: two rows both called MANAGE_DI would mean the admin
// screen shows the right twice, and ticking one of the two would give a role a
// permission whose id does not match the one the other screens display.
@Table(name = "permissions",
       uniqueConstraints = @UniqueConstraint(name = "uk_permissions_code", columnNames = "code"))
@Getter
@Setter
// JPA needs the empty constructor to rebuild the object after a SELECT; the
// all-args one only exists so that @Builder has something to call.
@NoArgsConstructor
@AllArgsConstructor
// @Builder: Permission.builder().code("VIEW_KPI").module("KPI").build() names
// every value. The all-args constructor takes three strings in a row, so
// swapping the code and the module would compile without any warning and put
// "KPI" in the column the security checks compare against.
@Builder
public class Permission extends BaseEntity {

    // THE key of the whole security system: the exact string compared by
    // @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')") on a service method.
    // The comparison is a plain string equality, so it is case-sensitive and
    // space-sensitive: a row seeded as "assign_developer" or with a trailing
    // space would never match, the check would simply always refuse, and
    // nothing would say why.
    // unique = true says one code exists only once; the constraint itself is
    // uk_permissions_code (see @Table above). length = 100 must match the
    // VARCHAR(100) of V1, or the boot-time validation stops the application.
    @Column(nullable = false, unique = true, length = 100)
    private String code;    // ex. "ASSIGN_DEVELOPER"

    // Functional family the permission belongs to, for example "PROJET",
    // "ADMIN" or "KPI". It has no effect on security whatsoever; it is used to
    // group the rows on the admin screen - PermissionAdminService sorts by
    // module first, then by code. Without it the page would be one flat list of
    // about twenty codes and an administrator could not find anything.
    @Column(nullable = false, length = 50)
    private String module;  // ex. "PROJET"

    // Plain-language sentence shown next to the code in the admin UI, so that
    // the person granting a right understands what it opens. Added by V25 with
    // the role-administration module, which also filled it for every existing
    // code.
    // It is the only nullable column here, and it has to stay nullable: a
    // permission inserted by a future migration that forgets the description
    // must still load. The Angular side declares the field optional for the
    // same reason.
    @Column(length = 255)
    private String description;
}
