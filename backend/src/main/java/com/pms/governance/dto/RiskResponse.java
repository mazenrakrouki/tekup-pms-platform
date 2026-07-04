package com.pms.governance.dto;

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;

public record RiskResponse(
        Long id,
        Long projectId,
        String projectCode,
        String description,
        NiveauRisque probabilite,
        NiveauRisque impact,
        String planMitigation,
        StatutRisque statut
) {}
