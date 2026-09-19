package com.pms.governance.mapper;

import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.entity.DemandeChangement;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * A translator between one row of the change-request register (the
 * DemandeChangement entity, table "demandes_changement", created by migration
 * V11) and the flat JSON object sent to the browser (the
 * DemandeChangementResponse DTO).
 *
 * A "demande de changement" is a change request: somebody asks for something in
 * the project to be modified, and the request is then approved or rejected.
 * Each row keeps a title, an optional description, a priority, a status, the
 * day it was asked (dateDemande), the day it was decided (dateDecision), the
 * project it belongs to, and the person who asked (demandeur).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here
 *    DemandeChangement.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record
 *    DemandeChangementResponse.
 *  - "mapper" = the piece of code that copies the fields of an entity into a
 *    DTO. This file describes the copying rules and adds one tiny helper;
 *    MapStruct writes the rest of the real code from those rules.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   DemandeChangementController  ->  /api/projects/{projectId}/demandes-changement
 *     DemandeChangementService   ->  opens the transaction and holds the
 *                                    security check (VIEW_GOVERNANCE to read,
 *                                    MANAGE_GOVERNANCE to write, including the
 *                                    two decisions approuver / rejeter)
 *       DemandeChangementRepository ->  returns DemandeChangement entities,
 *                                       already filtered on deleted = false
 *         DemandeChangementMapper   ->  THIS FILE: DemandeChangement entity ==>
 *                                       DemandeChangementResponse
 *           Jackson                 ->  writes the record as JSON
 *             Angular               ->  reads it as the "DemandeChangement"
 *                                       interface in
 *                                       core/models/governance.model.ts
 *
 * This file calls nothing itself, apart from User.getFullName() inside its own
 * helper. MapStruct (the annotation processor declared in pom.xml, ADR-018)
 * reads this interface at compile time and writes the real class
 * DemandeChangementMapperImpl; Spring injects that generated class into
 * DemandeChangementService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 * This is the most important mapper of the four in this package, because the
 * entity points at a User, not only at a Project.
 *  1. THE REAL DANGER: returning the entity would publish the whole User
 *     object - the account email, the bcrypt password hash (bcrypt is the
 *     one-way function used to store passwords), the tokenVersion counter and
 *     the role. A simple read endpoint would become a gift to anyone trying to
 *     crack passwords offline. This mapper sends exactly two things about the
 *     person: the id and the display name.
 *  2. Both relations are @ManyToOne(fetch = FetchType.LAZY), so each one is
 *     only a placeholder until Hibernate loads it. If Jackson tried to read
 *     them after the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the request.
 *  3. The entity also carries createdAt, createdBy, updatedAt, updatedBy and
 *     deleted from BaseEntity. Those are internal bookkeeping columns; sending
 *     them would make them part of the public contract by accident.
 *  4. Database column names would become the public API contract. Renaming
 *     date_decision would then silently break the Angular governance page.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never decides anything. Setting statut to APPROUVE or REJETE and
 *    stamping dateDecision with LocalDate.now() is done by
 *    DemandeChangementService, which also refuses to decide twice with a
 *    BusinessRuleException. The mapper only reports the state reached.
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_GOVERNANCE') or
 *    hasAuthority('MANAGE_GOVERNANCE') is applied on the SERVICE method, never
 *    on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own).
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true. That filter lives in the
 *    repository queries (AND d.deleted = false), not here.
 *
 * ============================================================================
 * SISTER FILES IN THIS PACKAGE
 * ============================================================================
 * RiskMapper, LivrableMapper and PartiePrenanteMapper do the same job for the
 * three other registers of the governance module. All four share the same two
 * @Mapping lines for the project and the same pair of methods (one object, one
 * list). This one is the longest of the four only because it also has to
 * flatten the second relation, the demandeur.
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: DemandeChangementService receives a DemandeChangementMapper through its
// constructor, so Spring has to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// DemandeChangementMapper".
@Mapper(componentModel = "spring")
public interface DemandeChangementMapper {

