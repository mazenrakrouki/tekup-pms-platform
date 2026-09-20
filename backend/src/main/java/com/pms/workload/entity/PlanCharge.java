package com.pms.workload.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * JPA entity for one row of plan_charges ("plan de charge"): the days a manager plans for a person
 * on a project in a month. Twin of ChargeReelle (the actual-worked side). Days only, never money —
 * cost is derived at read time (days x daily rate x TCC-of-year) so a renegotiated rate never drifts.
 */

/**
 * One planned workload line: this user, this project, this month, this many days. @Builder only
 * covers these four fields, not BaseEntity's. Unique index uk_pc_active (project_id, user_id,
 * period, WHERE deleted=false) blocks duplicates for the same month; chk_pc_days requires 0 &lt; days &lt;= 31.
 * No @Version, so concurrent saves last-write-wins.
 */
@Entity
@Table(name = "plan_charges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanCharge extends BaseEntity {

    // LAZY to avoid N+1 across ~120 lines/project; not-null so update/delete can compare this id
    // against the URL's projectId and block cross-project access.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Who the days are planned for. LAZY; not-null since KpiService prices via this person's rate.
    // create() has already checked team membership (H-8) before this object is built.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Always the 1st of the month (not two int columns), so it sorts naturally and matches the
    // uk_pc_active unique index.
    @Column(nullable = false)
    private LocalDate period;

    // NUMERIC(5,2) via BigDecimal for exact arithmetic (double would drift across a yearly sum).
    // chk_pc_days requires > 0: a "nothing planned" line should simply not exist.
    @Column(name = "planned_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal plannedDays;
}
