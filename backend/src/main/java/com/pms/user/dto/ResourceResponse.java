package com.pms.user.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// Read-only view of one Resource for the browser. Flattens the lazy User relation into two plain
// fields while the transaction is still open, avoiding both LazyInitializationException and
// leaking the full account into a cost screen.

/**
 * One resource, as the client sees it.
 *
 * <p>Built by ResourceMapper.toResponse(). A record, not a class, so nothing between the mapper
 * and the JSON writer can change an amount by mistake.
 */
public record ResourceResponse(
        // Primary key, reused in the URL of PUT and of the yearly TCC endpoints.
        Long id,

        // Id of the account behind this resource, so the "create a resource" form can offer only
        // users who don't have one yet.
        Long userId,

        // "Firstname Lastname", built by the mapper via User.getFullName(); null when no user.
        String userFullName,

        // Rates exactly as stored (daily_rate NUMERIC(10,2), tcc_rate NUMERIC(5,4), 0.4200 = 42%).
        // BigDecimal, not double, to keep exact decimal values for money.
        BigDecimal dailyRate,
        BigDecimal tccRate,

        // Indicative yearly cost, always computed (dailyRate * (1 + tccRate) * 218 working days,
        // HALF_UP), never stored, so it can never go stale relative to the rates next to it.
        // Uses the resource's BASE rates only, ignoring per-year tcc_annuels rows (spec F-AFF-13
        // 6.3 rule 4) — it's a rough list-screen figure; KpiService computes the real margins.
        BigDecimal annualCost,

        // Staffing window; staffingEnd is null when still open-ended.
        LocalDate staffingStart,
        LocalDate staffingEnd
) {}
