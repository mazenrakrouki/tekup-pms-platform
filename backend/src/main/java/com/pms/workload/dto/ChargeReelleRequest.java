package com.pms.workload.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of the JSON body a client sends to declare how many days a person REALLY worked on
 *   one project during one month ("charge reelle" = actual workload). It is a DTO (Data Transfer
 *   Object): a small object whose only job is to carry data between the outside world and our
 *   code, so that the database entity never travels over HTTP.
 *   This is the REALITY side. The sister file PlanChargeRequest, in this same folder, carries the
 *   forecast. The two together are what the workload screens compare.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen -> WorkloadService.submitCharge()
 *     -> POST /api/projects/{projectId}/charges-reelles        (declare a month)
 *     -> PUT  /api/projects/{projectId}/charges-reelles/{id}   (correct a declaration)
 *     -> ProjectScopeInterceptor.preHandle() runs FIRST, on the URL alone (ADR-021).
 *     -> WorkloadController.submitCharge / updateCharge: only then does Spring (through Jackson,
 *        the library that reads JSON) turn the body into this record, and @Valid on the parameter
 *        runs the checks written below BEFORE the controller body and the service run.
 *     -> ChargeReelleService.submit(...) / update(...): @PreAuthorize checks the permission
 *        SUBMIT_WORKLOAD there, on the SERVICE method, then the business rules run
 *        (BR-033 ownership, H-8 team membership, no duplicate for the same month), then these
 *        values are copied into a ChargeReelle entity.
 *     -> ChargeReelleRepository -> table charges_reelles.
 *   The answer travels back the other way as a ChargeReelleResponse (sister file in this folder),
 *   built by ChargeReelleMapper.
 *   So the walls are crossed in this order: project scope on the URL, then the field checks in
 *   this file, then the permission on the service method, then the business rules.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   - The controller would have to accept the ChargeReelle entity itself. A caller could then send
 *     "validatedAt": "2026-01-01" or "validatedBy": 3 in the body and declare their own month
 *     already approved, which is exactly the fraud the validation step is meant to prevent. Those
 *     two fields simply do not exist in this record, so no request can ever set them.
 *   - These annotations are the first wall of the application. Without this file there is no
 *     single place saying "userId is required, month is between 1 and 12", so the same checks
 *     would be copy-pasted inside the service and would drift apart over time.
 *
 * WHY userId IS IN THE BODY EVEN THOUGH THE SERVER KNOWS WHO IS CALLING
 *   Because the server does not blindly trust it. BR-033 applies: ChargeReelleService.assertOwnership
 *   compares this userId with the logged-in user and throws AccessDeniedException (403) if they
 *   differ. The only escape is a caller who ALSO holds VALIDATE_WORKLOAD, who may then declare a
 *   month on behalf of a team member who forgot. So the field is a request, never a statement the
 *   server takes for granted.
 *   Be precise about that escape if you are asked: it is written by capability, never by role name
 *   (ADR-001). With the roles seeded in V2, SUBMIT_WORKLOAD is held only by the developer profile
 *   and VALIDATE_WORKLOAD only by the project manager profile, so with those seeded roles nobody
 *   holds both and the escape is never taken. It exists because roles are built dynamically in the
 *   admin module: the day an administrator creates a role carrying both permissions, that person
 *   can declare for a team member, and the code already handles it without being changed.
 *
 * ONE IMPORTANT DETAIL ABOUT THE TWO USES
 *   submit() reads all four values. update() treats only actualDays as a real change: it compares
 *   userId, year and month with the row already stored and throws BusinessRuleException if they
 *   differ, and it refuses the whole edit when the row is already validated. So on a correction
 *   those three act as a confirmation, not as an instruction. Why: moving a declared month to
 *   another month or to another person would break the pairing with the planned charge, and the
 *   unique index uk_cr_active (project_id, user_id, period) could end up with two live lines for
 *   the same person and the same month.
 * =====================================================================
 */

