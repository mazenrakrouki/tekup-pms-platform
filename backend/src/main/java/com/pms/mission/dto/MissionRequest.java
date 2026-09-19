package com.pms.mission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * The shape of the JSON body sent by the browser when someone creates or edits
 * a mission. In this project a "mission" is a work trip: one employee travels
 * for one project, from a start date to an end date.
 * This is a DTO (Data Transfer Object): a small object whose only job is to
 * carry data between the browser and the server. It is not a database row.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular missions screen (missions.component.ts, missionForm)
 *     -> POST /api/projects/{projectId}/missions
 *        or  PUT  /api/projects/{projectId}/missions/{id}
 *     -> MissionController.create / update. The controller writes @Valid in
 *        front of this object, so every rule written below is checked BEFORE
 *        our own code runs. A broken body never reaches the service.
 *     -> MissionService.create / update. It reads the fields one by one and
 *        copies them into a Mission entity (com.pms.mission.entity.Mission).
 *     -> MissionRepository.save -> row in table `missions` (migration V10).
 *   The answer sent back is a different object: MissionResponse, same folder.
 *
 * WHY IT EXISTS
 * Without it the controller would have to accept the Mission entity directly.
 * Two things would break:
 *   1. The caller could then set any field of the entity, including `id`,
 *      `deleted`, `createdBy` or the whole Project object. Someone could move
 *      a mission to another project just by putting a different project in the
 *      body. This file only exposes the five values a user is allowed to send.
 *   2. Validation rules (@NotNull, @NotBlank) would have to live on the entity,
 *      where they would also fire on internal saves such as the soft delete.
 *
 * WHY THERE IS NO projectId FIELD HERE
 * The project id travels in the URL, not in the body. This is what makes
 * ADR-021 possible: ProjectScopeInterceptor reads the URL
 * (/api/projects/{id}/**) and checks that the current user is allowed on that
 * project, before the controller method is even called. If projectId were a
 * body field the interceptor could not see it, and the permission check alone
 * (@PreAuthorize("hasAuthority('MANAGE_MISSION')") on MissionService) would
 * let a project manager create missions inside a project that is not his.
 * ============================================================================
 */

/**
 * Body of "create a mission" and "update a mission".
 *
 * Gives back: nothing by itself, it is pure data. Spring (Jackson) builds one
 * from the JSON body, Bean Validation checks it, MissionService reads it.
 *
 * Why a `record` and not a normal class: a record is immutable. Once Spring has
 * built it, no filter, no interceptor and no service can quietly change a field
 * between the validation step and the save step. The values the jury sees
 * validated are exactly the values written to the database. A mutable class
 * with setters would give no such guarantee.
 *
 * Note on lengths: the columns are `objet VARCHAR(500)` and `lieu VARCHAR(255)`
 * in V10, but there is no @Size rule below, so a text that is too long is only
 * stopped by PostgreSQL, as a 500 error instead of a clear 400.
 */
public record MissionRequest(
        // WHAT: id of the employee who makes the trip. @NotNull refuses a body
        //       where "userId" is absent or null, and answers 400 Bad Request.
        // WHY: the column `missions.user_id` is NOT NULL and has a foreign key
        //      to `users`, so a mission without a traveller cannot exist.
        // WITHOUT IT: a body with no userId would reach
        //      MissionService.loadUser(null) -> userRepository.findById(null),
        //      which throws deep inside Spring Data and the user would get an
        //      ugly 500 server error instead of a clear "userId is required".
        // NOTE: the Angular form only offers the members of the project team,
        //       but nothing here re-checks that on the server side.
        @NotNull Long userId,

        // WHAT: the purpose of the trip, for example "client kick-off meeting".
        //       @NotBlank refuses null, "" and a text made only of spaces.
        // WHY: this text is the only human label of the mission; the missions
        //      table in the UI shows it as the main column.
        // WITHOUT IT: @NotNull alone would accept "   ", and the list would
        //      show an empty line that nobody can recognise or search for.
        @NotBlank String objet,

        // WHAT: where the trip takes place, for example "Tunis" or "Paris".
        // WHY NO ANNOTATION: this one is on purpose optional, because the
        //      column `lieu VARCHAR(255)` in V10 accepts NULL. A trip can be
        //      booked before the exact place is known.
        // WITHOUT THIS FIELD: the user could not say where he goes, and the
        //      expense components below (ticket, stay) would have no context.
        String lieu,

        // WHAT: first day of the trip. @NotNull -> 400 if missing.
        // WHY LocalDate AND NOT LocalDateTime: a trip is counted in whole days,
        //      never in hours. LocalDate carries no time and no time zone.
        // WITHOUT THAT CHOICE: a date sent as an instant by a browser in
        //      Europe/Paris and read by a server in UTC could move one day
        //      backwards, and a 3-day trip would be paid as a 2-day trip.
        @NotNull LocalDate dateDebut,

        // WHAT: last day of the trip. @NotNull -> 400 if missing.
        // WHY THE "END AFTER START" RULE IS NOT HERE: an annotation placed on a
        //      field can only look at that one field, it cannot compare two.
        //      So the comparison lives in MissionService.validateDates(), which
        //      throws BusinessRuleException, and it is guarded a second time in
        //      the database by CHECK constraint chk_mission_dates (V10).
        // WITHOUT THOSE TWO GUARDS: a mission from 10 May to 2 May could be
        //      saved, and any later count of days would be negative.
        @NotNull LocalDate dateFin
) {}
