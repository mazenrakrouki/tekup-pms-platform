package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProjectResponse(
        Long id,
        String code,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal initialBudget,
        BigDecimal revisedBudget,
        BigDecimal effectiveBudget,
        Long directorId,
        String directorName,
        Long chefProjetId,
        String chefProjetName,

        // ── Fiche d'identification (modèle Excel) ───────────────
        String contractId,
        String client,
        String funder,
        BusinessModel businessModel,
        EngagementType engagementType,
        String currency,
        BigDecimal exchangeRateToTnd,
        BigDecimal licenseSubcontractBudget,
        BigDecimal soldWorkloadDays,
        BigDecimal warrantyWorkloadDays,
        BigDecimal penaltyProvision,

        boolean archived,

        // ── Champs calculés (non stockés) ───────────────────────
        Long durationDays,
        BigDecimal budgetTnd,
        BigDecimal pprTnd
) {}
