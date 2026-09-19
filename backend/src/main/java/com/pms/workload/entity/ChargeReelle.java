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
 * =========================================================================
 * WHAT THIS FILE IS
 *   The JPA entity of one real workload line ("charge reelle" in French):
 *   this person declares that he worked this many days, on this project, in
 *   this month, and the project manager then approves it. "Entity" means that
 *   one Java object of this class is exactly one row of the table
 *   "charges_reelles" (created by migration V7; the two audit columns
 *   created_by and updated_by were added later by V19).
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen
 *     -> WorkloadController      /api/projects/{projectId}/charges-reelles
 *                                (list, submit, update, validate, delete)
 *     -> ChargeReelleService     (permission check + business rules)
 *     -> ChargeReelleRepository  (reads and writes the rows)
 *     -> THIS CLASS              (the row held in memory)
 *     -> ChargeReelleMapper      -> ChargeReelleResponse -> JSON -> screen.
 *   One important reader never passes through the controller: KpiService
 *   calls ChargeReelleRepository.findValidatedByProjectId() directly to build
 *   the consumed budget of the project. The demo seeders (DemoDataSeeder,
 *   EnterpriseDataSeeder, and migration V26) also write rows of this shape.
 *
 * WHY IT EXISTS
 *   It is the "what was really spent" half of the cost picture of a project;
 *   PlanCharge, in this same package, is the "what we plan to spend" half.
 *   The two tables carry almost the same columns on purpose, so the KPI
 *   screen can put them side by side month by month and person by person.
 *   Delete this file and the application no longer knows how many days were
 *   really consumed: no consumed budget, no real margin, no drift between the
 *   plan and the reality - only a plan nobody can check.
 *
 * WHY THIS CLASS HAS THREE COLUMNS THAT PlanCharge DOES NOT HAVE
 *   A plan is written by the manager, so it is true as soon as it is saved. A
 *   declaration of real work is written by the person who did the work, so it
 *   is only a claim until somebody approves it. submittedAt, validatedAt and
 *   validatedBy are the three columns that carry that second step: who
 *   approved, and when. This is exactly why KpiService reads only the
 *   validated rows: an unapproved declaration would let a developer change
 *   the consumed budget and the margin of a project alone, just by typing a
 *   number.
 *
 * IT STORES DAYS, NEVER MONEY
 *   There is no amount column here, and that is deliberate. The cost of a
 *   line is derived when it is read: KpiService multiplies the days below by
 *   the daily rate of the person (Resource) and by the TCC coefficient of the
 *   year those days belong to (TccAnnuel, F-AFF-13). Storing an amount would
 *   freeze the rate of the day the line was typed; after the next rate
 *   negotiation the stored figure would silently disagree with the rate
 *   table, and nobody could tell which of the two is right.
 *
 * NO SECURITY CODE LIVES HERE
 *   An entity object cannot know who is asking, so it must not try to decide.
 *   The permission is checked on the methods of ChargeReelleService with
 *   @PreAuthorize("hasAuthority('VIEW_WORKLOAD')") for reading,
 *   @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')") for declaring days and
 *   @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')") for approving or
 *   deleting; the project perimeter is checked by ProjectScopeInterceptor for
 *   every URL of the form /api/projects/{id}/** (ADR-021): holding the
 *   permission is not enough, the caller must also have the right to that
 *   project. Two more narrowings live in the service and not here: BR-033,
 *   somebody without VALIDATE_WORKLOAD may only act on his own lines, and
 *   BR-062...064, somebody without VALIDATE_WORKLOAD or VIEW_ALL_PROJECTS
 *   only sees his own lines.
 *
 * NOTHING IS REALLY DELETED
 *   BaseEntity carries a boolean "deleted" and ChargeReelleService.delete()
 *   only sets it to true (soft delete), and only on a line that is not
 *   validated yet. The row stays, so the past stays auditable and the KPI
 *   snapshots taken earlier keep their meaning. Hibernate does not hide those
 *   rows by itself, so every query of ChargeReelleRepository states the
 *   filter itself: the @Query methods write "AND cr.deleted = false" inside
 *   the JPQL, and the derived method carries it in its own name
 *   (existsByProjectIdAndUserIdAndPeriodAndDeletedFalse). Calling the
 *   ready-made findAll() of Spring Data instead would put cancelled
 *   declarations back into the consumed budget.
 * =========================================================================
 */

