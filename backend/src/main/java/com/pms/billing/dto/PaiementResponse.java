package com.pms.billing.dto;

// Read-side DTO for one payment received against a billing milestone. No projectId here (unlike
// JalonResponse/AvenantResponse): a payment is always read through its milestone's URL, which
// already carries the project id.

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record, built by PaiementMapper; no validation annotations since this only goes OUT.
public record PaiementResponse(
        // Primary key; needed to build the delete URL.
        Long id,
        // Flattened from Paiement.jalon.id by PaiementMapper — just the id, since the caller
        // already has the milestone on screen and re-sending it per row would be redundant.
        Long jalonId,
        // Amount received, exactly as saved. BigDecimal so the browser's total matches the one
        // the server used to decide whether the milestone became PAYE.
        BigDecimal montantRecu,
        // Value date of the transfer.
        LocalDate datePaiement,
        // Bank/accounting reference of the transfer. Can be null.
        String reference
) {}
