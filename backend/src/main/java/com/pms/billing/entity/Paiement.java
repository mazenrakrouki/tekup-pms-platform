package com.pms.billing.entity;

// JPA entity for one row of "paiements" (Flyway V9): money actually received from the client
// against one billing milestone, which can be settled in several instalments (several rows).
// This is the only thing that drives a milestone to PAYE: after every create()/delete(),
// PaiementService calls JalonService.recalculerStatut(jalon), which sums the rows via
// PaiementRepository.sumMontantByJalonId() and moves the milestone accordingly.
//
// Points at JalonFacturation, not Project — no project_id column here, on purpose: a payment
// only has meaning against an invoice, and duplicating the project id risks the two copies
// disagreeing after a bad update. Scoping (ADR-021) still works because the URL
// /api/projects/{projectId}/jalons/{jalonId}/paiements is checked by ProjectScopeInterceptor,
// and PaiementService re-verifies the milestone/payment chain before touching anything.

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// @Table pins the real name "paiements" (Hibernate would otherwise look for singular
// "paiement"). extends BaseEntity brings id/audit columns/soft delete: PaiementService.delete()
// only flags deleted=true, and the SUM used to decide PAYE filters on that flag too, so
// cancelling a payment immediately drops the milestone back out of PAYE while staying auditable.
@Entity
@Table(name = "paiements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Paiement extends BaseEntity {

    /*
     * Milestone this payment settles, in whole or in part. LAZY because @ManyToOne defaults to
     * EAGER, which would reload the same milestone once per payment (N+1). PaiementMapper only
     * ever reads jalon.id, which Hibernate already has on the proxy, so no JOIN FETCH is needed
     * here — unlike AvenantRepository/JalonFacturationRepository, which do read a real column.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jalon_id", nullable = false)
    private JalonFacturation jalon;

    /*
     * Amount received for this instalment. BigDecimal, not double, since the value is summed
     * across instalments and compared against the milestone amount to decide PAYE — a double
     * could compare as slightly short and leave a fully-paid milestone stuck unpaid forever.
     * Guarded twice: PaiementRequest's @NotNull/@Positive reject bad input before any DB call,
     * and V9's chk_pmt_montant CHECK blocks a negative value even on a raw SQL insert.
     */
    @Column(name = "montant_recu", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRecu;

    // Day the money arrived (value date), used to order instalments and to place cash in the
    // right accounting period. LocalDate, not a date-time type, since a value date has no
    // meaningful hour or time zone. NOT NULL: undated money can't be reconciled with a statement.
    @Column(name = "date_paiement", nullable = false)
    private LocalDate datePaiement;

    // Bank/accounting reference of the transfer, used to match this row to a bank statement
    // line. Optional: the reference is sometimes only known days after the money is seen.
    @Column(length = 255)
    private String reference;
}
