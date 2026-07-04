package com.pms.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AvenantResponse(
        Long id,
        Long projectId,
        String projectCode,
        String numero,
        String objet,
        BigDecimal montant,
        BigDecimal workloadDays,
        LocalDate dateAvenant
) {}
