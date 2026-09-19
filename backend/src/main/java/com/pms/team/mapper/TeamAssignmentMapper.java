package com.pms.team.mapper;

import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.entity.TeamAssignment;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one row of the team table (the TeamAssignment entity,
 * table "team_assignments", created by migration V6__schema_team.sql) and the
 * flat JSON object sent to the browser (the TeamAssignmentResponse DTO).
 *
 * A "team assignment" (affectation) is the link between ONE person and ONE
 * project: who works on what, with which role inside the team (for example
 * "Developpeur back", "Chef de projet"), from which date, and until which date
 * when the end is already known.
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here
 *    TeamAssignment, which maps the table team_assignments.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record
 *    TeamAssignmentResponse.
 *  - "mapper" = the piece of code that copies the fields of an entity into a
 *    DTO. This file copies nothing itself: it only describes the copying
 *    rules, and MapStruct writes the real code from them.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   TeamController  ->  two families of URLs:
 *                       /api/projects/{projectId}/team   (the team of one
 *                                                         project)
 *                       /api/users/{userId}/assignments  (every project of one
 *                                                         person)
 *     TeamAssignmentService  ->  opens the transaction and holds the security
 *                                check (VIEW_TEAM to read, ASSIGN_DEVELOPER to
 *                                add, change or remove a member)
 *       TeamAssignmentRepository ->  returns TeamAssignment entities, already
 *                                    filtered on deleted = false and already
 *                                    loaded with JOIN FETCH on the project and
 *                                    on the user
 *         TeamAssignmentMapper   ->  THIS FILE: TeamAssignment entity ==>
 *                                    TeamAssignmentResponse
 *           Jackson              ->  writes the record as JSON
 *             Angular            ->  reads it as the "TeamAssignment"
 *                                    interface in core/models/team.model.ts,
 *                                    through core/services/team.service.ts.
 *                                    TeamService.list(projectId) calls GET
 *                                    /api/projects/{projectId}/team, and six
 *                                    places in the front end use its answer:
 *                                      - project-detail.component.ts prints
 *                                        the team tab (one row per member);
 *                                      - workload, agile, missions and
 *                                        governance (each one .component.ts)
 *                                        build a "pick a member" dropdown out
 *                                        of userId + userFullName, so a person
 *                                        outside the team cannot be picked;
 *                                      - shared/project-picker.component.ts
 *                                        only counts the rows, to show the
 *                                        team size of the selected project.
 *                                    Note: no Angular screen calls GET
 *                                    /api/users/{userId}/assignments today.
 *                                    That endpoint exists and is served by the
 *                                    same mapper; the three project fields
 *                                    below are what makes its answer readable.
 *
 * This file calls nothing itself, except its own helper fullName() at the
 * bottom. MapStruct (the annotation processor declared in pom.xml, ADR-018)
 * reads this interface at compile time and writes the real class
 * TeamAssignmentMapperImpl; Spring injects that generated class into
 * TeamAssignmentService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. This is one of the most dangerous entities in the application to publish
 *     as it is. A TeamAssignment holds a whole User object, and User holds
 *     passwordHash (the encrypted password), email, the active and firstLogin
 *     flags, tokenVersion (the counter used to revoke every session of that
 *     person at once) and the whole Role with its permissions. Returning the
 *     entity would put all of that in the JSON of a simple team list. This
 *     mapper lets exactly two values of the user out: the id and the printed
 *     name.
 *  2. A TeamAssignment also holds a whole Project object, so a team list would
 *     publish the budget, the client and the dates of the project to anyone
 *     allowed to read the team.
 *  3. TeamAssignment.project and TeamAssignment.user are both
 *     @ManyToOne(fetch = FetchType.LAZY), so they are only placeholders until
 *     Hibernate loads them. If Jackson tried to read one after the transaction
 *     is closed, the call would fail with LazyInitializationException instead
 *     of returning the team.
 *  4. The entity also carries createdAt, createdBy, updatedAt, updatedBy and
 *     deleted from BaseEntity. Those are internal bookkeeping columns; sending
 *     them would make them part of the public contract by accident.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_TEAM') or
 *    hasAuthority('ASSIGN_DEVELOPER') is applied on the SERVICE method, never
 *    on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL matching
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own). Note which URLs that
 *    pattern covers: /api/projects/{projectId}/team is inside it, so the four
 *    project-scoped calls pass the scope check as well as the permission
 *    check; /api/users/{userId}/assignments has a different shape, so for that
 *    one the permission check on the service method is what applies.
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true (see
 *    TeamAssignmentService.remove, which calls setDeleted(true) and saves the
 *    row again - it never runs a SQL DELETE). That
 *    filter lives in the repository queries (AND ta.deleted = false), not
 *    here. Keeping the row is what lets the history of past members survive,
 *    and the partial unique index uk_ta_project_user_active of V6 - unique on
 *    (project_id, user_id) WHERE deleted = FALSE - is what still allows the
 *    same person to be put back on the same project later.
 *  - It never decides whether a member is "active today". It copies startDate
 *    and endDate as they are and lets the screen compare them with the current
 *    date. A member whose endDate is already in the past is still returned.
 *    Comparing dates here would hide a business rule inside a translator,
 *    where nobody would look for it, and would make the same list mean two
 *    different things depending on the day it was read.
 *  - It never validates anything. The rule "the end date cannot come before
 *    the start date" lives in TeamAssignmentService, and is doubled in the
 *    database by the constraint chk_ta_dates of V6.
 *
 * ============================================================================
 * SISTER FILES
 * ============================================================================
 * Six mappers of this project flatten a User the very same way, with the same
 * qualifiedByName = "fullName" and the same small helper at the bottom of the
 * file. MissionMapper, ChargeReelleMapper, PlanChargeMapper and ResourceMapper
 * apply it to a field also called "user", like this one; ProjectMapper applies
 * it twice, to director and to chefProjet, and DemandeChangementMapper applies
 * it to demandeur. That repetition is deliberate: each mapper stays readable on
 * its own, and the shared behaviour that really matters - how a full name is
 * built - lives in one single place, User.getFullName().
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: TeamAssignmentService receives a TeamAssignmentMapper through its
// constructor (Lombok @RequiredArgsConstructor), so Spring has to hold one
// instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// TeamAssignmentMapper".
@Mapper(componentModel = "spring")
public interface TeamAssignmentMapper {

