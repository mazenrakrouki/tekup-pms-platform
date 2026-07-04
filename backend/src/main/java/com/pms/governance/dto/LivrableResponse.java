package com.pms.governance.dto;

import com.pms.governance.entity.StatutLivrable;

import java.time.LocalDate;

public record LivrableResponse(
        Long id,
        Long projectId,
        String projectCode,
        String titre,
        String description,
        LocalDate dateEcheance,
        StatutLivrable statut
) {}
