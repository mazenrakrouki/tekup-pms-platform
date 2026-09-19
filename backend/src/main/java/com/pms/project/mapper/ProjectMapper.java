package com.pms.project.mapper;

import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.Project;
import com.pms.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * The translator between one row of the "projects" table (the Project entity)
 * and the flat JSON object sent to the browser (the ProjectResponse record).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here Project,
 *    table "projects".
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record ProjectResponse.
 *  - "mapper" = the code that copies the fields of an entity into a DTO. This
 *    file only DESCRIBES the copying rules and adds two tiny helpers;
 *    MapStruct writes the real code at compile time.
 *  - "record" = a Java class whose fields are set once in the constructor and
 *    never change afterwards.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   ProjectController        ->  /api/projects, /api/projects/{id}, /archived,
 *                                and the lifecycle endpoints (status, chef,
 *                                archive, unarchive)
 *     ProjectService         ->  opens the transaction and holds the security
 *                                checks: hasAuthority('VIEW_PROJECT'),
 *                                'CREATE_PROJECT', 'EDIT_PROJECT',
 *                                'DELETE_PROJECT', 'ASSIGN_CHEF_PROJET'
 *       ProjectRepository    ->  returns Project entities, already filtered on
 *                                deleted = false, with the two users loaded by
 *                                LEFT JOIN FETCH
 *         ProjectMapper      ->  THIS FILE: Project entity ==> ProjectResponse
 *           ProjectService.toResponse(...)  ->  applies BR-050 right after
 *                                this mapper: without the VIEW_KPI permission
 *                                it calls response.withoutFinancials() and
 *                                blanks every amount
 *             Jackson        ->  writes the record as JSON
 *               Angular      ->  reads it as the "Project" interface in
 *                                core/models/
 *
 * This file calls nothing itself, apart from User.getId() / User.getFullName()
 * and Project.getEffectiveBudget() from inside its own rules. MapStruct (the
 * annotation processor declared in pom.xml; ADR-018 makes it mandatory) reads
 * this interface while the project compiles and writes the real class
 * ProjectMapperImpl; Spring injects that generated class into ProjectService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. THE REAL DANGER: returning the Project entity as it is would publish the
 *     two whole User objects behind director and chefProjet - account email,
 *     the bcrypt password hash (bcrypt is the one-way function used to store
 *     passwords, so the column holds a hash and not the password itself), the
 *     tokenVersion counter and the role. A plain read endpoint would become a
 *     gift to anyone trying to crack passwords offline. This mapper sends
 *     exactly two things about each person: the id and the display name.
 *  2. Both relations are @ManyToOne(fetch = FetchType.LAZY): until Hibernate
 *     loads them they are only placeholders. If Jackson tried to read them
 *     after the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the project.
 *  3. The entity also carries createdBy, updatedAt, updatedBy and deleted from
 *     BaseEntity. Those are internal bookkeeping columns; sending them would
 *     make them part of the public contract by accident. Only createdAt is
 *     wanted, and ProjectResponse lists it on purpose.
 *  4. Database column names would become the public API contract. Renaming
 *     marge_nette_vendue would then silently break the Angular project sheet.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never hides money. Blanking the amounts for a user who has no VIEW_KPI
 *    permission (BR-050) happens AFTER this mapper, in the private method
 *    ProjectService.toResponse(...), which calls
 *    ProjectResponse.withoutFinancials(). The rule is one level up on purpose:
 *    a mapper has no access to the security context, and MapStruct rules are
 *    fixed at compile time while this decision changes with every caller.
 *  - It never checks a permission and never checks a project scope.
 *    Authorization in PMS is dynamic and permission-based:
 *    @PreAuthorize("hasAuthority('VIEW_PROJECT')") sits on the SERVICE method,
 *    never on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL under /api/projects/{id}/**,
 *    that this user may see THIS project (ADR-021: holding the permission is
 *    not enough on its own), and ProjectService additionally calls
 *    scopeService.assertCanAccess(...) or filters on
 *    scopeService.accessibleProjectIds(...).
 *  - It never hides deleted or archived rows. Deletion in PMS is "soft": the
 *    row stays and the boolean column "deleted" is set to true. That filter,
 *    and the archived = true / false split, live in the repository queries, not
 *    here.
 *  - It never computes a stored amount. effectiveBudget, budgetTnd, pprTnd and
 *    durationDays are all DERIVED when read, from getters on the entity. No
 *    column holds them, so they can never fall out of date.
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: ProjectService receives a ProjectMapper through its constructor
// (@RequiredArgsConstructor), so Spring has to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// ProjectMapper".
@Mapper(componentModel = "spring")
public interface ProjectMapper {

    /*
     * WHAT IT DOES: turns one Project row into one ProjectResponse record,
     * ready to be serialised to JSON. It builds a new object and never modifies
     * the entity it is given.
     *
     * WHY AN INTERFACE WITH NO BODY, rather than a class written by hand:
     * MapStruct generates the body while the project is compiled, so the
     * compiler checks every field. Add a field to ProjectResponse and forget to
     * say where it comes from, and the BUILD warns immediately; a hand-written
     * mapper would compile fine and quietly send null to the browser. ADR-018
     * makes MapStruct mandatory in this project for exactly that reason.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides:
     * id, code, name, description, status, startDate, endDate, initialBudget,
     * revisedBudget, contractId, client, funder, businessModel, engagementType,
     * currency, exchangeRateToTnd, licenseSubcontractBudget, soldWorkloadDays,
     * warrantyWorkloadDays, penaltyProvision, margeNetteVendue, archived and
     * createdAt (createdAt comes from BaseEntity). Only the five names that do
     * not match a plain field are declared below.
     *
     * THE THREE COMPUTED FIELDS THAT NEED NO LINE HERE: durationDays,
     * budgetTnd and pprTnd. MapStruct matches a target name against a GETTER,
     * not against a column, so it finds Project.getDurationDays(),
     * getBudgetTnd() and getPprTnd() by name alone. None of the three is stored
     * anywhere: durationDays counts the days between startDate and endDate with
     * both ends included, budgetTnd multiplies the effective budget by
     * exchangeRateToTnd, and pprTnd takes 5 % of that. Storing them in columns
     * instead would mean three values to refresh by hand every time a date or a
     * rate changes, and a project sheet showing a duration that no longer
     * matches its own two dates.
     *
     * ABOUT status, businessModel AND engagementType: these are the enums
     * ProjectStatus, BusinessModel (SEUL | GROUPEMENT) and EngagementType
     * (FORFAIT | REGIE) on both sides, so MapStruct copies each value as it is,
     * with no conversion. The entity stores them with
     * @Enumerated(EnumType.STRING), so the database holds the readable word and
     * the JSON shows "engagementType":"FORFAIT". Those exact strings are what
     * the TypeScript union types expect and what the Transloco translation keys
     * are built from. Had the entity kept the default EnumType.ORDINAL, the
     * database would store 0, 1, 2, and adding a new value in the middle of the
     * enum would silently turn every fixed-price project into a
     * time-and-means one.
     *
     * ABOUT startDate AND endDate: LocalDate is a calendar day with no time and
     * no time zone, so Jackson writes "2026-09-18". A timestamp would be
     * converted to the time zone of the reader, and a contract ending on the
     * 30th could be shown as the 29th to a user one zone behind.
     *
     * THE FIVE @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "directorId", source = "director",
     *      qualifiedByName = "userId"
     *      WHAT: the source is the WHOLE User object, not one of its
     *            properties. MapStruct cannot turn a User into a Long by
     *            itself, so qualifiedByName sends the object through the helper
     *            method named "userId" written at the bottom of this file, and
     *            the result lands in the flat field directorId.
     *      WHY:  the response is deliberately flat, and the edit form has to
     *            send that same id back in ProjectRequest.directorId().
     *      WITHOUT IT: the field arrives as null, the form cannot pre-select the
     *            director, and saving the form would erase the director of the
     *            project.
     *
     *  (2) target = "directorName", source = "director",
     *      qualifiedByName = "fullName"
     *      WHAT: same whole-object source, sent through the helper "fullName",
     *            which returns "Firstname Lastname".
     *      WHY:  a project list is read by humans, who recognise a name, not a
     *            numeric id. Sending it in the same answer avoids a second HTTP
     *            call per row.
     *      WITHOUT IT: the column is empty on screen, or the front end has to
     *            call /api/users/{id} once per project - twenty projects, twenty
     *            extra calls, the "N+1 calls" problem.
     *
     *  (3) and (4) target = "chefProjetId" / "chefProjetName",
     *      source = "chefProjet"
     *      WHAT: exactly the same two rules for the second person attached to a
     *            project, the chef de projet (project manager).
     *      WHY TWO SEPARATE PEOPLE: the director and the chef de projet are two
     *            different jobs in the company and are set by different
     *            endpoints - assigning the chef even needs its own permission,
     *            ASSIGN_CHEF_PROJET, on top of CREATE_PROJECT or EDIT_PROJECT.
     *      WITHOUT THEM: the project list could not show who runs each project,
     *            and ProjectScopeService - which decides which projects a chef
     *            may see (ADR-021) - would have nothing to display next to the
     *            rows it just filtered.
     *
     *      WHY qualifiedByName RATHER THAN NOTHING, on all four lines: it names
     *            the helper explicitly instead of letting MapStruct look for any
     *            method able to turn a User into a Long or into a String. Today
     *            only one of each exists, so it would work either way; the day
     *            somebody adds a second one - initials(User), for example - the
     *            build would stop with "Ambiguous mapping methods found".
     *            Naming the helper removes that guesswork for good.
     *      WHY ONLY THE ID AND THE NAME: see the file header. Passing the User
     *            object straight into the response would publish the email, the
     *            password hash, the tokenVersion and the role along with it.
     *
     *  (5) target = "effectiveBudget",
     *      expression = "java(project.getEffectiveBudget())"
     *      WHAT: "expression" lets a rule call plain Java code instead of
     *            copying a field. getEffectiveBudget() returns revisedBudget
     *            when a revised budget exists, and initialBudget otherwise.
     *      WHY:  a project can be re-budgeted. Every screen that shows "the"
     *            budget must show the one in force, and every figure computed
     *            from it (budgetTnd, then pprTnd) must start from the same
     *            number. Writing the rule once here means the front end never
     *            has to choose between the two columns itself, and can never
     *            choose differently from the back end.
     *      WHY DERIVED AND NOT A STORED COLUMN: an effective_budget column would
     *            have to be rewritten on every budget revision. Forget it once
     *            and the sheet shows an amount that contradicts the two other
     *            lines just above it. Derived on read, it cannot go stale.
     *      WHY THE LINE IS WRITTEN AT ALL: the target name effectiveBudget also
     *            matches the getter getEffectiveBudget(), so MapStruct could
     *            find it on its own. Spelling the rule out states the intent in
     *            one place instead of leaving it to a name match that a later
     *            rename could quietly undo.
     *      NOTE ON THE WORD "project" INSIDE THE STRING: it is the NAME OF THE
     *            METHOD PARAMETER declared just below. MapStruct copies this
     *            text as it is into the generated class, so renaming the
     *            parameter to "p" breaks the BUILD with "cannot find symbol:
     *            variable project". The compiler cannot check anything inside
     *            that string until the code is generated - that is the price of
     *            an expression, and the reason the two helpers at the bottom are
     *            written as real methods instead.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part: both
     * project.director and project.chefProjet are LAZY, so each may still be a
     * proxy (a shell object that only knows its id). getId() on a proxy costs
     * nothing, Hibernate already has that value; but getFullName() reads real
     * columns, so it forces a trip to the database. That is why every read query
     * in ProjectRepository writes "LEFT JOIN FETCH p.director LEFT JOIN FETCH
     * p.chefProjet": both people arrive already loaded and this mapping costs
     * zero extra query. The join is LEFT and not INNER because director_id and
     * chef_projet_id are both nullable - an INNER JOIN FETCH would simply drop
     * every project that has no chef de projet yet, and a freshly created
     * project would disappear from the list. Remove the JOIN FETCH clauses and
     * the code still works - the service methods are @Transactional, so the
     * Hibernate session is still open - but a list of twenty projects silently
     * fires up to forty extra SELECT statements, two per row. This is the
     * classic "N+1 queries" problem.
     */
    @Mapping(target = "directorId",    source = "director",   qualifiedByName = "userId")
    @Mapping(target = "directorName",  source = "director",   qualifiedByName = "fullName")
    @Mapping(target = "chefProjetId",  source = "chefProjet", qualifiedByName = "userId")
    @Mapping(target = "chefProjetName",source = "chefProjet", qualifiedByName = "fullName")
    @Mapping(target = "effectiveBudget", expression = "java(project.getEffectiveBudget())")
    ProjectResponse toResponse(Project project);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us (create an ArrayList, walk the source list, call toResponse on each
     * element, add the result). It returns null for a null input instead of
     * throwing a NullPointerException.
     *
     * WHY DECLARE IT HERE: so that the list version can never drift away from
     * the single-object version - the generated loop reuses the very same five
     * @Mapping rules written above.
     *
     * WHO CALLS IT TODAY: nobody. ProjectService deliberately keeps its own
     * private toResponseList(...), which walks the projects and calls its own
     * private toResponse(...) on each one, because that private method is where
     * BR-050 is applied: without the VIEW_KPI permission every amount is blanked
     * by ProjectResponse.withoutFinancials(). Calling THIS method from the
     * service instead would skip that step and send initialBudget,
     * revisedBudget, effectiveBudget, licenseSubcontractBudget, penaltyProvision
     * and margeNetteVendue to a user who is not allowed to see money. The method
     * is kept for symmetry with the other mappers of the project; as it stands
     * it must not be wired into the project endpoints.
     */
    List<ProjectResponse> toResponseList(List<Project> projects);

    /*
     * WHAT IT DOES: gives back the primary key of a User, or null when there is
     * no user. It is the helper that rules (1) and (3) above point at.
     *
     * WHY @Named("userId"): it gives the method a label that a @Mapping line can
     * call by name. Without the label MapStruct would have to guess which method
     * to use from the types alone, and the mapping would break as soon as a
     * second User-to-Long method appeared in this interface.
     *
     * WHY "default" AND NOT AN ABSTRACT METHOD: since Java 8 an interface may
     * carry a method with a body. MapStruct generates a class that implements
     * this interface, so the generated class inherits this body and can call it
     * directly.
     *
     * WHY THE NULL CHECK MATTERS: MapStruct calls this method with
     * project.getDirector() WITHOUT testing it first - it trusts the method to
     * cope with null. Both director_id and chef_projet_id are nullable columns,
     * and a project created without a chef de projet really does arrive here
     * with chefProjet == null. Without the guard, user.getId() would throw a
     * NullPointerException and GET /api/projects would answer 500 instead of
     * returning the list.
     */
    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }

    /*
     * WHAT IT DOES: gives back the display name of a User ("Firstname Lastname",
     * built by User.getFullName()), or null when there is no user. It is the
     * helper that rules (2) and (4) above point at.
     *
     * WHY THE NULL CHECK MATTERS: same reason as userId just above, and the risk
     * is higher here - getFullName() reads two columns and joins them, so on a
     * null user it would throw straight away. A project with no chef de projet
     * assigned yet must simply show an empty cell, not break the whole list.
     *
     * WHY NOT REUSE ANOTHER MAPPER'S fullName: MapStruct only sees the methods
     * declared in this interface (or in a mapper it is told to use). Each mapper
     * that needs the rule declares its own three-line copy. It is a little
     * repetition, but it keeps every mapper readable on its own and avoids a web
     * of dependencies between mappers.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}
