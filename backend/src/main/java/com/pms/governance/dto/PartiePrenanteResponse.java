package com.pms.governance.dto;

// JSON the API sends back for one stakeholder — read-side mirror of PartiePrenanteRequest. Flat
// on purpose: the entity's lazy Project relation and BaseEntity audit columns have no business
// being published. This is also the only governance response carrying personal data (name,
// email, phone), so this explicit field list is the answer to "what does this endpoint expose?".

import com.pms.governance.entity.NiveauRisque;

public record PartiePrenanteResponse(
        Long id,
        Long projectId,
        String projectCode,
        String nom,
        // May be null, like their columns; the frontend shows a dash for each.
        String fonction,
        String email,
        String telephone,
        // Power over / interest in the project, shared FAIBLE/MOYEN/ELEVE scale. Kept as two
        // separate values (not one score) because the pair is what places the stakeholder on
        // the power/interest grid.
        NiveauRisque influence,
        NiveauRisque interet
) {}
