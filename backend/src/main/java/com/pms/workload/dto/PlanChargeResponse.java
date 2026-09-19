package com.pms.workload.dto;

import java.math.BigDecimal;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of one planned-workload line as the API sends it back to the browser: who, which
 *   project, which month, how many days. It is a DTO (Data Transfer Object): a small read-only
 *   object built only to travel over HTTP, so the PlanCharge entity never leaves the backend.
 *
 * WHERE IT SITS IN THE FLOW
 *   table plan_charges -> PlanChargeRepository -> PlanCharge entity
 *     -> PlanChargeMapper.toResponse(...) / toResponseList(...) builds this record. MapStruct
 *        writes that mapper code at compile time from the @Mapping lines in the interface.
 *     -> PlanChargeService.findByProject(...) returns it, either as a List or wrapped in a
 *        Spring Data Page. The service decides there whether the caller sees the whole team or
 *        only their own lines (BR-062..064).
 *     -> WorkloadController returns it as JSON; on a create it also reads id() to build the
 *        Location header of the 201 Created answer.
 *     -> Angular: interface PlanCharge in core/models/workload.model.ts. The names below must
 *        match that interface exactly, field by field.
 *   The other direction, browser to backend, uses PlanChargeRequest (sister file in this folder).
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   - The controller would have to return the PlanCharge entity. That entity has
 *     @ManyToOne(fetch = FetchType.LAZY) links to Project and to User. Serializing it outside the
 *     transaction throws LazyInitializationException; and if the links were loaded, the JSON would
 *     contain the full User row, including passwordHash and tokenVersion. Both outcomes are
 *     unacceptable, so the API answers with this flat, hand-picked shape instead.
 *   - It is also the contract with the frontend. Renaming a column in the database does not break
 *     Angular as long as the mapper still fills these names.
 *
 * WHAT IS DELIBERATELY ABSENT
 *   No "deleted" flag, no createdAt / updatedAt. Rows are soft-deleted (deleted = true) and the
 *   repository queries already filter them out, so a deleted line never reaches this record; the
 *   flag is internal bookkeeping and has no meaning for the screen.
 * =====================================================================
 */

/**
 * One planned-workload line, flattened for the workload screen.
 *
 * <p>Why a record: it is final and has no setters, so once the mapper has built it the values
 * cannot be changed further down the call chain. Java writes the constructor, the accessors
 * ({@code id()}, {@code plannedDays()}, ...), {@code equals} and {@code hashCode}, and Jackson
 * turns it into JSON by calling those accessors, so the JSON field names are exactly the names
 * written below.</p>
 *
 * <p>Why the response is FLAT (projectId + projectCode + userId + userFullName) instead of holding
 * a nested project object and a nested user object: the workload table shows one row per line and
 * needs a label and an id, nothing more. A nested ProjectResponse and UserResponse would multiply
 * the size of every page of 20 rows and would tempt the frontend to depend on fields it does not
 * display. The ids are there so the screen can build links and filters; the code and the full name
 * are there so it can print something readable without a second HTTP call.</p>
 *
 * <p>Why year and month are two numbers here while the database stores one date: the entity keeps
 * a {@code LocalDate period} always set to the 1st of the month, and the mapper splits it back with
 * {@code getPeriod().getYear()} and {@code getPeriod().getMonthValue()}. This way the JSON carries
 * no fake day number that the screen would have to hide, and the shape matches
 * PlanChargeRequest, which also speaks in year + month.</p>
 */
public record PlanChargeResponse(
        // WHAT: primary key of the row in plan_charges.
        // WHY it is exposed: every later call needs it -- PUT and DELETE end with
        //   /plan-charges/{id}, and WorkloadController.createPlanCharge calls created.id() to
        //   build the Location header of the 201 answer.
        // WITHOUT IT: after creating a line the screen could never edit or delete it again,
        //   because it would have no way to name that exact row.
        Long id,

        // WHAT: id of the project this planned line belongs to.
        //   Filled by the mapper from project.id, which reads the lazy @ManyToOne link.
        // WHY: the screen uses it to check that the row it received really belongs to the project
        //   currently open, and to rebuild the scoped URL /api/projects/{projectId}/plan-charges.
        Long projectId,

        // WHAT: the short human code of the project (for example "PRJ-2026-014").
        // WHY it travels next to the id: the id alone means nothing to a reader. Sending the code
        //   here saves one HTTP call per row.
        // WITHOUT IT: a workload list of 20 rows would need 20 extra calls to /api/projects/{id}
        //   just to print a title, or the table would show bare numbers.
        String projectCode,

        // WHAT: id of the person the days are planned for.
        // WHY: the frontend compares it with the logged-in user to decide what to enable, and the
        //   edit form sends it back inside PlanChargeRequest, where the service checks it still
        //   matches the stored row.
        Long userId,

        // WHAT: "FirstName LastName" of that person, produced by User.getFullName() through the
        //   mapper helper annotated @Named("fullName").
        // WHY that helper exists rather than a plain source mapping: it returns null when the user
        //   object is null instead of calling getFullName() on nothing.
        // WHY only the name is sent: the User entity also carries email, passwordHash, tokenVersion
        //   and the role. None of that belongs in a workload table, and the password hash must
        //   never leave the server at all.
        String userFullName,

        // WHAT: the year of the planned month, computed by the mapper from period.getYear().
        Integer year,

        // WHAT: the month of the planned line, 1 for January up to 12 for December, computed from
        //   period.getMonthValue().
        // NOTE for the reader: getMonthValue() returns 1..12, unlike java.util.Calendar and unlike
        //   the JavaScript Date object, which both count months from 0. Getting this wrong would
        //   shift every line by one month on the screen.
        Integer month,

        // WHAT: the planned number of working days, for example 7.50.
        // WHY BigDecimal and not double: the column is NUMERIC(5,2) and BigDecimal keeps the exact
        //   decimal value and its scale, so 7.50 stays 7.50. With double the same value could be
        //   serialized as 7.499999999999999 and the table would show a figure the user never typed.
        BigDecimal plannedDays
) {}
