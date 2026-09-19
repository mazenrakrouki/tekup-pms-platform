package com.pms.governance.mapper;

import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.entity.PartiePrenante;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one row of the stakeholder register (the PartiePrenante
 * entity, table "parties_prenantes", created by migration V11) and the flat
 * JSON object sent to the browser (the PartiePrenanteResponse DTO).
 *
 * A "partie prenante" is a stakeholder: a person or a body concerned by the
 * project - the client sponsor, an end user, an auditor. Each row keeps a name,
 * an optional job title, an optional email and phone, and two levels: how much
 * power the person has over the project (influence) and how much the project
 * matters to them (interet).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here PartiePrenante.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record
 *    PartiePrenanteResponse.
 *  - "mapper" = the piece of code that copies the fields of an entity into a
 *    DTO. This file copies nothing itself: it only describes the copying rules,
 *    and MapStruct writes the real code from them.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   PartiePrenanteController  ->  /api/projects/{projectId}/parties-prenantes
 *     PartiePrenanteService   ->  opens the transaction and holds the security
 *                                 check (VIEW_GOVERNANCE to read,
 *                                 MANAGE_GOVERNANCE to write)
 *       PartiePrenanteRepository ->  returns PartiePrenante entities, already
 *                                    filtered on deleted = false
 *         PartiePrenanteMapper   ->  THIS FILE: PartiePrenante entity ==>
 *                                    PartiePrenanteResponse
 *           Jackson              ->  writes the record as JSON
 *             Angular            ->  reads it as the "PartiePrenante" interface
 *                                    in core/models/partie-prenante.model.ts
 *
 * This file calls nothing itself. MapStruct (the annotation processor declared
 * in pom.xml, ADR-018) reads this interface at compile time and writes the real
 * class PartiePrenanteMapperImpl; Spring injects that generated class into
 * PartiePrenanteService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The service would have to return the PartiePrenante entity itself, and a
 *     PartiePrenante holds a whole Project object. The answer for one
 *     stakeholder would then also publish the project budget, its client and
 *     its dates - data the reader may have no right to see.
 *  2. PartiePrenante.project is @ManyToOne(fetch = FetchType.LAZY), so it is
 *     only a placeholder until Hibernate loads it. If Jackson tried to read it
 *     after the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the stakeholder.
 *  3. The entity also carries createdAt, createdBy, updatedAt, updatedBy and
 *     deleted from BaseEntity. Those are internal bookkeeping columns; sending
 *     them would make them part of the public contract by accident.
 *  4. This register is the only place in the governance module that holds
 *     personal data (a name, an email, a phone number). Because the mapping is
 *     written here, in one short list, the question "what personal data does
 *     this endpoint publish?" has a precise answer instead of "whatever the
 *     entity happens to contain today".
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never merges influence and interet into a single score, and it never
 *    places the person in a quadrant of the power/interest grid. The two levels
 *    travel separately and the screen draws the grid. A score invented here
 *    would be a business rule hidden inside a translator, where nobody would
 *    look for it.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_GOVERNANCE') or
 *    hasAuthority('MANAGE_GOVERNANCE') is applied on the SERVICE method, never
 *    on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own).
 *  - It never hides or masks an email or a phone number. Whoever passes
 *    VIEW_GOVERNANCE and the project scope check sees them in full.
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true. That filter lives in the
 *    repository queries (AND p.deleted = false), not here.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * RiskMapper, LivrableMapper and DemandeChangementMapper do the same job for
 * the three other registers of the governance module. All four are built the
 * same way on purpose: the same two @Mapping lines for the project, the same
 * pair of methods (one object, one list). This one and RiskMapper are the two
 * shortest, because every other field already has the same name on both sides
 * there, so no extra rule is needed. Only
 * DemandeChangementMapper needs more, because it flattens a second relation as
 * well (the person who asked for the change).
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: PartiePrenanteService receives a PartiePrenanteMapper through its
// constructor, so Spring has to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// PartiePrenanteMapper".
@Mapper(componentModel = "spring")
public interface PartiePrenanteMapper {

