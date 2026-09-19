package com.pms.governance.dto;

/*
 * WHAT THIS FILE IS
 * The shape of the JSON body a client sends when it creates or edits a
 * deliverable ("livrable") of a project.
 * DTO = Data Transfer Object: a small object whose only job is to carry data
 * between the browser and the server, so the database entity never leaves the
 * backend.
 *
 * WHERE IT SITS IN THE FLOW
 * Browser (POST or PUT /api/projects/{projectId}/livrables)
 *   -> LivrableController, which declares "@Valid @RequestBody LivrableRequest".
 *      Jackson builds this record from the JSON, then Bean Validation applies the
 *      rule below.
 *   -> LivrableService.create() / update(), which read request.titre(),
 *      request.description(), request.dateEcheance() and copy them into the
 *      Livrable entity.
 *   -> LivrableRepository saves the row in table "livrables" (migration V11).
 * The answer comes back as LivrableResponse.
 *
 * WHY IT EXISTS
 * This is the clearest example in the governance module of a DTO protecting a
 * business rule. A deliverable follows a state machine:
 * EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE, and each step has its own endpoint
 * (PATCH .../demarrer, .../livrer, .../valider) with its own check in
 * LivrableService. There is deliberately NO "statut" field in this record.
 * If the controller accepted the Livrable entity instead, a user could send
 * {"statut": "VALIDE"} in a simple PUT and jump straight to validated, skipping
 * delivery altogether - the state machine would exist in the code but mean
 * nothing.
 */

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * WHAT IT IS
 * A Java "record": a short, immutable data holder. The compiler generates the
 * constructor, the accessors titre(), description(), dateEcheance(), plus
 * equals(), hashCode() and toString().
 *
 * WHY A RECORD RATHER THAN A CLASS WITH SETTERS
 * No setter means the object checked by the validator is the exact object the
 * service later reads, so a value cannot be changed between the check and the
 * save.
 *
 * WHAT IS DELIBERATELY ABSENT
 * - statut: see above, it is moved only by the three PATCH endpoints.
 * - projectId: it comes from the URL, where ProjectScopeInterceptor (ADR-021)
 *   checks it against the caller's project scope before the controller runs.
 *   A project id in the body would let a caller satisfy the scope check with
 *   one project and write the row into another.
 */
public record LivrableRequest(
        // @NotBlank refuses null, "" and a value made only of spaces.
        // Why: "titre" is VARCHAR(255) NOT NULL in V11, and the title is the only
        // thing that identifies the deliverable on screen.
        // Without it: a user presses Save on an empty form, the row is created,
        // and the deliverables table shows a line with nothing in the first
        // column that nobody can tell apart from the next one.
        @NotBlank String titre,

        // Free text, optional on purpose: the column "description" is
        // VARCHAR(1000) and nullable in V11. A deliverable is often registered
        // with just a title during the kick-off and described later.
        String description,

        // Due date. LocalDate means a calendar day with no time and no time zone;
        // Jackson reads and writes it as "2026-09-18".
        // Why LocalDate and not LocalDateTime or Date: a deadline is a day, not
        // an instant. With a timestamp, a user in another time zone could see the
        // deadline shift by one day, and a deliverable due on the 30th would
        // appear late on the 29th.
        // No @NotNull: the column date_echeance is nullable, because a
        // deliverable can be listed before its date is agreed with the client.
        LocalDate dateEcheance
) {}