    /*
     * WHAT IT DOES: turns one DemandeChangement row into one
     * DemandeChangementResponse record, ready to be serialised to JSON. It
     * builds a new object and never modifies the entity it is given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to DemandeChangementResponse and
     * forget to say where it comes from, and the BUILD warns immediately; a
     * hand-written mapper would compile fine and quietly send null to the
     * browser. ADR-018 makes MapStruct mandatory in this project for exactly
     * that reason.
     *
     * WHY THE PARAMETER IS CALLED dc: the name is only used inside the
     * generated code, and DemandeChangement is long enough to make the
     * generated lines unreadable. The short name changes nothing for the
     * caller.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * titre, description, priorite, statut, dateDemande and dateDecision. Only
     * the four names that do not match are declared below.
     *
     * ABOUT priorite AND statut: they are the enums PrioriteChangement (FAIBLE,
     * NORMALE, ELEVEE, CRITIQUE) and StatutChangement (EN_ATTENTE, APPROUVE,
     * REJETE) on both sides, so MapStruct copies each value as it is, with no
     * conversion. The entity stores them with @Enumerated(EnumType.STRING), so
     * the database holds the readable word and the JSON shows
     * "statut":"APPROUVE". Those exact strings are what the TypeScript union
     * types expect and what the Transloco translation keys are built from. Had
     * the entity kept the default EnumType.ORDINAL, the database would store a
     * number instead (0, 1, 2, 3 for the four priorities; 0, 1, 2 for the three
     * statuses), and adding a new value in the middle of the enum would turn
     * every pending request into an approved one.
     *
     * ABOUT dateDemande AND dateDecision: LocalDate is a calendar day with no
     * time and no time zone, so Jackson writes "2026-09-18". A timestamp would
     * be converted to the time zone of the reader, and a decision taken on the
     * 30th could be shown as the 29th to a user one zone behind. dateDecision
     * stays null while the request is still EN_ATTENTE.
     *
     * THE FOUR @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads dc.getProject().getId() and puts it in the flat field
     *            projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null - the
     *            governance page can no longer link a request back to its
     *            project.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads dc.getProject().getCode(), the short readable project
     *            code, and copies it into the response.
     *      WHY:  a change register is read by humans, who recognise the project
     *            code, not a numeric id. Sending it in the same answer avoids a
     *            second HTTP call just to display one string.
     *      WITHOUT IT: that column is empty on screen, or the front end has to
     *            call /api/projects/{id} once per row.
     *
     *  (3) target = "demandeurId", source = "demandeur.id"
     *      WHAT: reads dc.getDemandeur().getId() - the primary key of the User
     *            who asked for the change - and puts it in the flat field
     *            demandeurId.
     *      WHY:  the edit form has to send that same id back in
     *            DemandeChangementRequest.demandeurId(), and the screen uses it
     *            to link to the person.
     *      WITHOUT IT: the field arrives as null, the edit form cannot
     *            pre-select the requester, and saving the form would send
     *            demandeurId: null - which the request validation rejects.
     *
     *  (4) target = "demandeurFullName", source = "demandeur",
     *      qualifiedByName = "fullName"
     *      WHAT: note the source here is the WHOLE User object, not one of its
     *            properties. MapStruct cannot turn a User into a String by
     *            itself, so qualifiedByName sends the object through the helper
     *            method named "fullName" written at the bottom of this file.
     *      WHY:  without the name the browser would get only demandeurId and
     *            would have to call /api/users/{id} once per row. Twenty change
     *            requests would mean twenty extra HTTP calls (the "N+1 calls"
     *            problem) just to print twenty names.
     *      WHY qualifiedByName RATHER THAN NOTHING: it names the helper
     *            explicitly instead of letting MapStruct look for any method
     *            able to turn a User into a String. Today only one such method
     *            exists, so it would work either way; the day somebody adds a
     *            second one - initials(User), for example - the build would
     *            stop with "Ambiguous mapping methods found". Naming the helper
     *            removes that guesswork for good.
     *      WHY ONLY THE NAME: see the file header. Passing the User object
     *            straight into the response would publish the email, the
     *            password hash and the role along with it.
     *
     * A NOTE ON THE DOTTED PATHS "project.id" AND "demandeur.id": these are
     * nested source paths. For such a path MapStruct generates a small private
     * helper that checks every step for null, so a row with no project would
     * give projectId = null instead of a NullPointerException. That safety net
     * costs nothing here, because project_id and demandeur_id are both declared
     * NOT NULL in V11.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * both dc.project and dc.demandeur are LAZY, so each may still be a proxy
     * (a shell object that only knows its id). getId() on a proxy costs
     * nothing, Hibernate already has that value; but getCode() and
     * getFullName() read real columns, so each one forces a trip to the
     * database. That is why DemandeChangementRepository.findActiveByProjectId
     * and findActiveById both write "JOIN FETCH d.project JOIN FETCH
     * d.demandeur": the project and the requester arrive already loaded and
     * this mapping costs zero extra query. Remove those JOIN FETCH clauses and
     * the code still works - the service methods are @Transactional, so the
     * Hibernate session is still open - but a list of twenty requests silently
     * fires forty extra SELECT statements, two per row. This is the classic
     * "N+1 queries" problem, and it is the reason the two JOIN FETCH clauses
     * are there.
     */
    @Mapping(target = "projectId",         source = "project.id")
    @Mapping(target = "projectCode",       source = "project.code")
    @Mapping(target = "demandeurId",       source = "demandeur.id")
    @Mapping(target = "demandeurFullName", source = "demandeur", qualifiedByName = "fullName")
    DemandeChangementResponse toResponse(DemandeChangement dc);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on
     * each element, add the result).
     *
     * WHY DECLARE IT HERE instead of writing
     * list.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same four @Mapping rules as the single
     * method above, so the list version can never drift away from it. It also
     * returns null for a null input instead of throwing a
     * NullPointerException.
     *
     * WHO CALLS IT: DemandeChangementService.findByProject(), to fill the
     * change-request table of one project. The repository already sorts the
     * rows with "ORDER BY d.dateDemande DESC", newest request first, and this
     * loop keeps that order, so the most recent demands are on top with no
     * sorting done in the front end.
     */
    List<DemandeChangementResponse> toResponseList(List<DemandeChangement> list);

    /*
     * WHAT IT DOES: takes a User and gives back the name to print - first name
     * plus a space plus last name, since that is what User.getFullName()
     * builds. It gives back null when the User is null.
     *
     * WHY @Named("fullName"): it puts a label on this method so that a @Mapping
     * line can ask for it by name with qualifiedByName = "fullName" (see rule
     * (4) above). Without the label MapStruct would have to guess which method
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
     * WHY THE NULL CHECK MATTERS: MapStruct calls this method with
     * dc.getDemandeur() WITHOUT testing it first - it trusts the method to cope
     * with null. So if demandeur were ever null, user.getFullName() would throw
     * a NullPointerException and the whole GET would answer 500 instead of
     * returning the list. Today the column demandeur_id is NOT NULL, so it
     * should not happen; the guard is what keeps a data problem from becoming a
     * crash.
     *
     * WHY NOT REUSE ProjectMapper.fullName: MapStruct only sees the methods
     * declared in this interface (or in a mapper it is told to use). Each
     * mapper that needs the rule declares its own three-line copy. It is a
     * little repetition, but it keeps every mapper readable on its own and
     * avoids a web of dependencies between mappers.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}
