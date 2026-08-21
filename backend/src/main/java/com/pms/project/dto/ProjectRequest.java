package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProjectRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 255) String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        @PositiveOrZero BigDecimal initialBudget,
        Long directorId,
        Long chefProjetId,

        // ── Fiche d'identification (modèle Excel) ───────────────
        @Size(max = 100) String contractId,
        @Size(max = 255) String client,
        @Size(max = 255) String funder,
        BusinessModel businessModel,
        EngagementType engagementType,
        @Size(max = 10) String currency,
        @PositiveOrZero BigDecimal exchangeRateToTnd,
        @PositiveOrZero BigDecimal licenseSubcontractBudget,
        @PositiveOrZero BigDecimal soldWorkloadDays,
        @PositiveOrZero BigDecimal warrantyWorkloadDays,
        @PositiveOrZero BigDecimal penaltyProvision,
        @DecimalMin("-1.0") @DecimalMax("1.0") BigDecimal margeNetteVendue // ex. 0.4412 = 44,12 %
) {}
