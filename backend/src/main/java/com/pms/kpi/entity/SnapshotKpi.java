package com.pms.kpi.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of snapshot_kpis: a frozen photo of a project's money and progress figures on one
 * date. Unlike everywhere else in PMS, these values are stored rather than recomputed — the
 * live KPI screen (KpiService.computeLive) ignores this table and always derives fresh figures.
 */
@Entity
// Hibernate would default to "snapshot_kpi" (singular); Flyway V8 created it plural.
@Table(name = "snapshot_kpis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// Plain @Builder, not @SuperBuilder: id/createdAt/deleted still come from BaseEntity/auditing.
@Builder
public class SnapshotKpi extends BaseEntity {

    // LAZY: a snapshot history can hold dozens of rows; JOIN FETCH in SnapshotKpiRepository
    // avoids N+1 (open-in-view is false, so a lazy touch after the transaction would throw).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // One live snapshot per project per day, enforced by the partial unique index
    // uk_kpi_project_date (V8); KpiService checks the same rule first for a clean error message.
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    // TND; precision/scale mirror NUMERIC(15,2) in V8 (ddl-auto=validate checks it at startup).
    // Null, not 0, when there is no workload plan yet.
    @Column(name = "budget_planifie", precision = 15, scale = 2)
    private BigDecimal budgetPlanifie;

    // TND; only validated timesheets count — an unapproved entry is a claim, not a fact.
    @Column(name = "budget_consome", precision = 15, scale = 2)
    private BigDecimal budgetConsome;

    // Cost consumed + planned cost of periods with no validated actual yet (not consumed + the
    // full plan, which would double-count the months already worked).
    @Column(name = "eac", precision = 15, scale = 2)
    private BigDecimal eac;

    // Budget − EAC; left null rather than a large negative number when no budget is recorded.
    @Column(name = "marge", precision = 15, scale = 2)
    private BigDecimal marge;

    // Fraction, not a percent (0.7532 = 75.32%); 4 decimals so NUMERIC(7,4) mirrors V8 without
    // losing precision, and the value can exceed 1 on a budget overrun.
    @Column(name = "taux_consommation", precision = 7, scale = 4)
    private BigDecimal tauxConsommation;

    // ── EVM indicators (F-AFF-13 §5) — added by Flyway V22 ──

    // Typed by the CdP at review, not derived: days spent don't prove work delivered. Feeds the
    // live screen via the latest snapshot; when empty, KpiService adds a warning instead.
    @Column(name = "ev_pct", precision = 5, scale = 2)
    private BigDecimal evPct;

    // LIVRE/VALIDE deliverables ÷ all planned × 100 — a fact to cross-check evPct's opinion
    // against. Null, not 0, when the project has no deliverable at all.
    @Column(name = "delivery_pct", precision = 5, scale = 2)
    private BigDecimal deliveryPct;

    // JH (jour-homme) from validated timesheets. 2 decimals so half/quarter days aren't rounded away.
    @Column(name = "consomme_jh", precision = 10, scale = 2)
    private BigDecimal consommeJh;

    // RAF (reste à faire): planned JH of periods with no validated actual yet, reusing the same
    // workload plan the project manager already maintains.
    @Column(name = "raf_jh", precision = 10, scale = 2)
    private BigDecimal rafJh;

    // Sold workload − consumed − RAF, measured against what was sold rather than the plan (which
    // can be edited and would hide the overrun). Null when no sold workload was recorded.
    @Column(name = "derive_jh", precision = 10, scale = 2)
    private BigDecimal deriveJh;

    // Budget × EV% — revenue earned by work done, not by invoices sent.
    @Column(name = "ca_production", precision = 15, scale = 2)
    private BigDecimal caProduction;

    // Sum of FACTURE/PAYE billing milestones, converted to TND with the project's exchange rate.
    @Column(name = "total_facture", precision = 15, scale = 2)
    private BigDecimal totalFacture;

    // Facture à établir = CA production − total already invoiced; negative means the client was
    // invoiced ahead of the work.
    @Column(name = "fae", precision = 15, scale = 2)
    private BigDecimal fae;

    // CA production − cost really consumed: the margin realised today, vs. "marge" above which
    // is the end-of-project forecast.
    @Column(name = "marge_actuelle", precision = 15, scale = 2)
    private BigDecimal margeActuelle;

    // margeActuelle ÷ caProduction; null when caProduction is 0 or missing. Same NUMERIC(7,4)
    // precision as taux_consommation, for the same reason.
    @Column(name = "marge_actuelle_pct", precision = 7, scale = 4)
    private BigDecimal margeActuellePct;

    // CdP's forecast at the time of review; kept per-snapshot rather than on the project so a
    // slipping date shows up in the history instead of just overwriting the previous answer.
    @Column(name = "date_fin_estimee")
    private LocalDate dateFinEstimee;

    // Review notes explaining the figures; length mirrors VARCHAR(2000) in V22 (default JPA
    // mapping is 255), also enforced client-side by SnapshotRequest's @Size.
    @Column(name = "faits_marquants", length = 2000)
    private String faitsMarquants;
}
