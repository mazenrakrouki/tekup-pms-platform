package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * The translator between one row of the planned-workload table (the PlanCharge
 * entity, table "plan_charges", created by migration V7__schema_workload.sql)
 * and the flat JSON object sent to the browser (the PlanChargeResponse DTO).
 *
 * "Plan de charge" is French for "workload plan". One row = one person, on one
 * project, for one month, with the number of days the manager PLANS for them
 * (planned_days). It is the forecast side of the workload feature; the real
 * side - the days actually worked - lives in ChargeReelle, next door.
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here PlanCharge,
 *    which maps the table plan_charges.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record PlanChargeResponse.
 *  - "mapper" = the code that copies the fields of an entity into a DTO. This
 *    file copies nothing itself: it only DESCRIBES the copying rules, and
 *    MapStruct writes the real code from them at compile time.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   WorkloadController  ->  /api/projects/{projectId}/plan-charges
 *                           (GET list, POST create, PUT .../{id},
 *                            DELETE .../{id})
 *     PlanChargeService  ->  opens the transaction and holds the security
 *                            checks: hasAuthority('VIEW_WORKLOAD') to read,
 *                            hasAuthority('VALIDATE_WORKLOAD') to create,
 *                            change or remove a planned line
 *       PlanChargeRepository ->  returns PlanCharge entities, already filtered
 *                                on deleted = false and already loaded with
 *                                JOIN FETCH on project and user
 *         PlanChargeMapper   ->  THIS FILE: PlanCharge entity ==>
 *                                PlanChargeResponse
 *           Jackson          ->  writes the record as JSON
 *             Angular        ->  reads it as the "PlanCharge" interface in
 *                                core/models/workload.model.ts, through
 *                                core/services/workload.service.ts
 *                                (listPlanCharges / createPlanCharge), and
 *                                prints it in
 *                                features/workload/workload.component.ts,
 *                                where the planned days of each month sit in
 *                                the same cell as the actual days, so the two
 *                                can be compared at a glance.
 *
 * This file calls nothing itself, except its own helper fullName() at the
 * bottom. MapStruct (the annotation processor declared in pom.xml, ADR-018)
 * reads this interface while the project compiles and writes the real class
 * PlanChargeMapperImpl; Spring injects that generated class into
 * PlanChargeService.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. A PlanCharge holds a whole User object. User holds passwordHash (the
 *     encrypted password), email, the active and firstLogin flags,
 *     tokenVersion (the counter used to revoke every session of that person at
 *     once) and the whole Role with its permission list. Returning the entity
 *     would put all of that in the JSON of a simple plan list. This mapper lets
 *     exactly two values of the user out: the id and the printed name.
 *  2. A PlanCharge also holds a whole Project object, so a plan list would
 *     publish the budget, the client and the dates of the project to anyone
 *     allowed to read the workload.
 *  3. project and user are both @ManyToOne(fetch = FetchType.LAZY), so they are
 *     only placeholders until Hibernate loads them. If Jackson tried to read
 *     one after the transaction is closed, the call would fail with
 *     LazyInitializationException instead of returning the plan.
 *  4. The database stores ONE date column, "period", always set to the 1st of
 *     the month (see V7 and PlanChargeService, which builds
 *     LocalDate.of(year, month, 1)). The screen needs a year and a month as two
 *     numbers. Splitting that date is done here, in one place, for every
 *     answer.
 *  5. The entity also carries createdAt, createdBy, updatedAt, updatedBy and
 *     deleted from BaseEntity. Those are internal bookkeeping columns; sending
 *     them would make them part of the public contract by accident.
 *
 * ============================================================================
 * WHAT THIS MAPPER DOES NOT DO - useful if the jury asks
 * ============================================================================
 *  - It never checks a permission. Authorization in PMS is dynamic and
 *    permission-based: hasAuthority('VIEW_WORKLOAD') and
 *    hasAuthority('VALIDATE_WORKLOAD') are applied on the SERVICE methods,
 *    never on a role name and never on the controller. On top of that,
 *    ProjectScopeInterceptor checks, for every URL matching
 *    /api/projects/{id}/**, that this user may see THIS project (ADR-021:
 *    holding the permission is not enough on its own). The four URLs of this
 *    feature are inside that pattern, so both checks apply to them.
 *  - It never decides WHOSE rows are returned. BR-062..064 say a developer sees
 *    only their own lines while a holder of VALIDATE_WORKLOAD or
 *    VIEW_ALL_PROJECTS sees the whole team. That choice is made in
 *    PlanChargeService.canSeeAllWorkload(), which picks between two repository
 *    queries. By the time a row reaches this mapper, the decision is already
 *    taken and the mapper translates whatever it is given.
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true (PlanChargeService.delete
 *    calls setDeleted(true) and saves - it never runs a SQL DELETE). The filter
 *    "AND pc.deleted = false" lives in the repository queries, not here.
 *  - It never validates anything. "Planned days must be above zero and at most
 *    31" is checked twice before a row reaches this file: by the annotations on
 *    PlanChargeRequest and by the database constraint chk_pc_days of V7. "One
 *    line per person per month" is the partial unique index uk_pc_active on
 *    (project_id, user_id, period) WHERE deleted = FALSE, doubled in
 *    PlanChargeService by an existsBy... check that turns it into a readable
 *    message instead of a raw SQL error. "The person must be an active member
 *    of the project team" is H-8, in PlanChargeService.assertTeamMembership.
 *
 * ============================================================================
 * SISTER FILE
 * ============================================================================
 * ChargeReelleMapper, right next to this one, does the same job for the ACTUAL
 * workload (table charges_reelles). Comparing the two is the quickest way to
 * see the difference between the two halves of this feature:
 *  - this file has SIX mapping rules, the other has EIGHT. The two extra ones
 *    carry the validator (validatedById, validatedByName), because an actual
 *    charge goes through a submit-then-approve cycle. A plan has no such cycle:
 *    it is written by the manager, so there is nobody left to approve it. That
 *    is also why this file needs only ONE helper at the bottom and the other
 *    needs two.
 *  - this file has no nullable user link, so its repository query can use a
 *    plain JOIN FETCH on both sides, where the other one needs
 *    LEFT JOIN FETCH on the validator.
 *  - the permissions differ: writing a plan needs VALIDATE_WORKLOAD (planning
 *    is a manager's act), while declaring an actual charge needs only
 *    SUBMIT_WORKLOAD, which a developer holds for their own rows (BR-033).
 * The two files are kept separate on purpose rather than merged behind one
 * generic mapper: they translate two different tables into two different DTOs,
 * and a shared parent would have to be told, at every call, which half it is
 * working on.
 * Six mappers of this project flatten a User the very same way, with the same
 * qualifiedByName = "fullName" helper: MissionMapper, ChargeReelleMapper,
 * ResourceMapper and TeamAssignmentMapper on a field also called "user",
 * ProjectMapper twice (director and chefProjet), DemandeChangementMapper on
 * demandeur. The shared behaviour that really matters - how a full name is
 * built - lives in one single place, User.getFullName().
 */

