package com.pms.billing.entity;

// The three states a billing milestone can be in. An enum instead of a plain String so the
// compiler rejects a typo like "FACTUREE" outright — a bad String would silently fail every
// comparison in JalonService and vanish from KpiService's invoiced total, with no error anywhere.
// The three names must stay exactly as-is: V9's chk_jf_statut CHECK constraint hardcodes them,
// so renaming one here without a matching migration breaks every save and every stored row.
public enum JalonStatut {

    /*
     * PLANNED — agreed but not yet invoiced (the starting state, see @Builder.Default in
     * JalonFacturation). The only state that can still be edited or deleted, and the only one
     * whose amount is recomputed when the budget moves (marker H-4): once invoiced, the figure
     * must match what the client actually received.
     */
    PREVU,

    // INVOICED — sent to the client, JalonFacturation.dateFacture holds the issue date, amount
    // now frozen. First state PaiementService accepts a payment against (no invoice, no cash-in).
    // KpiService counts this together with PAYE as "already invoiced".
    FACTURE,

    /*
     * PAID — settled in full. Never set by hand: JalonService.recalculerStatut() sums non-deleted
     * payments after every change and switches here once the total reaches the milestone amount.
     * Reversible: if a payment is later deleted and the total no longer covers it, the milestone
     * drops back to FACTURE (or PREVU if no invoice date was ever recorded), so a payment entered
     * by mistake and cancelled doesn't leave the milestone permanently marked as paid.
     */
    PAYE
}
