package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// The account a person signs in with: email, password hash, active flag, and the Role
// deciding what they may do. Kept apart from Resource (ADR-022) so an account can exist
// without a billable rate. Soft-deleted; email uniqueness is a partial index (see below).

/**
 * A user account. Common columns (id, audit, soft-delete) come from BaseEntity.
 * revokeAllTokens/getFullName live here because they are rules about a user, not one screen.
 */
// Required for Hibernate to map this class to a table.
@Entity
// Table name pinned to "users" — the default "user" would collide with PostgreSQL's reserved word.
@Table(name = "users")
// Lombok-generated accessors; Hibernate and MapStruct both need real getters/setters.
@Getter
@Setter
// Required by JPA: Hibernate builds the object via the empty constructor, then fills fields.
@NoArgsConstructor
// Exists only so @Builder has a constructor to call.
@AllArgsConstructor
// Named builder avoids silently swapping fields (e.g. firstName/lastName) like a positional constructor would.
@Builder
public class User extends BaseEntity {

    // length=100 must match VARCHAR(100) in V1__schema_auth.sql (ddl-auto: validate).
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    // No unique=true: enforced by a PARTIAL unique index (V18, WHERE deleted=false) so a
    // soft-deleted account's email can be reused instead of colliding forever.
    @Column(nullable = false, length = 255)
    private String email;

    // BCrypt hash only, never the raw password; no mapper in the project ever copies this to a DTO.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // @Builder.Default is required or Lombok's builder ignores "= true", locking every new account out.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // @Builder.Default needed again; without it new accounts wouldn't force the change-password screen.
    @Column(name = "first_login", nullable = false)
    @Builder.Default
    private boolean firstLogin = true;

    // Revocation counter (ADR-017): bumping it invalidates every token already issued to this user.
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;

    // EAGER because open-in-view is false: role/permissions must load in the same trip for
    // MapStruct and @PreAuthorize to see them. nullable=false: a role-less user must fail loudly,
    // not be silently locked out of every screen.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /** Bumps the token-version counter, invalidating every token already issued to this user. */
    public void revokeAllTokens() {
        this.tokenVersion++;
    }

    /** First + last name; not stored as a column so it can never drift from the two real ones. */
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
