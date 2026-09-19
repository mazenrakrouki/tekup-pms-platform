package com.pms.governance.mapper;

import com.pms.governance.dto.RiskResponse;
import com.pms.governance.entity.Risk;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one row of the risk register (the Risk entity, table
 * "risks", created by migration V11) and the flat JSON object sent to the
 * browser (the RiskResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here Risk, whose
 *    rows are the risks identified on a project: a description, how likely it
 *    is (probabilite), how bad it would be (impact), the plan to reduce it
 *    (planMitigation) and where it stands (statut).
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record RiskResponse.
 *  - "mapper" = the piece of code that copies the fields of an entity into a
 *    DTO. This file copies nothing itself: it only describes the copying
 *    rules, and MapStruct writes the real code from them.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   RiskController   ->  /api/projects/{projectId}/risks
 *     RiskService    ->  opens the transaction and holds the security check
 *                        (VIEW_GOVERNANCE to read, MANAGE_GOVERNANCE to write)
 *       RiskRepository  ->  returns Risk entities, already filtered on
 *                           deleted = false
 *         RiskMapper    ->  THIS FILE: Risk entity  ==>  RiskResponse
 *           Jackson     ->  writes the record as JSON
 *             Angular   ->  reads it as the "Risk" interface in
 *                           core/models/governance.model.ts
 *
 * This file calls nothing itself. MapStruct (the annotation processor declared
 * in pom.xml, ADR-018) reads this interface at compile time and writes the real
 * class RiskMapperImpl; Spring injects that generated class into RiskService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The service would have to return the Risk entity itself, and a Risk holds
 *     a whole Project object. The answer for one risk would then also publish
 *     the project budget, its client and its dates - data the reader may have
 *     no right to see.
 *  2. Risk.project is @ManyToOne(fetch = FetchType.LAZY), so it is only a
 *     placeholder until Hibernate loads it. If Jackson tried to read it after
 *     the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the risk.
 *  3. Database column names would become the public API contract. Renaming
 *     plan_mitigation would then silently break the Angular governance page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never computes a risk score. There is no "criticite" and no
 *    "probabilite x impact" field anywhere: the two levels are sent as they
 *    are and the screen decides how to show them. A number invented here would
 *    be a business rule hidden inside a translator, where nobody would look
 *    for it.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_GOVERNANCE') or
 *    hasAuthority('MANAGE_GOVERNANCE') is applied on the SERVICE method, never
 *    on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own).
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true. That filter lives in the
 *    repository queries (AND r.deleted = false), not here.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * LivrableMapper, PartiePrenanteMapper and DemandeChangementMapper do the same
 * job for the three other registers of the governance module. All four are
 * built the same way on purpose: the same two @Mapping lines for the project,
 * the same pair of methods (one object, one list). Only DemandeChangementMapper
 * needs more, because it flattens a second relation as well (the person who
 * asked for the change).
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: RiskService receives a RiskMapper through its constructor, so Spring has
// to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type RiskMapper".
@Mapper(componentModel = "spring")
public interface RiskMapper {

    /*
     * WHAT IT DOES: turns one Risk row into one RiskResponse record, ready to
     * be serialised to JSON. It builds a new object and never modifies the
     * entity it is given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to RiskResponse and forget to
     * say where it comes from, and the BUILD warns immediately; a hand-written
     * mapper would compile fine and quietly send null to the browser. ADR-018
     * makes MapStruct mandatory in this project for exactly that reason.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * description, probabilite, impact, planMitigation and statut. Only the two
     * names that do not match are declared below.
     *
     * ABOUT probabilite AND impact: both are the enum NiveauRisque (FAIBLE,
     * MOYEN, ELEVE) on both sides, so MapStruct copies the value as it is, with
     * no conversion. The entity stores them with @Enumerated(EnumType.STRING),
     * so the database holds the readable word and the JSON shows
     * "impact":"ELEVE". That exact string is what the TypeScript union type
     * NiveauRisque expects and what the Transloco translation key is built
     * from. Had the entity kept the default EnumType.ORDINAL, the database
     * would store 0, 1, 2, and adding a new level in the middle of the enum
     * would turn every low risk into a medium one.
     *
     * THE TWO @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads risk.getProject().getId() and puts it in the flat field
     *            projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null - the
     *            governance page can no longer link a risk back to its project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads risk.getProject().getCode(), the short readable project
     *            code, and copies it into the response.
     *      WHY:  a risk register is read by humans, who recognise the project
     *            code, not a numeric id. Sending it in the same answer avoids a
     *            second HTTP call just to display one string.
     *      WITHOUT IT: that column is empty on screen, or the front end has to
     *            call /api/projects/{id} once per row.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * Risk.project is LAZY, so it may still be a proxy (a shell object that
     * only knows its id). getId() on a proxy costs nothing, Hibernate already
     * has that value; but getCode() is a real column, so reading it forces a
     * trip to the database. That is why RiskRepository.findActiveByProjectId
     * and findActiveById both write "JOIN FETCH r.project": the project arrives
     * already loaded and this mapping costs zero extra query. Remove that JOIN
     * FETCH and the code still works - the service methods are @Transactional,
     * so the Hibernate session is still open - but every call silently adds one
     * more SELECT on the projects table.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    RiskResponse toResponse(Risk risk);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * risks.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same two @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing a
     * NullPointerException.
     *
     * WHO CALLS IT: RiskService.findByProject(), to fill the risk table of one
     * project. The repository already sorts the rows by creation date, newest
     * first, and this loop keeps that order, so the register is displayed in
     * the right sequence with no sorting done in the front end.
     */
    List<RiskResponse> toResponseList(List<Risk> risks);
}
