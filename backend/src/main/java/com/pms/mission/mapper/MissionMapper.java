package com.pms.mission.mapper;

import com.pms.mission.dto.MissionResponse;
import com.pms.mission.entity.Mission;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one database row (the Mission entity) and the flat JSON
 * object sent to the browser (the MissionResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "mission" here means a professional trip made for a project: an object
 *    ("objet", why the person travels), a place ("lieu"), a start date and an
 *    end date, one project and one person who travels. Its cost lines live in
 *    a separate table and are handled by ComposanteMapper.
 *  - "entity" = a Java object mapped to a database table. Here the table is
 *    "missions", created by the Flyway migration V10.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here it is the record
 *    MissionResponse.
 *  - "MapStruct" = a code generator declared in pom.xml. It reads this
 *    interface while the project is compiled and writes the real class
 *    MissionMapperImpl. Nothing is generated at run time.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   MissionController  ->  /api/projects/{projectId}/missions
 *     MissionService   ->  opens the transaction, carries the permission check
 *                          (VIEW_MISSION to read, MANAGE_MISSION to write), the
 *                          "own missions only" rule of UC-21 and the date rule
 *                          (end date cannot be before start date)
 *       MissionRepository  ->  returns Mission entities, already sorted by
 *                              start date
 *         MissionMapper    ->  THIS FILE: entity  ==>  MissionResponse
 *           Jackson        ->  writes the record as JSON
 *
 * This file calls nothing itself, except its own small fullName() helper below.
 * Spring injects the generated MissionMapperImpl into MissionService, which
 * uses it in three of its four methods: findByProject (a list), create and
 * update (one object each). delete() returns nothing, so it never needs it.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The controller would have to return the Mission entity itself. That
 *     entity holds a whole Project object and a whole User object. Asking for
 *     the list of trips of one project would then also send the project budget
 *     and, for every traveller, the fields of the users table - including the
 *     password hash and the tokenVersion counter used to revoke sessions.
 *  2. Mission.project and Mission.user are both @ManyToOne(fetch =
 *     FetchType.LAZY), so they are only placeholders until Hibernate loads
 *     them. Jackson reading them after the transaction is closed ends the call
 *     with LazyInitializationException (HTTP 500) instead of returning the
 *     trips.
 *  3. The entity also inherits createdAt, updatedAt, createdBy, updatedBy and
 *     the soft-delete flag "deleted" from BaseEntity. MissionResponse has no
 *     field for any of them, so MapStruct leaves them behind and the answer
 *     stays clean.
 *  4. Database column names would become the public API contract. Renaming the
 *     column "objet" would then silently break the Angular missions page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never checks a permission and never applies the "own missions only"
 *    rule. Authorization in PMS is dynamic and permission-based:
 *    hasAuthority('VIEW_MISSION') and hasAuthority('MANAGE_MISSION') are placed
 *    on the SERVICE methods, never on a role name and never on the controller.
 *    UC-21 (a developer sees only their own trips) is applied earlier, by
 *    MissionService choosing a narrower repository query. On top of all that,
 *    ProjectScopeInterceptor checks, for every URL under /api/projects/{id}/**,
 *    that this user is allowed to see THIS project (ADR-021: holding the
 *    permission is not enough on its own). By the time a row reaches this
 *    mapper, every one of those gates has already been passed.
 *  - It never computes a cost. A trip has no amount of its own; the money is in
 *    its cost lines (ComposanteMission) and the total is summed by the Angular
 *    page over the list it received.
 *  - It never checks the dates. MissionService.validateDates() rejects an end
 *    date earlier than the start date, and the database repeats the rule with
 *    the CHECK constraint chk_mission_dates of migration V10.
 *
 * ============================================================================
 * SISTER FILE IN THIS PACKAGE
 * ============================================================================
 * ComposanteMapper maps the cost lines of the trips mapped here (per diem,
 * ticket, tax stamp, local transport, accommodation). The link between the two
 * is the mission id: it is the field MissionResponse.id produced here and the
 * field ComposanteResponse.missionId produced there.
 */
@Mapper(componentModel = "spring")
public interface MissionMapper {

    /*
     * WHAT IT DOES: turns one Mission row into one MissionResponse record ready
     * to be serialised to JSON. It builds a new object and never modifies the
     * entity it was given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct writes the body while the project is compiled, so the compiler
     * checks every single field. Add a field to MissionResponse and forget the
     * entity side and the BUILD complains straight away; a hand-written mapper
     * would compile fine and quietly send null to the browser.
     *
     * WHY @Mapper(componentModel = "spring") above the interface: it tells
     * MapStruct to put @Component on the generated class, so Spring keeps one
     * instance of it and injects it into MissionService. WITHOUT IT the
     * generated class is not a Spring bean; MissionService asks Spring for a
     * MissionMapper, none is found, and the whole application refuses to start
     * with NoSuchBeanDefinitionException.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides:
     * objet, lieu, dateDebut, dateFin, and id which the entity inherits from
     * BaseEntity (MapStruct reads inherited getters too). The four names that
     * do not match are declared below. dateDebut and dateFin are LocalDate on
     * both sides, so no conversion happens and Jackson writes them in ISO form,
     * "2026-03-14", which is what the Angular date pipe expects.
     *
     * THE FOUR @Mapping LINES WRITTEN JUST ABOVE THE METHOD SIGNATURE:
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads mission.getProject().getId() into the flat field
     *            projectId.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the project object.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser gets projectId: null - a trip can
     *            no longer be linked back to its project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads mission.getProject().getCode(), the short readable
     *            project code such as "PRJ-2026-014", into the response.
     *      WHY:  humans work with that code, not with a numeric id. Sending it
     *            in the same answer means no second HTTP call just to show one
     *            string. The Angular interface Mission (mission.model.ts)
     *            declares projectCode as a required field, so the contract
     *            expects it to be filled.
     *      WITHOUT IT: the field arrives empty and any screen that shows the
     *            code has to call /api/projects/{id} once per row.
     *
     *  (3) target = "userId", source = "user.id"
     *      WHAT: reads mission.getUser().getId() into the flat field userId.
     *      WHY:  the missions page needs the id to preselect the right person
     *            in the "traveller" dropdown when a trip is edited; that
     *            dropdown is filled with the team members of the project.
     *      WITHOUT IT: the edit form opens with no one selected, and saving
     *            again would either fail validation (userId is @NotNull in
     *            MissionRequest) or silently reassign the trip.
     *
     *  (4) target = "userFullName", source = "user",
     *      qualifiedByName = "fullName"
     *      WHAT: this one does not copy a field. It hands the WHOLE User object
     *            to the fullName() method written at the bottom of this
     *            interface, and stores what that method returns.
     *      WHY:  a person's display name is "first name + space + last name",
     *            two columns joined into one string. Doing it here means the
     *            browser receives one ready-to-print name and never has to know
     *            how it is built.
     *      WHY qualifiedByName = "fullName": it names exactly which helper to
     *            use. A method marked @Named is only used when it is asked for
     *            by that name, so WITHOUT this line MapStruct has no way left
     *            to turn a User into a String and the build stops with "Can't
     *            map property User user to String userFullName".
     *
     * NULL SAFETY: for a nested source such as "project.id", MapStruct
     * generates a small private helper that first tests whether getProject() is
     * null and returns null in that case, so a broken row gives projectId: null
     * instead of a NullPointerException. In practice project_id and user_id are
     * both NOT NULL in migration V10, so these guards should never fire.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * Mission.project and Mission.user are LAZY, so they may still be proxies
     * (shells that know nothing but their own id). Calling getId() on a proxy
     * costs nothing, Hibernate already holds that value; but getCode() and
     * getFirstName()/getLastName() are real columns, so reading them forces a
     * trip to the database. That is exactly why every query in
     * MissionRepository is written with "JOIN FETCH m.project JOIN FETCH
     * m.user": both parents arrive already loaded and this mapping costs zero
     * extra query. Remove those JOIN FETCH and the code still works - the
     * service methods are transactional, so the Hibernate session is still open
     * - but a list of 50 trips silently fires 100 extra SELECT statements. That
     * is the classic "N+1 queries" problem.
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    MissionResponse toResponse(Mission mission);

    /*
     * WHAT IT DOES: maps a whole list in one go. MapStruct generates the loop
     * for us (create an ArrayList of the right size, walk the source list, call
     * toResponse on each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * missions.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop reuses the very same four @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing NullPointerException.
     *
     * WHO CALLS IT: MissionService.findByProject(), for both branches of the
     * UC-21 rule - the full list of the project for someone holding
     * MANAGE_MISSION or VIEW_ALL_PROJECTS, and the "own trips only" list for
     * everyone else. Both branches go through this same method, so the two
     * views can never show the same trip in two different shapes.
     *
     * ABOUT THE ORDER OF THE ROWS: the mapper keeps the order the repository
     * gave it, so no sorting is needed in the front end. Both queries end with
     * "ORDER BY m.dateDebut", so the trips are listed from the earliest to the
     * latest departure.
     */
    List<MissionResponse> toResponseList(List<Mission> missions);

    /*
     * WHAT IT DOES: builds the display name of the person who travels, and
     * returns null when there is no person at all.
     *
     * WHY IT LIVES HERE, in the mapper, and not in the DTO or the service: it
     * is pure presentation. The service is about rules and transactions, the
     * DTO is a plain data holder; joining two columns into one label is exactly
     * the mapper's job. One place to change, so every screen shows names the
     * same way.
     *
     * WHY @Named("fullName") sits above the method: it gives this helper a
     * label so that the @Mapping line above can ask for it by name with
     * qualifiedByName = "fullName". WITHOUT THIS ANNOTATION the build stops
     * with "Qualifier error. No method found annotated with @Named#value:
     * [fullName]". The label also means the method is used ONLY where it is
     * explicitly asked for: an unlabelled User -> String method would be
     * treated as a general conversion and could be applied on its own to any
     * other String field fed from a User, in this mapper or in another one that
     * reuses it.
     *
     * WHY "default" and not "abstract": Java allows a real body inside an
     * interface with the keyword "default". MapStruct simply calls it from the
     * generated class. WITHOUT the body MapStruct would have to invent the
     * conversion itself, and it cannot guess that two columns must be joined
     * with a space in between.
     *
     * WHY THE null TEST "user != null ?": MapStruct passes the source object
     * straight to this method, so nothing has checked it before. WITHOUT the
     * test, a mission whose user could not be loaded would throw a
     * NullPointerException inside the mapper, and the whole list of trips would
     * fail with HTTP 500 instead of showing one row with an empty name column.
     * Returning null also lets the front end decide what to display, rather
     * than printing the misleading text "null null".
     *
     * NOTE: User.getFullName() itself is the one that concatenates first name
     * and last name; this helper only guards it against a missing user.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}
