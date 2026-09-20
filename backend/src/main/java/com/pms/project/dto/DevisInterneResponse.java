package com.pms.project.dto;

import java.math.BigDecimal;
import java.util.List;

// Full answer sent for one project's Devis Interne (DI, "internal quote"): what was sold, what
// the work really costs, and the margin between the two. DevisInterneService.compute() builds it
// fresh on every read; the totals and margins are never stored (F-AFF-13), so this record is the
// only place the computed view of the DI exists. Every method returning it requires MANAGE_DI,
// checked on the service, plus the ADR-021 project-scope check on the controller's URL.

/**
 * Full Devis Interne of one project: the lines, then the totals over those lines.
 * A record so the totals can't be silently rewritten after the engine produced them.
 */
public record DevisInterneResponse(
        // Database id of the project this quote belongs to.
        Long projectId,
        // Human code of the project, for example "PRJ-2024-07", so the screen can label itself
        // without a second GET.
        String projectCode,
        // Currency the project sells in (TND, EUR, FCFA...); every "Devise" amount below is in it.
        String currency,
        // Rate turning one unit of that currency into TND. Returned so the user can redo the
        // arithmetic by hand; falls back to 1 when the project has none, so this is never null.
        BigDecimal exchangeRateToTnd,
        // The quote lines, in the stable order set by the repository (section, then position,
        // then id).
        List<LigneDiResponse> lignes,
        // -- totals over the lines above --
        // Sum of every line amount, in the project currency (charge sold x unit price).
        BigDecimal totalVenduDevise,
        // Same total converted to dinars, rounded to 2 decimals. Both are sent because the
        // contract is signed in one currency but the business is steered in dinars.
        BigDecimal totalVenduTnd,
        // Total workload sold, in JH ("jour-homme", one person one day) — a quantity, not money.
        BigDecimal totalChargeVendueJh,
        // Total workload the company actually plans to spend internally, also in JH.
        BigDecimal totalQuantiteInterneJh,
        // Total real cost in dinars: internal cost of the lines, plus extra fees, plus the
        // percentage-based lines (taxes, risk provision).
        BigDecimal totalCoutFinal,
        // Net margin in dinars = totalVenduTnd - totalCoutFinal.
        BigDecimal margeNette,
        // Net margin as a fraction of the sold amount, 4 decimals (0.4412 = 44.12%). Null, not
        // zero, when nothing has been sold yet, so the screen prints "-" instead of a false "0%".
        // Also the baseline computeMargeVenduePct() hands to the KPI engine when DI lines exist.
        BigDecimal margePct
) {}
