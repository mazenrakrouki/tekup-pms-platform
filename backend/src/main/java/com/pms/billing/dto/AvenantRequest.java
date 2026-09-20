package com.pms.billing.dto;

// JSON body for creating a contract amendment ("avenant"). No projectId field on purpose:
// the project always comes from the URL, which ProjectScopeInterceptor (ADR-021) scopes to
// the caller, so a body-supplied id could never be used to write into another project.

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record: Jackson builds it from the request body, and @Valid on the controller
// parameter runs the checks below, turned into HTTP 400 by GlobalExceptionHandler.
public record AvenantRequest(
        // Human reference of the signed document ("AV-2026-01"); column is NOT NULL.
        @NotBlank String numero,
        // Free-text purpose of the amendment. Optional: the money and date are the facts.
        String objet,
        /*
         * Amount the amendment adds to (or, if negative, removes from) the contract. Only
         * @NotNull, deliberately no @Positive: the sign carries meaning and a reduction must
         * stay representable. AvenantService adds this to the effective budget on create and
         * subtracts it on delete. BigDecimal, not double, to keep exact money digits.
         */
        @NotNull BigDecimal montant,
        // Impact on the sold workload in man-days (JH). Optional: many amendments are money-only.
        BigDecimal workloadDays,
        // Contractual signature date; LocalDate because a contract is signed on a day, not an hour.
        @NotNull LocalDate dateAvenant
) {}
