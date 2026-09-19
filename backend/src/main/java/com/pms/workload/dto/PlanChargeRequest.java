package com.pms.workload.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of the JSON body a client sends to plan how many days one person is expected to
 *   work on one project during one month ("plan de charge" = planned workload). It is a DTO
 *   (Data Transfer Object): a small object whose only job is to carry data between the outside
 *   world and our code, so that the database entity never travels over HTTP.
 *   This is the FORECAST side. The sister file ChargeReelleRequest, in this same folder, carries
 *   the days that were really worked. Comparing the two is what makes the workload screens
 *   meaningful.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular workload screen -> WorkloadService.createPlanCharge()
 *     -> POST /api/projects/{projectId}/plan-charges        (create a new planned line)
 *     -> PUT  /api/projects/{projectId}/plan-charges/{id}   (edit an existing planned line)
 *     -> ProjectScopeInterceptor.preHandle() runs FIRST, on the URL alone (ADR-021).
 *     -> WorkloadController.createPlanCharge / updatePlanCharge: only then does Spring (through
 *        Jackson, the library that reads JSON) turn the body into this record, and @Valid on the
 *        parameter runs the checks written below BEFORE the controller body and the service run.
 *     -> PlanChargeService.create(...) / update(...): @PreAuthorize checks the permission
 *        VALIDATE_WORKLOAD there, on the SERVICE method, then the business rules run, then these
 *        values are copied into a PlanCharge entity.
 *     -> PlanChargeRepository -> table plan_charges.
 *   The answer travels back the other way as a PlanChargeResponse (sister file in this folder),
 *   built by PlanChargeMapper.
 *   So the walls are crossed in this order: project scope on the URL, then the field checks in
 *   this file, then the permission on the service method, then the business rules.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   - The controller would have to accept the PlanCharge entity itself. A caller could then send
 *     "id": 7 or "deleted": true in the body and write fields the API must never expose. The
 *     entity also holds whole Project and User objects, which a JSON body cannot sensibly carry.
 *   - These annotations are the first wall of the application. Without this file there is no
 *     single place saying "userId is required, month is between 1 and 12", so the same checks
 *     would be copy-pasted inside the service and would drift apart over time.
 *
 * ONE IMPORTANT DETAIL ABOUT THE TWO USES
 *   create() reads all four values. update() treats only plannedDays as a real change: it compares
 *   userId, year and month with the row already stored and throws BusinessRuleException if they
 *   differ. So on an edit those three act as a confirmation, not as an instruction. Why: moving a
 *   planned line to another month or to another person would silently break the pairing with the
 *   real charge of that month, and the unique index uk_pc_active (project_id, user_id, period)
 *   could end up with two live lines for the same person and the same month.
 * =====================================================================
 */

/**
 * Input for "plan N days for this person on this project for this month".
 *
 * <p>Why a record instead of a normal class with fields and setters: a record is final and has no
 * setters, so once Spring has built it from the JSON nobody can change the values while the
 * request is being handled. Java also writes the constructor, the accessors ({@code userId()},
 * {@code plannedDays()}, ...), {@code equals} and {@code hashCode} for us, so there is no
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
 * ({@code /api/projects/{projectId}/plan-charges}). That is deliberate: per ADR-021 the
 * ProjectScopeInterceptor reads the project id from the URL and checks that the caller is allowed
 * on THAT project before the controller is reached. If the project id travelled inside the body
 * instead, the interceptor would not see it, and a user holding VALIDATE_WORKLOAD could aim at a
 * project outside their own perimeter. Permission alone is not enough; the scope check needs the
 * id in the path.</p>
 *
 * <p>Note also that there is no single "period" field of type date. The client sends year and
 * month separately, and PlanChargeService rebuilds the stored date with
 * {@code LocalDate.of(year, month, 1)}. Why: a plan de charge is monthly, so every row must sit on
 * the 1st of its month. Letting the client send a full date would allow 2026-03-17, and then two
 * rows for March could both exist because the unique index on (project_id, user_id, period) would
 * see two different dates and would not catch the duplicate.</p>
 */
public record PlanChargeRequest(
        // WHAT: database id of the person this planned workload is for.
        // WHY @NotNull: the request is refused with 400 Bad Request before the service starts,
        //   because the column user_id is NOT NULL and points at the users table.
        // WITHOUT IT: a body with no "userId" would reach the service, loadUser(null) would call
        //   userRepository.findById(null), Spring Data would throw IllegalArgumentException, and
        //   the caller would get an unhelpful 500 instead of a readable 400.
        @NotNull Long userId,

        // WHAT: the year of the month being planned, for example 2026.
        // WHY @Min(2000) @Max(2100): the service builds the stored date with
        //   LocalDate.of(year, month, 1), and LocalDate happily accepts any year up to nine
        //   digits. These bounds turn a typo into a clean 400 instead of a stored row.
        // WITHOUT IT: "year": 20255 (one extra key press) would be accepted and saved. The line
        //   would then never appear again in any screen that lists the current or the next years,
        //   and nobody would understand where the planned days went.
        @NotNull @Min(2000) @Max(2100) Integer year,

        // WHAT: the month being planned, 1 for January up to 12 for December.
        // WHY @Min(1) @Max(12): same idea, but here the failure would be louder.
        //   LocalDate.of(2026, 13, 1) throws DateTimeException, which is not a validation error,
        //   so Spring turns it into a 500 Internal Server Error.
        // WITHOUT IT: a client bug sending a zero-based month ("month": 0 for January, which is
        //   how the JavaScript Date object counts months) would crash the endpoint with a 500
        //   instead of answering 400 with "month must be greater than or equal to 1".
        @NotNull @Min(1) @Max(12) Integer month,

        // WHAT: how many working days are planned for that person, that project and that month.
        //   Half days are allowed, so 7.5 is a valid value.
        // WHY BigDecimal and not double: BigDecimal keeps decimals exactly, and the column is
        //   NUMERIC(5,2). With double, 0.1 + 0.2 gives 0.30000000000000004, so a yearly total
        //   built by adding twelve monthly values would end at 89.99999999999999 days and the
        //   screen would show a number nobody can explain.
        // WHY @Positive (strictly greater than zero): it mirrors the database rule
        //   CHECK chk_pc_days (planned_days > 0 AND planned_days <= 31). Planning zero days has no
        //   meaning; the way to plan nothing is to not create the line at all.
        // WHY @DecimalMax("31"): no month has more than 31 days, so a larger figure is always a
        //   mistake. The limit is written as the text "31" and not as a number because a decimal
        //   written as a double literal cannot always be represented exactly; the annotation
        //   parses the text into a BigDecimal. It is inclusive, so exactly 31 passes.
        // WITHOUT THESE TWO: a value of 0, of -5 or of 310 (a slipped decimal point) would travel
        //   all the way to PostgreSQL, the CHECK constraint would reject it, and the caller would
        //   get a raw database error as a 500 instead of a readable 400 naming the field.
        // NOTE: this strict ">" is the one real difference with ChargeReelleRequest, which uses
        //   @PositiveOrZero. Reporting zero days actually worked is a real answer; planning zero
        //   days is not.
        @NotNull @Positive @DecimalMax("31") BigDecimal plannedDays
) {}
