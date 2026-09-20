package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// One row of the "resources" table: a person's cost sheet — daily rate, TCC overhead
// coefficient, and staffing window. Kept separate from User (ADR-022) because User is the
// account (who can sign in) while Resource is the money (what the person costs), and a cost
// sheet must keep existing after the account is deactivated. Rate visibility is scoped in
// ResourceService: MANAGE_RESOURCES sees everything, VIEW_RESOURCES alone (project managers)
// only sees resources on their own projects — enforced by hand since /api/resources/** isn't
// covered by ProjectScopeInterceptor (ADR-021).

/**
 * The cost sheet of one person: daily rate, TCC coefficient, staffing window.
 *
 * <p>Id, timestamps, author columns and the soft-delete flag are inherited from BaseEntity.
 * Amounts are BigDecimal, never double, so money never drifts from binary rounding.
 */
@Entity
@Table(name = "resources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resource extends BaseEntity {

    // The person this cost sheet belongs to. LAZY: KpiService loads many resources at once and
    // often only needs ids, so eager loading the user would multiply queries for nothing; screens
    // that need the name use JOIN FETCH r.user in the repository instead. unique = true because
    // one person has at most one active cost sheet (backed by uk_resources_user_id, V4) — an
    // ABSOLUTE unique constraint, so a soft-deleted row still holds its user_id.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Base cost of one man-day before the TCC coefficient, used when the charged year has no
    // specific row in tcc_annuels. NUMERIC(10,2); DB also enforces CHECK (daily_rate > 0).
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    // Overhead coefficient added on top of the daily rate (social charges, office, tooling),
    // stored as a fraction, not a percentage — the loaded cost of a day is dailyRate * (1 +
    // tccRate). precision/scale caps it at 4 decimals and under 10, so a coefficient mistakenly
    // entered as a whole percentage can't be stored.
    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;

    // First day the person can be staffed. LocalDate, not LocalDateTime — staffing is decided by
    // the day, an hour would only invite time-zone questions with no business meaning here.
    @Column(name = "staffing_start", nullable = false)
    private LocalDate staffingStart;

    // Last day of staffing, or null when still available with no planned end (the normal case).
    // DB enforces CHECK (staffing_end IS NULL OR staffing_end > staffing_start).
    @Column(name = "staffing_end")
    private LocalDate staffingEnd;

    /**
     * Working days per year used only for the indicative yearly estimate below — not an
     * accounting figure. A constant, not a parameters-table row, so the assumption stays visible
     * next to the code that uses it.
     */
    public static final int JOURS_OUVRES_AN = 218;

    /**
     * Indicative loaded yearly cost (base rate * (1 + TCC) * working days, rounded to 2 decimals).
     * Ignores per-year tcc_annuels rates — real margins go through KpiService instead, which
     * applies the rate of the year each day was actually charged to (spec F-AFF-13 6.3 rule 4).
     * Computed, not stored, so it can never go stale relative to the rates it's built from.
     */
    public BigDecimal getAnnualCost() {
        // tccRate.add(ONE) turns the coefficient into a multiplier; without it the result would
        // be a fraction of the yearly cost instead of the full loaded cost.
        return dailyRate.multiply(tccRate.add(BigDecimal.ONE))
                .multiply(BigDecimal.valueOf(JOURS_OUVRES_AN))
                // HALF_UP, applied once at the end — the same rounding KpiService uses, so the
                // two never disagree by a cent.
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
