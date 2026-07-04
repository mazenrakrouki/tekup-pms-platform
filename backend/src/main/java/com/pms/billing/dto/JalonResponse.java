package com.pms.billing.dto;

import com.pms.billing.entity.JalonStatut;

import java.math.BigDecimal;
import java.time.LocalDate;

public record JalonResponse(
        Long id,
        Long projectId,
        String projectCode,
        String label,
        BigDecimal pourcentage,
        BigDecimal montant,
        LocalDate datePrevue,
        LocalDate dateFacture,
        JalonStatut statut
) {}
