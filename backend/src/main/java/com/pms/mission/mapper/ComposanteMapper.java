package com.pms.mission.mapper;

import com.pms.mission.dto.ComposanteResponse;
import com.pms.mission.entity.ComposanteMission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one database row (the ComposanteMission entity) and the
 * flat JSON object sent to the browser (the ComposanteResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "composante de mission" is the French term used in this application for
 *    one cost line of a business trip. The five allowed types are PERDIEM
 *    (daily allowance), BILLET (plane or train ticket), TIMBRE (tax stamp),
 *    TRANSPORT (local travel) and SEJOUR (hotel / accommodation). One mission
 *    has many such lines.
 *  - "entity" = a Java object mapped to a database table. Here the table is
 *    "composantes_mission", created by the Flyway migration V10.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here it is the record
 *    ComposanteResponse.
 *  - "MapStruct" = a code generator declared in pom.xml. It reads this
 *    interface while the project is compiled and writes the real class
 *    ComposanteMapperImpl. Nothing is generated at run time.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   MissionController
 *     -> /api/projects/{projectId}/missions/{missionId}/composantes
 *     ComposanteService  -> opens the transaction, carries the permission check
 *                           (VIEW_MISSION to read, MANAGE_MISSION to write) and
 *                           verifies that the mission really belongs to
 *                           {projectId} before touching anything
 *       ComposanteMissionRepository -> returns ComposanteMission entities
 *         ComposanteMapper          -> THIS FILE: entity ==> ComposanteResponse
 *           Jackson                 -> writes the record as JSON
 *
 * This file calls nothing itself. Spring injects the generated
 * ComposanteMapperImpl into ComposanteService, which uses it in three of its
 * four methods: findByMission (a list), create and update (one object each).
 * delete() returns nothing, so it never needs the mapper.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The controller would have to return the ComposanteMission entity itself.
 *     That entity holds a whole Mission object, and a Mission holds a Project
 *     and a User. Asking for one 120 TND taxi fare would then also send back
 *     the project and the personal data of the colleague who travelled.
 *  2. ComposanteMission.mission is @ManyToOne(fetch = FetchType.LAZY). The
 *     repository does load the mission itself (JOIN FETCH c.mission), but
 *     Mission.project and Mission.user stay lazy, which means they are only
 *     placeholders. Jackson would walk into them after the transaction is
 *     closed and the call would end with LazyInitializationException (HTTP 500)
 *     instead of returning the cost line.
 *  3. The entity also inherits createdAt, updatedAt, createdBy, updatedBy and
 *     the soft-delete flag "deleted" from BaseEntity. None of them belong in
 *     the answer. ComposanteResponse simply has no field for them, so MapStruct
 *     leaves them behind. Serialising the entity would publish who edited what
 *     and when, plus an internal technical flag.
 *  4. Database column names would become the public API contract. Renaming the
 *     column "montant" would then silently break the Angular missions page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never adds the cost lines up. There is no stored "total" column and no
 *    total in this DTO. The Angular missions page sums the amounts itself over
 *    the list it received. Keeping it that way means a total can never fall out
 *    of step with the lines it is made of.
 *  - It never converts a currency. Each line carries its own "devise" (currency
 *    code, three letters, "TND" by default). The mapper copies that code as it
 *    is, so a line entered in EUR is still shown as EUR.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_MISSION') and
 *    hasAuthority('MANAGE_MISSION') are placed on the SERVICE methods, never on
 *    a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under /api/projects/{id}/**,
 *    that this user is allowed to see THIS project (ADR-021: holding the
 *    permission is not enough on its own).
 *  - It never filters out deleted rows. The repository queries already contain
 *    "AND c.deleted = false", so a soft-deleted line never reaches the mapper.
 *
 * ============================================================================
 * SISTER FILE IN THIS PACKAGE
 * ============================================================================
 * MissionMapper does the same job one level up, for the trip itself (who
 * travelled, where, from when to when). A ComposanteMission always belongs to
 * one of the missions mapped there, which is why the only field this mapper has
 * to translate by hand is the parent mission id.
 */
@Mapper(componentModel = "spring")
public interface ComposanteMapper {

    /*
     * WHAT IT DOES: turns one ComposanteMission row into one ComposanteResponse
     * record, ready to be serialised to JSON. It builds a new object and never
     * modifies the entity it was given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct writes the body while the project is compiled, so the compiler
     * checks every single field. Add a field to ComposanteResponse and forget
     * the entity side and the BUILD complains straight away; a hand-written
     * mapper would compile fine and quietly send null to the browser.
     *
     * WHY @Mapper(componentModel = "spring") above the interface: it tells
     * MapStruct to put @Component on the generated class, so Spring keeps one
     * instance of it and injects it into ComposanteService. WITHOUT IT the
     * generated class is not a Spring bean; ComposanteService asks Spring for a
     * ComposanteMapper, none is found, and the whole application refuses to
     * start with NoSuchBeanDefinitionException.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides:
     * typeComposante, montant, devise, description, and id which the entity
     * inherits from BaseEntity (MapStruct reads inherited getters too). Only
     * the one name that does not match is declared below.
     *
     * ABOUT typeComposante: it is the enum TypeComposante on both sides, so the
     * value is copied as it is, with no conversion. The entity stores it with
     * @Enumerated(EnumType.STRING), so the database holds the readable word and
     * the JSON shows "typeComposante":"PERDIEM". That matters: with the default
     * EnumType.ORDINAL the database would store 0, 1, 2..., and simply
     * inserting a new value in the middle of the enum would turn every per diem
     * row into a plane ticket. The word is also what the CHECK constraint
     * chk_comp_type of migration V10 accepts, and what the Angular side matches
     * against its own TypeComposante union type.
     *
     * ABOUT montant: BigDecimal on both sides, on purpose. BigDecimal keeps
     * exact decimal values, unlike double. With a double, 0.1 + 0.2 gives
     * 0.30000000000000004, and an expense report that is one cent off is an
     * expense report the accounting department sends back.
     *
     * THE @Mapping LINE WRITTEN JUST ABOVE THE METHOD SIGNATURE:
     *   target = "missionId", source = "mission.id"
     *   WHAT: reads composante.getMission().getId() and puts that number in the
     *         flat field missionId of the response.
     *   WHY:  the response is deliberately flat. The front end only needs the
     *         number of the parent trip to build its URLs and to group the
     *         lines under the right mission, not the Mission object.
     *   WITHOUT IT: MapStruct finds no property called "missionId" on the
     *         entity, prints the compile warning "Unmapped target property:
     *         missionId", and the browser receives missionId: null - the page
     *         can no longer tell which trip a cost line belongs to.
     *   NULL SAFETY: for a nested source like "mission.id", MapStruct generates
     *         a small private helper that first tests whether getMission() is
     *         null and returns null in that case. So a row with no parent gives
     *         missionId: null rather than a NullPointerException. In practice
     *         the column mission_id is NOT NULL in V10, so this guard should
     *         never fire.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * ComposanteMission.mission is LAZY, so it may be only a proxy (a shell
     * that knows nothing but its own id). Calling getId() on such a proxy costs
     * nothing at all, because Hibernate already holds that value: this mapping
     * would work even without the JOIN FETCH. Reading any other field of the
     * mission would, on the contrary, trigger an extra SELECT - which is
     * exactly why this mapper copies the id and nothing else from the parent.
     */
    @Mapping(target = "missionId", source = "mission.id")
    ComposanteResponse toResponse(ComposanteMission composante);

    /*
     * WHAT IT DOES: maps a whole list in one go. MapStruct generates the loop
     * for us (create an ArrayList of the right size, walk the source list, call
     * toResponse on each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * composantes.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop reuses the very same @Mapping rule as the single method
     * above, so the list version can never drift away from it. It also returns
     * null for a null input instead of throwing NullPointerException.
     *
     * WHO CALLS IT: ComposanteService.findByMission(), to fill the cost table
     * of one trip on the missions page.
     *
     * ABOUT THE ORDER OF THE ROWS: the mapper keeps the order the repository
     * gave it, so no sorting is needed in the front end. That query ends with
     * "ORDER BY c.typeComposante". Since the type is stored as a word
     * (@Enumerated(EnumType.STRING)), the database sorts on that word, which
     * gives alphabetical order (BILLET, PERDIEM, SEJOUR, TIMBRE, TRANSPORT) and
     * not the order in which the values are written in the enum. Same cost
     * lines, same order on every screen and every reload - that is the point.
     */
    List<ComposanteResponse> toResponseList(List<ComposanteMission> composantes);
}
