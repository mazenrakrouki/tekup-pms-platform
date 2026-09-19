package com.pms.billing.entity;

/*
 * ============================================================================
 * FILE: Avenant.java -- JPA entity for a signed contract amendment.
 * ============================================================================
 *
 * WHAT THIS FILE IS
 *   One Java object here = one row of the SQL table "avenants".
 *   The table is created by the Flyway migration V9__schema_billing.sql and is
 *   extended later by V15__avenant_workload.sql (the workload_days column).
 *   An "avenant" is a change to the client contract that both sides signed:
 *   the client asks for more work (or less), so the agreed price of the project
 *   moves up (or down).
 *   "JPA entity" means a plain Java class that Hibernate maps onto a database
 *   table, so we read and write rows as objects instead of writing SQL by hand.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular frontend
 *     -> BillingController      GET and POST on /api/projects/{projectId}/avenants,
 *                               DELETE on /api/projects/{projectId}/avenants/{id}
 *     -> AvenantService         permission check + the business rules
 *     -> AvenantRepository      loads and saves the rows
 *     -> THIS ENTITY            the row itself, in memory
 *     -> AvenantMapper          copies this entity into AvenantResponse, the DTO
 *                               (Data Transfer Object = a small read-only object
 *                               whose only job is to carry data out as JSON)
 *
 *   Two extra effects happen in AvenantService around this entity:
 *     1. create() takes the project's effective budget (the revised budget when
 *        there is one, otherwise the initial one), adds this amount to it, and
 *        writes the result into Project.revisedBudget, so the project now
 *        carries the new agreed price.
 *     2. create() and delete() then call JalonService.recomputePrevuMontants(),
 *        which re-prices the billing milestones that are still only planned.
 *        That call carries the traceability marker H-4.
 *
 * WHY IT EXISTS / WHAT BREAKS WITHOUT IT
 *   Delete this file and a project would forever keep only its very first
 *   budget. Every price change agreed later with the client would be invisible:
 *   the revised budget, the re-priced billing milestones, and every financial
 *   indicator built on the budget (EAC, margins, FAE) would be computed on a
 *   price that is no longer the real one. This table is also the audit trail:
 *   it says which amendment number moved the price, by how much, and on which
 *   date.
 *
 * SISTER FILES IN THIS PACKAGE
 *   JalonFacturation -- a billing milestone, a slice of the budget to invoice.
 *   Paiement         -- money actually received against one milestone.
 *   JalonStatut      -- the three states a milestone can be in.
 *   An avenant moves the budget; the milestones then re-slice that new budget.
 */

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * THE ANNOTATIONS ON THIS CLASS, ONE BY ONE
 *
 * @Entity
 *   WHAT: tells Hibernate "this class is a database table, manage it".
 *   WHY : without it the class is just an ordinary object.
 *   EXAMPLE OF THE BREAKAGE: avenantRepository.save(avenant) would fail with
 *   "Not a managed type: class com.pms.billing.entity.Avenant".
 *
 * @Table(name = "avenants")
 *   WHAT: pins the exact table name.
 *   WHY : by default Hibernate derives the name from the class name and would
 *         look for a table called "avenant" (singular).
 *   EXAMPLE: our Flyway script created "avenants" (plural). The application
 *   runs with spring.jpa.hibernate.ddl-auto = validate, so Hibernate compares
 *   every entity against the real schema when it boots. A wrong table name
 *   would stop the whole application from starting.
 *
 * extends BaseEntity
 *   WHAT: brings in the columns shared by every table of the application:
 *         id, created_at, updated_at, created_by, updated_by and deleted.
 *   WHY : so we do not repeat them in twenty entities, and so the auditing
 *         listener fills created_by and updated_at the same way everywhere.
 *   NOTE: "deleted" is the soft delete flag. We never physically remove a row;
 *         we set deleted = true and every query filters on deleted = false.
 *         A removed amendment must still be readable for accounting history.
 *
 * @Getter / @Setter  (Lombok)
 *   WHAT: Lombok writes the getXxx() and setXxx() methods at compile time.
 *   WHY : Hibernate and the MapStruct mapper both need them to read the fields.
 *
 * @NoArgsConstructor
 *   WHAT: generates the empty constructor Avenant().
 *   WHY : the JPA specification requires one. Hibernate builds an empty object
 *         first and then fills the fields when it loads a row.
 *   EXAMPLE: without it, reading any avenant from the database throws
 *   "No default constructor for entity".
 *
 * @AllArgsConstructor + @Builder
 *   WHAT: @Builder gives the fluent style used in AvenantService:
 *         Avenant.builder().project(p).numero("AV-01")...build();
 *         @AllArgsConstructor gives the constructor that @Builder calls.
 *   WHY : this entity has six fields, several of them BigDecimal or LocalDate.
 *         A plain constructor with six arguments is easy to fill in the wrong
 *         order, and the compiler cannot catch two swapped BigDecimal values.
 *         The builder names each value at the call site.
 *   CAREFUL: Lombok's @Builder only covers the fields declared in THIS class,
 *         not the ones inherited from BaseEntity. That is why nothing ever
 *         calls .id(...) or .deleted(...) on this builder; "deleted" gets its
 *         false value from the field initializer inside BaseEntity.
 */
