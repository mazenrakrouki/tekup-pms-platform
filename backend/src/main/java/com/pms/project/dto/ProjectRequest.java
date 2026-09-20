package com.pms.project.dto;

import com.pms.project.entity.BusinessModel;
import com.pms.project.entity.EngagementType;
import com.pms.project.entity.ProjectStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// JSON body for creating or updating a project, following the company's "Fiche identification"
// Excel sheet (F-AFF-13). Deliberately excludes revisedBudget, archived and deleted — those have
// their own guarded endpoints — and the computed figures (duration, budget in TND, PPR), which
// are always derived, never accepted from the client. directorId/chefProjetId are present but
// only applied by the service if the caller holds the matching permission.

/**
 * Everything a user may type about a project, in one immutable object.
 * One record for both create and update: same form, same fields, only the URL/permission differ.
 * Bean Validation ignores null, so only @NotBlank/@NotNull demand a value — a project starts
 * with just a name and code, and the financial block is filled in later.
 */
public record ProjectRequest(
        // Business key; upper-cased and checked for uniqueness by the service. @NotBlank (not
        // just @NotNull) rejects a blank/whitespace-only code; @Size mirrors the VARCHAR(20) column.
        @NotBlank @Size(max = 20) String code,
        // Display name, capped at the 255-char column.
        @NotBlank @Size(max = 255) String name,
        // Free text (TEXT column), no length rule needed.
        String description,
        // Lifecycle status; enum so only the five valid values can arrive. Null keeps DRAFT on
        // create and leaves the existing status untouched on update. Note: this field is written
        // straight through by update(), with no transition check — the life-cycle rule
        // (ProjectStatus.canTransitionTo) is enforced only by the dedicated
        // PATCH /api/projects/{id}/status endpoint.
        ProjectStatus status,
        // Contract dates. Duration is derived from these via Project.getDurationDays() (both
        // ends included). No Bean Validation rule enforces end >= start; that lives in the DB
        // constraint chk_project_dates, so reversed dates fail with a raw Postgres error.
        LocalDate startDate,
        LocalDate endDate,
        // Base of the risk provision and every planned billing milestone; negative would poison
        // both. Zero allowed for an internal/pilot project.
        @PositiveOrZero BigDecimal initialBudget,
        // Director / chef de projet ids, or null to leave unchanged. The service loads and
        // validates the real users; a body can't invent a person.
        Long directorId,
        Long chefProjetId,

        // -- Identification sheet (Excel model) -------------------
        // Reference of the signed contract.
        @Size(max = 100) String contractId,
        // Client (signs) and funder (pays) — often different bodies.
        @Size(max = 255) String client,
        @Size(max = 255) String funder,
        // SEUL (alone) or GROUPEMENT (consortium).
        BusinessModel businessModel,
        // FORFAIT (fixed price) or REGIE (time and materials).
        EngagementType engagementType,
        // Contract currency; service upper-cases it and keeps the current value when blank.
        @Size(max = 10) String currency,
        // Dinars per unit of that currency; 6 decimals because small-unit currencies (e.g. FCFA
        // at ~0.005850 TND) would otherwise be off by roughly 70% at 2 decimals. A null here does
        // NOT blank the stored rate — the entity defaults to 1, so writing null through would
        // silently misprice every converted figure of the project.
        @PositiveOrZero BigDecimal exchangeRateToTnd,
        // Part of the budget going to licences/subcontractors — money the company doesn't keep.
        @PositiveOrZero BigDecimal licenseSubcontractBudget,
        // Sold workload and warranty-period workload, in JH. Quantities, not money, so they stay
        // visible to users walled off from financial data (BR-050).
        @PositiveOrZero BigDecimal soldWorkloadDays,
        @PositiveOrZero BigDecimal warrantyWorkloadDays,
        // Money set aside for late-delivery penalties.
        @PositiveOrZero BigDecimal penaltyProvision,
        // Net margin sold, as a fraction (0.4412 = 44.12%). Range -1..1: the max blocks typing
        // "44" for "44%"; the min still allows a real loss-making project (-1 = total loss).
        // Commercial baseline only — the computed DI margin takes priority when DI lines exist.
        @DecimalMin("-1.0") @DecimalMax("1.0") BigDecimal margeNetteVendue
) {}
