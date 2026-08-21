package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import com.pms.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "initial_budget", precision = 15, scale = 2)
    private BigDecimal initialBudget;

    @Column(name = "revised_budget", precision = 15, scale = 2)
    private BigDecimal revisedBudget;

    // ── Fiche d'identification (modèle Excel) ────────────────────
    @Column(name = "contract_id", length = 100)
    private String contractId;

    @Column(length = 255)
    private String client;

    @Column(length = 255)
    private String funder;                 // Bailleur de fonds

    @Enumerated(EnumType.STRING)
    @Column(name = "business_model", length = 20)
    private BusinessModel businessModel;   // SEUL | GROUPEMENT

    @Enumerated(EnumType.STRING)
    @Column(name = "engagement_type", length = 20)
    private EngagementType engagementType; // FORFAIT | REGIE

    @Column(length = 10)
    @Builder.Default
    private String currency = "TND";       // Devise projet (FCFA, TND, EUR…)

    @Column(name = "exchange_rate_to_tnd", precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal exchangeRateToTnd = BigDecimal.ONE;

    @Column(name = "license_subcontract_budget", precision = 15, scale = 2)
    private BigDecimal licenseSubcontractBudget;

    @Column(name = "sold_workload_days", precision = 10, scale = 2)
    private BigDecimal soldWorkloadDays;   // Workload vendu (JH)

    @Column(name = "warranty_workload_days", precision = 10, scale = 2)
    private BigDecimal warrantyWorkloadDays; // Workload garantie (JH)

    @Column(name = "penalty_provision", precision = 15, scale = 2)
    private BigDecimal penaltyProvision;   // PPP

    @Column(name = "marge_nette_vendue", precision = 7, scale = 4)
    private BigDecimal margeNetteVendue;   // Baseline commerciale (ex. 0.4412 = 44,12 %) ; le DI calculé prime s'il existe

    @Column(nullable = false)
    @Builder.Default
    private boolean archived = false;      // projet archivé (terminé, hors listes actives)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "director_id")
    private User director;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_projet_id")
    private User chefProjet;

    public BigDecimal getEffectiveBudget() {
        return revisedBudget != null ? revisedBudget : initialBudget;
    }

    /** Durée du contrat en jours (bornes incluses, comme la Fiche Excel). */
    public Long getDurationDays() {
        if (startDate == null || endDate == null) return null;
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /** Budget effectif converti en TND (devise pragmatique : budget × taux). */
    public BigDecimal getBudgetTnd() {
        BigDecimal eff = getEffectiveBudget();
        if (eff == null) return null;
        BigDecimal rate = exchangeRateToTnd != null ? exchangeRateToTnd : BigDecimal.ONE;
        return eff.multiply(rate);
    }

    /** Provision Pour Risques = budget TND × 5 % (Fiche identification). */
    public BigDecimal getPprTnd() {
        BigDecimal tnd = getBudgetTnd();
        return tnd != null ? tnd.multiply(new BigDecimal("0.05")) : null;
    }
}
