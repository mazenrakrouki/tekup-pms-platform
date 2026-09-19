package com.pms.mission.dto;

import java.time.LocalDate;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * The shape of the JSON the server sends back for one mission (a work trip).
 * It is the read side of the pair: MissionRequest comes in, MissionResponse
 * goes out. It is a DTO (Data Transfer Object): an object whose only job is to
 * carry data to the browser, never a database row.
 *
 * WHERE IT SITS IN THE FLOW
 *   MissionRepository.findActiveByProjectId / findActiveById
 *     -> gives Mission entities (with their Project and their User attached)
 *   -> MissionMapper.toResponse / toResponseList builds these records.
 *      MissionMapper is a MapStruct interface: the mapping code is generated at
 *      compile time, which ADR-018 makes mandatory in this project (no mapping
 *      written by hand).
 *   -> MissionService.findByProject / create / update returns them
 *   -> MissionController (GET, POST, PUT on /api/projects/{projectId}/missions)
 *   -> Angular: mission.service.ts list(), shown by missions.component.ts.
 *
 * WHY IT EXISTS
 * If the controller returned the Mission entity itself, three things would go
 * wrong:
 *   1. Mission holds `project` and `user` as @ManyToOne(fetch = LAZY). Turning
 *      the entity into JSON outside the transaction would try to load those
 *      proxies and throw LazyInitializationException, so the request would end
 *      in a 500 error.
 *   2. Even when loaded, the User object carries fields the mission screen must
 *      never receive, such as the password hash. Here only the id and the full
 *      name leave the server.
 *   3. The JSON would follow Mission -> Project -> ... and could loop.
 * Deleting this file would mean losing the safe, flat, stable shape the Angular
 * screen is written against.
 *
 * WHY IT IS FLAT (ids + one readable label)
 * The entity holds whole objects; this record keeps only `projectId` with
 * `projectCode`, and `userId` with `userFullName`. The id is what the UI sends
 * back on the next call, the label is what it prints. So the mission table can
 * display "PRJ-2025-001 / Ahmed Ben Ali" without a second HTTP call per row,
 * which on a list of 50 missions would mean 100 extra requests.
 * ============================================================================
 */

/**
 * One mission as the browser sees it.
 *
 * Gives back: the identity of the trip, who travels, for which project, what
 * for, where, and between which two dates.
 *
 * Why a `record`: it is read-only data. A record has no setters, so nothing
 * between the mapper and the JSON writer can change a value that was read from
 * the database. It also gives equals/hashCode for free, which makes tests that
 * compare an expected response with the real one straightforward.
 *
 * What is deliberately NOT here: the cost components of the mission, and any
 * total. Components are a separate call,
 * GET /api/projects/{projectId}/missions/{missionId}/composantes, answered with
 * ComposanteResponse. Why: the missions list would otherwise carry every line
 * of every trip even though the screen only shows them when the user opens one
 * mission.
 */
public record MissionResponse(
        // Primary key of the row in `missions`. The browser sends it back in
        // the URL of PUT and DELETE, and MissionController.create uses it to
        // build the Location header of the 201 answer.
        Long id,

        // WHAT: id of the project the trip belongs to.
        // WHY: MapStruct fills it from the entity with
        //      @Mapping(target = "projectId", source = "project.id"), so the
        //      nested Project object never reaches the JSON.
        // WITHOUT IT: the Angular screen would not know which project URL to
        //      call next, since every mission endpoint starts with
        //      /api/projects/{projectId}/ (ADR-021).
        Long projectId,

        // WHAT: the short business code of the project, e.g. "PRJ-2025-001".
        // WHY: @Mapping(target = "projectCode", source = "project.code") copies
        //      it so the UI can print something a human recognises instead of
        //      a bare number.
        // WITHOUT IT: the screen would show "project 42", or would have to call
        //      the projects endpoint again for every mission it displays.
        String projectCode,

        // WHAT: id of the employee who travels.
        // WHY: filled by @Mapping(target = "userId", source = "user.id"). The
        //      id, not the User object, so nothing else of the user leaks.
        // WITHOUT IT: the edit form could not pre-select the traveller, because
        //      a name is not a stable key (two people can share a name).
        Long userId,

        // WHAT: the traveller's name, ready to print.
        // WHY: the mapper does NOT read a field here; it calls its own method
        //      marked @Named("fullName"), selected by
        //      qualifiedByName = "fullName". That method returns null when the
        //      user is null instead of crashing.
        // WITHOUT THAT NULL CHECK: a mission whose user row was removed would
        //      throw NullPointerException inside generated mapping code, and
        //      the whole missions list would fail, not just one line.
        String userFullName,

        // The purpose of the trip, copied as it was sent in MissionRequest.
        String objet,

        // Where the trip takes place. May be null: the column `lieu` in V10
        // accepts NULL, so the Angular template must survive an empty value.
        String lieu,

        // WHAT: first day of the trip.
        // WHY LocalDate: Jackson writes it as the plain text "2026-05-10", with
        //      no hour and no time zone.
        // WITHOUT THAT CHOICE: a date sent with a time would be shifted by the
        //      browser's zone and a trip could appear to start one day earlier.
        LocalDate dateDebut,

        // Last day of the trip. The rule dateFin >= dateDebut was already
        // enforced on the way in (MissionService.validateDates and the database
        // constraint chk_mission_dates), so no check is repeated here.
        LocalDate dateFin
) {}
