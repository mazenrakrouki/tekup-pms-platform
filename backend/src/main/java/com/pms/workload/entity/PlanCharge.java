package com.pms.workload.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The JPA entity of one planned workload line: this person, on this
 *   project, for this month, this many days planned. "Entity" means that one
 *   Java object of this class is exactly one row of the table "plan_charges"
 *   (created by migration V7; the two audit columns created_by and updated_by
 *   were added later by V19). "Plan de charge" is the French name of that
 *   table: the work the project manager INTENDS to spend, month by month.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen
 *     -> WorkloadController     /api/projects/{projectId}/plan-charges
 *     -> PlanChargeService      (permission check + business rules)
 *     -> PlanChargeRepository   (reads and writes the rows)
 *     -> THIS CLASS             (the row held in memory)
 *     -> PlanChargeMapper       -> PlanChargeResponse -> JSON -> screen.
 *   One important reader never passes through the controller: KpiService
 *   calls PlanChargeRepository.findActiveByProjectId() directly to build the
 *   planned budget of the project. The demo seeders (DemoDataSeeder,
 *   EnterpriseDataSeeder, and migration V26) also write rows of this shape.
 *
 * WHY IT EXISTS
 *   It is the "what we plan to spend" half of the cost picture of a project.
 *   Its twin ChargeReelle, in this same package, is the "what was really
 *   spent" half. The two tables carry almost the same columns on purpose: the
 *   KPI screen puts them side by side, month by month and person by person.
 *   Delete this file and KpiService has no planned days at all - no planned
 *   budget, no plan-versus-actual comparison, and the screen that shows
 *   whether a project is drifting has nothing left to compare.
 *
 * IT STORES DAYS, NEVER MONEY
 *   There is no amount column here, and that is deliberate. The cost of a
 *   line is derived when it is read: KpiService multiplies the days below by
 *   the daily rate of the person (Resource) and by the TCC coefficient of the
 *   year the days belong to (TccAnnuel, F-AFF-13). Storing an amount here
 *   would freeze the rate of the day the line was typed; the next time a rate
 *   is renegotiated the stored figure would silently disagree with the rate
 *   table, and nobody could tell which of the two is right.
 *
 * NO SECURITY CODE LIVES HERE
 *   An entity object cannot know who is asking, so it must not try to decide.
 *   The permission is checked on the methods of PlanChargeService with
 *   @PreAuthorize("hasAuthority('VIEW_WORKLOAD')") for reading and
 *   @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')") for writing, and the
 *   project perimeter is checked by ProjectScopeInterceptor for every URL of
 *   the form /api/projects/{id}/** (ADR-021): holding the permission is not
 *   enough, the caller must also have the right to that project. On top of
 *   that, PlanChargeService narrows what is returned: somebody who holds
 *   neither VALIDATE_WORKLOAD nor VIEW_ALL_PROJECTS only gets his own lines
 *   (BR-062...064). None of that is visible in this file, which is why it is
 *   written down here.
 *
 * NOTHING IS REALLY DELETED
 *   BaseEntity carries a boolean "deleted" and PlanChargeService.delete()
 *   only sets it to true (soft delete). The row stays, so the history of what
 *   had been planned stays readable and the KPI snapshots taken in the past
 *   keep their meaning. Hibernate does not hide those rows by itself, so
 *   every query of PlanChargeRepository states the filter itself: the @Query
 *   methods write "AND pc.deleted = false" inside the JPQL, and the derived
 *   method carries it in its own name
 *   (existsByProjectIdAndUserIdAndPeriodAndDeletedFalse). Calling the
 *   ready-made findAll() of Spring Data instead would put cancelled planning
 *   lines back into the budget.
 * =========================================================================
 */

