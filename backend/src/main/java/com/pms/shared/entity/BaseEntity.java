package com.pms.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// Parent of every entity (Project, User, Sprint, BacklogItem, ...): holds the columns every
// table repeats - id, created_at/by, updated_at/by, soft-delete "deleted" flag. Relies on
// JpaConfig's @EnableJpaAuditing and SpringSecurityAuditorAware to fill the audit columns.
//
// Two rules for the whole file: no security/authorization code belongs here (that's
// @PreAuthorize on services plus ProjectScopeInterceptor, ADR-021) - an entity doesn't know
// who's calling. And soft delete is not automatic: no @Where/@SQLRestriction filter is used,
// so every repository query must add "AND x.deleted = false" by hand.

/**
 * Shared parent of every entity: primary key, audit columns, soft-delete flag.
 * {@code @MappedSuperclass} copies these fields into each child table rather than creating a
 * shared base_entity table or forcing a join. {@code abstract} because there's no table to
 * save a bare BaseEntity into.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public abstract class BaseEntity {

    // IDENTITY: the database (BIGSERIAL) assigns the id, not Java - picking it in Java could let
    // two concurrent inserts collide. Long, not long, so an unsaved object can be "no id" (null).
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Stamped once at first save. updatable=false keeps it out of every UPDATE, so it can never
    // be silently overwritten by a later save.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Restamped on every save (no updatable=false, unlike createdAt above).
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Filled by SpringSecurityAuditorAware: the caller's username, or "system" when there's no
    // logged-in user (seeders, start-up). AgileDemoSeeder uses that "system" marker to tell its
    // own rows apart from ones a real person built by hand. No nullable=false: added later by
    // V19, so pre-existing rows hold NULL.
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    // Soft delete: rows are never SQL-deleted (missions/DI lines are accounting history other
    // rows point at); services only set deleted=true, and every query must filter it by hand
    // (see file header). NOT NULL because "deleted = false" in SQL is never true for a NULL row,
    // which would otherwise vanish from lists while still sitting in the table.
    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * Two objects are equal when they carry the same non-null id (or are the same instance).
     * Written by hand because Object's equals() compares memory addresses, which would let
     * Role.permissions (a HashSet) hold the same permission twice when loaded via two paths.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        // Pattern-matching instanceof also covers the null case, so no separate null check.
        if (!(o instanceof BaseEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    /**
     * Constant per class rather than {@code id.hashCode()}: an entity's id is null until first
     * save, and if the hash code changed after being added to a HashSet (once Hibernate
     * assigns the id), the object would end up in the wrong bucket and {@code contains()} would
     * wrongly return false. The cost (everything of one class shares a bucket) is fine since
     * these sets are small (e.g. Role.permissions).
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