    /*
     * WHAT IT DOES: turns one TeamAssignment row into one
     * TeamAssignmentResponse record, ready to be serialised to JSON. It builds
     * a new object and never modifies the entity it is given. Given null it
     * gives back null: the generated code starts with "if (ta == null) return
     * null;", so a missing row never becomes a NullPointerException here.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to TeamAssignmentResponse and
     * forget to say where it comes from, and the BUILD warns immediately; a
     * hand-written mapper would compile fine and quietly send null to the
     * browser. ADR-018 makes MapStruct mandatory in this project for exactly
     * that reason.
     *
     * WHY THE PARAMETER IS CALLED ta: the name is only used inside the
     * generated code, and TeamAssignment is long enough to make the generated
     * lines unreadable. The short name changes nothing for the caller.
     *
     * WHO CALLS IT DIRECTLY: TeamAssignmentService.assign() and
     * TeamAssignmentService.update(), each time on the object just returned by
     * repository.save(), so that the browser gets the saved row back with its
     * database-generated id.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * roleInTeam, startDate and endDate. Only the five names that do not match
     * are declared below.
     *
     * ABOUT roleInTeam: it is a plain String, not an enum. The column is
     * VARCHAR(50) in V6, the entity repeats length = 50, and
     * TeamAssignmentRequest repeats @Size(max = 50). It is free text on
     * purpose: the role a person holds inside one project team is not the same
     * thing as the security role that gives permissions, and this project must
     * never mix the two. The mapper copies the string as it is, with no
     * translation: the value is typed by a user, so there is no Transloco key
     * behind it.
     *
     * ABOUT startDate AND endDate: both are LocalDate, a date with no time and
     * no time zone. Jackson writes them as "2026-03-15". Had the entity used
     * LocalDateTime or Date, the value would carry an hour, which would be
     * converted to the time zone of the reader, and a member starting on the
     * 1st could appear as starting on the 31st for a reader one zone behind.
     * endDate is nullable in the database and optional in the Angular
     * interface ("endDate?: string"), and a null there means "still on the
     * project, no end planned".
     *
     * THE FIVE @Mapping LINES BELOW must stay glued to the method signature:
     * an annotation always describes the element written right after it, so a
     * line of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads ta.getProject().getId() and puts it in the flat field
     *            projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null. The team
     *            tab would survive that, because it already knows the project
     *            from the URL it called; but the answer of
     *            /api/users/{userId}/assignments would become useless, since
     *            there the id is the only thing that says which project each
     *            row belongs to, and no link could be built from it.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads ta.getProject().getCode(), the short readable project
     *            code, and copies it into the response.
     *      WHY:  a list of the projects of one person is read by humans, who
     *            recognise the project code, not a numeric id. Sending it in
     *            the same answer avoids a second HTTP call just to display one
     *            string.
     *      WITHOUT IT: the answer of /api/users/{userId}/assignments would show
     *            bare numbers, and its reader would have to call
     *            /api/projects/{id} once per row to print one short string.
     *
     *  (3) target = "projectName", source = "project.name"
     *      WHAT: reads ta.getProject().getName(), the long readable title of
     *            the project.
     *      WHY:  the code alone is short but not always enough to recognise a
     *            project at a glance, so the two travel together and the reader
     *            can show the code next to the title.
     *      WITHOUT IT: the same bare numbers, or one extra HTTP call per row.
     *
     *  (4) target = "userId", source = "user.id"
     *      WHAT: reads ta.getUser().getId() - the primary key of the assigned
     *            person - and puts it in the flat field userId.
     *      WHY:  the front end needs that id as a key, not as decoration. Two
     *            different uses lean on it:
     *              a) the four "pick a member" dropdowns (workload, agile,
     *                 missions, governance) use it as the value of each option
     *                 and send it back as the person of a planned or real
     *                 charge, of a mission, of a backlog card or of a change
     *                 request. For the workload and the agile board the server
     *                 checks the same thing again: ChargeReelleService,
     *                 PlanChargeService and BacklogItemService all call
     *                 TeamAssignmentRepository
     *                 .existsByProjectIdAndUserIdAndDeletedFalse and refuse a
     *                 person who is not an active member, so a hand-made HTTP
     *                 call cannot go around the dropdown;
     *              b) project-detail.component.ts builds the set of the ids
     *                 already on the team out of this field, and removes those
     *                 people from the "add a member" picker.
     *      WITHOUT IT: the field arrives as null. The dropdowns can no longer
     *            tell two members apart - two people with the same printed name
     *            become one single option - and the "add a member" picker keeps
     *            offering somebody who is already on the team. The POST is then
     *            refused by existsByProjectIdAndUserIdAndDeletedFalse in
     *            TeamAssignmentService.assign with "the user is already a member
     *            of this project", which looks like a bug to the user.
     *
     *  (5) target = "userFullName", source = "user",
     *      qualifiedByName = "fullName"
     *      WHAT: note the source here is the WHOLE User object, not one of its
     *            properties. MapStruct cannot turn a User into a String by
     *            itself, so qualifiedByName sends the object through the helper
     *            method named "fullName" written at the bottom of this file.
     *      WHY:  without the name the browser would get only userId and would
     *            have to call /api/users/{id} once per row. A team of ten
     *            people would mean ten extra HTTP calls (the "N+1 calls"
     *            problem) just to print ten names.
     *      WHY qualifiedByName RATHER THAN NOTHING: it names the helper
     *            explicitly instead of letting MapStruct look for any method
     *            able to turn a User into a String. Today only one such method
     *            exists, so it would work either way; the day somebody adds a
     *            second one - initials(User), for example - the build would
     *            stop with "Ambiguous mapping methods found". Naming the helper
     *            removes that guesswork for good.
     *      WHY ONLY THE NAME: see point 1 of the file header. Passing the User
     *            object straight into the response would publish the password
     *            hash, the email, the token version and the whole Role with
     *            its permissions.
     *
     * A NOTE ON THE DOTTED PATHS "project.id", "project.code", "project.name"
     * AND "user.id": these are nested source paths. For such a path MapStruct
     * generates a small private helper that checks every step for null, so a
     * row with no project would give projectId = null instead of a
     * NullPointerException. That safety net costs nothing here, because
     * project_id and user_id are both declared NOT NULL in V6 and repeated as
     * nullable = false on the entity.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * both ta.project and ta.user are LAZY, so each may still be a proxy (a
     * shell object that only knows its id). getId() on a proxy costs nothing,
     * Hibernate already has that value; but getCode(), getName() and
     * getFullName() read real columns, so each one forces a trip to the
     * database. That is why all three read queries of TeamAssignmentRepository
     * write "JOIN FETCH ta.user JOIN FETCH ta.project": both sides arrive
     * already loaded and this mapping costs zero extra query. Remove those
     * JOIN FETCH clauses and the code still works - the service methods are
     * @Transactional, so the Hibernate session is still open - but a team of
     * ten members silently fires twenty extra SELECT statements, two per row.
     * This is the classic "N+1 queries" problem, and it is the reason the JOIN
     * FETCH clauses are there.
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "projectName",  source = "project.name")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    TeamAssignmentResponse toResponse(TeamAssignment ta);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * list.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same five @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing a
     * NullPointerException.
     *
     * WHO CALLS IT: both read methods of TeamAssignmentService, and this is
     * why one single mapper serves two readings that look different.
     *  - findByProject(projectId) fills the team tab of one project. Every row
     *    then carries the same projectId, projectCode and projectName, which
     *    look redundant there but cost nothing, because the project is already
     *    loaded by the JOIN FETCH.
     *  - findByUser(userId) answers GET /api/users/{userId}/assignments, the
     *    reading "on which projects does this person work". There the three
     *    project fields are the useful part of each row, and userId and
     *    userFullName are the ones that repeat. No Angular screen calls that
     *    URL today; it is served all the same, because the history of one
     *    person is a question the API must be able to answer, and it costs
     *    almost nothing to keep: one extra repository query
     *    (findActiveByUserId), then the same response shape and the same
     *    mapper as the team tab.
     * One single response shape for the two directions means one single
     * TypeScript interface, one single mapper, and no risk that the two
     * answers slowly stop agreeing on the names of their fields.
     *
     * A NOTE ON ORDER: the repository queries carry no ORDER BY, so the rows
     * arrive in whatever order PostgreSQL returns them, and this loop keeps
     * that order untouched. The order is therefore not guaranteed to be the
     * same from one call to the next; a screen that needs a stable order has
     * to sort the list itself.
     */
    List<TeamAssignmentResponse> toResponseList(List<TeamAssignment> list);