/**
 * One planned workload line: this user, on this project, for this month, this
 * many days.
 *
 * Why the class is written this way, annotation by annotation:
 *  - @Entity tells JPA to manage the class. Without it, PlanChargeRepository
 *    fails at start up with "Not a managed type", and any JPQL that names
 *    "PlanCharge" no longer resolves.
 *  - @Table(name = "plan_charges") names the real table. The name Hibernate
 *    would build on its own is "plan_charge" (singular); since the
 *    application starts with ddl-auto=validate (Flyway owns the schema), it
 *    would stop immediately with "table not found" instead of silently
 *    working on a wrong table.
 *  - @Getter / @Setter (Lombok) write the accessors at compile time. The
 *    service and the MapStruct mapper use them; Hibernate reads the fields
 *    directly, because the @Id of BaseEntity is placed on a field.
 *  - @NoArgsConstructor is required by JPA: Hibernate first creates an empty
 *    object, then fills it with the values read from the row. Without it,
 *    reading a plan line fails at runtime.
 *  - @AllArgsConstructor is there to feed @Builder. As soon as one
 *    constructor annotation is present, Lombok stops adding on its own the
 *    all-fields constructor that the builder needs.
 *  - @Builder is what PlanChargeService.create() and the seeders use. It also
 *    prevents a silent mistake: with a positional constructor nothing stops
 *    somebody from swapping two arguments of the same type, and the line
 *    would be saved against the wrong project or the wrong person. Named
 *    steps such as .user(...) make that impossible. One limit to know: the
 *    builder only knows the four fields declared below. The fields inherited
 *    from BaseEntity (id, the audit dates, deleted) are not builder steps,
 *    which is why delete() sets the flag with pc.setDeleted(true) and does
 *    not rebuild the object.
 *  - extends BaseEntity brings id, created_at, updated_at, created_by,
 *    updated_by and the deleted flag - the columns every table of the project
 *    carries since the audit migration V19. BaseEntity also carries
 *    @EntityListeners(AuditingEntityListener.class), so those four audit
 *    values are filled by Spring on each save: no code in this module has to
 *    remember to stamp "who changed the plan and when". On a planning table
 *    that trace is what answers "who raised this month from 10 days to 20".
 *
 * Rules and indexes that live in the database, not in this class:
 *  - uk_pc_active (V7) is a UNIQUE index on (project_id, user_id, period)
 *    limited by "WHERE deleted = FALSE". It allows only one ACTIVE plan line
 *    per person, per project, per month, while still allowing old
 *    soft-deleted lines for the same month, so a cancelled plan can be typed
 *    again. A normal unique constraint would block that second entry forever.
 *    PlanChargeService.create() asks
 *    existsByProjectIdAndUserIdAndPeriodAndDeletedFalse first, to answer a
 *    clear message; the index is what still holds if two managers click
 *    "save" at the very same moment and both checks pass. In that race the
 *    database refuses the second insert and GlobalExceptionHandler turns the
 *    failure into 409 Conflict. Without the index the project would end up
 *    with the same month counted twice in its planned budget.
 *  - chk_pc_days (V7) checks "planned_days > 0 AND planned_days <= 31".
 *    PlanChargeRequest repeats the same limits with @Positive and
 *    @DecimalMax("31") so the user gets a readable 400 instead of a database
 *    error, but the database keeps the guarantee even for a row written by a
 *    seeder or by hand in psql. Without it a typing slip such as 300 days
 *    would multiply the planned budget of that month by ten.
 *  - idx_pc_project and idx_pc_user (V7) are two ordinary indexes, also
 *    limited by "WHERE deleted = FALSE". They make the two everyday queries
 *    fast: the plan of one project (the screen, and every KPI computation)
 *    and the plan of one person. Without them PostgreSQL reads the whole
 *    table each time, and that cost is paid on every request, not once.
 *  - fk_pc_project and fk_pc_user (V7) are plain foreign keys with no ON
 *    DELETE clause, so PostgreSQL refuses to erase a project or a user while
 *    plan lines still point at them. In normal use this never fires, because
 *    projects and users are soft-deleted; it is the safety net against a real
 *    DELETE run by hand, which would otherwise leave planning rows pointing
 *    at nothing.
 *
 * One limit worth knowing: there is no @Version field here, so JPA does no
 * optimistic locking on this table. If two managers open the same line and
 * save one after the other, the second save wins and the first change is lost
 * with no warning. The unique index above protects against duplicate rows,
 * not against this.
 */
