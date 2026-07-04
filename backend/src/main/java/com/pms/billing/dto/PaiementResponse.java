package com.pms.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaiementResponse(
        Long id,
        Long jalonId,
        BigDecimal montantRecu,
        LocalDate datePaiement,
        String reference
) {}
