package com.pms.billing.mapper;

import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one database row (the Avenant entity) and the flat JSON
 * object that is sent to the browser (the AvenantResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "Avenant" is the French word for a contract amendment: a signed document
 *    that adds money, and sometimes sold workload, to an existing project.
 *  - "entity" = a Java object that is mapped to a database table (here the
 *    table "avenants").
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here it is the Java record
 *    AvenantResponse.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   BillingController   ->  /api/projects/{projectId}/avenants
 *     AvenantService    ->  opens the transaction and holds the security check
 *       AvenantRepository  ->  returns Avenant entities from PostgreSQL
 *         AvenantMapper    ->  THIS FILE: Avenant  ==>  AvenantResponse
 *           Jackson        ->  writes the record as JSON on the wire
 *
 * This file calls nothing itself. It is only an interface: MapStruct (the
 * annotation processor declared in pom.xml) reads it while the project is
 * being compiled and writes the real class, AvenantMapperImpl, into
 * target/generated-sources. Spring then injects that generated class into
 * AvenantService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The controller would have to return the Avenant entity itself. The entity
 *     holds a whole Project object, so the JSON answer would drag the project,
 *     its budget fields, its director and so on into a response that is only
 *     supposed to show "amendment number 2, 15 000 TND, signed on 3 March".
 *  2. Avenant.project is declared @ManyToOne(fetch = FetchType.LAZY). A lazy
 *     field is a placeholder, not real data. If Jackson tries to read it after
 *     the transaction is closed, the request fails with
 *     LazyInitializationException instead of returning the amendment.
 *  3. The database column names would become the public API. Renaming a column
 *     one day would silently break the Angular front end.
 *
 * It also removes a long list of hand-written "response.setX(entity.getX())"
 * lines. That is exactly the kind of code where one copy/paste mistake (taking
 * montant from workloadDays, for example) is never spotted by a reviewer.
 *
 * ============================================================================
 * NOTE ON SECURITY - important if the jury asks
 * ============================================================================
 * This file does no permission check at all, and that is on purpose.
 * Authorization in PMS is dynamic and permission-based, and it is applied:
 *   - on the SERVICE method, with hasAuthority('VIEW_BILLING') or
 *     hasAuthority('MANAGE_BILLING') - never on a role name, and never on the
 *     controller;
 *   - and, for every URL under /api/projects/{id}/**, by
 *     ProjectScopeInterceptor, which also checks that this user is allowed to
 *     see THIS project (ADR-021: having the permission alone is not enough).
 * Code that is only ever reached after those two gates must not invent a third,
 * different rule; two rules that disagree is how holes appear.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * JalonMapper and PaiementMapper follow exactly the same pattern, for billing
 * milestones and for payments. The three are linked by the business flow:
 * creating an Avenant raises the project budget, and JalonService then
 * recomputes the amount of every milestone still in status PREVU (marker H-4).
 */
@Mapper(componentModel = "spring")
public interface AvenantMapper {

    /*
     * WHAT IT DOES: turns one Avenant row into one AvenantResponse record that
     * can be serialised to JSON. It gives back a brand new object; the entity
     * itself is never modified.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a normal class written by
     * hand: MapStruct generates the body at compile time, so the compiler
     * checks it. If someone adds a field to AvenantResponse that has no source
     * on the entity, the BUILD prints a warning straight away ("Unmapped target
     * property"). A hand-written mapper would simply send null to the browser
     * with no warning at all, and nobody would notice until a user reports a
     * blank column.
     *
     * WHY @Mapper(componentModel = "spring") on the interface above: it tells
     * MapStruct to put @Component on the generated class, so Spring keeps one
     * shared instance and can inject it. WITHOUT IT the generated class carries
     * no annotation, AvenantService asks Spring for an AvenantMapper bean, none
     * exists, and the whole application refuses to start with
     * NoSuchBeanDefinitionException.
     *
     * FIELDS COPIED AUTOMATICALLY: id, numero, objet, montant, workloadDays and
     * dateAvenant have the same name on both sides, so MapStruct copies them on
     * its own. Only the two names that do NOT match are declared below.
     *
     * THE TWO @Mapping LINES WRITTEN JUST ABOVE THE METHOD SIGNATURE:
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads avenant.getProject().getId() and stores it in the flat
     *            field projectId of the response.
     *      WHY:  the response is flat on purpose. The front end only needs the
     *            number of the project to build a link, not the project object.
     *      WITHOUT IT: MapStruct finds nothing on Avenant called "projectId",
     *            prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null - so the
     *            "back to the project" link on the amendments page points
     *            nowhere.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads avenant.getProject().getCode(), the short readable code
     *            of the project (for example "PRJ-2026-014"), and copies it in.
     *      WHY:  the billing screen shows that code to the user, not the
     *            numeric id. Sending it inside the same answer saves a second
     *            HTTP call to /api/projects/{id} just to display one string.
     *      WITHOUT IT: the column stays empty, or the front end has to fire one
     *            extra request per row.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * Avenant.project is LAZY, so at this point it may still be a proxy (an
     * empty shell that only knows its id). Calling getId() on a proxy costs
     * nothing - Hibernate already has that value. But getCode() is a real
     * column, so reading it forces Hibernate to go to the database. That is why
     * AvenantRepository writes "JOIN FETCH a.project" in both of its queries:
     * the project comes back already loaded and this mapping costs zero extra
     * query. If someone deletes that JOIN FETCH the code still works, because
     * the service method is transactional and the session is still open, but
     * every single call quietly adds one SELECT on the projects table.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    AvenantResponse toResponse(Avenant avenant);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each item, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * avenants.stream().map(mapper::toResponse).toList() inside the service:
     * the generated loop reuses the exact same two @Mapping rules as the single
     * method, so the list version and the single version can never drift apart.
     * It also returns null for a null input instead of throwing
     * NullPointerException, which keeps the service short.
     *
     * WHO CALLS IT: AvenantService.findByProject(), to fill the "Avenants"
     * table of one project.
     */
    List<AvenantResponse> toResponseList(List<Avenant> avenants);
}
