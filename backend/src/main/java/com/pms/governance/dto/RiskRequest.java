package com.pms.governance.dto;

// JSON body for creating/editing a risk. Deliberately excludes id, projectId,
// deleted, createdAt so a client can never move or hide a risk via the body.

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Immutable record so the object the validator approved is exactly what the service reads.
 * projectId comes from the URL, not the body; ProjectScopeInterceptor (ADR-021) checks it there.
 */
public record RiskRequest(
        // VARCHAR(1000) NOT NULL in V11; blank text would make the register entry useless.
        @NotBlank String description,

        // Enum, not a free String, so an invalid level is a clean 400 instead of a DB constraint error.
        @NotNull NiveauRisque probabilite,

        // Kept separate from probabilite (not multiplied into one score) so the UI grid shows both axes.
        @NotNull NiveauRisque impact,

        // Nullable in V11: a risk is often recorded before a mitigation plan exists.
        String planMitigation,

        // Unlike Livrable/DemandeChangement, statut has no state machine, so it travels in the body as-is.
        @NotNull StatutRisque statut
) {}
