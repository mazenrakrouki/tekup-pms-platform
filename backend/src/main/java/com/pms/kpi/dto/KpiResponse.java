package com.pms.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record KpiResponse(
        Long snapshotId,          // null = calcul en temps réel
        Long projectId,
        String projectCode,
        LocalDate snapshotDate,   // null = calcul en temps réel
        BigDecimal budgetPlanifie,
        BigDecimal budgetConsome,
        BigDecimal eac,
        BigDecimal marge,
        BigDecimal tauxConsommation  // 0.0 – 1.0+ (ex. 0.75 = 75 %)
) {}
