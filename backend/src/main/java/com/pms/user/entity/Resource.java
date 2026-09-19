package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// =============================================================================
// FILE: Resource.java
//
// WHAT THIS FILE IS
//   One row of the "resources" table: the COST sheet of a person. It says what
//   one of their working days costs the company (daily rate plus the TCC
//   overhead coefficient) and between which dates they are staffable.
//
// WHERE IT SITS IN THE FLOW
//   Written by:  ResourceService (create, update, delete), every method guarded
//     by @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") on the SERVICE, not
//     on the controller. V24 gives that permission to ADMIN and DIRECTEUR.
//   Read by:
//     - ResourceService.findAll / findById (VIEW_RESOURCES), which feed
//       GET /api/resources through ResourceController;
//     - ResourceMapper, which turns the row into ResourceResponse and calls
//       getAnnualCost() below to fill the "annualCost" field;
//     - KpiService, which uses dailyRate and tccRate as the FALLBACK cost when
//       the charged year has no specific rate in tcc_annuels.
//   Points at:  User, one to one. Owns nothing else; the yearly rates live in
//     TccAnnuel, which points back at this row.
//
// WHY IT EXISTS - and why it is not merged into User (ADR-022)
//   User is the ACCOUNT: who can sign in and what they are allowed to do.
//   Resource is the MONEY: what the person costs. They are kept apart on
//   purpose. An assistant or an intern needs an account but has no billable
//   rate, so half of the columns would always be empty on a merged table. And
//   a cost sheet must keep existing for the cost of past months even after the
//   account has been switched off.
//   Delete this file and every cost, every margin and every KPI of the
//   application loses its price per day: KpiService would have nothing to
//   multiply the man-days by.
//
// WHO IS ALLOWED TO SEE A COST - an important point for the jury
//   Rates are sensitive data. Two levels exist, and the check is done in
//   ResourceService, not here:
//     - MANAGE_RESOURCES (ADMIN, DIRECTEUR) sees and edits the whole catalogue;
//     - VIEW_RESOURCES alone (CHEF_PROJET) sees only the resources of the
//       people on their own projects, through
//       ResourceRepository.findVisibleToProjectManager.
//   Note why the check is written by hand there: ProjectScopeInterceptor
//   (ADR-021) guards the URLs /api/projects/{id}/**, and /api/resources/** is
//   not one of them, so the data scope of this screen is applied by the service
//   itself. The permission alone would not be enough.
//
// SOFT DELETE
//   The "deleted" flag comes from BaseEntity. ResourceService.delete only sets
//   it to true; the row stays in the table so that old cost calculations keep
//   their rate. Every query that must ignore removed rows says
//   "r.deleted = false" explicitly.
// =============================================================================

/**
 * The cost sheet of one person: daily rate, TCC coefficient, staffing window.
 *
 * <p>The id, the created and updated dates, the author columns and the
 * soft-delete flag are inherited from BaseEntity, which carries the JPA
 * auditing listener. That is why only five fields are declared below.
 *
 * <p>Why the amounts are BigDecimal and never double: a double stores 0.1 as an
 * approximation, so adding a few hundred day costs drifts by a few cents and
 * the total shown on a screen stops matching the sum of the lines the user can
 * see. BigDecimal keeps exact decimal values, which is what money needs.
 */
// @Entity maps the class to a database table. Without it Hibernate ignores the
// class and "SELECT r FROM Resource r" in ResourceRepository fails at startup
// with "Unknown entity: com.pms.user.entity.Resource".
@Entity
// @Table pins the table name to "resources". The name derived from the class
// would be "resource" in the singular, which is not the table V3 created, and
// the application starts with ddl-auto: validate (Flyway owns the schema,
// ADR-019), so the boot would stop.
@Table(name = "resources")
@Getter
@Setter
// JPA needs the empty constructor to rebuild the object after a SELECT; the
// all-args one only exists so that @Builder has a constructor to call.
@NoArgsConstructor
@AllArgsConstructor
// @Builder gives Resource.builder().user(u).dailyRate(...).tccRate(...).build(),
// which ResourceService and the data seeders use. Why it matters: the all-args
// constructor takes dailyRate and tccRate one after the other, both BigDecimal,
// and swapping them would compile with no warning and price a day at 0.42
// instead of 450.
@Builder
public class Resource extends BaseEntity {

