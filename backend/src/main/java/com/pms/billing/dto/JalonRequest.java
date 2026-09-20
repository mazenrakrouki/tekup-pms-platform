package com.pms.billing.dto;

// Body for creating or editing a billing milestone ("jalon de facturation"): one slice of the
// contract invoiced at a project step (e.g. "30% on kick-off"). One record serves both create
// and edit since they accept the same three fields.
//
// montant, dateFacture and statut are deliberately absent: montant is server-computed from the
// budget and percentage (H-4 recompute keeps it in step), and dateFacture/statut only change
// through the dedicated /facturer endpoint and payment recording, never a plain PUT. No
// projectId either: it comes from the URL, scoped by ProjectScopeInterceptor (ADR-021).

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record so the body can't change between validation and the amount computation;
// @Valid on the controller parameter runs the checks below (HTTP 400 on failure).
public record JalonRequest(
        // Label shown in the billing table; NOT NULL column, so an empty one would be unreadable.
        @NotBlank String label,
        /*
         * Share of the contract this milestone invoices, in percent. @Positive and
         * @DecimalMax("100") (string, so it parses as an exact BigDecimal) duplicate the DB's
         * CHECK constraint but answer 400 with the field name instead of a vague 409. Only
         * guards one row though — the "all milestones together stay under 100%" rule lives in
         * JalonService.validatePourcentageSum.
         */
        @NotNull @Positive @DecimalMax("100") BigDecimal pourcentage,
        // Expected invoicing date, optional since the schedule isn't always known upfront. Feeds
        // the cash forecast.
        LocalDate datePrevue
) {}
