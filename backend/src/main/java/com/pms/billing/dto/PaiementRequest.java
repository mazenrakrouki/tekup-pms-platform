package com.pms.billing.dto;

// Body for recording money received from the client against one billing milestone. No jalonId
// or projectId (both come from the URL, scoped by ProjectScopeInterceptor / ADR-021) and no
// status (the server derives it from the sum of payments, never the client).

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record so the amount validated here is exactly the amount saved and summed by
// recalculerStatut; @Valid on the controller parameter runs the checks below.
public record PaiementRequest(
        /*
         * Money actually received. @Positive rejects 0 and negatives (a real refund shouldn't be
         * encoded as a negative payment). Doesn't need to equal the milestone amount — clients
         * often pay in installments, and JalonService.recalculerStatut compares the running SUM
         * against the milestone amount before flipping it to PAYE. BigDecimal to avoid double's
         * rounding drift, which could leave a fully-paid milestone a cent short forever.
         */
        @NotNull @Positive BigDecimal montantRecu,
        // Value date of the transfer (not the day the row was typed); used for cash-in reporting.
        @NotNull LocalDate datePaiement,
        // Bank/accounting reference of the transfer. Optional: the payment is a fact even before
        // the reference is known.
        String reference
) {}