    /*
     * WHAT IT DOES: takes a User and gives back the name to print - first name
     * plus a space plus last name, since that is what User.getFullName()
     * builds. It gives back null when the User is null.
     *
     * WHY @Named("fullName"): it puts a label on this method so that a @Mapping
     * line can ask for it by name with qualifiedByName = "fullName" (see rule
     * (5) above). Without the label MapStruct would have to guess which method
     * to use from the types alone, and the mapping would break as soon as a
     * second User-to-String method appeared in this interface.
     *
     * WHY "default" AND NOT AN ABSTRACT METHOD: since Java 8 an interface may
     * carry a method with a body. MapStruct generates a class that implements
     * this interface, so the generated class inherits this body and can call it
     * directly. Writing the rule as an expression inside the @Mapping line
     * instead would hide plain Java code inside an annotation string, where the
     * compiler checks nothing until the code is generated.
     *
     * WHY IT DELEGATES TO User.getFullName() INSTEAD OF JOINING THE TWO NAMES
     * HERE: the way a name is printed is decided once, on the User entity, and
     * every mapper that shows a person reuses it. Change it there - to "LAST
     * NAME, First name", for instance - and the team screen, the mission
     * screen and the two workload screens all change together.
     *
     * WHY THE NULL CHECK MATTERS: MapStruct calls this method with ta.getUser()
     * WITHOUT testing it first - it trusts the method to cope with null. So if
     * user were ever null, user.getFullName() would throw a
     * NullPointerException and the whole GET would answer 500 instead of
     * returning the team. Today the column user_id is NOT NULL, so it cannot
     * happen through the normal path; the check costs one comparison and keeps
     * one broken row from bringing the whole list down.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}
