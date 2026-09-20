package com.pms.project.dto;

import com.pms.project.entity.SectionDi;

import java.math.BigDecimal;

// One Devis Interne line as sent to the browser: raw inputs plus the amounts derived from them.
// DevisInterneService.compute() builds one per LigneDi entity; the derived amounts are never
// stored (BUSINESS_ANALYSIS.md section 16), so they exist only here. The input side uses a
// separate record, LigneDiRequest, so a client can never dictate its own montantTnd or margin.

/**
 * One DI line with its amounts computed at read time (never stored). "Devise" is the project
 * currency, "TND" the dinar used for internal steering, "JH" a man-day. Money is rounded to 2
 * decimals HALF_UP; percentages keep 4 decimals (0.4412 = 44.12%).
 * The SOLD side is in the project currency and converted once to TND; the COST side
 * (coutUnitaireTcc onward) is already in dinars and never multiplied by the exchange rate again
 * — that's what lets margeNette subtract coutFinal from montantTnd directly.
 */
public record LigneDiResponse(
        // Database id; the client sends it back in the URL to update/delete this line.
        Long id,
        // Block of the quote: HONORAIRES, FRAIS or AUTRES_FRAIS — see SectionDi.
        SectionDi section,
        // Display rank inside the section, so the screen matches the order the user arranged.
        Integer ordre,
        // Contractual profile sold to the client, e.g. "Senior developer".
        String profilContractuel,
        // Person proposed in the offer, and the person finally staffed — kept separate because
        // the two can differ.
        String ressourceProposee,
        String ressourceRetenue,
        // Unit the line is counted in, "H-Jour" by default.
        String unite,
        // Workload sold to the client on this line, in JH.
        BigDecimal chargeVendueJh,
        // Price of one unit sold, in the project currency.
        BigDecimal prixVenteUnitaire,
        // Workload the company really plans to spend, in JH — the gap vs. chargeVendueJh is
        // where the margin comes from.
        BigDecimal quantiteInterneJh,
        // Cost of one internal day, in DINARS (TCC = "Taux de Cout Charge", fully loaded daily
        // rate). Copied onto the line rather than read from TccAnnuel because a quote is a
        // forecast, often for a profile or subcontractor with no resource row, and must not
        // change retroactively when a rate is renegotiated.
        BigDecimal coutUnitaireTcc,
        // Three flat extra costs in dinars: misc fees (FD), general overhead (FG-P&ST), taxes.
        // Kept as separate columns so a reviewer can see why a line costs more than days x rate.
        BigDecimal fraisDivers,
        BigDecimal fraisGeneraux,
        BigDecimal coutImpots,
        // Rate used by AUTRES_FRAIS lines costed as a share of total sold (0.05 = 5%).
        BigDecimal tauxPourcentage,
        // -- computed at read time, never stored --
        // Amount sold in the project currency: chargeVendueJh x prixVenteUnitaire (nulls as 0).
        BigDecimal montantDevise,
        // Same amount in dinars: montantDevise x project exchange rate. Recomputed, not stored,
        // so correcting the project's rate updates every line at once.
        BigDecimal montantTnd,
        // Internal cost of the days, in dinars: quantiteInterneJh x coutUnitaireTcc ("prix de
        // revient", the French accounting term for cost price).
        BigDecimal prixRevient,
        // Final line cost in dinars: normally prixRevient + fraisDivers + fraisGeneraux +
        // coutImpots, but for an AUTRES_FRAIS line WITH a tauxPourcentage it's
        // tauxPourcentage x total sold in TND instead — a tax/provision is priced off the whole
        // quote, which is why the engine computes totals in two passes. An AUTRES_FRAIS line
        // with no rate still takes the ordinary path (a fixed-sum registration fee, say).
        BigDecimal coutFinal,
        // What the line leaves after cost: margeNette = montantTnd - coutFinal. Can be
        // negative on purpose — a line sold below cost must stay visible.
        BigDecimal margeNette,
        // Margin as a fraction of the line amount: margeNette / montantTnd, 4 decimals. Null,
        // not zero, when montantTnd is zero or less, so a pure cost line doesn't misread as
        // "breaks even".
        BigDecimal margePct
) {}
