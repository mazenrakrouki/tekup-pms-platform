package com.pms.project.dto;

import com.pms.project.entity.SectionDi;

import java.math.BigDecimal;

/** Ligne DI avec montants calculés à la lecture (jamais stockés). */
public record LigneDiResponse(
        Long id,
        SectionDi section,
        Integer ordre,
        String profilContractuel,
        String ressourceProposee,
        String ressourceRetenue,
        String unite,
        BigDecimal chargeVendueJh,
        BigDecimal prixVenteUnitaire,
        BigDecimal quantiteInterneJh,
        BigDecimal coutUnitaireTcc,
        BigDecimal fraisDivers,
        BigDecimal fraisGeneraux,
        BigDecimal coutImpots,
        BigDecimal tauxPourcentage,
        // ── calculés ──
        BigDecimal montantDevise,   // charge vendue × prix de vente
        BigDecimal montantTnd,      // montant devise × taux projet
        BigDecimal prixRevient,     // quantité interne × coût unitaire TCC
        BigDecimal coutFinal,       // prix de revient + FD + FG + impôts (ou taux % × total vendu TND)
        BigDecimal margeNette,      // montant TND − coût final
        BigDecimal margePct         // marge nette / montant TND
) {}
