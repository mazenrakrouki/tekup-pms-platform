package com.pms.governance.mapper;

import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.entity.Livrable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one row of the deliverables register (the Livrable
 * entity, table "livrables", created by migration V11) and the flat JSON object
 * sent to the browser (the LivrableResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here Livrable, whose
 *    rows are the things the project must hand over to the client: a title, an
 *    optional description, an optional due date (dateEcheance) and a status
 *    (statut).
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record LivrableResponse.
 *  - "mapper" = the piece of code that copies the fields of an entity into a
 *    DTO. This file copies nothing itself: it only describes the copying rules,
 *    and MapStruct writes the real code from them.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   LivrableController  ->  /api/projects/{projectId}/livrables
 *     LivrableService   ->  opens the transaction and holds the security check
 *                           (VIEW_GOVERNANCE to read, MANAGE_GOVERNANCE to
 *                           write, including the three state moves
 *                           demarrer / livrer / valider)
 *       LivrableRepository ->  returns Livrable entities, already filtered on
 *                              deleted = false
 *         LivrableMapper   ->  THIS FILE: Livrable entity ==> LivrableResponse
 *           Jackson        ->  writes the record as JSON
 *             Angular      ->  reads it as the "Livrable" interface in
 *                              core/models/governance.model.ts
 *
 * This file calls nothing itself. MapStruct (the annotation processor declared
 * in pom.xml, ADR-018) reads this interface at compile time and writes the real
 * class LivrableMapperImpl; Spring injects that generated class into
 * LivrableService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The service would have to return the Livrable entity itself, and a
 *     Livrable holds a whole Project object. The answer for one deliverable
 *     would then also publish the project budget, its client and its dates -
 *     data the reader may have no right to see.
 *  2. Livrable.project is @ManyToOne(fetch = FetchType.LAZY), so it is only a
 *     placeholder until Hibernate loads it. If Jackson tried to read it after
 *     the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the deliverable.
 *  3. The entity also carries createdAt, createdBy, updatedAt, updatedBy and
 *     deleted from BaseEntity. Those are internal bookkeeping columns; sending
 *     them would make them part of the public contract by accident.
 *  4. Database column names would become the public API contract. Renaming
 *     date_echeance would then silently break the Angular governance page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never decides whether a deliverable is late. There is no "enRetard"
 *    field: the due date is sent as it is and the screen compares it with
 *    today. A flag computed here would be a business rule hidden inside a
 *    translator, where nobody would look for it.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_GOVERNANCE') or
 *    hasAuthority('MANAGE_GOVERNANCE') is applied on the SERVICE method, never
 *    on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own).
 *  - It never enforces the state machine EN_ATTENTE -> EN_COURS -> LIVRE ->
 *    VALIDE. That belongs to LivrableService, which refuses an illegal move
 *    with a BusinessRuleException. The mapper only reports the status reached.
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true. That filter lives in the
 *    repository queries (AND l.deleted = false), not here.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * RiskMapper, PartiePrenanteMapper and DemandeChangementMapper do the same job
 * for the three other registers of the governance module. All four are built
 * the same way on purpose: the same two @Mapping lines for the project, the
 * same pair of methods (one object, one list). Only DemandeChangementMapper
 * needs more, because it flattens a second relation as well (the person who
 * asked for the change).
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: LivrableService receives a LivrableMapper through its constructor, so
// Spring has to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// LivrableMapper".
@Mapper(componentModel = "spring")
public interface LivrableMapper {

    /*
     * WHAT IT DOES: turns one Livrable row into one LivrableResponse record,
     * ready to be serialised to JSON. It builds a new object and never modifies
     * the entity it is given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to LivrableResponse and forget
     * to say where it comes from, and the BUILD warns immediately; a
     * hand-written mapper would compile fine and quietly send null to the
     * browser. ADR-018 makes MapStruct mandatory in this project for exactly
     * that reason.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * titre, description, dateEcheance and statut. Only the two names that do
     * not match are declared below.
     *
     * ABOUT statut: it is the enum StatutLivrable (EN_ATTENTE, EN_COURS, LIVRE,
     * VALIDE) on both sides, so MapStruct copies the value as it is, with no
     * conversion. The entity stores it with @Enumerated(EnumType.STRING), so
     * the database holds the readable word and the JSON shows
     * "statut":"LIVRE". That exact string is what the TypeScript union type
     * StatutLivrable expects and what the Transloco translation key is built
     * from; the screen also uses it to decide which action buttons to show. Had
     * the entity kept the default EnumType.ORDINAL, the database would store
     * 0, 1, 2, 3, and adding a new step in the middle of the enum would turn
     * every finished deliverable into a started one.
     *
     * ABOUT dateEcheance: LocalDate is a calendar day with no time and no time
     * zone, so Jackson writes "2026-09-18". A timestamp would be converted to
     * the time zone of the reader, and a deliverable due on the 30th could be
     * shown as the 29th to a user one zone behind.
     *
     * THE TWO @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads livrable.getProject().getId() and puts it in the flat
     *            field projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null - the
     *            governance page can no longer link a deliverable back to its
     *            project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads livrable.getProject().getCode(), the short readable
     *            project code, and copies it into the response.
     *      WHY:  a deliverables register is read by humans, who recognise the
     *            project code, not a numeric id. Sending it in the same answer
     *            avoids a second HTTP call just to display one string.
     *      WITHOUT IT: that column is empty on screen, or the front end has to
     *            call /api/projects/{id} once per row.
     *
     * A NOTE ON "project.id" WITH A DOT: this is a nested source path. For such
     * a path MapStruct generates a small private helper that checks every step
     * for null, so a Livrable with no project would give projectId = null
     * instead of a NullPointerException. That safety net costs nothing here,
     * because the column project_id is declared NOT NULL in V11.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * Livrable.project is LAZY, so it may still be a proxy (a shell object that
     * only knows its id). getId() on a proxy costs nothing, Hibernate already
     * has that value; but getCode() is a real column, so reading it forces a
     * trip to the database. That is why LivrableRepository.findActiveByProjectId
     * and findActiveById both write "JOIN FETCH l.project": the project arrives
     * already loaded and this mapping costs zero extra query. Remove that JOIN
     * FETCH and the code still works - the service methods are @Transactional,
     * so the Hibernate session is still open - but every call silently adds one
     * more SELECT on the projects table.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    LivrableResponse toResponse(Livrable livrable);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * livrables.stream().map(mapper::toResponse).toList() inside the service:
     * the generated loop reuses the very same two @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing a
     * NullPointerException.
     *
     * WHO CALLS IT: LivrableService.findByProject(), to fill the deliverables
     * table of one project. The repository already sorts the rows with
     * "ORDER BY l.dateEcheance NULLS LAST, l.titre" - nearest deadline first,
     * deliverables with no date at the end, ties broken by title - and this
     * loop keeps that order, so the register is displayed in the right sequence
     * with no sorting done in the front end.
     */
    List<LivrableResponse> toResponseList(List<Livrable> livrables);
}
