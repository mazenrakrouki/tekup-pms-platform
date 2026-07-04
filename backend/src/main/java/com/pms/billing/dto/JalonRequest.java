package com.pms.billing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record JalonRequest(
        @NotBlank String label,
        @NotNull @Positive @DecimalMax("100") BigDecimal pourcentage,
        LocalDate datePrevue
) {}