/**
 * Input for "this person really worked N days on this project during this month".
 *
 * <p>Why a record instead of a normal class with fields and setters: a record is final and has no
 * setters, so once Spring has built it from the JSON nobody can change the values while the
 * request is being handled. Java also writes the constructor, the accessors ({@code userId()},
 * {@code actualDays()}, ...), {@code equals} and {@code hashCode} for us, so there is no
 * hand-written boilerplate that could silently be wrong. Jackson knows how to build a record
 * through its single constructor, so no extra annotation is needed here.</p>
 *
 * <p>Why the number fields use the object types {@code Long} / {@code Integer} / {@code BigDecimal}
 * and not the primitives {@code long} / {@code int}: a primitive cannot be null, so a body that
 * simply forgets "year" would arrive as the year 0 and {@code @NotNull} would never fire. With the
 * object type the missing field stays null and the request is refused with a clear 400 Bad Request
 * naming the field.</p>
 *
 * <p>Note that the project id is NOT a field of this record. It comes from the URL
 * ({@code /api/projects/{projectId}/charges-reelles}). That is deliberate: per ADR-021 the
 * ProjectScopeInterceptor reads the project id from the URL and checks that the caller is allowed
 * on THAT project before the controller is reached. If the project id travelled inside the body
 * instead, the interceptor would not see it, and a developer holding SUBMIT_WORKLOAD could declare
 * days on a project outside their own perimeter. Permission alone is not enough; the scope check
 * needs the id in the path.</p>
 *
 * <p>Note also that there is no single "period" field of type date. The client sends year and
 * month separately, and ChargeReelleService rebuilds the stored date with
 * {@code LocalDate.of(year, month, 1)}. Why: workload is declared per month, so every row must sit
 * on the 1st of its month. Letting the client send a full date would allow 2026-03-17, and then
 * two rows for March could both exist because the unique index on (project_id, user_id, period)
 * would see two different dates and would not catch the duplicate.</p>
 */
public record ChargeReelleRequest(
        // WHAT: database id of the person these worked days belong to.
        // WHY @NotNull: the request is refused with 400 Bad Request before the service starts,
        //   because the column user_id is NOT NULL and points at the users table.
        // WHY it is not simply taken from the logged-in user: see "WHY userId IS IN THE BODY" in
        //   the header above -- a caller who also holds VALIDATE_WORKLOAD may declare for a team
        //   member, and BR-033 blocks everybody else from doing the same.
        // WITHOUT IT: a body with no "userId" would reach the service, assertOwnership(null) would
        //   compare an id with null, and the caller would get a confusing 403 or a 500 instead of
        //   a readable 400.
        @NotNull Long userId,

        // WHAT: the year of the declared month, for example 2026.
        // WHY @Min(2000) @Max(2100): the service builds the stored date with
        //   LocalDate.of(year, month, 1), and LocalDate happily accepts any year up to nine
        //   digits. These bounds turn a typo into a clean 400 instead of a stored row.
        // WITHOUT IT: "year": 20255 (one extra key press) would be accepted and saved. The days
        //   would disappear from every yearly report, and the person would look idle for a month
        //   they actually worked.
        @NotNull @Min(2000) @Max(2100) Integer year,

        // WHAT: the declared month, 1 for January up to 12 for December.
        // WHY @Min(1) @Max(12): same idea, but here the failure would be louder.
        //   LocalDate.of(2026, 13, 1) throws DateTimeException, which is not a validation error,
        //   so Spring turns it into a 500 Internal Server Error.
        // WITHOUT IT: a client bug sending a zero-based month ("month": 0 for January, which is
        //   how the JavaScript Date object counts months) would crash the endpoint with a 500
        //   instead of answering 400 with "month must be greater than or equal to 1".
        @NotNull @Min(1) @Max(12) Integer month,

        // WHAT: how many working days that person really spent on that project during that month.
        //   Half days are allowed, so 7.5 is a valid value.
        // WHY BigDecimal and not double: BigDecimal keeps decimals exactly, and the column is
        //   NUMERIC(5,2). With double, 0.1 + 0.2 gives 0.30000000000000004, so a yearly total
        //   built by adding twelve monthly values would end at 89.99999999999999 days and the
        //   screen would show a number nobody can explain.
        // WHY @PositiveOrZero and NOT @Positive: zero is a real answer here. A developer assigned
        //   to a project may have spent no day at all on it that month, and saying so explicitly
        //   is different from saying nothing. This mirrors the database rule
        //   CHECK chk_cr_days (actual_days >= 0 AND actual_days <= 31), which also allows 0.
        //   The planned side is stricter (@Positive) because planning zero days is meaningless.
        // WHY @DecimalMax("31"): no month has more than 31 days, so a larger figure is always a
        //   mistake. The limit is written as the text "31" and not as a number because a decimal
        //   written as a double literal cannot always be represented exactly; the annotation
        //   parses the text into a BigDecimal. It is inclusive, so exactly 31 passes.
        // WITHOUT THESE TWO: a value of -5 or of 310 (a slipped decimal point) would travel all
        //   the way to PostgreSQL, the CHECK constraint would reject it, and the caller would get
        //   a raw database error as a 500 instead of a readable 400 naming the field.
        @NotNull @PositiveOrZero @DecimalMax("31") BigDecimal actualDays
) {}
