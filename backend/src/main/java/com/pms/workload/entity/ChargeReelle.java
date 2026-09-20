package com.pms.workload.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * JPA entity for one row of charges_reelles: a person declares days really worked, a manager then
 * approves it. Twin of PlanCharge (the forecast side). Days only, never money — cost is derived at
 * read time (days x daily rate x TCC-of-year), so a renegotiated rate never disagrees with a stored figure.
 */

/**
 * One declaration of real work: this user, this project, this month, this many days — plus when
 * submitted, when approved and by whom. @Builder only covers these seven fields, not BaseEntity's.
 * Unique index uk_cr_active (project_id, user_id, period, WHERE deleted=false) blocks duplicates
 * for the same month; chk_cr_days allows 0..31. No @Version, so concurrent saves last-write-wins.
 */
@Entity
@Table(name = "charges_reelles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeReelle extends BaseEntity {

    // LAZY to avoid N+1 across ~120 lines/project; not-null so update/validate/delete can compare
    // this id against the URL's projectId and block cross-project access.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // The declaration's author (not the approver — see validatedBy). LAZY; not-null since KpiService
    // prices via this person's rate, and BR-033 lets non-validators only touch their own lines.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Always the 1st of the month (not two int columns), so it sorts naturally and matches the
    // uk_cr_active unique index. Immutable after submit — update() rejects a mismatched period.
    @Column(nullable = false)
    private LocalDate period;

    // NUMERIC(5,2) via BigDecimal for exact arithmetic (double would drift across a yearly sum).
    // chk_cr_days allows >=0: a real month with zero days worked is a legitimate declaration.
    @Column(name = "actual_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal actualDays;

    // Timestamp (not a boolean+date pair) so "sent" and "when" can never disagree. Nullable: rows
    // written outside the normal path (seeders, imports) may not set it.
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    // Single source of truth for approval state (see isValidated()); no separate status column to
    // drift out of sync. KpiService only counts rows where this is set.
    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    // The approver, distinct from user (the author). LAZY + queries use LEFT JOIN FETCH since most
    // lines are still unapproved and a plain join would hide them from the list.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by")
    private User validatedBy;

    /** True once validate() has set validatedAt. update()/delete() use this to refuse touching an already-approved line. */
    public boolean isValidated() {
        return validatedAt != null;
    }
}