@Entity
@Table(name = "avenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Avenant extends BaseEntity {

    /*
     * The project this amendment belongs to.
     *
     * @ManyToOne
     *   WHAT: many amendment rows point at one project row.
     *   WHY : a project can be amended several times during its life.
     *
     * fetch = FetchType.LAZY
     *   WHAT: do NOT load the whole Project object when an Avenant is read.
     *         Hibernate puts a lightweight placeholder here and only goes back
     *         to the database if somebody calls avenant.getProject().
     *   WHY : the default for @ManyToOne is EAGER, which is the opposite. With
     *         EAGER, loading the 30 amendments of a project fires one extra
     *         SELECT per row on the projects table (the classic "N+1 queries"
     *         problem), and each Project loaded drags in whatever that entity
     *         itself loads eagerly.
     *   EXAMPLE of what LAZY costs us, and how we pay for it: the application
     *         runs with spring.jpa.open-in-view = false, so the database
     *         session closes as soon as the service method returns. If the
     *         mapper read project.getCode() after that point, Hibernate would
     *         throw LazyInitializationException. This is exactly why
     *         AvenantRepository writes "JOIN FETCH a.project" in its queries:
     *         it asks for the project inside the same SELECT, on purpose,
     *         while the transaction is still open.
     *
     * @JoinColumn(name = "project_id", nullable = false)
     *   WHAT: names the foreign key column holding the project id, and says it
     *         can never be empty.
     *   WHY : V9 declares a real foreign key constraint, fk_av_project, on that
     *         exact column. Naming the column here keeps the Java side and the
     *         SQL side provably in step under ddl-auto = validate.
     *   EXAMPLE: an amendment with no project would be a price change belonging
     *         to nobody, and it would silently never reach any budget.
     *         nullable = false makes the database itself refuse that row, even
     *         if a future bug forgot to set the project.
     *
     * SECURITY NOTE (ADR-021)
     *   This link is also what makes the amendment scopeable. The URL is
     *   /api/projects/{projectId}/avenants, so ProjectScopeInterceptor checks
     *   BOTH the permission and the caller's right to that one project before
     *   the service runs. AvenantService.delete() then double-checks that the
     *   loaded amendment really belongs to the projectId in the URL. Without
     *   this project link, neither check would be possible.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /*
     * The amendment reference as written on the signed paper, for example
     * "AV-2026-01". It is the human key the client and the accountants quote.
     *
     * nullable = false: an amendment with no number cannot be traced back to a
     *   signed document, so the database refuses the row.
     * length = 50: maps to VARCHAR(50) in V9. Hibernate checks this at startup
     *   against the real column; a mismatch stops the application, which is the
     *   whole point of ddl-auto = validate.
     */
    @Column(nullable = false, length = 50)
    private String numero;

    /*
     * Free text describing what the amendment is about, for example
     * "add a second reporting screen". Optional on purpose: some amendments are
     * purely financial and the number plus the date are enough.
     * length = 500 matches VARCHAR(500) in V9: long enough for a full sentence,
     * short enough to stay readable in the frontend table.
     */
    @Column(length = 500)
    private String objet;

    /*
     * How much money this amendment adds to, or removes from, the contract.
     * A positive value is an increase, a negative value is a reduction.
     * AvenantService.create() adds it to the project's effective budget and
     * saves the result as the revised budget; delete() subtracts it again the
     * same way.
     *
     * WHY BigDecimal AND NOT double
     *   double cannot hold most decimal amounts exactly. In Java, 0.1 + 0.2
     *   gives 0.30000000000000004. On a contract worth hundreds of thousands
     *   those tiny errors add up, and the invoiced total stops matching the
     *   contract total. BigDecimal stores the digits exactly.
     *
     * precision = 15, scale = 2
     *   WHAT: 15 digits in total, 2 of them after the decimal point, which is
     *         NUMERIC(15,2) in PostgreSQL, up to 9 999 999 999 999.99.
     *   WHY : money always has exactly two decimals, and the database then
     *         rounds to that scale itself instead of trusting the caller.
     *   EXAMPLE: without the scale, a value such as 1000.005 would be stored
     *         with more decimals than any invoice can print, and the sum of the
     *         amendments would differ from the sum shown on the documents.
     *
     * nullable = false: an amendment with no amount has no effect on the
     *   budget, so it would be a meaningless row.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    /*
     * Impact of this amendment on the SOLD WORKLOAD, counted in JH
     * ("jours-homme" = person-days: one person working for one day).
     * This mirrors the "Workload avenants en JH" line of the company's Excel
     * review sheet, which is the reference document this application digitises.
     *
     * @Column(name = "workload_days")
     *   WHAT: pins the SQL column name.
     *   WHY : the column was added later, by V15__avenant_workload.sql, and the
     *         Java field is workloadDays (camel case) while the SQL column is
     *         workload_days (snake case).
     *   EXAMPLE: drop the name and Hibernate looks for a column called
     *         "workloaddays"; under ddl-auto = validate the application refuses
     *         to start with a schema validation error.
     *
     * Nullable on purpose: an amendment can change only the price and not the
     * amount of work (a discount, a currency correction). It can also be
     * negative, when the amendment removes work from the scope.
     *
     * precision = 10, scale = 2 -> NUMERIC(10,2): half-days such as 12.50 JH
     * are common, so we keep two decimals rather than a whole number.
     */
    @Column(name = "workload_days", precision = 10, scale = 2)
    private BigDecimal workloadDays;   // Impact on the sold workload, in person-days (JH)

    /*
     * The date the amendment was signed. Financial reports sort and filter on
     * it, and AvenantRepository.findActiveByProjectId() orders the list by it so
     * the frontend shows the contract history in chronological order.
     *
     * WHY LocalDate AND NOT Date OR LocalDateTime
     *   A signature date has no hour and no time zone. LocalDate stores exactly
     *   a calendar day, which is what the SQL type DATE holds.
     *   EXAMPLE: with a date-and-time type plus a time zone, an amendment
     *   signed on the 1st of the month at 00:30 in Tunis could be read back as
     *   the last day of the previous month by a server running in UTC, and it
     *   would land in the wrong accounting month.
     *
     * nullable = false: the signature date is what makes the amendment legally
     * dated, so the database requires it.
     */
    @Column(name = "date_avenant", nullable = false)
    private LocalDate dateAvenant;
}
