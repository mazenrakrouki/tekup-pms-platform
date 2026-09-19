package com.pms.team.dto;

import java.time.LocalDate;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of one line of a project team as the API sends it back. It is the read side of
 *   the team feature: a flat, ready-to-display view of one row of the table team_assignments,
 *   together with the few labels the screen needs (project code, project name, member name).
 *
 * WHERE IT SITS IN THE FLOW
 *   TeamAssignmentRepository (findActiveById / findActiveByProjectId / findActiveByUserId,
 *   which use JOIN FETCH so the project and the user are already loaded)
 *     -> TeamAssignmentService (permission VIEW_TEAM to read, ASSIGN_DEVELOPER to write)
 *     -> TeamAssignmentMapper.toResponse(...) / toResponseList(...): MapStruct writes the copy
 *        code at compile time and fills the flat fields below from the nested objects
 *        (project.id, project.code, project.name, user.id, user.getFullName()).
 *     -> TeamController returns it on:
 *          GET    /api/projects/{projectId}/team
 *          POST   /api/projects/{projectId}/team        (plus a Location header built from id())
 *          PUT    /api/projects/{projectId}/team/{id}
 *          GET    /api/users/{userId}/assignments
 *     -> Jackson turns it into JSON -> Angular team screen.
 *
 * WHY IT EXISTS (what would break if you deleted it and returned the entity instead)
 *   - Leak: TeamAssignment holds a User, and User holds passwordHash. Returning the entity would
 *     publish the hashed password of every team member in the HTTP answer.
 *   - Crash: Jackson builds the JSON in the controller, after the transaction is closed. The
 *     repository queries JOIN FETCH only the project and the user, so Jackson would then walk
 *     into project.director and project.chefProjet, which are LAZY, and throw
 *     LazyInitializationException on a list that worked fine inside the service.
 *   - Weight: User loads its Role EAGERLY, so the answer would also drag the role and its
 *     permissions behind every single line of the table.
 *   - Contract: this record freezes what the front end receives. Renaming a column or a field in
 *     the entity does not change the JSON, as long as the mapper is adjusted.
 *
 * WHAT IS DELIBERATELY ABSENT
 *   No "deleted" flag and no audit columns (createdAt, createdBy, ...). Removing a member is a
 *   soft delete (the row stays, deleted = true) and all three repository queries filter
 *   deleted = false, so every object of this type is by construction an ACTIVE assignment; a
 *   flag that is always false would only invite the front end to write a useless test.
 * =====================================================================
 */

/**
 * One active team assignment, flattened for display.
 *
 * <p>Why a record: the answer must not be modified after the service built it, and a record is
 * final with no setters, so that is guaranteed by the language rather than by discipline. Java
 * also writes the constructor and the accessors, which is exactly what MapStruct and Jackson
 * need, with no boilerplate to keep in sync.</p>
 *
 * <p>Why the fields are flat instead of holding a Project object and a User object: the team
 * screen shows one table, and each row needs only a handful of labels. Sending the whole nested
 * objects would multiply the size of the answer, drag along fields the screen never uses
 * (passwordHash among them), and force the front end to dig through several levels for a simple
 * cell. Flat also means the same record fits both readings: "the team of a project" and "the
 * projects of a person", which is why it carries the project labels AND the user labels.</p>
 */
public record TeamAssignmentResponse(
        // WHAT: id of the ASSIGNMENT row itself (team_assignments.id, inherited from BaseEntity).
        //   It is neither the project id nor the user id.
        // WHY it is exposed: the client needs it to build the next call,
        //   PUT / DELETE /api/projects/{projectId}/team/{id}, and the controller also uses it to
        //   build the Location header of the 201 Created answer.
        // WITHOUT IT: after adding a member the screen would have no way to edit or remove that
        //   line without reloading the whole list to find the id again.
        Long id,
        // WHAT: id of the project this assignment belongs to.
        // WHY: it is what the front end puts back in the URL /api/projects/{projectId}/team/{id}.
        //   It matters most for GET /api/users/{userId}/assignments, where the rows come from
        //   several different projects and the id cannot be guessed from the URL that was called.
        Long projectId,
        // WHAT: the human reference of the project, for example "S2I-2022-001".
        // WHY it is copied here: the screen shows the code, not the numeric id. Sending it with
        //   the row avoids one extra HTTP call to /api/projects/{id} per line of the table.
        String projectCode,
        // WHAT: the project title in clear text.
        // WHY: same reason as the code, and it is what a human reads first in the list of a
        //   member's assignments.
        String projectName,
        // WHAT: id of the user who is assigned.
        // WHY: lets the screen link the row to the member's page, and lets the front end tell two
        //   people apart when they happen to have the same displayed name.
        Long userId,
        // WHAT: the member's name as one string, for example "Ahmed Ben Ali" (first name, one
        //   space, last name).
        // WHY it is built and not stored: there is no full_name column. User.getFullName() joins
        //   firstName and lastName, and the mapper calls it through its qualifiedByName = "fullName"
        //   method, which also returns null safely if the user is missing.
        // WITHOUT IT: the team table would display bare numbers, and the front end would have to
        //   fetch every user one by one just to print a name.
        String userFullName,
        // WHAT: the job label stored for this member on this project. The demo data of
        //   V26__seed_demo_data.sql uses "DEVELOPPEUR" and "CHEF_PROJET", but the column is free
        //   text, so any label sent in TeamAssignmentRequest is echoed back exactly as it came.
        // WHY it is only a label: the application never reads this text to decide what the person
        //   may do. Rights come from the permissions attached to the user's role, checked with
        //   @PreAuthorize on the service methods. Confusing the two is the mistake to avoid here.
        String roleInTeam,
        // WHAT: first day of the assignment, sent as ISO text, for example "2026-04-01".
        // WHY LocalDate and not LocalDateTime: an assignment starts on a day, not at an hour, and
        //   a date with no time zone cannot be shifted by one day when the browser and the server
        //   sit in different time zones.
        LocalDate startDate,
        // WHAT: last day of the assignment, or null when the person is still on the project with
        //   no planned end.
        // WHY null is allowed: it is the normal case for a running project, and the screen shows
        //   it as "in progress". The front end must therefore test for null before formatting it;
        //   without that test the cell would print "null" to the user.
        LocalDate endDate
) {}
