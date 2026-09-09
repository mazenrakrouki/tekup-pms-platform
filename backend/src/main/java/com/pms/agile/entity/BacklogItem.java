package com.pms.agile.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "backlog_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacklogItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Null means the item is still in the product backlog, not committed to a sprint. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id")
    private Sprint sprint;

    /**
     * Team member doing the work. Null is legitimate: an item can be committed to
     * a sprint before anyone picks it up, and a product backlog item usually has
     * no owner at all.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private BacklogPriority priority = BacklogPriority.MEDIUM;

    /** Effort in man-days, consistent with the workload module. */
    @Column(name = "estimate_days", precision = 6, scale = 2)
    private BigDecimal estimateDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private BacklogItemStatus status = BacklogItemStatus.TODO;
}
