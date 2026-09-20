package com.pms.billing.entity;

// JPA entity for one row of the "avenants" table (Flyway V9, extended by V15 with
// workload_days): a signed contract amendment that raises or lowers a project's agreed price.
// This is the only record of price changes after the initial contract — without it a project
// would keep only its very first budget, and every indicator built on the budget would be stale.
//
// AvenantService.create() adds this amount to the project's effective budget and writes it as
// the revised budget; create()/delete() then call JalonService.recomputePrevuMontants() to
// re-price still-planned billing milestones (marker H-4).
//
// Sister entities: JalonFacturation (a billing milestone), Paiement (money received against
// one), JalonStatut (the milestone's three states). An avenant moves the budget; milestones
// re-slice it.

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// @Table pins the real name "avenants" (Hibernate would otherwise derive singular "avenant"),
// checked at startup by ddl-auto=validate. extends BaseEntity brings id/audit columns/soft
// delete ("deleted"): amendments are never physically removed, only flagged, so accounting
// history stays intact. @Builder only covers fields declared in this class, not BaseEntity's —
// that's why nothing calls .id(...) or .deleted(...) on the builder.
@Entity
@Table(name = "avenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Avenant extends BaseEntity {

    /*
     * Project this amendment belongs to. LAZY because @ManyToOne defaults to EAGER, which would
     * fire one extra SELECT per amendment (N+1). Since open-in-view=false closes the session
     * once the service returns, AvenantRepository uses "JOIN FETCH a.project" so the project is
     * already loaded while the transaction is still open.
     *
     * Also what makes ADR-021 scoping possible: the URL is /api/projects/{projectId}/avenants,
     * checked by ProjectScopeInterceptor, and AvenantService.delete() double-checks the loaded
     * amendment really belongs to that projectId.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Amendment reference on the signed paper ("AV-2026-01"); NOT NULL since an untraceable
    // amendment is meaningless. length=50 matches VARCHAR(50) in V9.
    @Column(nullable = false, length = 50)
    private String numero;

    // Free-text purpose of the amendment. Optional: some amendments are purely financial.
    @Column(length = 500)
    private String objet;

    /*
     * Amount this amendment adds to (or removes from) the contract; positive grows it, negative
     * shrinks it. BigDecimal, not double, to avoid decimal drift on large contract sums.
     * precision=15, scale=2 -> NUMERIC(15,2), matching how invoices are actually printed.
     * NOT NULL: an amendment with no amount has no effect on the budget.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    /*
     * Impact on the sold workload, in JH (jours-homme / person-days) — mirrors the "Workload
     * avenants en JH" line of the reference Excel sheet. @Column(name=...) is needed because the
     * SQL column (added later by V15) is snake_case while the field is camelCase. Nullable: an
     * amendment can be price-only, and can be negative when work is removed from scope.
     */
    @Column(name = "workload_days", precision = 10, scale = 2)
    private BigDecimal workloadDays;

    // Signature date. AvenantRepository.findActiveByProjectId orders by it so the frontend shows
    // contract history chronologically. LocalDate, not a date-time type, since a signature has
    // no meaningful hour or time zone. NOT NULL: it's what makes the amendment legally dated.
    @Column(name = "date_avenant", nullable = false)
    private LocalDate dateAvenant;
}
