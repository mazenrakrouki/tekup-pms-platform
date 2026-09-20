package com.pms.billing.dto;

// Read-side DTO for one billing milestone. Richer than JalonRequest because it also carries
// what the server decided: amount, invoice date and status.

import com.pms.billing.entity.JalonStatut;

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record. Reuses the entity enum JalonStatut directly rather than a copy in the dto
// package, since it's a closed three-value list and a duplicate would drift out of sync.
public record JalonResponse(
        // Primary key; the front end builds invoice/edit/delete/payment URLs from it.
        Long id,
        // Flattened from JalonFacturation.project by JalonMapper while the transaction is open,
        // so the LAZY proxy resolves without a LazyInitializationException.
        Long projectId,
        // Human-readable project code, so a screen or export doesn't need a second API call.
        String projectCode,
        // Milestone label, as sent in JalonRequest.
        String label,
        // Share of the contract this milestone invoices, in percent.
        BigDecimal pourcentage,
        /*
         * Money value of this milestone, computed server-side by JalonService.computeMontant
         * (effective budget x pourcentage / 100). Frozen once invoiced or paid — only PREVU rows
         * are rewritten by recomputePrevuMontants when the budget moves (marker H-4). Can be null
         * when the project has no budget yet.
         */
        BigDecimal montant,
        // Expected invoicing date, feeding the cash forecast. Null while unscheduled.
        LocalDate datePrevue,
        // Date the real invoice was issued; stays null until PATCH .../facturer is called.
        LocalDate dateFacture,
        /*
         * Milestone state: PREVU (planned), FACTURE (invoiced), PAYE (fully paid). Drives which
         * actions the billing screen offers, since the server refuses edits/deletes/re-invoicing
         * once a milestone leaves PREVU, and refuses a payment while it's still PREVU.
         */
        JalonStatut statut
) {}
