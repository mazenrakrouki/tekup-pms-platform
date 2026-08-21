package com.pms.project.dto;

import java.math.BigDecimal;
import java.util.List;

/** Devis Interne complet d'un projet, totaux calculés à la lecture. */
public record DevisInterneResponse(
        Long projectId,
        String projectCode,
        String currency,
        BigDecimal exchangeRateToTnd,
        List<LigneDiResponse> lignes,
        // ── totaux ──
        BigDecimal totalVenduDevise,
        BigDecimal totalVenduTnd,
        BigDecimal totalChargeVendueJh,
        BigDecimal totalQuantiteInterneJh,
        BigDecimal totalCoutFinal,
        BigDecimal margeNette,      // total vendu TND − total coût final
        BigDecimal margePct         // marge nette / total vendu TND (= marge nette vendue baseline)
) {}
