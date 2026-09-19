package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

// =============================================================================
// FILE: TccAnnuel.java
//
// WHAT THIS FILE IS
//   One row of the "tcc_annuels" table: the cost rates of ONE resource for ONE
//   calendar year. TCC is the coefficient the company adds on top of a daily
//   rate to get what a working day really costs it: social charges, office,
//   tooling. Example row: resource number 7, year 2024, daily_rate 450.00,
//   tcc_rate 0.4200, which means 42 percent.
//
// WHERE IT SITS IN THE FLOW
//   Written by:  ResourceService.replaceTccAnnuels, guarded by
//     @PreAuthorize("hasAuthority('MANAGE_RESOURCES')"), reached through
//     PUT /api/resources/{id}/tcc.
//   Read by:
//     - ResourceService.findTccAnnuels (VIEW_RESOURCES) for the rates table of
//       the resource screen;
//     - KpiService, which loads every yearly rate of the project members in one
//       query (TccAnnuelRepository.findActiveByUserIdIn) and arranges them as
//       userId -> (year -> TccAnnuel) before costing each charge line.
//   Created by:  Flyway migration V21, spec F-AFF-13 section 6.3 rule 4.
//
// WHY IT EXISTS - the rule it implements
//   The cost of one man-day depends on the YEAR the day was charged to: the
//   rates are renegotiated every year, so TCC 2024 is not TCC 2025. Without
//   this table a project spanning two years would cost all of its days at the
//   single rate stored on the resource sheet, so days worked in 2024 would be
//   priced at the 2025 rate. On a long project that moves the margin by several
//   points, and the figure could never be reconciled with the accounting.
//
// THE FALLBACK RULE - say it exactly right in front of the jury
//   The yearly row is not mandatory. When a resource has NO row for the year a
//   day belongs to, KpiService falls back to the base rates carried by the
//   Resource itself (resources.daily_rate and resources.tcc_rate). So this
//   table holds the exceptions, not the whole history: a resource whose rate
//   never moved needs no row here at all.
//
// ONE ROW PER RESOURCE AND YEAR
//   V21 creates a PARTIAL unique index,
//     uk_tcc_annuel_resource_annee ON tcc_annuels(resource_id, annee)
//     WHERE deleted = FALSE,
//   so the pair is unique among the rows that are still alive, while
//   soft-deleted rows keep their place in the table. Without the
//   "WHERE deleted = FALSE" part, a year that had been removed once could never
//   be entered again.
//   Nothing in this Java file declares that index: Flyway owns the schema
//   (ADR-019; the application runs with ddl-auto: validate and never generates
//   the schema). That index is also the reason ResourceService.replaceTccAnnuels
//   updates the existing row of a year in place instead of deleting it and
//   inserting a new one. Hibernate sends its INSERT statements before its
//   UPDATE statements when it flushes, so the new row would arrive before the
//   old one was marked deleted, and the index would reject it with a duplicate
//   key error.
// =============================================================================

/**
 * The daily rate and the TCC coefficient of one resource for one year
 * (spec F-AFF-13 section 6.3 rule 4: TCC 2024 is not TCC 2025).
 *
 * <p>When there is no entry for the year a day is charged to, the base rates of
 * the resource apply.
 *
 * <p>Why a separate table rather than more columns on "resources": the number
 * of years is not known in advance. Columns such as daily_rate_2024 and
 * daily_rate_2025 would need a migration every January, and asking "what did
 * this person cost in year N" would mean building a column name out of a
 * number.
 *
 * <p>The id, the dates, the author columns and the soft-delete flag come from
 * BaseEntity, so only four fields are declared here.
 */