/**
 * One declaration of real work: this user, on this project, for this month,
 * this many days - plus when it was sent, when it was approved and by whom.
 *
 * Why the class is written this way, annotation by annotation:
 *  - @Entity tells JPA to manage the class. Without it,
 *    ChargeReelleRepository fails at start up with "Not a managed type", and
 *    any JPQL that names "ChargeReelle" no longer resolves.
 *  - @Table(name = "charges_reelles") names the real table. The name
 *    Hibernate would build on its own is "charge_reelle" (singular); since
 *    the application starts with ddl-auto=validate (Flyway owns the schema),
 *    it would stop immediately with "table not found" instead of silently
 *    working on a wrong table.
 *  - @Getter / @Setter (Lombok) write the accessors at compile time. The
 *    service and the MapStruct mapper use them; Hibernate reads the fields
 *    directly, because the @Id of BaseEntity is placed on a field. The
 *    setters matter here more than in most entities: validate() approves a
 *    line with setValidatedAt() and setValidatedBy() on an object loaded
 *    inside a transaction.
 *  - @NoArgsConstructor is required by JPA: Hibernate first creates an empty
 *    object, then fills it with the values read from the row. Without it,
 *    reading a declaration fails at runtime.
 *  - @AllArgsConstructor is there to feed @Builder. As soon as one
 *    constructor annotation is present, Lombok stops adding on its own the
 *    all-fields constructor that the builder needs.
 *  - @Builder is what ChargeReelleService.submit() and the seeders use. It
 *    also prevents a silent mistake: this class holds two User fields (user
 *    and validatedBy) and two date-time fields (submittedAt and validatedAt),
 *    so a positional constructor could take the approver for the author, or
 *    the approval date for the submission date, and still compile. Named
 *    steps such as .user(...) make that impossible. One limit to know: the
 *    builder only knows the seven fields declared below. The fields inherited
 *    from BaseEntity (id, the audit dates, deleted) are not builder steps,
 *    which is why delete() sets the flag with cr.setDeleted(true) and does
 *    not rebuild the object.
 *  - extends BaseEntity brings id, created_at, updated_at, created_by,
 *    updated_by and the deleted flag - the columns every table of the project
 *    carries since the audit migration V19. BaseEntity also carries
 *    @EntityListeners(AuditingEntityListener.class), so those four audit
 *    values are filled by Spring on each save. Note that they answer a
 *    different question from validatedBy below: created_by/updated_by say who
 *    touched the row at all, validatedBy says who took responsibility for the
 *    days becoming a cost.
 *
 * Rules and indexes that live in the database, not in this class:
 *  - uk_cr_active (V7) is a UNIQUE index on (project_id, user_id, period)
 *    limited by "WHERE deleted = FALSE". It allows only one ACTIVE
 *    declaration per person, per project, per month, while still allowing old
 *    soft-deleted lines for the same month, so a cancelled declaration can be
 *    typed again. A normal unique constraint would block that second entry
 *    forever. ChargeReelleService.submit() asks
 *    existsByProjectIdAndUserIdAndPeriodAndDeletedFalse first, to answer a
 *    clear message; the index is what still holds if the same person clicks
 *    "send" twice at the very same moment and both checks pass. In that race
 *    the database refuses the second insert and GlobalExceptionHandler turns
 *    the failure into 409 Conflict. Without the index the same month would be
 *    counted twice in the consumed budget of the project.
 *  - chk_cr_days (V7) checks "actual_days >= 0 AND actual_days <= 31". Note
 *    the difference with the plan table, where the rule is "> 0": a real
 *    month with no day worked on this project is a legitimate declaration.
 *    ChargeReelleRequest repeats the same limits with @PositiveOrZero and
 *    @DecimalMax("31") so the user gets a readable 400 instead of a database
 *    error, but the database keeps the guarantee even for a row written by a
 *    seeder or by hand in psql. Without it a typing slip such as 300 days
 *    would multiply the consumed cost of that month by ten and destroy the
 *    margin shown for the project.
 *  - idx_cr_project and idx_cr_user (V7) are two ordinary indexes, also
 *    limited by "WHERE deleted = FALSE". They make the two everyday queries
 *    fast: the declarations of one project (the screen, and every KPI
 *    computation) and the declarations of one person. Without them PostgreSQL
 *    reads the whole table each time, and that cost is paid on every request.
 *  - fk_cr_project, fk_cr_user and fk_cr_validated_by (V7) are plain foreign
 *    keys with no ON DELETE clause, so PostgreSQL refuses to erase a project
 *    or a user while declarations still point at them. In normal use this
 *    never fires, because projects and users are soft-deleted; it is the
 *    safety net against a real DELETE run by hand, which would otherwise
 *    leave a declaration whose author, or whose approver, no longer exists.
 *
 * One limit worth knowing: there is no @Version field here, so JPA does no
 * optimistic locking on this table. If two project managers open the same
 * line and save one after the other, the second save wins and the first
 * change is lost with no warning. The unique index above protects against
 * duplicate rows, not against this.
 */
