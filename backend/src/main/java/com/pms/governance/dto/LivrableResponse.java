package com.pms.governance.dto;

// JSON the API sends back for one deliverable — read-side mirror of LivrableRequest. Flat on
// purpose: the entity's lazy Project relation and BaseEntity audit columns (deleted, createdAt,
// createdBy) have no business being published.

import com.pms.governance.entity.StatutLivrable;

import java.time.LocalDate;

public record LivrableResponse(
        Long id,
        Long projectId,
        String projectCode,
        String titre,
        String description,
        LocalDate dateEcheance,
        // EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE. Drives the frontend's action buttons (e.g.
        // "Valider" shown only when LIVRE), mirroring the rule LivrableService.valider() enforces.
        StatutLivrable statut
) {}