    // The person this cost sheet belongs to.
    //
    // @OneToOne: one user has at most one cost sheet, and one cost sheet
    // belongs to exactly one user.
    // fetch = LAZY: the user row is not read together with the resource;
    // Hibernate fetches it only when getUser() is actually called. Why LAZY:
    // KpiService loads the resources of a whole team to price charge lines and
    // only needs the ids, so eagerly loading every user would be a pile of
    // queries for nothing. The screens that DO display the name use queries
    // that say JOIN FETCH r.user (see ResourceRepository), which brings the
    // user back in the same trip. ResourceMapper then calls user.getFullName()
    // while still inside the @Transactional service method, so even the paths
    // that did not JOIN FETCH work - they simply cost one extra query. Outside
    // a transaction it would throw LazyInitializationException, because
    // application.yml sets open-in-view: false.
    //
    // @JoinColumn: the foreign key column is user_id, on this table.
    // nullable = false: a cost sheet with no owner could never be matched to
    // the days somebody charged, so it would silently price nothing.
    // unique = true: one person cannot have two active cost sheets, otherwise
    // "the daily rate of Ahmed" would have two different answers and the margin
    // of a project would depend on which row was read first. The real rule is
    // the constraint uk_resources_user_id added by V4.
    //
    // Careful, a trap worth knowing before the jury asks: uk_resources_user_id
    // is an ABSOLUTE unique constraint, not a partial one like uk_users_email
    // (V18). Since deletion here is a soft delete, the row of a removed cost
    // sheet keeps holding its user_id.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // The base cost of one man-day, in the currency of the platform, before the
    // TCC coefficient is applied. It is the rate used when the charged year has
    // no specific row in tcc_annuels.
    // precision = 10, scale = 2 means "up to 10 digits in all, 2 of them after
    // the point", so at most 99999999.99; it must match the NUMERIC(10,2) of
    // V3 or the boot-time validation stops the application. V3 also adds
    // CHECK (daily_rate > 0), so a rate of zero is refused by the database even
    // if a future caller forgets to validate it.
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    // TCC = the overhead coefficient the company adds on top of the daily rate
    // (social charges, office, tooling). It is stored as a fraction, NOT as a
    // percentage: 0.4200 means 42 percent, and the loaded cost of a day is
    // dailyRate multiplied by (1 + tccRate).
    // precision = 5, scale = 4 gives values from 0.0000 to 9.9999: four
    // decimals because a negotiated coefficient like 0.4275 must survive
    // exactly, and only five digits in total so that somebody typing 42
    // instead of 0.42 cannot be stored - it would multiply every cost by 43.
    // V3 also adds CHECK (tcc_rate >= 0 AND tcc_rate < 10) as a second net.
    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;

    // First day the person can be staffed on a project.
    // LocalDate and not LocalDateTime: staffing is decided by the day, and an
    // hour would only invite time-zone questions that have no business meaning
    // here.
    @Column(name = "staffing_start", nullable = false)
    private LocalDate staffingStart;

    // Last day of the staffing window, or null when the person is still
    // available with no planned end - which is the normal case for a permanent
    // employee. That is exactly why this column has no nullable = false: a
    // conventional far-away date such as 31/12/2099 would be read as a real end
    // date by anyone writing a report later.
    // V3 adds CHECK (staffing_end IS NULL OR staffing_end > staffing_start), so
    // a window that ends before it starts is refused by the database.
    @Column(name = "staffing_end")
    private LocalDate staffingEnd;

    /**
     * Number of working days kept for the indicative yearly estimate
     * (365 days minus week-ends, paid leave and public holidays).
     *
     * <p>Why a constant and not a row in the parameters table: this value is
     * only used by the estimate just below, which is a piece of information
     * shown on the resources screen, never an accounting figure. A constant
     * makes the assumption visible in the code that uses it; if the company
     * ever wants to tune it, that is the day to move it into a setting.
     *
     * <p>static: the value belongs to the class, not to each resource - all
     * resources share the same working calendar. final: nothing can change it
     * at runtime, so two screens can never compute with two different values.
     */
    public static final int JOURS_OUVRES_AN = 218;

    /**
     * Indicative loaded yearly cost: base rate multiplied by (1 + TCC)
     * multiplied by the number of working days. Returns an amount rounded to
     * two decimals.
     *
     * <p>It does NOT take the per-year rates (tcc_annuels) into account. The
     * margin calculations do not go through this method: they go through
     * KpiService, which applies the rate of the year the day was charged to
     * (spec F-AFF-13 section 6.3 rule 4). So this number is an order of
     * magnitude for the resources screen, not a figure to defend in front of
     * the accounting department.
     *
     * <p>Why it is computed and not stored in a column: a stored yearly cost
     * would have to be rewritten every time the daily rate or the TCC changes,
     * and the two would eventually disagree. The same rule is applied all over
     * the Devis Interne (the internal quote): computed amounts are derived when
     * they are read, never kept in a column.
     *
     * <p>ResourceMapper calls this method to fill ResourceResponse.annualCost,
     * so the browser receives the result and never the formula.
     */
    public BigDecimal getAnnualCost() {
        // tccRate.add(BigDecimal.ONE) turns the coefficient into a multiplier:
        // 0.42 becomes 1.42. Without the "+ 1" the method would return 42
        // percent of the yearly cost instead of 142 percent of it, and every
        // resource would look more than twice as cheap as it really is.
        // BigDecimal.valueOf(int) is needed because BigDecimal cannot be
        // multiplied by a plain int.
        return dailyRate.multiply(tccRate.add(BigDecimal.ONE))
                .multiply(BigDecimal.valueOf(JOURS_OUVRES_AN))
                // Rounding is done once, at the very end, and HALF_UP is the
                // rule a human expects: 0.125 becomes 0.13. Without an explicit
                // setScale the result would keep all the decimals of the
                // multiplication (0.42 has four decimals in the database) and
                // the screen would show a yearly cost with a long tail of
                // digits. HALF_UP is also the rounding used by KpiService for
                // every cost line, so the two never disagree by one cent.
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
