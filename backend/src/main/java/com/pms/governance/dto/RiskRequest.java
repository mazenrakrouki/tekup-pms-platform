package com.pms.governance.dto;

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RiskRequest(
        @NotBlank String description,
        @NotNull NiveauRisque probabilite,
        @NotNull NiveauRisque impact,
        String planMitigation,
        @NotNull StatutRisque statut
) {}
