package com.pms.governance.dto;

// JSON body for creating/editing a change request. No "statut" or "dateDecision" here on
// purpose — only PATCH .../approuver and .../rejeter can move the status, so a requester can't
// approve their own request via a PUT body. projectId isn't here either: it comes from the URL,
// which ProjectScopeInterceptor (ADR-021) already validates against the caller's scope.

import com.pms.governance.entity.PrioriteChangement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Immutable request record; no setters, so what the validator checked is what gets saved. */
public record DemandeChangementRequest(
        // The client sends only the id; the service resolves it to a real (non-deleted) User
        // and 404s otherwise, so a request can never be attached to an invented person.
        @NotNull Long demandeurId,

        @NotBlank String titre,

        // Optional: an urgent request is often opened with just a title and filled in later.
        String description,

        // Four-value scale (FAIBLE/NORMALE/ELEVEE/CRITIQUE) — distinct from the three-value
        // NiveauRisque used elsewhere; mixing them would break the chk_dc_priorite constraint.
        @NotNull PrioriteChangement priorite,

        // Calendar day, not a timestamp: avoids a request filed on the 1st shifting to the
        // previous month in another time zone's report.
        @NotNull LocalDate dateDemande
) {}
