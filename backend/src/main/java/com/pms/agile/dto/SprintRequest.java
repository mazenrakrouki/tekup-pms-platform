package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/* ============================================================================
 * FILE: SprintRequest
 *
 * WHAT THIS FILE IS
 *   The JSON body sent to create or fully update one sprint (one iteration of a
 *   project). It is a DTO ("Data Transfer Object"): an object whose only job is
 *   to carry the values of a form from the browser to the service.
 *
 * WHERE IT SITS IN THE FLOW
 *   Sprint form in the browser
 *     -> POST /api/projects/{projectId}/sprints       (create)
 *        PUT  /api/projects/{projectId}/sprints/{id}  (update)
 *     -> SprintController binds the JSON into this record; because the
 *        parameter is marked @Valid, the rules below run first and a broken
 *        body is refused with HTTP 400 before any service code executes.
 *     -> SprintService.create / .update call validateDates(request), then copy
 *        the values onto the Sprint entity and save it.
 *     -> SprintMapper builds the SprintResponse sent back.
 *
 * WHY IT EXISTS
 *   The Sprint entity inherits id, createdAt, createdBy, updatedAt, updatedBy
 *   and the soft-delete flag "deleted" from BaseEntity, and it also holds the
 *   Project link. None of those may come from a browser. If the controller
 *   bound JSON directly onto the entity, a body such as {"deleted":true} would
 *   let any user hide a sprint, and {"id":5} would let him overwrite a
 *   different one. This record contains only the five fields a user is allowed
 *   to send, so there is nothing else to attack.
 *
 * TWO VALIDATIONS, TWO PLACES - AND WHY
 *   Field-by-field rules (required, not blank) live here as annotations,
 *   because they need nothing but the request itself. The rule that compares
 *   two fields (end date not before start date) lives in
 *   SprintService.validateDates, because a standard annotation looks at one
 *   field at a time. The database repeats that same rule a third time with the
 *   constraint chk_sprint_dates (end_date >= start_date) in migration V27, so
 *   even a direct SQL insert cannot create an impossible sprint.
 * ============================================================================
 */

/**
 * One sprint as the client sends it, used for both create and full update.
 *
 * <p>WHY ONE RECORD FOR BOTH: a create and a full update accept exactly the
 * same five fields. Two separate records would slowly drift apart and one of
 * them would end up missing a rule, so an invalid sprint could be created
 * through the endpoint that was forgotten.
 *
 * <p>WHY A RECORD: a Java record is a short immutable data holder; the compiler
 * writes the constructor, the accessors ({@code name()}, not {@code getName()})
 * and equals/hashCode/toString. Immutable means the values checked by the
 * validator are exactly the values the service later reads.
 *
 * <p>WHY THERE IS NO projectId FIELD: the project comes from the URL
 * (/api/projects/{projectId}/sprints), and ADR-021 says
 * ProjectScopeInterceptor checks the project id found in that URL. If the body
 * carried its own projectId, the interceptor would approve one project while
 * the sprint was actually written into another.
 */
public record SprintRequest(
        // WHAT: @NotBlank refuses null, "" and a string of spaces only ("   ").
        // WHY: the name is what identifies the iteration on every screen, and
        // sprints.name is NOT NULL in migration V27.
        // WITHOUT it: @NotNull alone would accept "   ", and the backlog would
        // then offer a nameless sprint in its list, which nobody can pick with
        // confidence.
        @NotBlank String name,

        // The goal of the sprint, free text, optional on purpose: a sprint is
        // often created during planning and its goal written a moment later.
        // The column sprints.goal is nullable, so null and "" are both fine.
        String goal,

        // WHAT: @NotNull refuses a missing or null start date. The date arrives
        // as an ISO string such as "2026-04-01" and Jackson turns it into a
        // LocalDate.
        // WHY LocalDate and not a full date-time: a sprint starts on a day, not
        // at an hour. Using a timestamp would drag a time zone into the model,
        // and a sprint starting on 2026-04-01 in Tunis could be read as
        // 2026-03-31 elsewhere, shifting the whole burndown by one day.
        // WITHOUT @NotNull: SprintService.validateDates calls
        // request.endDate().isBefore(request.startDate()) and would throw a
        // NullPointerException, so the user would get a 500 server error
        // instead of a clear "start date is required".
        @NotNull LocalDate startDate,

        // WHAT: @NotNull refuses a missing or null end date.
        // WHY: same as above, plus the column sprints.end_date is NOT NULL.
        // NOTE: "after the start date" is NOT checked here. An annotation sees
        // one field at a time, so that comparison is done in
        // SprintService.validateDates, which throws BusinessRuleException.
        // WITHOUT that second check: a sprint ending before it starts would be
        // accepted by this record, then refused by the database constraint
        // chk_sprint_dates as a 500 error, and any burndown computed from a
        // negative duration would be meaningless.
        @NotNull LocalDate endDate,

        // WHAT: @NotNull refuses a missing or null status.
        // WHY: SprintService copies this value onto the entity as it arrives,
        // so the default PLANNED declared on the Sprint entity is overwritten
        // even by a null; and sprints.status is NOT NULL with a CHECK limited
        // to ('PLANNED','ACTIVE','CLOSED').
        // WITHOUT it: a sprint sent without a status would reach PostgreSQL as
        // NULL and be refused there, turning a simple form mistake into a 500
        // server error.
        @NotNull SprintStatus status
) {}
