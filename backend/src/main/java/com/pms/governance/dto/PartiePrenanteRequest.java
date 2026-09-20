package com.pms.governance.dto;

// JSON body for creating/editing a stakeholder. projectId is deliberately absent: it comes from
// the URL, which ProjectScopeInterceptor (ADR-021) already validates — otherwise a PUT could
// carry a different projectId and move a stakeholder into a project the caller only scoped
// through the URL.

import com.pms.governance.entity.NiveauRisque;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Immutable request record; no setters, so what the validator checked is what gets saved. */
public record PartiePrenanteRequest(
        @NotBlank String nom,

        // Optional: a name and contact are often known before the exact title is.
        String fonction,

        // @Email/@Size both accept null, so the field stays optional but must look like an
        // address when present. Size(255) mirrors the column so Postgres never has to reject it.
        @Email @Size(max = 255) String email,

        // Free text, not numeric: phone numbers can start with 0 or + ("+216 71 000 000").
        String telephone,

        // Shares the three-level NiveauRisque enum used for risks (chk_pp_influence /
        // chk_pp_interet mirror the risk columns), so labels and colours stay consistent
        // across the governance module.
        @NotNull NiveauRisque influence,

        // Kept separate from influence, not merged into one score: the pair is what places the
        // stakeholder on the power/interest grid the project manager uses to prioritize them.
        @NotNull NiveauRisque interet
) {}
