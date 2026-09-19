package com.pms.billing.entity;

/*
 * ============================================================================
 * FILE: JalonStatut.java -- the life cycle of a billing milestone, as an enum.
 * ============================================================================
 *
 * WHAT THIS FILE IS
 *   A closed list of the only three states a JalonFacturation row may be in.
 *   An "enum" is a type whose values are fixed at compile time: a variable of
 *   type JalonStatut can hold PREVU, FACTURE or PAYE and nothing else.
 *
 * WHERE IT SITS IN THE FLOW
 *   - JalonFacturation.statut stores one of these values. The field is mapped
 *     with @Enumerated(EnumType.STRING), so the database column keeps the NAME
 *     ("FACTURE"), not the position in this list.
 *   - JalonService reads the value on every write operation to decide what is
 *     allowed, and JalonService.recalculerStatut() moves the value when
 *     payments arrive or are cancelled.
 *   - PaiementService refuses to record money while the milestone is PREVU.
 *   - KpiService keeps only the milestones in FACTURE or PAYE to compute the
 *     total already invoiced, which then feeds FAE (work produced but not yet
 *     invoiced).
 *   - JalonResponse (the DTO sent to the Angular frontend) keeps this very enum
 *     as the type of its "statut" field; Jackson then writes it into the JSON as
 *     the name text ("FACTURE"). billing.component.ts compares that text to pick
 *     the badge colour of the row.
 *
 * WHY IT EXISTS / WHAT BREAKS WITHOUT IT
 *   The obvious alternative is a plain String field. It was not chosen because
 *   a String accepts anything: a typo like "FACTUREE" or "facture" would be
 *   saved without complaint, then silently fail every comparison in
 *   JalonService and disappear from the KpiService total, so the invoiced
 *   revenue of the project would be understated with no error anywhere. With an
 *   enum the compiler itself rejects an unknown value, and the switch of states
 *   is checked before the code even runs.
 *
 * THE THREE VALUES MUST STAY EXACTLY AS THEY ARE
 *   V9__schema_billing.sql declares
 *       CONSTRAINT chk_jf_statut CHECK (statut IN ('PREVU','FACTURE','PAYE'))
 *   so the database accepts only these three spellings. Renaming a value here
 *   without a matching Flyway migration would make every save fail with a check
 *   constraint violation, and every row already stored would no longer map back
 *   to any Java value.
 */
public enum JalonStatut {

    /*
     * PLANNED. The milestone is agreed but no invoice has been sent yet.
     * This is the starting state (see @Builder.Default in JalonFacturation).
     *
     * It is the ONLY state that can still be edited. JalonService.update() and
     * JalonService.delete() both refuse anything else, and only a PREVU
     * milestone can be invoiced by facturer().
     * WHY: once an invoice has left the company, changing its label, its
     * percentage or its amount would make the database disagree with the paper
     * the client received.
     *
     * It is also the only state whose amount is recomputed when the budget
     * moves (JalonService.recomputePrevuMontants, marker H-4): a milestone that
     * is still a forecast follows the new budget, an invoiced one does not.
     */
    PREVU,

    /*
     * INVOICED. The invoice has been sent to the client and
     * JalonFacturation.dateFacture holds the day it was issued.
     * The amount is now frozen: no service method rewrites it in this state.
     *
     * This is the first state in which PaiementService accepts a payment.
     * WHY: money can only be received against an invoice that exists. Allowing
     * a payment on a PREVU milestone would create cash in the reports with no
     * invoice behind it, and the accounts would not reconcile.
     *
     * KpiService counts this milestone in the total already invoiced, together
     * with PAYE.
     */
    FACTURE,

    /*
     * PAID. The client has settled the invoice in full.
     *
     * Nothing sets this value by hand. JalonService.recalculerStatut() runs
     * after every payment is added or removed: it sums the non-deleted payments
     * of the milestone and switches to PAYE as soon as that total reaches or
     * passes the milestone amount.
     * WHY it is computed and not typed in: a milestone can be settled in
     * several instalments, and a human ticking a "paid" box would forget, or
     * tick it too early.
     *
     * The move is reversible, which is the point of recalculerStatut(): if a
     * payment is deleted and the remaining total no longer covers the amount,
     * the milestone drops back to FACTURE (or to PREVU when no invoice date was
     * ever recorded).
     * EXAMPLE of the breakage without that reverse path: a payment entered by
     * mistake and then cancelled would leave the milestone marked PAYE forever,
     * and the cash follow-up would show money that never arrived.
     */
    PAYE
}
