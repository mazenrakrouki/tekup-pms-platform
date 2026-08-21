package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Tarif TCC d'une ressource pour une année donnée (TCC 2024 ≠ TCC 2025). */
public record TccAnnuelDto(
        @NotNull @Min(2000) @Max(2100) Integer annee,
        @NotNull @Positive BigDecimal dailyRate,
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate
) {}
