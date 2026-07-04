package com.pms.workload.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ChargeReelleRequest(
        @NotNull Long userId,
        @NotNull @Min(2000) @Max(2100) Integer year,
        @NotNull @Min(1) @Max(12) Integer month,
        @NotNull @PositiveOrZero @DecimalMax("31") BigDecimal actualDays
) {}