// @Entity maps the class to a database table. Without it, the query
// "SELECT t FROM TccAnnuel t ..." in TccAnnuelRepository fails at startup with
// "Unknown entity: com.pms.user.entity.TccAnnuel".
@Entity
// @Table pins the table name to "tcc_annuels". The name derived from the class
// would be "TccAnnuel", which is not the table V21 created, so the boot-time
// validation would stop the application.
@Table(name = "tcc_annuels")
@Getter
@Setter
// JPA needs the empty constructor to rebuild the object after a SELECT; the
// all-args one exists so that @Builder has a constructor to call.
@NoArgsConstructor
@AllArgsConstructor
// @Builder is used by ResourceService and by the data seeders:
// TccAnnuel.builder().resource(r).annee(2024).build(). Why it matters here: the
// all-args constructor takes two BigDecimal values in a row, dailyRate then
// tccRate, and swapping them would compile with no warning and price a day at
// 0.42 multiplied by 451 instead of 450 multiplied by 1.42.
@Builder
public class TccAnnuel extends BaseEntity {

    // The resource these rates belong to. Many yearly rows point at one
    // resource, hence @ManyToOne, stored as the column resource_id with the
    // foreign key fk_tcc_annuel_resource created by V21.
    //
    // fetch = LAZY: the resource row is NOT read together with the rate;
    // Hibernate goes and gets it only if somebody actually calls getResource().
    // Why LAZY and not EAGER: KpiService loads every yearly rate of a project
    // team at once. With EAGER, each of those rows would drag its resource, and
    // then that resource's user, behind it - the classic N+1 problem, dozens of
    // extra queries on a single screen.
    // What makes LAZY safe here: the one place that really calls
    // t.getResource().getUser().getId() is KpiService, and the query it uses
    // (TccAnnuelRepository.findActiveByUserIdIn) says JOIN FETCH t.resource r
    // JOIN FETCH r.user, so both are already in memory. Without that JOIN FETCH
    // the call would throw LazyInitializationException, because application.yml
    // sets open-in-view: false and the database session is closed by then.
    //
    // nullable = false: a yearly rate that belongs to nobody could never be
    // applied to anything, and would sit in the table for ever.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    // The calendar year the two rates apply to, for example 2024.
    //
    // Why Integer (the object) and not int (the plain number): a field that was
    // never filled stays null, so the NOT NULL column refuses the insert
    // loudly. With a plain int, a forgotten year would silently become 0, and 0
    // matches no charge line at all, so the rate would simply never be used and
    // nobody would ever see an error. V21 adds CHECK (annee BETWEEN 2000 AND
    // 2100), which is the second safety net under this one.
    //
    // Note there is no month: the rule of F-AFF-13 works at year granularity.
    // KpiService takes the period of the charge line and keeps only
    // period.getYear().
    @Column(nullable = false)
    private Integer annee;

    // What one man-day of this resource costs during that year, before the TCC
    // coefficient is applied. NUMERIC(10,2) in the database.
    //
    // BigDecimal and not double: a double stores 0.1 as an approximation, so
    // adding up a few hundred day costs drifts by a few cents and the total of
    // a screen stops matching the sum of the lines the user can see. BigDecimal
    // keeps exact decimal values, which is what money needs.
    // precision = 10, scale = 2 means "up to 10 digits in all, 2 of them after
    // the point", so at most 99999999.99. It has to match the NUMERIC(10,2) of
    // V21, or the boot-time validation stops the application with a clear
    // message instead of silently rounding amounts later.
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    // The overhead coefficient for that year, stored as a fraction and not as a
    // percentage: 0.4200 means 42 percent. The cost of a day is
    // dailyRate multiplied by (1 + tccRate), so 0.42 gives the multiplier 1.42.
    // Storing 42 here instead of 0.42 would multiply every cost by 43.
    //
    // precision = 5, scale = 4 means at most 5 digits with 4 of them after the
    // point, so values from 0.0000 to 9.9999. That leaves room for a
    // coefficient of up to 999 percent, far more than any real case, while
    // making the value 42 impossible to store by mistake. The CHECK named
    // chk_tcc_rate on the resources table expresses the same intent for the
    // base rate.
    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;
}
