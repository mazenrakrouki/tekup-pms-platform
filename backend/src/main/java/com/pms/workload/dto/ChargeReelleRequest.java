package com.pms.workload.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * JSON body for declaring actual days worked ("charge reelle") by one person on one project in one month.
 * Sister of PlanChargeRequest (the forecast side). userId travels in the body because BR-033 lets a
 * VALIDATE_WORKLOAD holder submit for a teammate; ownership is still checked server-side, never assumed.
 */

/**
 * Input for "this person really worked N days on this project this month". projectId comes from the
 * URL, not here — scope is enforced before this body is even parsed (ADR-021).
 */
public record ChargeReelleRequest(
        // Not taken from the token: BR-033 lets a VALIDATE_WORKLOAD holder submit on behalf of a teammate.
        @NotNull Long userId,

        // Bounds catch a typo (e.g. an extra digit) before it becomes a silently-accepted stored date.
        @NotNull @Min(2000) @Max(2100) Integer year,

        // 1-12; out of range would otherwise throw inside LocalDate.of(...) as an unhandled 500.
        @NotNull @Min(1) @Max(12) Integer month,

        // BigDecimal for exact NUMERIC(5,2) precision. PositiveOrZero (not Positive): zero worked days
        // is a real answer, unlike a planned line, which requires a strictly positive value.
        @NotNull @PositiveOrZero @DecimalMax("31") BigDecimal actualDays
) {}
