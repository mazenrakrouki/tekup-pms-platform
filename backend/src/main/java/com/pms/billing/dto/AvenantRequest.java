package com.pms.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AvenantRequest(
        @NotBlank String numero,
        String objet,
        @NotNull BigDecimal montant,     // positif = augmentation ; négatif = réduction
        BigDecimal workloadDays,         // impact charge vendue (JH), optionnel
        @NotNull LocalDate dateAvenant
) {}
