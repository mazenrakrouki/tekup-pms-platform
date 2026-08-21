package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
        BigDecimal margeNetteVendue,

        boolean archived,

        // ── Audit ───────────────────────────────────────────────
        LocalDateTime createdAt,

        // ── Champs calculés (non stockés) ───────────────────────
        Long durationDays,
        BigDecimal budgetTnd,
        BigDecimal pprTnd
) {

    /**
     * Copie expurgée de toute donnée financière (montants et marge).
     * BR-050 : les rôles sans VIEW_KPI (ex. DEVELOPPEUR) sont cloisonnés du financier.
     * Les charges en JH (soldWorkloadDays / warrantyWorkloadDays) ne sont pas financières et restent visibles.
     */
    public ProjectResponse withoutFinancials() {
        return new ProjectResponse(
                id, code, name, description, status, startDate, endDate,
                null, null, null,                       // initialBudget, revisedBudget, effectiveBudget
                directorId, directorName, chefProjetId, chefProjetName,
                contractId, client, funder, businessModel, engagementType, currency, exchangeRateToTnd,
                null,                                   // licenseSubcontractBudget
                soldWorkloadDays, warrantyWorkloadDays,
                null,                                   // penaltyProvision
                null,                                   // margeNetteVendue
                archived, createdAt, durationDays,
                null, null                              // budgetTnd, pprTnd
        );
    }
}
