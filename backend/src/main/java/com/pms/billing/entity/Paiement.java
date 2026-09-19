package com.pms.billing.entity;

/*
 * ============================================================================
 * FILE: Paiement.java -- JPA entity for one payment received from the client.
 * ============================================================================
 *
 * WHAT THIS FILE IS
 *   One Java object here = one row of the SQL table "paiements", created by the
 *   Flyway migration V9__schema_billing.sql.
 *   A "paiement" is money that actually arrived, recorded against one billing
 *   milestone. A milestone can be settled in several instalments, so one
 *   milestone may have several rows of this table.
 *   "JPA entity" means a plain Java class that Hibernate maps onto a database
 *   table, so we read and write rows as objects instead of writing SQL by hand.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular frontend (billing screen)
 *     -> BillingController   /api/projects/{projectId}/jalons/{jalonId}/paiements
 *     -> PaiementService     permission check + the business rules
 *     -> PaiementRepository  loads, saves and SUMS the rows
 *     -> THIS ENTITY
 *     -> PaiementMapper      copies this entity into PaiementResponse, the DTO
 *                            (Data Transfer Object = a small read-only object
 *                            whose only job is to carry data out as JSON)
 *
 *   After every create() and delete(), PaiementService calls
 *   JalonService.recalculerStatut(jalon). That method asks
 *   PaiementRepository.sumMontantByJalonId() for the total received and moves
 *   the milestone to PAYE when the total reaches the milestone amount, or back
 *   to FACTURE / PREVU when a payment is cancelled and the total drops again.
 *   So this entity is the only thing that drives a milestone into PAYE.
 *
 * WHY IT EXISTS / WHAT BREAKS WITHOUT IT
 *   Without this table the application could say what has been invoiced but
 *   never what has been cashed. The PAYE state could not be computed, partial
 *   payments could not be represented at all, and nobody could tell an invoice
 *   waiting for money from one already settled. The bank reference kept here is
 *   also what lets the accountants match a line of the bank statement with a
 *   project.
 *
 * ONE DESIGN POINT WORTH DEFENDING
 *   This entity points at JalonFacturation, NOT at Project. There is no
 *   project_id column here. The project is reached through jalon.getProject().
 *   WHY: a payment only has meaning against an invoice. Storing the project id
 *   as well would duplicate the information, and the two copies could disagree
 *   after a bad update, which is exactly the kind of silent inconsistency an
 *   accounting table must not allow.
 *   HOW SCOPING STILL WORKS (ADR-021): the URL is still
 *   /api/projects/{projectId}/jalons/{jalonId}/paiements, so
 *   ProjectScopeInterceptor checks BOTH the permission and the caller's right
 *   to that project. PaiementService then re-checks that the milestone really
 *   belongs to that projectId, and that the payment really belongs to that
 *   jalonId, before touching anything.
 */

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
 *   WHY : without it, paiementRepository.save(...) fails with
 *         "Not a managed type: class com.pms.billing.entity.Paiement".
 *
 * @Table(name = "paiements")
 *   WHAT: pins the exact table name.
 *   WHY : Hibernate would otherwise look for "paiement" (singular), which does
 *         not exist. The application runs with spring.jpa.hibernate.ddl-auto =
 *         validate, so a wrong name stops the whole application at startup
 *         rather than failing later on a user request.
 *
 * extends BaseEntity
 *   WHAT: brings in id, created_at, updated_at, created_by, updated_by and the
 *         soft delete flag "deleted".
 *   WHY : a payment is accounting data, so it is never physically removed.
 *         PaiementService.delete() sets deleted = true, and every query in
 *         PaiementRepository filters on deleted = false.
 *   EXAMPLE of why the soft delete matters here: the SUM used to decide whether
 *   a milestone is fully paid also filters deleted = false, so cancelling a
 *   payment immediately lowers the total and the milestone falls back out of
 *   PAYE, while the cancelled row stays visible for the audit.
 *   The created_by column also records WHO entered the payment, which is what
 *   an auditor asks first when an amount looks wrong.
 *
 * @Getter / @Setter, @NoArgsConstructor, @AllArgsConstructor, @Builder (Lombok)
 *   WHAT: generated methods and constructors.
 *   WHY : JPA requires the empty constructor to rebuild a row from the
 *         database; the getters and setters are used by Hibernate and by the
 *         MapStruct mapper; the builder lets PaiementService name each value,
 *         which matters here because montantRecu and datePaiement are easy to
 *         confuse in a positional constructor.
 *   CAREFUL: Lombok's @Builder covers only the fields declared in THIS class,
 *         not those inherited from BaseEntity, which is why no call ever does
 *         .id(...) or .deleted(...).
 */
