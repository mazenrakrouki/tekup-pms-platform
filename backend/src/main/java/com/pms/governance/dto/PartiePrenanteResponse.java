package com.pms.governance.dto;

import com.pms.governance.entity.NiveauRisque;

public record PartiePrenanteResponse(
        Long id,
        Long projectId,
        String projectCode,
        String nom,
        String fonction,
        String email,
        String telephone,
        NiveauRisque influence,
        NiveauRisque interet
) {}
