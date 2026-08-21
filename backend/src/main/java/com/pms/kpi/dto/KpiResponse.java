package com.pms.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record KpiResponse(
        Long snapshotId,             // null = calcul en temps réel
        Long projectId,
        String projectCode,
        LocalDate snapshotDate,      // null = calcul en temps réel
        BigDecimal budgetPlanifie,
        BigDecimal budgetConsome,
        BigDecimal eac,
        BigDecimal marge,
        BigDecimal tauxConsommation, // 0.0 – 1.0+ (ex. 0.75 = 75 %)
        // ── Indicateurs EVM (F-AFF-13 §5) ──
        BigDecimal evPct,            // Earned Value 0-100, saisi par le CdP au snapshot
        BigDecimal deliveryPct,      // livrés / planifiés × 100
        BigDecimal consommeJh,       // Σ imputations validées (JH)
        BigDecimal rafJh,            // reste à faire (JH)
        BigDecimal deriveJh,         // workload vendu − consommé − RAF
        BigDecimal caProduction,     // budget TND × EV %
        BigDecimal totalFacture,     // Σ jalons FACTURE/PAYE (TND)
        BigDecimal fae,              // CA production − total facturé (stock)
        BigDecimal margeActuelle,    // CA production − coût actuel (TND)
        BigDecimal margeActuellePct, // marge actuelle / CA production
        BigDecimal margeVenduePct,   // baseline : DI calculé si saisi, sinon fiche identification
        LocalDate dateFinEstimee,
        String faitsMarquants,
        List<String> warnings        // H-3 : utilisateurs sans tarif journalier (coût = 0)
) {}
