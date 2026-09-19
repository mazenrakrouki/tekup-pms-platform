package com.pms.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// ============================================================================
// FILE: ResourceRequest
//
// WHAT THIS FILE IS
//   The shape of the JSON body an administrator sends to create or update a
//   RESOURCE. A Resource is the cost side of a person: the daily rate the
//   company pays for them, the TCC rate (taux de charges - the extra cost added
//   on top of the daily rate, for example 0.42 = 42%), and the dates between
//   which they are staffed. This is a DTO (Data Transfer Object): a small flat
//   object whose only job is to carry data from the browser to the server.
//
// WHERE IT SITS IN THE FLOW
//   Angular resources.component
//     -> ResourceController, POST /api/resources and PUT /api/resources/{id},
//        where @Valid @RequestBody runs the checks written below
//     -> ResourceService.create() / update(), both guarded by
//        @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") - the permission is
//        tested on the SERVICE method, never on the controller
//     -> Resource entity, table "resources"
//     -> the answer comes back as a ResourceResponse.
//
// WHY IT EXISTS
//   Without it the controller would have to accept the Resource entity itself.
//   A client could then push an id, the audit columns, the "deleted" flag or a
//   whole User object, and would be writing straight into the table that feeds
//   every margin calculation of the application. This record accepts five
//   values and nothing else.
//
// WHY THE RULES LIVE HERE AND NOT ONLY IN THE DATABASE
//   Bean Validation (the @NotNull / @Positive annotations below) runs before
//   any service code, and GlobalExceptionHandler turns the resulting
//   MethodArgumentNotValidException into a 400 answer that names each bad
//   field. The same limits also exist as CHECK constraints in
//   V3__schema_user_resource.sql, but a database refusal only reaches the user
//   as a flat 409 "conflict" with no field name, so catching it here is what
//   makes the form usable.
// ============================================================================

/**
 * Creating or updating a resource (a person's cost line).
 *
 * <p>This is a Java "record": an immutable data carrier where the compiler
 * writes the constructor and the accessors (userId(), dailyRate(), ...). A
 * record is used instead of a class with setters because a request body is
 * read once and never modified afterwards; nothing should be able to change
 * the values between the validation and the write to the database.
 *
 * <p>Note what is NOT here: no id, no annualCost. The id travels in the URL,
 * and the annual cost is always recomputed when the row is read
 * (Resource.getAnnualCost()), never sent by the client and never stored.
 */
public record ResourceRequest(
        // The account this cost line belongs to. Only the id travels, not the
        // whole user: the client has no business sending a name or an e-mail
        // here, and the server reads the real user from the database anyway.
        //
        // @NotNull: resources.user_id is NOT NULL. Without this check a body
        // with no userId would reach ResourceService.create(), which would call
        // userRepository.findById(null) and fail deep inside the service
        // instead of answering "userId is required".
        //
        // One user can have at most one active resource. ResourceService.create
        // checks that first (findActiveByUserId) so the admin gets a clear
        // message; the real guarantee is the constraint uk_resources_user_id
        // added by V4__add_unique_resource_user.sql.
        //
        // Careful when reading ResourceService.update(): it never looks at this
        // field. A resource therefore never changes owner, even if the client
        // sends a different userId in a PUT - which is what we want, because
        // past cost calculations must keep pointing at the same person.
        @NotNull Long userId,

        // Daily rate paid for this person, in the currency held by the
        // "CURRENCY" parameter row. Stored as NUMERIC(10,2).
        //
        // BigDecimal and not double: a double stores 0.1 as an approximation,
        // so adding money in double slowly drifts (0.1 + 0.2 gives
        // 0.30000000000000004). BigDecimal keeps the exact decimal value, which
        // is what an amount shown to a client must be.
        //
        // @Positive means strictly greater than zero; it mirrors the database
        // rule CHECK (daily_rate > 0). Without it a rate of 0 would be
        // accepted here and refused by PostgreSQL, so the user would see a
        // vague 409 instead of "the daily rate must be greater than 0"; and a
        // negative rate would turn every margin on the project into nonsense.
        @NotNull @Positive BigDecimal dailyRate,

        // Charge rate added on top of the daily rate, written as a fraction:
        // 0.4200 means 42%. Stored as NUMERIC(5,4), so four digits after the
        // decimal point and a value below 10.
        //
        // @DecimalMin("0.0") allows 0 (a person with no extra charges) and
        // refuses a negative rate. @DecimalMax("9.9999") is the largest value
        // NUMERIC(5,4) can hold, and matches the database rule
        // CHECK (tcc_rate >= 0 AND tcc_rate < 10).
        // Without the maximum, a typo such as 42 instead of 0.42 would pass
        // Java, be refused by PostgreSQL as a numeric overflow, and come back
        // as an unexplained 409. Without the minimum, -0.42 would multiply the
        // cost by 0.58 and quietly show a margin that does not exist.
        @NotNull @DecimalMin("0.0") @DecimalMax("9.9999") BigDecimal tccRate,

        // First day the person is staffed. @NotNull because staffing_start is
        // NOT NULL in the table: a cost line with no starting date could not be
        // placed on any year, and the yearly TCC lookup would have nothing to
        // compare against.
        @NotNull LocalDate staffingStart,

        // Last day of staffing, or null when the staffing is still open-ended.
        // There is deliberately NO @NotNull here: most resources have no end
        // date, and forcing one would mean inventing a date.
        //
        // The "end must come after the start" rule is not written in Java; it
        // is the database constraint chk_resource_dates
        // (staffing_end IS NULL OR staffing_end > staffing_start). So an end
        // date placed before the start date is still refused, but as a 409
        // without a field name rather than a 400 naming this field.
        LocalDate staffingEnd
) {}