@Entity
@Table(name = "charges_reelles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeReelle extends BaseEntity {

    // WHAT: the project the declared days were worked on. @ManyToOne = many
    //       declarations point to one project; @JoinColumn says the link is
    //       stored in the column project_id (foreign key fk_cr_project, V7).
    // WHY FetchType.LAZY: a @ManyToOne is EAGER by default, so Hibernate would
    //       read the whole Project row every time it reads a declaration, even
    //       when nobody looks at it. A project with 10 people over 12 months
    //       has around 120 declarations, so listing them would fire 120 extra
    //       SELECT statements for the same project - the "N+1 queries"
    //       problem. LAZY loads it only when it is really used, and the
    //       queries that do need it ask for it in one go with
    //       "JOIN FETCH cr.project".
    // WHY nullable = false: days worked on no project cannot be costed and
    //       cannot be invoiced. The field also carries a security meaning:
    //       ChargeReelleService.update(), .validate() and .delete() compare
    //       cr.getProject().getId() with the projectId taken from the URL and
    //       answer "not found" when the two differ. That is how a declaration
    //       of project A cannot be approved or deleted through the URL of
    //       project B, even by somebody who legitimately holds
    //       VALIDATE_WORKLOAD on project B. With a null project that
    //       comparison would throw a NullPointerException instead of
    //       protecting anything. V7 declares the column NOT NULL too, so the
    //       database refuses such a row as well.
    // SPEED: the column is covered by the partial index idx_cr_project (V7,
    //       WHERE deleted = FALSE), the one used by the screen and by every
    //       KPI computation.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // WHAT: the person who did the work and whose days are being declared.
    //       The link is stored in the column user_id (foreign key fk_cr_user,
    //       V7). This is the AUTHOR of the declaration, not its approver -
    //       the approver is validatedBy, further down.
    // WHY FetchType.LAZY: same reason as above. The queries that need the name
    //       say so explicitly ("JOIN FETCH cr.user"), which is also why
    //       ChargeReelleRepository can sort on cr.user.lastName and why
    //       ChargeReelleMapper can read user.getFullName() without a
    //       lazy-loading error once the transaction is closed.
    // WHY nullable = false: the person is what turns days into money.
    //       KpiService takes this id to find the daily rate of the person
    //       (Resource) and the TCC coefficient of the year (TccAnnuel). It is
    //       also the field the ownership rule leans on: BR-033, somebody
    //       without VALIDATE_WORKLOAD may only submit or change a line whose
    //       user is himself. A declaration with nobody on it could be priced
    //       at nothing and owned by nobody.
    // NOTE: before building this object, ChargeReelleService.submit() checks
    //       that the person really is an active member of the team of the
    //       project (or its project manager) - marker H-8. The entity itself
    //       cannot check that, because it does not see the team table.
    // SPEED: covered by the partial index idx_cr_user (V7).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // WHAT: the month the declared days belong to, ALWAYS stored as the first
    //       day of that month (2026-04-01 means "April 2026").
    // WHY a date and not two integer columns year and month: a date sorts and
    //       compares by itself. ChargeReelleRepository writes "ORDER BY
    //       cr.period" and the controller sorts its pages on "period"
    //       descending; with two columns every such query would need a
    //       two-level sort, and "December 2025 before January 2026" would
    //       have to be handled by hand. The outside world still speaks in
    //       year + month: the request sends them separately,
    //       ChargeReelleService rebuilds the date with LocalDate.of(year,
    //       month, 1), and ChargeReelleMapper converts back with getYear()
    //       and getMonthValue().
    // WHY the first of the month matters so much: this column is part of the
    //       unique index uk_cr_active. If one piece of code stored the 15th
    //       instead of the 1st, the database would see a different period, the
    //       duplicate check would pass, and the same person would hold two
    //       declarations for the same month - that month would then be
    //       counted twice in the consumed budget. The seed of V26 uses
    //       date_trunc('month', ...) for exactly the same reason.
    // WHY nullable = false: days with no month cannot be placed on the
    //       monthly curve, cannot be compared with the plan of that month,
    //       and cannot be priced with the TCC rate of the right year (a day of
    //       2025 does not cost what a day of 2026 costs).
    // NOTE: the month cannot be changed afterwards. update() rebuilds the
    //       expected period from the request and refuses the call when it does
    //       not match the stored one. Moving a declaration from one month to
    //       another would silently move a cost from one financial period to
    //       another; the user has to delete the line and type it again.
    @Column(nullable = false)
    private LocalDate period;

    // WHAT: how many working days that person really spent on that project in
    //       that month. Column actual_days, NUMERIC(5,2) in V7, which is what
    //       precision = 5 and scale = 2 describe: at most 5 digits in total
    //       with 2 of them after the point, so half days such as 12.50 are
    //       possible.
    // WHY BigDecimal and not double: double cannot hold values like 0.1
    //       exactly, and these days are multiplied by a daily rate to produce
    //       money. With double, the sum of twelve monthly lines can come out
    //       as 149.99999999999997 instead of 150, and the consumed budget
    //       shown to the management would not be reproducible. BigDecimal
    //       keeps the exact decimal value.
    // WHY days and not an amount: see the file header - the cost is derived
    //       when read (days x daily rate x TCC of the year), never stored.
    // WHY nullable = false: a declaration with no number of days declares
    //       nothing. The database rule is chk_cr_days (V7), ">= 0 AND <= 31":
    //       zero is accepted on purpose here, unlike in the plan table, so a
    //       person can state "I worked no day on this project this month"
    //       instead of leaving a hole that looks like a forgotten timesheet.
    // NOTE: this is the only value update() allows to change, and only while
    //       the line is not validated yet.
    @Column(name = "actual_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal actualDays;

    // WHAT: when the declaration was sent for approval.
    //       ChargeReelleService.submit() sets it to LocalDateTime.now(), and
    //       update() sets it again, so it always means "when the figure
    //       currently stored was sent".
    // WHY a timestamp and not a boolean "submitted": one column answers both
    //       "was it sent" and "when", and the two can never disagree. With a
    //       boolean plus a date, a wrong save could leave "true" next to an
    //       empty date and nobody could tell which one to believe. The date is
    //       also what lets the company see who declares his months late.
    // WHY the column allows NULL: V7 declares submitted_at without NOT NULL,
    //       because a row can be written outside the normal path - a seeder,
    //       a data import, a correction made in psql. The service always fills
    //       it, so in the application a line read from the screen has a date;
    //       code that reads this field elsewhere must still accept null.
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    // WHAT: when the project manager approved the declaration. Empty as long
    //       as nobody approved it. ChargeReelleService.validate() is the only
    //       place that sets it, together with validatedBy just below.
    // WHY this single column carries the whole state of the line: there is no
    //       "status" column and no boolean "validated" in this table. The
    //       state is derived from this date by isValidated() at the bottom of
    //       the file. Why it is better: a stored status could say "validated"
    //       while the date is empty, or the opposite, and the two would have
    //       to be repaired by hand; here there is nothing to keep in step.
    // WHY it matters so much: this is the line between a claim and a fact.
    //       KpiService reads only the rows where this column is not null
    //       (findValidatedByProjectId), so a declaration that nobody approved
    //       changes no budget and no margin. Without this column a developer
    //       could change the consumed cost of a project alone, just by typing
    //       a number. The service leans on the same idea to refuse to modify
    //       (update) or to remove (delete) an approved line: once the days
    //       have become a cost, they are not rewritten in silence.
    // WHY the column allows NULL: "not yet approved" is the normal state of a
    //       fresh declaration, and NULL is what expresses it.
    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    // WHAT: the person who approved the declaration. Second link to the users
    //       table, stored in the column validated_by (foreign key
    //       fk_cr_validated_by, V7), next to user_id which holds the author.
    // WHY two links to the same table: the person who declares the days and
    //       the person who accepts them must never be confused. The approver
    //       is the one who takes responsibility for the days becoming a real
    //       cost on the project, so his name has to stay next to the line and
    //       be shown on screen (ChargeReelleMapper fills validatedById and
    //       validatedByName from this field).
    // WHY FetchType.LAZY: same N+1 reason as the other links. The queries that
    //       show the approver ask for it with "LEFT JOIN FETCH
    //       cr.validatedBy" - LEFT, and not a plain JOIN FETCH, precisely
    //       because this link is often empty: an inner join would make every
    //       declaration waiting for approval disappear from the list, which is
    //       exactly the list the project manager needs to see.
    // WHY no nullable = false here: unlike the two links above, this one is
    //       empty until somebody approves the line, and V7 leaves the column
    //       nullable for that reason. It is filled at the same moment as
    //       validatedAt; reading one without the other would give a
    //       half-approved line.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by")
    private User validatedBy;

    /**
     * Says whether this declaration has already been approved, by testing
     * whether the approval date exists. Returns true once validate() has run.
     *
     * Why a derived method rather than a stored boolean column: the state then
     * has one single source of truth, the date. A stored flag could end up
     * saying "validated" while validated_at is empty (a partial save, a manual
     * fix in psql, a seeder that forgets one column), and the screen would
     * show an approved line that the KPI computation ignores, because
     * KpiService selects on validated_at and not on a flag.
     *
     * Who calls it: ChargeReelleService.update() refuses to change an approved
     * line, and .delete() refuses to remove one - in both cases with a clear
     * business message. Without this guard, approved days that already count
     * in the consumed cost and in the margin of the project could be rewritten
     * or made to disappear after the fact, and the figures given to the
     * management could no longer be reproduced.
     *
     * Note: the name follows the JavaBean rule for a boolean (isXxx), so
     * Jackson would also expose it as a field "validated" if this object were
     * ever serialized directly. It is not: the API always answers with
     * ChargeReelleResponse, which carries the dates themselves.
     */
    public boolean isValidated() {
        return validatedAt != null;
    }
}
