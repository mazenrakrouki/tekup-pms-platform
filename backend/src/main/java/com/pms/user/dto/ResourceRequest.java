package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// Body for creating/updating a Resource (a person's cost line): daily rate, TCC rate, staffing
// window. Kept separate from the entity so a client can never push an id, audit columns, or a
// whole User — and Bean Validation here turns a bad field into a clear 400 instead of a bare 409.

/**
 * Creating or updating a resource (a person's cost line).
 *
 * <p>Note what is NOT here: no id, no dailyLoadedCost. The id travels in the URL, and the
 * loaded man-day cost is always recomputed when the row is read (Resource.getDailyLoadedCost()),
 * never stored.
 */
public record ResourceRequest(
        // The account this cost line belongs to; only the id travels. update() never reads this
        // field, so a resource never changes owner even if a PUT sends a different userId.
        @NotNull Long userId,

        // Daily rate paid for this person. BigDecimal (not double) to avoid money drifting from
        // binary rounding. @Positive mirrors the DB CHECK (daily_rate > 0).
        @NotNull @Positive BigDecimal dailyRate,

        // Charge rate on top of the daily rate, as a fraction (0.4200 = 42%). Bounds mirror the
        // DB's NUMERIC(5,4) CHECK (tcc_rate >= 0 AND tcc_rate < 10); without the max, a typo like
        // 42 instead of 0.42 would only surface as a raw numeric-overflow 409.
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate,

        // First day staffed; NOT NULL because the yearly TCC lookup needs a year to compare.
        @NotNull LocalDate staffingStart,

        // Last day staffed, or null when open-ended (the common case). The "end after start" rule
        // lives only in the DB constraint chk_resource_dates, not here.
        LocalDate staffingEnd
) {}
