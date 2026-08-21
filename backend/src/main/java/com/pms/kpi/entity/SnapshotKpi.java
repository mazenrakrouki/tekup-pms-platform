package com.pms.kpi.entity;

import com.pms.project.entity.Project;
import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "snapshot_kpis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SnapshotKpi extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "budget_planifie", precision = 15, scale = 2)
    private BigDecimal budgetPlanifie;

    @Column(name = "budget_consome", precision = 15, scale = 2)
    private BigDecimal budgetConsome;

    @Column(name = "eac", precision = 15, scale = 2)
    private BigDecimal eac;

    @Column(name = "marge", precision = 15, scale = 2)
    private BigDecimal marge;

    @Column(name = "taux_consommation", precision = 7, scale = 4)
    private BigDecimal tauxConsommation;

    // ── Indicateurs EVM (F-AFF-13 §5) ─────────────────────────────

    @Column(name = "ev_pct", precision = 5, scale = 2)
    private BigDecimal evPct;              // avancement Earned Value (0-100), saisi par le CdP

    @Column(name = "delivery_pct", precision = 5, scale = 2)
    private BigDecimal deliveryPct;        // livrés / planifiés × 100

    @Column(name = "consomme_jh", precision = 10, scale = 2)
    private BigDecimal consommeJh;         // Σ imputations validées

    @Column(name = "raf_jh", precision = 10, scale = 2)
    private BigDecimal rafJh;              // reste à faire (plan de charge)

    @Column(name = "derive_jh", precision = 10, scale = 2)
    private BigDecimal deriveJh;           // workload vendu − consommé − RAF

    @Column(name = "ca_production", precision = 15, scale = 2)
    private BigDecimal caProduction;       // budget TND × EV %

    @Column(name = "total_facture", precision = 15, scale = 2)
    private BigDecimal totalFacture;       // Σ jalons facturés/réglés (TND)

    @Column(name = "fae", precision = 15, scale = 2)
    private BigDecimal fae;                // CA production − total facturé

    @Column(name = "marge_actuelle", precision = 15, scale = 2)
    private BigDecimal margeActuelle;      // CA production − coût actuel

    @Column(name = "marge_actuelle_pct", precision = 7, scale = 4)
    private BigDecimal margeActuellePct;   // marge actuelle / CA production

    @Column(name = "date_fin_estimee")
    private LocalDate dateFinEstimee;

    @Column(name = "faits_marquants", length = 2000)
    private String faitsMarquants;
}
