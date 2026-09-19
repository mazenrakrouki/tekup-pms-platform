package com.pms.billing.entity;

/*
 * ============================================================================
 * FILE: JalonFacturation.java -- JPA entity for one billing milestone.
 * ============================================================================
 *
 * WHAT THIS FILE IS
 *   One Java object here = one row of the SQL table "jalons_facturation",
 *   created by the Flyway migration V9__schema_billing.sql.
 *   A "jalon de facturation" (billing milestone) is one slice of the contract
 *   that the company is allowed to invoice when a step of the project is
 *   reached. Example: "30% on kick-off, 40% on delivery, 30% on acceptance"
 *   means three rows of this table for the same project.
 *   "JPA entity" means a plain Java class that Hibernate maps onto a database
 *   table, so we read and write rows as objects instead of writing SQL by hand.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular frontend (billing.component.ts)
 *     -> BillingController        /api/projects/{projectId}/jalons
 *     -> JalonService             permission check + all the business rules
 *     -> JalonFacturationRepository
 *     -> THIS ENTITY
 *     -> JalonMapper              copies this entity into JalonResponse, the DTO
 *                                 (Data Transfer Object = a small read-only
 *                                 object whose only job is to carry data out as
 *                                 JSON)
 *
 *   It is also read from OUTSIDE the billing package:
 *     - KpiService sums the "montant" of every milestone already FACTURE or
 *       PAYE to get the total invoiced, then computes FAE (work produced but
 *       not yet invoiced) = CA production - total invoiced.
 *     - AvenantService, after changing the budget, asks
 *       JalonService.recomputePrevuMontants() to re-price the milestones that
 *       are still only planned (marker H-4).
 *
 *   And it is the parent of Paiement: each payment received points back at one
 *   milestone, and JalonService.recalculerStatut() moves this row to PAYE once
 *   the payments add up to the milestone amount.
 *
 * WHY IT EXISTS / WHAT BREAKS WITHOUT IT
 *   Without this table the application would know the total price of a project
 *   but never WHEN that money may be asked for, nor how much of it has already
 *   been invoiced. The billing progress percentage, the FAE indicator, and the
 *   cash follow-up would all be impossible, and nothing would connect a payment
 *   to a step of the project.
 *
 * THE TWO SAFETY RULES AROUND "montant" (the reader should know these)
 *   1. "montant" is NOT typed in by a user. It is always computed by
 *      JalonService.computeMontant() as
 *          project effective budget x pourcentage / 100, rounded HALF_UP to 2
 *      so the milestone amounts can never drift away from the budget.
 *   2. Once the milestone leaves the PREVU state, the amount is frozen.
 *      recomputePrevuMontants() only touches rows still in PREVU, because an
 *      invoice already sent to the client must never change by itself.
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
 *   WHAT: tells Hibernate to manage this class as a database table.
 *   WHY : without it the class is an ordinary object and
 *         jalonRepository.save(...) fails with "Not a managed type".
 *
 * @Table(name = "jalons_facturation")
 *   WHAT: pins the exact table name.
 *   WHY : Hibernate would otherwise derive "jalonfacturation" from the class
 *         name, which is not the name V9 created.
 *   EXAMPLE: the application runs with spring.jpa.hibernate.ddl-auto =
 *   validate, so at startup Hibernate compares the entities with the real
 *   schema. A wrong table name stops the application before it serves anything,
 *   which is much better than failing later on a user request.
 *
 * extends BaseEntity
 *   WHAT: brings in id, created_at, updated_at, created_by, updated_by and the
 *         soft delete flag "deleted".
 *   WHY : a milestone is accounting data. We never physically delete the row;
 *         JalonService.delete() sets deleted = true and every query filters on
 *         deleted = false, so the history stays auditable.
 *
 * @Getter / @Setter, @NoArgsConstructor, @AllArgsConstructor, @Builder (Lombok)
 *   WHAT: generated methods and constructors.
 *   WHY : JPA needs the empty constructor to rebuild a row; the getters and
 *         setters are used by Hibernate and by the MapStruct mapper; the
 *         builder lets JalonService name each value at the call site instead of
 *         passing seven positional arguments.
 *   EXAMPLE: without @NoArgsConstructor, loading any milestone throws
 *         "No default constructor for entity".
 *   CAREFUL: Lombok's @Builder only covers fields declared in THIS class, not
 *         those inherited from BaseEntity, which is why no call ever does
 *         .id(...) or .deleted(...).
 */
