package com.pms.billing.mapper;

import com.pms.billing.dto.PaiementResponse;
import com.pms.billing.entity.Paiement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one database row (the Paiement entity) and the flat JSON
 * object sent to the browser (the PaiementResponse DTO).
 *
 * Words used here, explained on first use:
 *  - "Paiement" is the French word for a payment: money actually received from
 *    the client against one billing milestone. It carries the amount received,
 *    the date, and a free reference (bank transfer number, cheque number...).
 *  - "entity" = a Java object mapped to a database table (here "paiements").
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application; here the record PaiementResponse.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   BillingController  ->  /api/projects/{projectId}/jalons/{jalonId}/paiements
 *     PaiementService  ->  opens the transaction, holds the security check and
 *                          the business rule "the milestone must be invoiced
 *                          before a payment can be recorded"
 *       PaiementRepository  ->  returns Paiement entities
 *         PaiementMapper    ->  THIS FILE: Paiement  ==>  PaiementResponse
 *           Jackson         ->  writes the record as JSON
 *
 * This file calls nothing itself. MapStruct (the annotation processor declared
 * in pom.xml) reads this interface at compile time and writes the real class
 * PaiementMapperImpl; Spring injects that generated class into PaiementService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. The controller would return the Paiement entity, which holds a whole
 *     JalonFacturation object, which itself holds a whole Project. One small
 *     payment line would drag half the project into the JSON answer.
 *  2. Paiement.jalon is @ManyToOne(fetch = FetchType.LAZY), so it is only a
 *     placeholder until Hibernate loads it. If Jackson reads it after the
 *     transaction is closed, the call fails with LazyInitializationException
 *     instead of returning the payment.
 *  3. Column names would become the public API, so renaming montant_recu one
 *     day would silently break the Angular payments table.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never decides the status of the milestone. After each payment is saved
 *    or deleted, PaiementService calls JalonService.recalculerStatut(), which
 *    adds up the payments in SQL and moves the milestone to PAYE when the total
 *    reaches its amount. Keeping that decision in the service means the rule is
 *    applied once, on write; a mapper deciding it on read would show PAYE on
 *    screen while the database still says FACTURE.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_BILLING') or
 *    hasAuthority('MANAGE_BILLING') sits on the SERVICE method, never on a role
 *    name and never on the controller. On top of that, ProjectScopeInterceptor
 *    checks for every URL under /api/projects/{id}/** that this user may see
 *    THIS project (ADR-021: the permission alone is not enough). PaiementService
 *    adds one more check of its own: the milestone must really belong to the
 *    project in the URL, and the payment must really belong to that milestone.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * JalonMapper maps the milestone this payment is attached to, and AvenantMapper
 * maps contract amendments. Compared with those two, this mapper is the
 * simplest: it exposes only one foreign key (jalonId) and no project code,
 * because the payments table is always displayed inside a milestone that the
 * user has already opened.
 */
@Mapper(componentModel = "spring")
public interface PaiementMapper {

    /*
     * WHAT IT DOES: turns one Paiement row into one PaiementResponse record
     * ready to be serialised to JSON. It builds a new object and never modifies
     * the entity.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Money is involved here, so a silent mistake
     * is the worst possible outcome: a hand-written mapper that copies the
     * wrong BigDecimal still compiles, while the generated one is checked
     * against both types at build time.
     *
     * WHY @Mapper(componentModel = "spring") above the interface: it makes
     * MapStruct put @Component on the generated class, so Spring keeps one
     * instance and injects it into PaiementService. WITHOUT IT the generated
     * class is not a bean, PaiementService asks Spring for a PaiementMapper,
     * none is found, and the application refuses to start with
     * NoSuchBeanDefinitionException.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * montantRecu, datePaiement and reference. Only jalonId has to be declared.
     *
     * ABOUT montantRecu BEING A BigDecimal: the entity column is
     * precision = 15, scale = 2, and the DTO field is a BigDecimal too, so the
     * mapper copies it without any conversion. That is what we want. If either
     * side were a double, 0.1 + 0.2 would not give exactly 0.30 and the total
     * received could end a few cents away from the invoice, which is
     * unacceptable on an accounting screen.
     *
     * THE @Mapping LINE WRITTEN JUST ABOVE THE METHOD SIGNATURE:
     *
     *      target = "jalonId", source = "jalon.id"
     *      WHAT: reads paiement.getJalon().getId() and stores it in the flat
     *            field jalonId of the response.
     *      WHY:  the response must stay flat, so that a payment line does not
     *            pull the milestone, the project and everything behind it into
     *            the JSON. The front end only needs the number of the milestone
     *            to group the payments under it.
     *      WITHOUT IT: MapStruct finds no property named "jalonId" on Paiement,
     *            prints the compile warning "Unmapped target property: jalonId",
     *            and the browser receives jalonId: null - the payment then looks
     *            like it belongs to no milestone at all.
     *
     * A NOTE ON LAZY LOADING, and why this mapper needs no JOIN FETCH:
     * Paiement.jalon is LAZY, so it is often still a proxy (a shell that only
     * knows its id). Here we read nothing but getId(), and Hibernate can answer
     * that from the proxy itself, without going to the database. This is why
     * PaiementRepository.findActiveByJalonId does not need a JOIN FETCH, unlike
     * the queries in AvenantRepository and JalonFacturationRepository, which do
     * read a real column (project.code) and therefore must load the object.
     * Careful: if someone later adds a mapping such as
     * source = "jalon.label", this free ride stops and every payment line
     * triggers one extra SELECT.
     */
    @Mapping(target = "jalonId", source = "jalon.id")
    PaiementResponse toResponse(Paiement paiement);

    /*
     * WHAT IT DOES: maps a whole list at once. MapStruct generates the loop
     * (create an ArrayList, walk the source list, call toResponse on each
     * element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * paiements.stream().map(mapper::toResponse).toList() in the service: the
     * generated loop reuses the exact same @Mapping rule as the single method,
     * so the two can never drift apart. It also returns null for a null input
     * instead of throwing NullPointerException.
     *
     * WHO CALLS IT: PaiementService.findByJalon(), to fill the list of payments
     * received for one milestone. The repository already sorts by payment date,
     * and the mapper keeps that order, so the history reads from the oldest
     * payment to the newest without any sorting in the front end.
     */
    List<PaiementResponse> toResponseList(List<Paiement> paiements);
}