/**
 * Entity-to-DTO rules for the planned workload (plan de charge).
 *
 * <p>Why an interface with no body rather than a class written by hand:
 * MapStruct generates the body while the project is compiled, so the compiler
 * checks every field. Add a field to PlanChargeResponse and forget to say where
 * it comes from, and the BUILD warns immediately; a hand-written mapper would
 * compile fine and quietly send null to the browser. ADR-018 makes MapStruct
 * mandatory in this project for exactly that reason.
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: PlanChargeService receives a PlanChargeMapper through its constructor
// (Lombok @RequiredArgsConstructor), so Spring has to hold one instance of it.
// Without componentModel = "spring" the generated class is an ordinary class,
// not a Spring bean; nobody can inject it, and the application refuses to start
// with "NoSuchBeanDefinitionException: no qualifying bean of type
// PlanChargeMapper".
@Mapper(componentModel = "spring")
public interface PlanChargeMapper {

    /*
     * WHAT IT DOES: turns one PlanCharge row into one PlanChargeResponse
     * record, ready to be serialised to JSON. It builds a new object and never
     * modifies the entity it is given. Given null it gives back null: the
     * generated code starts with "if (planCharge == null) return null;", so a
     * missing row never becomes a NullPointerException here.
     *
     * WHO CALLS IT DIRECTLY: PlanChargeService.create() and
     * PlanChargeService.update(), each time on the object returned by
     * repository.save(), so that the browser gets the saved row back with its
     * database-generated id. The paged read passes it as a method reference to
     * Page.map(...), which applies it to every row of the page and keeps the
     * page metadata (total number of rows, page number) untouched.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id
     * and plannedDays. Only the six names that do not match are declared below.
     *
     * ABOUT plannedDays: it is a BigDecimal, not a double. The column is
     * NUMERIC(5,2) in V7 and the entity repeats precision = 5, scale = 2.
     * BigDecimal keeps exact decimals; a double cannot hold 0.1 exactly, so
     * adding twenty half-days of a double would slowly drift and the monthly
     * total shown on the workload screen would end in ...9999. The value is
     * copied as it is, with no rounding and no unit change - it is a number of
     * DAYS, not hours.
     *
     * THE SIX @Mapping LINES BELOW must stay glued to the method signature: an
     * annotation always describes the element written right after it, so a line
     * of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads planCharge.getProject().getId() and puts it in the flat
     *            field projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs and to check that a row
     *            belongs to the project it is showing, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads planCharge.getProject().getCode(), the short readable
     *            project code (for example "PRJ-2026-004"), and copies it.
     *      WHY:  it makes one row readable on its own, outside the screen that
     *            asked for it - in an export, in a log, or in a future "all my
     *            planned months" list where rows of several projects are mixed.
     *      WITHOUT IT: a row taken alone would carry only a number, and the
     *            reader would have to call /api/projects/{id} once per row just
     *            to print one short string.
     *      NOTE: the workload screen does not print it today, because it
     *            already knows which project it asked for. It costs nothing
     *            extra, because the project is already loaded by the JOIN FETCH
     *            in the repository query.
     *
     *  (3) target = "userId", source = "user.id"
     *      WHAT: reads planCharge.getUser().getId() - the primary key of the
     *            planned person - and puts it in the flat field userId.
     *      WHY:  the front end needs that id as a key, not as decoration. The
     *            workload screen builds one row of its occupation table per
     *            person and needs this id to put the planned days and the
     *            actual days of the same person on the same line; the "plan a
     *            month" form sends this same id back as the userId of the new
     *            line. The server then checks it again:
     *            PlanChargeService.assertTeamMembership calls
     *            TeamAssignmentRepository
     *            .existsByProjectIdAndUserIdAndDeletedFalse (H-8), so a
     *            hand-made HTTP call cannot plan work for somebody outside the
     *            team.
     *      WITHOUT IT: the field arrives as null, and the screen can no longer
     *            tell two people apart - two members with the same printed name
     *            would be merged into one line of the table.
     *
     *  (4) target = "userFullName", source = "user",
     *      qualifiedByName = "fullName"
     *      WHAT: note the source here is the WHOLE User object, not one of its
     *            properties. MapStruct cannot turn a User into a String by
     *            itself, so qualifiedByName sends the object through the helper
     *            method labelled "fullName" written at the bottom of this file.
     *      WHY:  without the name the browser would get only userId and would
     *            have to call /api/users/{id} once per row. A page of twenty
     *            planned lines would mean twenty extra HTTP calls (the "N+1
     *            calls" problem) just to print twenty names.
     *      WHY qualifiedByName RATHER THAN NOTHING: it names the helper
     *            explicitly instead of letting MapStruct look for any method
     *            able to turn a User into a String. Today only one such method
     *            exists in this interface, so it would work either way; the day
     *            somebody adds a second one - initials(User), for example - the
     *            build would stop with "Ambiguous mapping methods found".
     *            Naming the helper removes that guesswork for good.
     *      WHY ONLY THE NAME: see point 1 of the file header. Passing the User
     *            object straight into the response would publish the password
     *            hash, the email, the token version and the whole Role with its
     *            permissions.
     *      WITHOUT IT: the browser gets userFullName: null, and the occupation
     *            table shows a column of empty names that nobody can read.
     *
     *  (5) target = "year",
     *      expression = "java(planCharge.getPeriod().getYear())"
     *      WHAT: "expression" means "paste this Java code into the generated
     *            class instead of looking for a source property". Here it reads
     *            the LocalDate "period" and keeps only its year, so 2026-03-01
     *            becomes 2026.
     *      WHY:  the database stores the month as a whole DATE fixed on the 1st
     *            (V7: "period DATE NOT NULL", always the first day of the
     *            month), which is convenient for sorting and comparing in SQL.
     *            The screen, on the other hand, shows a year selector and a
     *            column per month, and the request DTO also speaks in year +
     *            month. Splitting the date here makes the answer and the
     *            request use the same two numbers.
     *      WHY AN EXPRESSION rather than source = "period.year": the expression
     *            states plainly what is computed and keeps the pair (year,
     *            month) reading as one deliberate split of one column into two
     *            fields, instead of looking like two ordinary copied
     *            properties.
     *      WITHOUT IT: the response would carry year: null, and the workload
     *            screen - which builds its list of years out of the years of
     *            the planned and the actual rows - would show an empty selector
     *            and an empty occupation table.
     *      THE TRAP TO KNOW: the text inside expression = "java(...)" is copied
     *            into the generated file AS IT IS, so "planCharge" must stay
     *            the exact name of the method parameter below. Rename that
     *            parameter to "pc" and this string still compiles as a string,
     *            but the GENERATED class no longer compiles: "cannot find
     *            symbol: variable planCharge". That is why the parameter here
     *            keeps its long name.
     *      ONE HONEST WARNING: this expression calls getPeriod() with no null
     *            check, so a PlanCharge whose period is null would throw a
     *            NullPointerException. It cannot happen through the normal
     *            path: the column is NOT NULL in V7, the entity repeats
     *            nullable = false, and the service always fills it. It is only
     *            a risk for an object built by hand in a test.
     *
     *  (6) target = "month",
     *      expression = "java(planCharge.getPeriod().getMonthValue())"
     *      WHAT: the same idea for the month. getMonthValue() gives the number
     *            1..12, so 2026-03-01 becomes 3.
     *      WHY getMonthValue() AND NOT getMonth(): getMonth() returns the enum
     *            java.time.Month, which Jackson would write as the English text
     *            "MARCH". The screen translates month names itself with
     *            Transloco, in the language of the user, so it wants the plain
     *            number. Sending "MARCH" would force English on a French
     *            screen and would break the sort, which compares numbers.
     *      WITHOUT IT: month: null, and every planned line would fall outside
     *            the twelve month columns, so the table would look empty even
     *            though the rows arrived.
     *
     * A NOTE ON THE DOTTED PATHS "project.id", "project.code" AND "user.id":
     * these are nested source paths. For such a path MapStruct generates a
     * small private helper that checks every step for null, so a row with no
     * project would give projectId = null instead of a NullPointerException.
     * That safety net costs nothing here, because project_id and user_id are
     * both declared NOT NULL in V7 and repeated as nullable = false on the
     * entity.
     *
     * A NOTE ON LAZY LOADING, because this is the fragile part:
     * project and user are both LAZY, so each may still be a proxy (a shell
     * object that only knows its id). getId() on a proxy costs nothing,
     * Hibernate already has that value; but getCode() and getFullName() read
     * real columns, so each one forces a trip to the database. That is why the
     * read queries of PlanChargeRepository write "JOIN FETCH pc.project JOIN
     * FETCH pc.user": both sides arrive already loaded and this mapping costs
     * zero extra query. Remove those clauses and the code still works - the
     * service methods are @Transactional, so the Hibernate session is still
     * open - but a page of twenty planned lines silently fires forty extra
     * SELECT statements, two per row. This is the classic "N+1 queries"
     * problem, and it is the reason the JOIN FETCH clauses are there.
     */
    @Mapping(target = "projectId",    source = "project.id")
    @Mapping(target = "projectCode",  source = "project.code")
    @Mapping(target = "userId",       source = "user.id")
    @Mapping(target = "userFullName", source = "user", qualifiedByName = "fullName")
    @Mapping(target = "year",         expression = "java(planCharge.getPeriod().getYear())")
    @Mapping(target = "month",        expression = "java(planCharge.getPeriod().getMonthValue())")
    PlanChargeResponse toResponse(PlanCharge planCharge);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us: create an ArrayList of the right size, walk the source list, call
     * toResponse on each element, add the result. It returns null for a null
     * input instead of throwing a NullPointerException.
     *
     * WHY DECLARE IT HERE instead of writing
     * list.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same six @Mapping rules as the single
     * method above, so the list version can never drift away from it.
     *
     * WHO CALLS IT: the non-paged PlanChargeService.findByProject(projectId),
     * both branches of it - the "whole team" branch and the "own rows only"
     * branch (BR-062..064). No controller method uses that overload today; the
     * REST endpoint uses the paged one, which maps row by row with
     * Page.map(planChargeMapper::toResponse). The plain-list overload is kept
     * because "give me the whole plan of this project" is a question the
     * service must be able to answer without paging - an export or a
     * whole-project computation needs the full set, not one page of twenty.
     *
     * A NOTE ON ORDER: this loop keeps the order it is given and sorts nothing.
     * The order comes from the SQL: findActiveByProjectId ends with
     * "ORDER BY pc.period, pc.user.lastName" and findActiveByProjectIdAndUserId
     * with "ORDER BY pc.period". Sorting here instead would hide the rule in a
     * translator and would make the work be done twice.
     */
    List<PlanChargeResponse> toResponseList(List<PlanCharge> list);

    /*
     * WHAT IT DOES: takes a User and gives back the name to print - first name
     * plus a space plus last name, since that is what User.getFullName()
     * builds. It gives back null when the User is null.
     *
     * WHY @Named("fullName"): it puts a label on this method so that the
     * @Mapping line of rule (4) can ask for it by name with
     * qualifiedByName = "fullName". Without the label MapStruct would have to
     * guess which method to use from the types alone, and the mapping would
     * break as soon as a second User-to-String method appeared in this
     * interface.
     *
     * WHY "default" AND NOT AN ABSTRACT METHOD: since Java 8 an interface may
     * carry a method with a body. MapStruct generates a class that implements
     * this interface, so the generated class inherits this body and can call it
     * directly. Writing the rule as an expression inside the @Mapping line
     * instead would hide plain Java code inside an annotation string, where the
     * compiler checks nothing until the code is generated.
     *
     * WHY IT DELEGATES TO User.getFullName() INSTEAD OF JOINING THE TWO NAMES
     * HERE: the way a name is printed is decided once, on the User entity, and
     * every mapper that shows a person reuses it. Change it there - to "LAST
     * NAME, First name", for instance - and the workload screen, the team
     * screen and the mission screen all change together.
     *
     * WHY THE NULL CHECK MATTERS: MapStruct calls this method with
     * planCharge.getUser() WITHOUT testing it first - it trusts the method to
     * cope with null. So if user were ever null, user.getFullName() would throw
     * a NullPointerException and the whole GET would answer 500 instead of
     * returning the plan. Today the column user_id is NOT NULL, so it cannot
     * happen through the normal path; the check costs one comparison and keeps
     * one broken row from bringing the whole list down.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }
}
