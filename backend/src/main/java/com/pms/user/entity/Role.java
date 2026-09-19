package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

// =============================================================================
// FILE: Role.java
//
// WHAT THIS FILE IS
//   One row of the "roles" table, plus the list of permissions attached to it.
//   A Role is a named bundle of permissions — nothing more. It is the middle
//   link of the chain that ADR-001 is built on:
//       User  ->  Role  ->  Permission  ->  authority string
//
// WHERE IT SITS IN THE FLOW
//   Loaded by:  RoleRepository, and by UserRepository.findActiveByEmailWithRole
//     which fetches the role and its permissions together with the user.
//   Read by:    UserDetailsServiceImpl and JwtAuthenticationFilter. Both do
//     user.getRole().getPermissions() and map each Permission.code to a Spring
//     Security authority. That authority list is what
//     @PreAuthorize("hasAuthority('X')") on the SERVICE methods checks.
//   Written by: RoleAdminService (create a role, rename it, change which
//     permissions it carries), reachable only with the MANAGE_ROLES permission.
//   Turned into a DTO by: RoleResponse, for the admin screens.
//
// WHY IT EXISTS
//   Without it a user would have to be linked to each permission one by one,
//   and giving a new right to all project managers would mean editing every
//   project manager's row. Here it is one line added in role_permissions.
//
// ADR-001 — the authorization in this application is DYNAMIC and
//   PERMISSION-BASED. The code never asks "is this person an ADMIN?". It asks
//   "does this person hold EDIT_PROJECT?". The answer lives in data
//   (role_permissions), not in Java, so the matrix can be changed from the
//   admin screen without recompiling or redeploying anything.
// =============================================================================

/**
 * A role: a name plus a set of permissions.
 *
 * <p>Why the permissions are a many-to-many set and not a column holding a list
 * of codes: a join table lets the database guarantee that every stored code
 * really exists in the permissions table (foreign key), and lets the admin
 * screen ask "which roles hold MANAGE_DI?" with a plain join. A text column
 * such as "VIEW_KPI,EDIT_PROJECT" could silently contain a typo that would
 * simply never match any check.
 */
// @Entity maps this class to a table; without it Hibernate ignores the class
// and the JOIN FETCH u.role in UserRepository fails at startup.
// @Table fixes the table name to "roles" and repeats the unique rule on the
// name. The constraint itself is created by Flyway in V1__schema_auth.sql — the
// application runs with ddl-auto: validate and never generates the schema — so
// what is written here is a check and a note for the reader. Why the rule is
// needed: two roles both called CHEF_PROJET would make "the project manager
// role" ambiguous, and the admin screen would show two identical lines carrying
// different permission sets.
@Entity
@Table(name = "roles",
       uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name"))
@Getter
@Setter
// Hibernate needs the empty constructor to rebuild the object after a SELECT;
// the all-args one exists to back the builder.
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role extends BaseEntity {

    // The four names seeded by V1/V2. They are LABELS for humans, not a
    // security rule: no @PreAuthorize and no if-statement in this project
    // branches on this value (ADR-001). The only code that reads it is
    // RoleAdminService, which refuses to rename a built-in role — see the
    // "system" field just below.
    @Column(nullable = false, unique = true, length = 50)
    private String name;  // ADMIN | DIRECTEUR | CHEF_PROJET | DEVELOPPEUR

    // Free text shown in the admin list so a new administrator understands what
    // a role is for. Added by V25 with the role-administration module; it is
    // nullable because roles created before that migration have none.
    @Column(length = 255)
    private String description;

    /**
     * Built-in business role — it cannot be renamed or deleted from the admin
     * screen. V25 sets this to TRUE for ADMIN, DIRECTEUR, CHEF_PROJET and
     * DEVELOPPEUR; RoleAdminService then refuses a rename or a delete on such a
     * row, and always creates new roles with system = false.
     *
     * <p>Why the guard is needed: the seed data and the demo accounts are
     * attached to those four names. Deleting ADMIN would leave its users
     * pointing at a role that no longer exists, so nobody could administer the
     * platform any more — and role_id is NOT NULL, so there would be no way
     * back through the UI.
     *
     * <p>Note that the guard protects the ROW, not the permissions inside it:
     * an administrator can still change what a built-in role is allowed to do.
     * That is deliberate, it is the whole point of the dynamic matrix.
     */
    // The Java field is "system" but the column is "is_system": SYSTEM is a
    // reserved word in several databases, so the migration named the column
    // defensively and the mapping has to say so explicitly.
    @Column(name = "is_system", nullable = false)
    // Without @Builder.Default, Role.builder()...build() would ignore the
    // "= false" initializer. Here the boolean would be false anyway, but the
    // annotation keeps the declared default and the built object in step if the
    // initial value ever changes.
    @Builder.Default
    private boolean system = false;

    // Many roles hold many permissions, stored in the join table
    // role_permissions (role_id, permission_id), created in V1.
    //
    // fetch = EAGER: the permissions are read in the same trip as the role.
    // Why: this set IS the authority list. It is needed on every single
    // authenticated request, and application.yml sets open-in-view: false, so
    // a LAZY set would throw LazyInitializationException as soon as the
    // authority list is built outside the loading transaction. Eager loading is
    // normally a bad default, but here the set is small (about twenty rows) and
    // it is always used.
    //
    // @JoinTable names the link table and says which column points where.
    // joinColumns = the side that owns the mapping (this Role);
    // inverseJoinColumns = the other side (Permission). Getting the two the
    // wrong way round compiles fine and then reads the matrix mirrored, so a
    // role would receive the permissions of whichever permission shares its id.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns        = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    // Set, not List: a role either holds a permission or it does not, and the
    // primary key of role_permissions is (role_id, permission_id) so the same
    // pair cannot be stored twice anyway. A List would let the same permission
    // appear twice in memory and be written twice, which the primary key would
    // then reject with a duplicate-key error.
    //
    // @Builder.Default matters here for real: without it,
    // Role.builder().name("X").build() would leave this field null, and the
    // first call to getPermissions() — which happens on every login — would
    // throw NullPointerException instead of returning an empty set.
    @Builder.Default
    private Set<Permission> permissions = new HashSet<>();

    /**
     * Returns true when this role carries the permission whose code is given,
     * for example hasPermission("MANAGE_DI").
     *
     * <p>How it works: permissions.stream() walks the set, and anyMatch stops
     * at the first element that matches instead of testing all of them.
     *
     * <p>Why the comparison is p.getCode().equals(code) and not
     * code.equals(p.getCode()): both read the same, but a Permission always has
     * a code (the column is NOT NULL), while the argument comes from the
     * caller. So this order fails loudly on a null argument instead of quietly
     * answering false for a call with a bug in it.
     *
     * <p>Where it is NOT used: the live permission check on an HTTP request
     * never goes through this method. @PreAuthorize tests the authority list
     * that UserDetailsServiceImpl and JwtAuthenticationFilter have already
     * built from this same set. This method is the in-memory equivalent for
     * code that holds a Role object in its hands.
     */
    public boolean hasPermission(String code) {
        return permissions.stream().anyMatch(p -> p.getCode().equals(code));
    }
}
