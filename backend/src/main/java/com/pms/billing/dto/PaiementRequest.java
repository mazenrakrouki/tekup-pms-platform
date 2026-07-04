package com.pms.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaiementRequest(
        @NotNull @Positive BigDecimal montantRecu,
        @NotNull LocalDate datePaiement,
        String reference
) {}
