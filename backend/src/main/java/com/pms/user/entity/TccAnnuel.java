package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

// One row of the "tcc_annuels" table: the cost rates of one resource for one calendar year. TCC
// is the coefficient added on top of a daily rate for social charges, office, tooling. Rates are
// renegotiated yearly (spec F-AFF-13 6.3 rule 4), so without this table a multi-year project
// would price every day at whichever single rate sits on the resource sheet. A resource with no
// row for a given year simply falls back to the base rates on Resource itself — this table holds
// only the exceptions. uk_tcc_annuel_resource_annee (V21, partial on deleted = FALSE) keeps the
// pair unique among live rows, which is also why ResourceService.replaceTccAnnuels updates an
// existing year's row in place rather than delete-then-insert (Hibernate flushes inserts before
// updates, so a fresh insert could collide with the not-yet-deleted old row).

/**
 * The daily rate and TCC coefficient of one resource for one year (spec F-AFF-13 6.3 rule 4).
 *
 * <p>A separate table rather than more columns on "resources", since the number of years isn't
 * known in advance and a per-year column would need a migration every year.
 *
 * <p>Id, timestamps, author columns and the soft-delete flag come from BaseEntity.
 */
@Entity
@Table(name = "tcc_annuels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TccAnnuel extends BaseEntity {

    // The resource these rates belong to. LAZY, because KpiService loads many yearly rates at
    // once and eager loading would drag each row's resource (and its user) behind it — the
    // classic N+1. Safe because the one caller that needs it (KpiService, via
    // TccAnnuelRepository.findActiveByUserIdIn) already JOIN FETCHes resource and user.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    // The calendar year these rates apply to. Integer, not int, so a forgotten value arrives as
    // null (refused loudly) instead of silently becoming 0 and matching nothing. No month: the
    // rule works at year granularity (KpiService keeps only period.getYear()).
    @Column(nullable = false)
    private Integer annee;

    // Man-day cost for that year, before the TCC coefficient. BigDecimal, not double, to keep
    // exact decimal values for money.
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    // Overhead coefficient for that year, stored as a fraction, not a percentage — the day's cost
    // is dailyRate * (1 + tccRate). precision/scale caps it under 10 with 4 decimals, ruling out
    // a value mistakenly entered as a whole percentage.
    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;
}