@Entity
@Table(name = "plan_charges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanCharge extends BaseEntity {

    // WHAT: the project this planned line belongs to. @ManyToOne = many plan
    //       lines point to one project; @JoinColumn says the link is stored in
    //       the column project_id (foreign key fk_pc_project, V7).
    // WHY FetchType.LAZY: a @ManyToOne is EAGER by default, so Hibernate would
    //       read the whole Project row every time it reads a plan line, even
    //       when nobody looks at it. A project with 10 people over 12 months
    //       has around 120 plan lines, so listing them would fire 120 extra
    //       SELECT statements for the same project - the "N+1 queries"
    //       problem. LAZY loads it only when it is really used, and the
    //       queries that do need it ask for it in one go with
    //       "JOIN FETCH pc.project".
    // WHY nullable = false: a planned day that belongs to no project cannot be
    //       costed and cannot be shown anywhere. The field also carries a
    //       security meaning: PlanChargeService.update() and .delete() compare
    //       pc.getProject().getId() with the projectId taken from the URL and
    //       answer "not found" when the two differ. That is how a line of
    //       project A cannot be edited through the URL of project B, even by
    //       somebody who legitimately holds VALIDATE_WORKLOAD on project B.
    //       With a null project that comparison would throw a
    //       NullPointerException instead of protecting anything. V7 declares
    //       the column NOT NULL too, so the database refuses such a row as
    //       well.
    // SPEED: the column is covered by the partial index idx_pc_project (V7,
    //       WHERE deleted = FALSE), the one used by the screen and by every
    //       KPI computation.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // WHAT: the person whose future work is being planned. The link is stored
    //       in the column user_id (foreign key fk_pc_user, V7).
    // WHY FetchType.LAZY: same reason as above. The queries that need the name
    //       say so explicitly ("JOIN FETCH pc.user"), which is also why
    //       PlanChargeRepository can sort on pc.user.lastName and why
    //       PlanChargeMapper can read user.getFullName() without a
    //       lazy-loading error once the transaction is closed.
    // WHY nullable = false: the person is what turns days into money.
    //       KpiService takes this id to find the daily rate of the person
    //       (Resource) and the TCC coefficient of the year (TccAnnuel). A plan
    //       line with nobody on it could not be priced at all, so the planned
    //       budget of the project would be too low with nothing on screen to
    //       show why.
    // NOTE: before building this object, PlanChargeService.create() checks
    //       that the person really is an active member of the team of the
    //       project (or its project manager) - marker H-8. The entity itself
    //       cannot check that, because it does not see the team table.
    // SPEED: covered by the partial index idx_pc_user (V7).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // WHAT: the month this planned effort belongs to, ALWAYS stored as the
    //       first day of that month (2026-04-01 means "April 2026").
    // WHY a date and not two integer columns year and month: a date sorts and
    //       compares by itself. PlanChargeRepository writes "ORDER BY
    //       pc.period" and the controller sorts its pages on "period"
    //       descending; with two columns every such query would need a
    //       two-level sort, and "December 2025 before January 2026" would
    //       have to be handled by hand. The outside world still speaks in
    //       year + month: the request sends them separately,
    //       PlanChargeService rebuilds the date with LocalDate.of(year, month,
    //       1), and PlanChargeMapper converts back with getYear() and
    //       getMonthValue().
    // WHY the first of the month matters so much: this column is part of the
    //       unique index uk_pc_active. If one piece of code stored the 15th
    //       instead of the 1st, the database would see a different period, the
    //       duplicate check would pass, and the same person would hold two
    //       plan lines for the same month - that month would then be counted
    //       twice in the planned budget. The seed of V26 uses
    //       date_trunc('month', ...) for exactly the same reason.
    // WHY nullable = false: a planned effort with no month cannot be placed on
    //       the monthly curve, and it could never be compared with the real
    //       days declared for that month.
    @Column(nullable = false)
    private LocalDate period;

    // WHAT: how many working days are planned for that person, on that
    //       project, in that month. Column planned_days, NUMERIC(5,2) in V7,
    //       which is what precision = 5 and scale = 2 describe: at most 5
    //       digits in total with 2 of them after the point, so half days such
    //       as 12.50 are possible.
    // WHY BigDecimal and not double: double cannot hold values like 0.1
    //       exactly, and these days are multiplied by a daily rate to produce
    //       money. With double, the sum of twelve monthly lines can come out
    //       as 149.99999999999997 instead of 150, and the margin shown to the
    //       management would not be reproducible. BigDecimal keeps the exact
    //       decimal value, which is why every figure that ends up in an amount
    //       is a BigDecimal in this application.
    // WHY days and not an amount: see the file header - the cost is derived
    //       when read (days x daily rate x TCC of the year), never stored.
    // WHY nullable = false: a plan line whose number of days is unknown is not
    //       a plan. The database rule is in fact stricter than plain NOT NULL:
    //       chk_pc_days (V7) demands "> 0 AND <= 31". Zero planned days is
    //       refused here - a line meaning "nothing planned" should simply not
    //       exist - while ChargeReelle, the twin of this class, does accept 0,
    //       because a person really can declare a month with no day worked on
    //       that project.
    @Column(name = "planned_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal plannedDays;
}
