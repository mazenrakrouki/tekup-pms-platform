package com.pms.workload.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * JSON body for planning how many days one person is expected to work on one project in one month
 * ("plan de charge"). Sister of ChargeReelleRequest (the actual-worked side); comparing the two is
 * what the workload screens are for.
 */

/**
 * Input for "plan N days for this person on this project this month". projectId comes from the
 * URL, not here — scope is enforced before this body is even parsed (ADR-021).
 */
public record PlanChargeRequest(
        // Missing this would hit findById(null) and surface as an unhelpful 500 instead of a 400.
        @NotNull Long userId,

        // Bounds catch a typo (e.g. an extra digit) before it becomes a silently-accepted stored date.
        @NotNull @Min(2000) @Max(2100) Integer year,

        // 1-12; out of range would otherwise throw inside LocalDate.of(...) as an unhandled 500.
        @NotNull @Min(1) @Max(12) Integer month,

        // BigDecimal for exact NUMERIC(5,2) precision. Strictly Positive (unlike ChargeReelleRequest's
        // PositiveOrZero): planning zero days is meaningless — just don't create the line.
        @NotNull @Positive @DecimalMax("31") BigDecimal plannedDays
) {}
