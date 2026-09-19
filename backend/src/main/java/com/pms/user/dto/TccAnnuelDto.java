package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

// ============================================================================
// FILE: TccAnnuelDto
//
// WHAT THIS FILE IS
//   The rates of ONE resource for ONE year: the daily rate and the TCC rate
//   (taux de charges - the extra cost added on top of the daily rate, written
//   as a fraction, so 0.4200 means 42%). It is a DTO (Data Transfer Object): a
//   small flat object built to travel over HTTP.
//
//   Why a year matters: the cost of one man-day depends on the year the work is
//   charged to. The TCC of 2024 is not the TCC of 2025 (spec F-AFF-13, section
//   6.3, rule 4). When a resource has no row for a given year, the base rates
//   held on the Resource itself apply instead.
//
// WHERE IT SITS IN THE FLOW
//   It is used in BOTH directions, which is unusual in this project:
//     reading  - TccAnnuel entity (table "tcc_annuels")
//                -> ResourceService.findTccAnnuels()
//                -> ResourceController, GET /api/resources/{id}/tcc
//     writing  - Angular resources screen sends the full list of years
//                -> ResourceController, PUT /api/resources/{id}/tcc
//                -> ResourceService.replaceTccAnnuels(), which answers with the
//                   saved list, again as TccAnnuelDto objects.
//   Reading needs VIEW_RESOURCES, writing needs MANAGE_RESOURCES; both
//   @PreAuthorize annotations sit on the SERVICE methods, not on the
//   controller.
//
// WHY ONE RECORD FOR BOTH DIRECTIONS
//   Here the two shapes really are identical: three values, no id, no audit
//   field. The resource is not repeated inside, because it is already in the
//   URL (/api/resources/{id}/tcc). Splitting this into a request record and a
//   response record would mean two files that must be kept identical by hand.
//
// WHY IT EXISTS
//   Without it the endpoints would have to expose the TccAnnuel entity, which
//   carries a link back to the Resource, and therefore to the User behind it -
//   the whole account would end up in the JSON of a rate list. The entity is
//   also lazy-loaded, so writing it to JSON after the transaction has closed is
//   how LazyInitializationException appears.
//
// HOW THE WRITE BEHAVES (worth knowing before reading the service)
//   PUT sends the COMPLETE list of years. ResourceService.replaceTccAnnuels()
//   updates the years already present, creates the new ones, and soft-deletes
//   the years the client did not send. It first refuses a list where the same
//   year appears twice, because the database index
//   uk_tcc_annuel_resource_annee - UNIQUE (resource_id, annee) WHERE deleted =
//   FALSE - would refuse it anyway, but as an unexplained 409.
// ============================================================================

/**
 * The rates of one resource for one year (the TCC of 2024 is not the TCC of
 * 2025 - spec F-AFF-13 section 6.3 rule 4).
 *
 * <p>Why a record: the three values are read once and never changed
 * afterwards, and the compiler writes the constructor and the accessors
 * (annee(), dailyRate(), tccRate()) for us.
 *
 * <p>The field name "annee" stays in French, like the column tcc_annuels.annee
 * and the JSON key the Angular screen sends. Renaming it here would break the
 * JSON contract on both sides at once.
 */
public record TccAnnuelDto(
        // The year these rates apply to, for example 2025.
        //
        // @NotNull because the column is NOT NULL and, more importantly,
        // because the year is what the whole row is keyed on: a null year could
        // not be matched against the year a task was charged to.
        // Integer (the object) and not int (the primitive) exactly so that a
        // missing value arrives as null and @NotNull can report it, instead of
        // silently becoming 0.
        //
        // @Min(2000) / @Max(2100) mirror the database rule
        // CHECK (annee BETWEEN 2000 AND 2100). Without them a typo such as 205
        // or 20255 would pass Java, be refused by PostgreSQL, and come back to
        // the user as a flat 409 "conflict" with no field name.
        @NotNull @Min(2000) @Max(2100) Integer annee,

        // Daily rate for that year, stored as NUMERIC(10,2).
        // BigDecimal and not double: a double cannot hold 0.1 exactly, so money
        // added in double slowly drifts; BigDecimal keeps the exact decimal
        // value, which is what an amount shown to a client must be.
        // @Positive means strictly greater than zero. Note that, unlike the
        // "resources" table, V21__tcc_annuel.sql wrote no CHECK on the sign
        // here, so this annotation is the only rule that refuses a rate of 0 or
        // a negative one - and a negative daily rate would turn the margin of
        // every task charged to that year into nonsense.
        @NotNull @Positive BigDecimal dailyRate,

        // Charge rate for that year, as a fraction: 0.4200 means 42%. Stored as
        // NUMERIC(5,4), so four digits after the decimal point and a value
        // below 10; @DecimalMax("9.9999") is exactly the largest value that
        // column can hold.
        // Without the maximum, a typo such as 42 instead of 0.42 would be
        // refused by PostgreSQL as a numeric overflow rather than reported as a
        // bad field. Without @DecimalMin("0.0"), a negative rate would multiply
        // the cost by less than one and show a margin that does not exist.
        //
        // Note for the defence: ResourceController declares the body as
        // @RequestBody List<@Valid TccAnnuelDto>, so the @Valid asks for these
        // three checks to be applied to every element of the list.
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate
) {}
