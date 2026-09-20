package com.pms.billing.entity;

// JPA entity for one row of "jalons_facturation" (Flyway V9): one billing milestone, a slice of
// the contract invoiced when a project step is reached (e.g. "30% kick-off / 40% delivery / 30%
// acceptance" = three rows for one project). Without this table the app would know a project's
// total price but never when it may be invoiced or how much already has been.
//
// Also used outside this package: KpiService sums FACTURE/PAYE milestones for FAE (work
// produced but not yet invoiced); AvenantService triggers recomputePrevuMontants() after a
// budget change (marker H-4); and it's the parent of Paiement, with
// JalonService.recalculerStatut() moving a row to PAYE once payments cover the amount.
//
// "montant" is never typed in — JalonService.computeMontant() derives it from the effective
// budget x pourcentage, and once a milestone leaves PREVU the amount is frozen for good.

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// @Table pins the real name (Hibernate would otherwise derive "jalonfacturation"). extends
// BaseEntity brings id/audit columns/soft delete: JalonService.delete() only flags deleted=true
// so billing history stays auditable. @Builder covers only this class's fields, not BaseEntity's.
@Entity
@Table(name = "jalons_facturation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JalonFacturation extends BaseEntity {

    /*
     * Project this milestone belongs to. LAZY because @ManyToOne defaults to EAGER, which would
     * fire one extra SELECT per milestone (N+1). Since open-in-view=false closes the session
     * once the service returns, JalonFacturationRepository uses "JOIN FETCH j.project" so it's
     * already loaded while the transaction is open.
     *
     * Also what makes ADR-021 scoping possible: ProjectScopeInterceptor checks the URL's
     * projectId, and JalonService.checkBelongsToProject() re-verifies the loaded milestone
     * really belongs to it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Milestone name shown in the billing screen and quoted in the contract ("Kick-off").
    // NOT NULL: an unnamed milestone can't be invoiced. length=255 matches VARCHAR(255) in V9.
    @Column(nullable = false, length = 255)
    private String label;

    /*
     * Share of the project budget this milestone represents, as a percentage (30.00 = 30%).
     * precision=5, scale=2 -> NUMERIC(5,2), two decimals so three milestones can split a budget
     * evenly (33.33/33.33/33.34) without losing money to rounding.
     *
     * Guarded three times on purpose: JalonRequest's @Positive/@DecimalMax reject a single bad
     * value before any DB call; V9's chk_jf_pourcentage constraint blocks it even on a raw SQL
     * insert; and JalonService.validatePourcentageSum() enforces the cross-row rule the database
     * can't express — that all of a project's milestones together stay under 100%.
     */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal pourcentage;

    /*
     * Money value of this milestone. Never typed in — JalonService.computeMontant() derives it
     * as effectiveBudget x pourcentage / 100 (HALF_UP, 2 decimals), and it's stored rather than
     * recomputed on read because an invoice already sent must never move again:
     * recomputePrevuMontants (marker H-4) only rewrites rows still in PREVU.
     *
     * Nullable: computeMontant() returns null when the project has no budget yet — KpiService
     * filters nulls out of its sum, and recalculerStatut() returns early on a null amount.
     * precision=15, scale=2 matches the project budget's NUMERIC(15,2).
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal montant;

    /*
     * Date the milestone is EXPECTED to be invoiced — a forecast, used for the cash plan.
     * Nullable, since a milestone can be agreed long before its trigger date is known.
     * JalonFacturationRepository sorts with "ORDER BY j.datePrevue NULLS LAST, j.id" so
     * undated rows sink to the bottom and the list order stays stable between requests.
     */
    @Column(name = "date_prevue")
    private LocalDate datePrevue;

    /*
     * Date the invoice was ACTUALLY issued; null while only planned, set by JalonService.facturer()
     * at the same time as the FACTURE status. Also read by recalculerStatut() when a payment is
     * cancelled: falls back to FACTURE if this is set, otherwise to PREVU — avoiding a milestone
     * stuck marked PAYE for money that never really arrived.
     */
    @Column(name = "date_facture")
    private LocalDate dateFacture;

    /*
     * Milestone state: PREVU (planned), FACTURE (invoiced), PAYE (paid) — see JalonStatut.
     *
     * @Enumerated(EnumType.STRING) stores the name ("FACTURE"), not the ordinal position:
     * ORDINAL would silently reinterpret every stored row if the enum's order ever changed.
     * V9's chk_jf_statut CHECK constraint backs this up at the DB level too.
     *
     * @Builder.Default is required because Lombok's builder otherwise ignores field
     * initializers — without it, JalonService.create() (which never calls .statut(...)) would
     * build a null status and hit the NOT NULL constraint at insert time.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JalonStatut statut = JalonStatut.PREVU;
}
