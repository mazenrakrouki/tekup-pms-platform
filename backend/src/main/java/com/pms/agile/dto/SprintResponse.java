package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;

import java.time.LocalDate;

/* ============================================================================
 * FILE: SprintResponse
 *
 * WHAT THIS FILE IS
 *   The JSON one sprint looks like when it leaves the server. It is the "read"
 *   side of the sprint pair: SprintRequest comes in, this goes out.
 *
 * WHERE IT SITS IN THE FLOW
 *   SprintRepository loads Sprint entities
 *     -> SprintService (findByProject, create, update) hands them to
 *     -> SprintMapper.toResponse / .toResponseList, which fills this record
 *        (MapStruct generates that copy code at compile time)
 *     -> SprintController returns it
 *     -> Jackson turns it into JSON for the sprint list and the board header.
 *
 * WHY IT EXISTS
 *   Without it the service would return the Sprint entity, and two things break.
 *   1. Sprint holds @ManyToOne(fetch = FetchType.LAZY) Project. Lazy means
 *      Hibernate returns a stand-in and loads the real row only when it is
 *      touched. Jackson touches it after the transaction is closed, which
 *      throws LazyInitializationException: the user gets a 500 on a page that
 *      only wanted to list sprints.
 *   2. Even when it works, serialising the entity drags the whole Project
 *      object into the answer, and with it fields the agile screen has no
 *      business showing. This record sends two chosen fields instead:
 *      projectId and projectCode.
 *
 * NOTE ON THE PAIR OF FILES
 *   The shape here is on purpose NOT the same as SprintRequest. The request has
 *   no id (it comes from the URL) and no project (ADR-021 takes it from the
 *   URL); the response has both, because a client holding a list of sprints in
 *   memory needs to know which row is which.
 * ============================================================================
 */

/**
 * One sprint, flattened for the screen.
 *
 * <p>WHY A RECORD: this is read-only data going out. A record is immutable and
 * gives short accessors ({@code id()}, not {@code getId()}), which is why
 * SprintController can write {@code created.id()} when it builds the Location
 * header of its 201 response.
 *
 * <p>WHAT IS DELIBERATELY ABSENT: the audit fields inherited from BaseEntity
 * (createdAt, createdBy, updatedAt, updatedBy) and the soft-delete flag
 * "deleted". The sprint screens never show them, and the repository already
 * filters on {@code deleted = false}, so a client could never receive a deleted
 * sprint anyway.
 */
public record SprintResponse(
        // Database key of the sprint. The browser sends it back on PUT and
        // DELETE, and a backlog card refers to it through its sprintId.
        Long id,

        // Owning project, filled by the mapper from project.id.
        // WHY it is sent although the browser already had it in the URL: the
        // sprint list is kept in memory and cards are matched against it; a
        // sprint that carries its own project id can be checked instead of
        // trusted.
        Long projectId,

        // Human-readable project reference (for example "S2I-2026-014"), taken
        // from project.code by the mapper.
        // WHY: reports and printed sprint reviews must show something a person
        // recognises. "project 42" means nothing to a project manager.
        String projectCode,

        // Name of the iteration, for example "Sprint 4".
        String name,

        // The goal of the sprint, or null when nobody wrote one. Null here is
        // normal, not a failure: the column sprints.goal is nullable.
        String goal,

        // First day of the iteration, sent as an ISO string such as
        // "2026-04-01".
        // WHY LocalDate and not a timestamp: a sprint starts on a day. A
        // timestamp would carry a time zone, and a sprint starting 2026-04-01
        // in Tunis could be read as 2026-03-31 by a browser set elsewhere,
        // shifting the whole burndown by one day.
        LocalDate startDate,

        // Last day of the iteration. The database guarantees it is not before
        // startDate through the constraint chk_sprint_dates (migration V27), so
        // a client can compute a duration from these two dates without
        // defending itself against a negative result.
        LocalDate endDate,

        // PLANNED / ACTIVE / CLOSED. Sent as the enum name, so the JSON reads
        // "ACTIVE" and not the position 1.
        // WHY that matters: a position would change silently if a value were
        // one day inserted in the middle of the enum, and every front-end
        // filter built on numbers would then point at the wrong state. The
        // database stores the name too (VARCHAR with a CHECK), so JSON, Java
        // and SQL all speak the same word.
        SprintStatus status
) {}
