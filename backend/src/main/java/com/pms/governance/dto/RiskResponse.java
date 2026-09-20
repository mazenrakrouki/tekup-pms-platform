package com.pms.governance.dto;

// Read side of the risk register, the mirror of RiskRequest. Returning the Risk entity directly
// would risk a LazyInitializationException on its lazy Project link and leak BaseEntity bookkeeping
// fields (deleted, createdAt...); this record publishes exactly the flat values meant to be public.

import com.pms.governance.entity.NiveauRisque;
import com.pms.governance.entity.StatutRisque;

/**
 * Immutable read-only record; its field names are the JSON contract the Angular "Risk" interface
 * expects. Flat by design: projectId/projectCode replace the entity's nested Project (via RiskMapper).
 */
public record RiskResponse(
        // Primary key; used to build URLs like DELETE /api/projects/7/risks/42 and as the *ngFor track key.
        Long id,

        // Filled by MapStruct from project.id, so a mixed-project list (dashboard, CSV export) needs no extra call.
        Long projectId,

        // Filled by MapStruct from project.code, the human-readable project code.
        String projectCode,

        String description,

        // Sent as the enum name so Jackson writes "probabilite": "ELEVE", matching the TypeScript union
        // and the Transloco keys riskLevel.FAIBLE/MOYEN/ELEVE.
        NiveauRisque probabilite,
        NiveauRisque impact,

        // May be null: a risk can be recorded before a mitigation plan exists.
        String planMitigation,

        // Sent as a raw value, not a pre-computed color/label, so display stays a front-end decision.
        StatutRisque statut
) {}