@Entity
@Table(name = "jalons_facturation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JalonFacturation extends BaseEntity {

    /*
     * The project this milestone belongs to.
     *
     * @ManyToOne
     *   WHAT: many milestone rows point at one project row.
     *   WHY : a project normally has three to six milestones.
     *
     * fetch = FetchType.LAZY
     *   WHAT: do not load the whole Project when a milestone is read; Hibernate
     *         puts a placeholder here and only fetches on first real use.
     *   WHY : the default for @ManyToOne is EAGER, the opposite. With EAGER,
     *         reading the milestones of a project fires one extra SELECT on the
     *         projects table per milestone (the "N+1 queries" problem).
     *   EXAMPLE and the price we pay for it: the application sets
     *         spring.jpa.open-in-view = false, so the database session is closed
     *         once the service method returns. If JalonMapper read
     *         project.getCode() after that, Hibernate would throw
     *         LazyInitializationException and the endpoint would answer 500.
     *         That is exactly why JalonFacturationRepository writes
     *         "JOIN FETCH j.project": it loads the project in the same SELECT,
     *         deliberately, while the transaction is still open.
     *
     * @JoinColumn(name = "project_id", nullable = false)
     *   WHAT: the foreign key column, which can never be empty.
     *   WHY : V9 declares the constraint fk_jf_project on that exact column,
     *         and an index idx_jf_project on it for the "all milestones of this
     *         project" query.
     *   EXAMPLE: a milestone with no project would be an invoice line belonging
     *         to nobody; nullable = false makes the database refuse it even if
     *         application code forgot to set the project.
     *
     * SECURITY NOTE (ADR-021)
     *   Every billing URL is /api/projects/{projectId}/..., so
     *   ProjectScopeInterceptor checks BOTH the permission and the caller's
     *   right to that particular project before the service runs. On top of
     *   that, JalonService.checkBelongsToProject() verifies that the loaded
     *   milestone really belongs to the projectId in the URL. Without this
     *   field neither check could exist, and a user allowed on project 7 could
     *   read the milestone amounts of project 9 by guessing an id.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /*
     * The human name of the milestone shown in the billing screen and quoted in
     * the contract, for example "Acceptance of the delivery" or "Kick-off".
     * nullable = false, because a milestone nobody can name cannot be invoiced.
     * length = 255 matches VARCHAR(255) in V9.
     */
    @Column(nullable = false, length = 255)
    private String label;

    /*
     * The share of the project budget this milestone represents, as a percentage
     * (for example 30.00 means 30%).
     *
     * precision = 5, scale = 2
     *   WHAT: NUMERIC(5,2) in PostgreSQL: at most 5 digits, 2 after the point,
     *         so at most 999.99. Enough for 0.01 to 100.00.
     *   WHY : two decimals let three milestones split a budget evenly
     *         (33.33 / 33.33 / 33.34) without losing money to rounding.
     *
     * nullable = false, because the amount is derived from this percentage; an
     * empty percentage would make the amount impossible to compute.
     *
     * THREE LAYERS GUARD THIS VALUE, ON PURPOSE
     *   1. The DTO JalonRequest carries @Positive and @DecimalMax("100"), so a
     *      single bad value is rejected before any database call.
     *   2. V9 adds the check constraint chk_jf_pourcentage
     *      (pourcentage > 0 AND pourcentage <= 100) on the column itself, so
     *      even a direct SQL insert cannot store 150.
     *   3. JalonService.validatePourcentageSum() adds the rule the database
     *      cannot express row by row: the SUM of the non-deleted percentages of
     *      one project must not pass 100.
     *   EXAMPLE of why layer 3 is needed: four milestones of 30% each are all
     *   individually legal, but together they invoice 120% of the contract. The
     *   client would be billed more than the price he signed.
     */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal pourcentage;

    /*
     * The money value of this milestone, in the currency of the project.
     *
     * It is NEVER typed in by a user. JalonService.computeMontant() derives it:
     *     project.getEffectiveBudget() x pourcentage / 100,
     *     rounded HALF_UP to 2 decimals.
     * getEffectiveBudget() returns the revised budget when there is one, and
     * falls back to the initial budget otherwise, so signed amendments are
     * automatically taken into account.
     *
     * WHY THE VALUE IS STORED IN A COLUMN AND NOT RECOMPUTED ON EVERY READ
     *   Because an invoice that has left the company must not move again. Once
     *   the milestone reaches FACTURE or PAYE the stored figure is what the
     *   client actually received, so it is frozen: recomputePrevuMontants()
     *   (marker H-4) only rewrites rows still in PREVU.
     *   EXAMPLE of the breakage without this freeze: an amendment raises the
     *   budget in March; if the amounts were recomputed live, the invoice
     *   already sent in January would silently show a new figure, and the
     *   accounting would no longer match the paper invoice.
     *
     * NULLABLE on purpose: computeMontant() returns null when the project has
     * no budget yet, so a milestone can be planned before the price is known.
     * Every reader must handle that null. KpiService filters nulls out of its
     * sum, and JalonService.recalculerStatut() returns immediately when the
     * amount is null, because "is it fully paid?" has no meaning yet.
     *
     * precision = 15, scale = 2 -> NUMERIC(15,2), the same money format as the
     * project budget, so a percentage of the budget can never overflow.
     * BigDecimal and not double: double cannot hold decimal amounts exactly
     * (0.1 + 0.2 gives 0.30000000000000004 in Java), and those errors would
     * make the sum of the milestones differ from the contract total.
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal montant;

    /*
     * The date the milestone is EXPECTED to be invoiced: a forecast, used to
     * build the cash plan and to show the billing schedule in order.
     *
     * Nullable, because a milestone can be agreed ("30% on acceptance") long
     * before anybody can say on which day acceptance will happen.
     * JalonFacturationRepository handles that null explicitly with
     * "ORDER BY j.datePrevue NULLS LAST, j.id", so undated milestones fall to
     * the bottom of the list instead of to the top.
     * EXAMPLE without NULLS LAST: PostgreSQL sorts NULLs last by default on
     * ascending order, but the order of undated rows between themselves would
     * still be unspecified; adding ", j.id" makes the list stable, so the
     * frontend does not reshuffle rows between two identical requests.
     *
     * LocalDate, not a date-and-time type: a billing date is a calendar day with
     * no hour and no time zone. With a time zone, a milestone planned for the
     * 1st at 00:30 in Tunis could read back as the previous month on a UTC
     * server and land in the wrong accounting period.
     */
    @Column(name = "date_prevue")
    private LocalDate datePrevue;

    /*
     * The date the invoice was ACTUALLY issued. It stays null while the
     * milestone is only planned, and JalonService.facturer() fills it at the
     * same moment it switches the status to FACTURE.
     *
     * It is not only information. JalonService.recalculerStatut() reads it when
     * a payment is cancelled: if the milestone was PAYE and the remaining
     * payments no longer cover the amount, it falls back to FACTURE when this
     * date exists, and to PREVU when it does not.
     * EXAMPLE of what that avoids: a payment recorded by mistake, then deleted,
     * would otherwise leave the milestone marked PAYE for money that never
     * arrived, and the cash report would be wrong.
     *
     * @Column(name = "date_facture") is required because the Java field is
     * dateFacture while the SQL column is date_facture; without it Hibernate
     * looks for "datefacture" and startup validation fails.
     */
    @Column(name = "date_facture")
    private LocalDate dateFacture;

    /*
     * Where the milestone stands: PREVU (planned), FACTURE (invoiced) or PAYE
     * (paid). See JalonStatut.java for what each value allows.
     *
     * @Enumerated(EnumType.STRING)
     *   WHAT: store the NAME of the enum value in the column ("FACTURE"), not
     *         its position in the list.
     *   WHY : EnumType.ORDINAL is the JPA default and stores 0, 1, 2 instead.
     *         The position is not part of the data model, so any future edit of
     *         the enum silently rewrites the meaning of rows already saved.
     *   EXAMPLE: with ORDINAL, inserting a new value ANNULE at the top of
     *         JalonStatut would turn every row holding 0 from PREVU into
     *         ANNULE. Thousands of milestones would change state overnight with
     *         no migration and no error message. With STRING, the stored text
     *         "PREVU" keeps meaning PREVU forever.
     *         There is a second safety net here: V9 declares the constraint
     *         chk_jf_statut CHECK (statut IN ('PREVU','FACTURE','PAYE')) on a
     *         VARCHAR(20) column, so a numeric ordinal would be rejected by the
     *         database as well.
     *
     * @Column(nullable = false, length = 20)
     *   nullable = false: a milestone with no state could not be filtered by any
     *     report, and JalonService compares the status on every operation.
     *   length = 20: matches VARCHAR(20) in V9, wide enough for the longest
     *     value name.
     *
     * @Builder.Default
     *   WHAT: tells Lombok that the builder must keep the initial value
     *         JalonStatut.PREVU when the caller does not set the status.
     *   WHY : this is a real trap. Lombok's generated builder IGNORES field
     *         initializers unless this annotation is present.
     *   EXAMPLE: JalonService.create() never calls .statut(...). Without
     *         @Builder.Default the built object would carry statut = null, the
     *         INSERT would break the NOT NULL constraint, and creating any
     *         milestone would fail at runtime with a constraint violation
     *         instead of simply starting as "planned".
     *
     * WHY PREVU IS THE STARTING VALUE
     *   Every milestone begins as a plan. The state only moves forward through
     *   controlled service methods: facturer() requires PREVU, and
     *   PaiementService refuses to record money while the milestone is still
     *   PREVU, because you cannot be paid for an invoice you never sent.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JalonStatut statut = JalonStatut.PREVU;
}
