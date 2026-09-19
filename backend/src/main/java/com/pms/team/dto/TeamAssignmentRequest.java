package com.pms.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of the JSON body a client sends to put a person on a project team, or to edit
 *   that person's line in the team list. It is a DTO (Data Transfer Object): a small object
 *   whose only job is to carry data between the outside world and our code, so that the
 *   database entity never travels over HTTP.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular team screen
 *     -> POST /api/projects/{projectId}/team        (add a member)
 *     -> PUT  /api/projects/{projectId}/team/{id}   (edit an existing line)
 *     -> ProjectScopeInterceptor (preHandle) runs FIRST, on the URL alone (ADR-021).
 *     -> TeamController: only then does Spring turn the JSON into this record, and @Valid on the
 *        parameter runs the checks written below BEFORE the controller body and the service run.
 *        So the order is: scope check on the URL, then body checks, then business rules.
 *     -> TeamAssignmentService.assign(...) / update(...): checks the permission
 *        ASSIGN_DEVELOPER, applies the business rules, then copies these values into a
 *        TeamAssignment entity.
 *     -> TeamAssignmentRepository -> table team_assignments.
 *   The answer comes back the other way as a TeamAssignmentResponse (the sister file in this
 *   same folder), built by TeamAssignmentMapper.
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   - The controller would have to accept the TeamAssignment entity itself. A caller could then
 *     send "id": 7 or "deleted": true in the body and write fields the API must never expose.
 *   - These annotations are the first wall of the application. Without this file there is no
 *     single place saying "userId is required, the role label is at most 50 characters", so the
 *     same checks would be copy-pasted inside the service and would drift apart over time.
 *
 * ONE IMPORTANT DETAIL ABOUT THE TWO USES
 *   assign() reads all four values. update() reads only roleInTeam, startDate and endDate: it
 *   keeps the member already stored on the row and ignores userId. So this record is on purpose
 *   slightly wider than what an edit really needs; the same shape serves both endpoints.
 * =====================================================================
 */

/**
 * Input for "add this person to this project team" and "edit this team line".
 *
 * <p>Why a record instead of a normal class with fields and setters: a record is final and has
 * no setters, so once Spring has built it from the JSON nobody can change the values while the
 * request is being handled. Java also writes the constructor, the accessors
 * ({@code userId()}, {@code roleInTeam()}, ...), {@code equals} and {@code hashCode} for us, so
 * there is no hand-written boilerplate that could silently be wrong. Jackson, the library that
 * reads the JSON, knows how to build a record through its single constructor, so no extra
 * annotation is needed here.</p>
 *
 * <p>Note that the project id is NOT a field of this record. It comes from the URL
 * ({@code /api/projects/{projectId}/team}). That is deliberate: per ADR-021 the
 * ProjectScopeInterceptor reads the project id from the URL and checks that the caller is
 * allowed on THAT project before the controller is reached. If the project id travelled inside
 * the body instead, the interceptor would not see it, and a user holding ASSIGN_DEVELOPER could
 * aim at a project outside their own perimeter. Permission alone is not enough; the scope check
 * needs the id in the path.</p>
 */
public record TeamAssignmentRequest(
        // WHAT: database id of the user we are putting in the team.
        // WHY @NotNull: the request is refused with 400 Bad Request before the service starts,
        //   because the column user_id is NOT NULL and points to the users table.
        // WITHOUT IT: a body with no "userId" would reach the service, loadUser(null) would call
        //   userRepository.findById(null), Spring Data would throw IllegalArgumentException, and
        //   GlobalExceptionHandler turns that one into 409 Conflict with the raw technical text
        //   "The given id must not be null". The caller would get a wrong status and a message he
        //   cannot act on. With the annotation he gets a 400 whose body joins the field name and
        //   the rule ("userId : must not be null"), so the screen can point at the guilty input.
        // Read only when adding a member; update() keeps the member already on the row.
        @NotNull Long userId,
        // WHAT: free text label of the job this person does on this project. The demo data uses
        //   "DEVELOPPEUR" and "CHEF_PROJET", but nothing in the code restricts the wording.
        //   It is a display label, NOT a security role: the application never decides what
        //   someone may do from this text, because authorization is permission-based.
        // WHY @NotBlank: it refuses null, "" and a value made only of spaces, so the team list can
        //   never show an empty line. @NotNull alone would happily accept "   ".
        // WHY @Size(max = 50): the column role_in_team is VARCHAR(50) (V6__schema_team.sql).
        // WITHOUT @Size: a 200-character label would pass here and only fail down in PostgreSQL
        //   ("value too long for type character varying(50)"). Spring wraps that in
        //   DataIntegrityViolationException, which GlobalExceptionHandler turns into 409 Conflict
        //   with the vague text "contrainte de donnees (doublon ou valeur invalide)". The user
        //   would never learn WHICH field is wrong, instead of the 400 naming roleInTeam.
        @NotBlank @Size(max = 50) String roleInTeam,
        // WHAT: first day the person counts as a member of this project.
        // WHY @NotNull: start_date is NOT NULL in the table, and the service compares the end date
        //   to it with endDate.isBefore(startDate).
        // WITHOUT IT, two different bad answers, and neither one is usable:
        //   - if an end date was also sent, endDate.isBefore(null) throws NullPointerException,
        //     which falls into the catch-all handler and answers 500 "Erreur interne du serveur";
        //   - if no end date was sent, the comparison is skipped and PostgreSQL refuses the
        //     NOT NULL column, which comes back as 409 Conflict.
        //   With the annotation the caller simply gets 400 naming the field startDate.
        // On the wire the value is ISO text, for example "2026-04-01" (Jackson default for LocalDate).
        @NotNull LocalDate startDate,
        // WHAT: last day of the assignment. It carries NO annotation on purpose: null is a normal,
        //   valid value and means "still on the project, no end date planned yet".
        // WHY the "end after start" rule is not here: that rule needs two fields at the same time,
        //   which a single-field annotation cannot express. TeamAssignmentService checks it and
        //   throws BusinessRuleException, which the caller receives as 422 Unprocessable Entity,
        //   and the table repeats the same rule as a last safety net with the constraint
        //   chk_ta_dates: CHECK (end_date IS NULL OR end_date >= start_date) (V6__schema_team.sql).
        // WITHOUT that pair of checks: a member could be stored as ending before he started, and
        //   any report counting worked days would return a negative number.
        LocalDate endDate
) {}
