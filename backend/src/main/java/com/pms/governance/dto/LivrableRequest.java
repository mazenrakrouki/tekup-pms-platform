package com.pms.governance.dto;

// JSON body for creating/editing a deliverable. No "statut" field on purpose: the state machine
// EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE only moves through the demarrer/livrer/valider PATCH
// endpoints, each with its own check in LivrableService. projectId comes from the URL, validated
// by ProjectScopeInterceptor (ADR-021), not from the body.

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/** Immutable request record; no setters, so what the validator checked is what gets saved. */
public record LivrableRequest(
        @NotBlank String titre,

        // Optional: a deliverable is often registered with just a title during kick-off.
        String description,

        // Calendar day, not a timestamp: a deadline is a day, and a timestamp could shift it
        // by one for a reader in another time zone. Nullable: due date may not be agreed yet.
        LocalDate dateEcheance
) {}
