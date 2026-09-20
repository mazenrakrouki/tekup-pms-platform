package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

// One row of the "roles" table plus its attached permissions — the middle link of ADR-001's
// chain (User -> Role -> Permission -> authority string). Authorization in this application is
// dynamic and permission-based: the code never asks "is this an ADMIN?", only "does this hold
// EDIT_PROJECT?" — the answer lives in role_permissions data, so the matrix can change from the
// admin screen without a redeploy.

/**
 * A role: a name plus a set of permissions.
 *
 * <p>Permissions are a many-to-many set, not a delimited text column, so the database can enforce
 * that every stored code really exists (foreign key) rather than silently tolerating a typo.
 */
@Entity
// uk_roles_name is the real constraint (Flyway, V1); repeated here so the Java mapping matches
// the database.
@Table(name = "roles",
       uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role extends BaseEntity {

    // The four names seeded by V1/V2. Labels for humans, not a security rule — no code branches
    // on this value (ADR-001); RoleAdminService just refuses to rename a built-in role.
    @Column(nullable = false, unique = true, length = 50)
    private String name;  // ADMIN | DIRECTEUR | CHEF_PROJET | DEVELOPPEUR

    // Free text shown in the admin list; nullable because roles predating V25 have none.
    @Column(length = 255)
    private String description;

    /**
     * Built-in business role — cannot be renamed or deleted from the admin screen (seed data and
     * demo accounts are attached to these four names, and role_id is NOT NULL, so deleting one
     * would strand its users). The guard protects the ROW only; permissions inside a system role
     * stay fully editable, which is the whole point of dynamic RBAC.
     */
    // Column is "is_system" (SYSTEM is a reserved word in several databases); field stays
    // "system".
    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    // Many roles hold many permissions via role_permissions (role_id, permission_id).
    // EAGER: this set IS the authority list, needed on every authenticated request, and
    // open-in-view: false would throw LazyInitializationException on a LAZY set. The set is small
    // (~20 rows), so eager loading is cheap here despite usually being a bad default.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns        = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    // Set, not List: the (role_id, permission_id) primary key already forbids duplicates.
    // @Builder.Default matters here for real — without it, a builder-created Role would have a
    // null set and getPermissions() (called on every login) would NPE.
    @Builder.Default
    private Set<Permission> permissions = new HashSet<>();

    /**
     * True when this role carries the permission with the given code.
     *
     * <p>Not used by the live HTTP request check — that goes through the authority list
     * UserDetailsServiceImpl/JwtAuthenticationFilter already built. This is the in-memory
     * equivalent for code holding a Role object directly.
     */
    public boolean hasPermission(String code) {
        return permissions.stream().anyMatch(p -> p.getCode().equals(code));
    }
}
