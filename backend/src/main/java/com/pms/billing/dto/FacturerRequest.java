package com.pms.billing.dto;

// Body for marking a billing milestone as invoiced. A separate, single-field DTO rather than
// reusing JalonRequest, because invoicing is a state change, not a full edit, and the invoice
// date must come from the client (it's set in the accounting tool, not by this server).

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Immutable record; Jackson fills it from the JSON key "dateFacture".
// dateFacture also doubles as JalonService.recalculerStatut's proof that an invoice exists:
// when payments no longer cover the amount, it falls back to FACTURE only if this is set.
public record FacturerRequest(@NotNull LocalDate dateFacture) {}
