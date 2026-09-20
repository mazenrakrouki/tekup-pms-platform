package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

// The daily rate and TCC rate of one resource for one year (spec F-AFF-13 6.3 rule 4: rates are
// renegotiated yearly, so an earlier year's TCC does not apply to a later one). Used for both
// reading and writing (GET/PUT /api/resources/{id}/tcc) since the shape is identical either way,
// and it keeps the entity — with its lazy Resource/User chain — out of the JSON.

/**
 * The rates of one resource for one year (spec F-AFF-13 section 6.3 rule 4).
 *
 * <p>The field name "annee" stays in French, matching the column and the JSON key the Angular
 * screen sends — renaming it here would break the contract on both sides at once.
 */
public record TccAnnuelDto(
        // The year these rates apply to. Integer (not int) so a missing value arrives as null and
        // @NotNull can report it. Bounds mirror the DB CHECK (annee BETWEEN 2000 AND 2100).
        @NotNull @Min(2000) @Max(2100) Integer annee,

        // Daily rate for that year. BigDecimal, not double, to keep exact decimal values for
        // money. @Positive is the only guard against a zero/negative rate — unlike "resources",
        // this table has no matching DB CHECK on the sign.
        @NotNull @Positive BigDecimal dailyRate,

        // Charge rate for that year, as a fraction. Bounds mirror the DB's NUMERIC(5,4) column;
        // without the max, a rate typed as a whole percentage instead of a fraction would only
        // surface as a raw numeric-overflow error.
        //
        // ResourceController declares the body as List<@Valid TccAnnuelDto>, so these checks
        // apply to every element of the list.
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate
) {}