    /*
     * WHAT IT DOES: turns one PartiePrenante row into one
     * PartiePrenanteResponse record, ready to be serialised to JSON. It builds
     * a new object and never modifies the entity it is given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to PartiePrenanteResponse and
     * forget to say where it comes from, and the BUILD warns immediately; a
     * hand-written mapper would compile fine and quietly send null to the
     * browser. ADR-018 makes MapStruct mandatory in this project for exactly
     * that reason.
     *
     * WHY THE PARAMETER IS CALLED pp: the name is only used inside the
     * generated code, and PartiePrenante is long enough to make the generated
     * lines unreadable. The short name changes nothing for the caller.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * nom, fonction, email, telephone, influence and interet. Only the two
     * names that do not match are declared below.
     *
     * ABOUT influence AND interet: both are the enum NiveauRisque (FAIBLE,
     * MOYEN, ELEVE) on both sides, so MapStruct copies the value as it is, with
     * no conversion. The entity stores them with @Enumerated(EnumType.STRING),
     * so the database holds the readable word and the JSON shows
     * "influence":"ELEVE". That exact string is what the TypeScript union type
     * NiveauRisque expects and what the Transloco translation key is built
     * from. Had the entity kept the default EnumType.ORDINAL, the database
     * would store 0, 1, 2, and adding a new level in the middle of the enum
     * would turn every low-influence stakeholder into a medium one. The same
     * enum is reused for the two levels of a risk, which is why the front end
     * can share one badge component for both screens.
     *
     * ABOUT fonction, email AND telephone: their columns are nullable, so the
     * mapper may well produce nulls. That is on purpose and matches the Angular
     * interface, which declares them with a question mark ("email?: string");
     * the table shows a dash for an empty cell.
     *
     * THE TWO @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads pp.getProject().getId() and puts it in the flat field
     *            projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null - the
     *            governance page can no longer link a stakeholder back to its
     *            project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads pp.getProject().getCode(), the short readable project
     *            code, and copies it into the response.
     *      WHY:  a stakeholder register is read by humans, who recognise the
     *            project code, not a numeric id. Sending it in the same answer
     *            avoids a second HTTP call just to display one string.
     *      WITHOUT IT: that column is empty on screen, or the front end has to
     *            call /api/projects/{id} once per row.
     *
     * A NOTE ON "project.id" WITH A DOT: this is a nested source path. For such
     * a path MapStruct generates a small private helper that checks every step
     * for null, so a stakeholder with no project would give projectId = null
     * instead of a NullPointerException. That safety net costs nothing here,
     * because the column project_id is declared NOT NULL in V11.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * PartiePrenante.project is LAZY, so it may still be a proxy (a shell
     * object that only knows its id). getId() on a proxy costs nothing,
     * Hibernate already has that value; but getCode() is a real column, so
     * reading it forces a trip to the database. That is why
     * PartiePrenanteRepository.findActiveByProjectId and findActiveById both
     * write "JOIN FETCH p.project": the project arrives already loaded and this
     * mapping costs zero extra query. Remove that JOIN FETCH and the code still
     * works - the service methods are @Transactional, so the Hibernate session
     * is still open - but every call silently adds one more SELECT on the
     * projects table.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    PartiePrenanteResponse toResponse(PartiePrenante pp);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * list.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same two @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing a
     * NullPointerException.
     *
     * WHO CALLS IT: PartiePrenanteService.findByProject(), to fill the
     * stakeholder table of one project. The repository already sorts the rows
     * with "ORDER BY p.nom", alphabetically by name, and this loop keeps that
     * order, so a long list stays easy to scan with no sorting done in the
     * front end.
     */
    List<PartiePrenanteResponse> toResponseList(List<PartiePrenante> list);
}
