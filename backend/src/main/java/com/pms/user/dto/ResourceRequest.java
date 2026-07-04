package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ResourceRequest(
        @NotNull Long userId,
        @NotNull @Positive BigDecimal dailyRate,
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate,
        @NotNull LocalDate staffingStart,
        LocalDate staffingEnd
) {}
