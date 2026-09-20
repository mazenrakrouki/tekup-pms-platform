package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

// JPA entity for one row of backlog_items (migration V27; assignee_id added by V28) — one card
// of the agile board. Holds the project link directly (not just via sprint) so a card pulled
// back into the product backlog, or one whose sprint is deleted, still belongs to a project and
// stays inside the ADR-021 scope check instead of becoming unreachable. No security or
// soft-delete filtering logic here: permissions live in BacklogItemService, and every repository
// query must add "AND b.deleted = false" by hand since Hibernate won't do it for you.

/**
 * One card of the agile board: work inside a project, optionally committed to a sprint and
 * optionally assigned to a team member.
 */
@Entity
@Table(name = "backlog_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacklogItem extends BaseEntity {

    // LAZY to avoid an N+1 SELECT per card on the board; project_id is NOT NULL because
    // BacklogItemService.loadItem() relies on it to reject a card whose project isn't the
    // one in the URL.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Null means the item is still in the product backlog, not committed to a sprint. */
    // Nullable on purpose (V27): dragging a card out of a sprint, or deleting a sprint,
    // sets this back to null instead of losing the item. Never trusted as sent by the
    // client — BacklogItemService.resolveSprint reloads and re-checks it belongs to this
    // project. Read queries use LEFT JOIN FETCH so unplanned items still show up.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id")
    private Sprint sprint;

    /**
     * Team member doing the work. Null is legitimate: an item can be committed to a sprint
     * before anyone picks it up, and a product backlog item usually has no owner at all.
     */
    // Not trusted as sent: BacklogItemService.resolveAssignee checks via TeamAssignmentRepository
    // that the user actually belongs to this project's team.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(nullable = false, length = 255)
    private String title;

    // No nullable=false: a card is often written with a title only, description added later.
    @Column(length = 2000)
    private String description;

    // EnumType.STRING (not the JPA default ORDINAL) so inserting a new priority later can't
    // silently reinterpret rows already saved.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private BacklogPriority priority = BacklogPriority.MEDIUM;

    /** Effort in man-days, consistent with the workload module. */
    // BigDecimal, not double, to avoid rounding drift when summing sprint estimates.
    // Null means "not estimated yet", distinct from 0 ("no effort").
    @Column(name = "estimate_days", precision = 6, scale = 2)
    private BigDecimal estimateDays;

    // EnumType.STRING for the same reason as priority above; this is the field drag-and-drop
    // changes via BacklogItemService.move().
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private BacklogItemStatus status = BacklogItemStatus.TODO;
}