@Entity
@Table(name = "paiements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Paiement extends BaseEntity {

    /*
     * The billing milestone this payment settles, in whole or in part.
     *
     * @ManyToOne
     *   WHAT: many payment rows point at one milestone row.
     *   WHY : a client may pay one invoice in two or three instalments, and
     *         each instalment is one row here.
     *
     * fetch = FetchType.LAZY
     *   WHAT: do not load the whole JalonFacturation when a payment is read;
     *         Hibernate puts a placeholder here and only fetches on first use.
     *   WHY : the default for @ManyToOne is EAGER, the opposite. With EAGER,
     *         listing the payments of a milestone fires one extra SELECT per
     *         payment on jalons_facturation to load the very same milestone
     *         over and over (the "N+1 queries" problem).
     *   EXAMPLE and the price of LAZY: because the application sets
     *         spring.jpa.open-in-view = false, the database session closes when
     *         the service method returns. After that point, reading a real
     *         field of the milestone, such as paiement.getJalon().getLabel(),
     *         throws LazyInitializationException. PaiementMapper
     *         only needs jalon.id, which Hibernate already holds in the
     *         placeholder without any query, so nothing extra is loaded for the
     *         JSON response. Where the real milestone IS needed, the service
     *         loads it itself first through jalonService.loadJalon(...).
     *
     * @JoinColumn(name = "jalon_id", nullable = false)
     *   WHAT: names the foreign key column and forbids an empty value.
     *   WHY : V9 declares the constraint fk_pmt_jalon on that exact column, and
     *         the index idx_pmt_jalon on it, because every read here is "all
     *         payments of this milestone" and the SUM used by
     *         recalculerStatut() runs on the same filter. Without the index
     *         that SUM would scan the whole payments table on each call.
     *   EXAMPLE: a payment with no milestone would be cash belonging to no
     *         invoice. It would never appear in any milestone total, so the
     *         milestone would stay unpaid forever while the money sat in the
     *         database. nullable = false makes the database itself refuse that
     *         row.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jalon_id", nullable = false)
    private JalonFacturation jalon;

    /*
     * The amount actually received for this instalment.
     *
     * @Column(name = "montant_recu")
     *   WHAT: pins the SQL column name.
     *   WHY : the Java field is montantRecu (camel case) and the column is
     *         montant_recu (snake case).
     *   EXAMPLE: without the name, Hibernate looks for "montantrecu" and, under
     *         ddl-auto = validate, the application refuses to start.
     *
     * WHY BigDecimal AND NOT double
     *   double cannot hold most decimal amounts exactly: in Java 0.1 + 0.2
     *   gives 0.30000000000000004. Here the value is summed across instalments
     *   and compared with the milestone amount to decide PAYE. With double, a
     *   milestone paid in full could compare as very slightly short and stay
     *   marked unpaid forever. BigDecimal stores the digits exactly.
     *
     * precision = 15, scale = 2 -> NUMERIC(15,2): the same money format as the
     *   milestone amount and the project budget, so the comparison in
     *   recalculerStatut() is between two values of identical shape.
     *
     * nullable = false: a payment with no amount cannot move any total, so it
     *   would be a row that pretends money arrived without saying how much.
     *
     * TWO EXTRA GUARDS ON THIS VALUE
     *   - the DTO PaiementRequest carries @NotNull and @Positive, so a zero or
     *     negative amount is rejected before any database call;
     *   - V9 adds the check constraint chk_pmt_montant CHECK (montant_recu > 0)
     *     on the column, so even a direct SQL insert cannot store a negative
     *     payment.
     *   EXAMPLE of why the database guard is kept as well: a negative payment
     *   would lower the total received and silently push a milestone back out
     *   of PAYE, which is a refund, not a payment, and has no place in this
     *   table.
     */
    @Column(name = "montant_recu", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRecu;

    /*
     * The day the money arrived. PaiementRepository.findActiveByJalonId()
     * orders the payments by this date, so the frontend shows the instalments
     * in the order they were received.
     *
     * WHY LocalDate AND NOT a date-and-time type
     *   A value date has no hour and no time zone; it is a calendar day, which
     *   is exactly what the SQL type DATE holds.
     *   EXAMPLE: with a date-and-time type plus a time zone, a payment received
     *   on the 1st at 00:30 in Tunis could be read back as the last day of the
     *   previous month by a server running in UTC, and the cash of the month
     *   would be reported in the wrong period.
     *
     * nullable = false: a payment with no date cannot be placed in any
     * accounting period, nor matched against a bank statement.
     *
     * @Column(name = "date_paiement") is required because the Java field is
     * datePaiement while the SQL column is date_paiement.
     */
    @Column(name = "date_paiement", nullable = false)
    private LocalDate datePaiement;

    /*
     * The bank or accounting reference of the transfer, for example a wire
     * transfer number or a cheque number. It is what lets an accountant match
     * this row with a line of the bank statement.
     *
     * Optional on purpose: the reference is sometimes only known days after the
     * money is seen on the account, and we do not want to block the recording
     * of the payment until then.
     * length = 255 matches VARCHAR(255) in V9, which is wide enough for any
     * bank reference format.
     * No @Column(name = ...) is needed here: the field name and the column name
     * are the same single word, "reference".
     */
    @Column(length = 255)
    private String reference;
}
