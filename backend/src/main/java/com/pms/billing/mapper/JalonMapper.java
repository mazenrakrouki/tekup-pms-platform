package com.pms.billing.mapper;

import com.pms.billing.dto.JalonResponse;
import com.pms.billing.entity.JalonFacturation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one database row (the JalonFacturation entity) and the
 * flat JSON object sent to the browser (the JalonResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "Jalon de facturation" is the French term for a billing milestone: a step
 *    of the contract that can be invoiced, for example "40% on delivery of the
 *    specification". It carries a percentage, the money amount that percentage
 *    represents, a planned date, an invoice date and a status.
 *  - "entity" = a Java object mapped to a database table (here
 *    "jalons_facturation").
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application; here the record JalonResponse.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   BillingController  ->  /api/projects/{projectId}/jalons
 *     JalonService     ->  opens the transaction, holds the security check and
 *                          all the business rules (sum of percentages <= 100,
 *                          a milestone can only be invoiced from status PREVU)
 *       JalonFacturationRepository  ->  returns JalonFacturation entities
 *         JalonMapper               ->  THIS FILE: entity  ==>  JalonResponse
 *           Jackson                 ->  writes the record as JSON
 *
 * This file calls nothing itself. MapStruct (the annotation processor declared
 * in pom.xml) reads this interface at compile time and writes the real class
 * JalonMapperImpl; Spring injects that generated class into JalonService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The controller would return the JalonFacturation entity itself, which
 *     holds a whole Project object. The answer for one milestone would then
 *     also expose the project budget, its client, its team, and so on.
 *  2. JalonFacturation.project is @ManyToOne(fetch = FetchType.LAZY), so it is
 *     only a placeholder until Hibernate loads it. If Jackson reads it after
 *     the transaction is closed, the call fails with
 *     LazyInitializationException instead of returning the milestone.
 *  3. Column names would become the public API contract, so renaming one would
 *     silently break the Angular billing page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never computes an amount. The "montant" column is computed once by
 *    JalonService.computeMontant() (effective budget x percentage / 100, two
 *    decimals, rounded HALF_UP) and recomputed by
 *    JalonService.recomputePrevuMontants() when the budget changes, for
 *    example after a new Avenant (marker H-4). Milestones already in status
 *    FACTURE or PAYE are frozen, because their invoice is already in the
 *    accounts. The mapper only copies the value it is given; if it recomputed
 *    the amount on its own, a frozen milestone could suddenly display a figure
 *    different from the invoice actually sent to the client.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_BILLING') or
 *    hasAuthority('MANAGE_BILLING') is applied on the SERVICE method, never on
 *    a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own).
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * AvenantMapper does the same job for contract amendments, PaiementMapper for
 * payments. A Paiement is always attached to one of the milestones mapped
 * here, and each payment recorded makes JalonService.recalculerStatut() move
 * the milestone to PAYE once the money received reaches its amount.
 */
@Mapper(componentModel = "spring")
public interface JalonMapper {

    /*
     * WHAT IT DOES: turns one JalonFacturation row into one JalonResponse
     * record ready to be serialised to JSON. It builds a new object and never
     * modifies the entity.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to JalonResponse and forget the
     * entity and the BUILD warns immediately; a hand-written mapper would
     * compile fine and quietly send null to the browser.
     *
     * WHY @Mapper(componentModel = "spring") above the interface: it makes
     * MapStruct put @Component on the generated class, so Spring holds one
     * instance and injects it into JalonService. WITHOUT IT the generated class
     * is not a bean, JalonService asks Spring for a JalonMapper, none is found,
     * and the application fails to start with NoSuchBeanDefinitionException.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * label, pourcentage, montant, datePrevue, dateFacture and statut. Only the
     * two names that do not match are declared below.
     *
     * ABOUT statut: it is the enum JalonStatut (PREVU, FACTURE, PAYE) on both
     * sides, so MapStruct copies the value as it is, with no conversion. The
     * entity stores it with @Enumerated(EnumType.STRING), so the database holds
     * the readable word and the JSON shows "statut":"FACTURE". That matters:
     * had the entity used the default EnumType.ORDINAL, the database would
     * store 0, 1, 2, and simply inserting a new value in the middle of the enum
     * would turn every invoiced milestone into a paid one.
     *
     * THE TWO @Mapping LINES WRITTEN JUST ABOVE THE METHOD SIGNATURE:
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads jalon.getProject().getId() and puts it in the flat field
     *            projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the project object.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser gets projectId: null - the billing
     *            page can no longer link a milestone back to its project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads jalon.getProject().getCode(), the short readable project
     *            code such as "PRJ-2026-014", and copies it into the response.
     *      WHY:  invoices and the billing screen are read by humans, who work
     *            with that code, not with a numeric id. Sending it in the same
     *            answer avoids a second HTTP call just to display one string.
     *      WITHOUT IT: the column is empty on screen, or the front end must
     *            call /api/projects/{id} once per row.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * JalonFacturation.project is LAZY, so it may still be a proxy (a shell
     * that only knows its id). getId() on a proxy costs nothing, Hibernate
     * already has the value; but getCode() is a real column, so reading it
     * forces a trip to the database. That is why
     * JalonFacturationRepository.findActiveByProjectId and findActiveById both
     * write "JOIN FETCH j.project": the project arrives already loaded and this
     * mapping costs zero extra query. Remove that JOIN FETCH and the code still
     * works - the service method is transactional, so the session is open - but
     * each call silently adds a SELECT on the projects table.
     */
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    JalonResponse toResponse(JalonFacturation jalon);

    /*
     * WHAT IT DOES: maps a whole list at once. MapStruct generates the loop
     * (create an ArrayList, walk the source list, call toResponse on each
     * element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * jalons.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop uses the very same two @Mapping rules as the single
     * method, so the list version can never drift away from it. It also returns
     * null for a null input instead of throwing NullPointerException.
     *
     * WHO CALLS IT: JalonService.findByProject(), to fill the billing schedule
     * table of one project. The repository already sorts the rows by planned
     * date (NULLS LAST, then id), and the mapper keeps that order, so the
     * schedule is displayed in the right sequence without any sorting in the
     * front end.
     */
    List<JalonResponse> toResponseList(List<JalonFacturation> jalons);
}
