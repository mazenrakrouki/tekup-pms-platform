package com.pms.workload.mapper;

import com.pms.user.entity.User;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * ============================================================================
 * The translator between one row of the actual-workload table (the ChargeReelle
 * entity, table "charges_reelles", created by migration V7__schema_workload.sql)
 * and the flat JSON object sent to the browser (the ChargeReelleResponse DTO).
 *
 * "Charge reelle" is French for "actual workload". One row = one person, on one
 * project, for one month, with the number of days really worked (actual_days),
 * plus the trace of the two steps that row goes through: it is SUBMITTED by the
 * person (submittedAt), then VALIDATED by a manager (validatedAt + validatedBy).
 *
 * Words used here, explained on first use:
 *  - "entity" = a Java object mapped to one database table. Here ChargeReelle,
 *    which maps the table charges_reelles.
 *  - "DTO" (Data Transfer Object) = a small read-only object whose only job is
 *    to carry data out of the application. Here the record ChargeReelleResponse.
 *  - "mapper" = the code that copies the fields of an entity into a DTO. This
 *    file copies nothing itself: it only DESCRIBES the copying rules, and
 *    MapStruct writes the real code from them at compile time.
 *
 * ============================================================================
 * WHERE IT SITS IN THE FLOW
 * ============================================================================
 *   WorkloadController  ->  /api/projects/{projectId}/charges-reelles
 *                           (GET list, POST submit, PUT update,
 *                            PATCH .../{id}/validate, DELETE .../{id})
 *     ChargeReelleService ->  opens the transaction and holds the security
 *                             checks: hasAuthority('VIEW_WORKLOAD') to read,
 *                             'SUBMIT_WORKLOAD' to submit or correct,
 *                             'VALIDATE_WORKLOAD' to validate or remove
 *       ChargeReelleRepository ->  returns ChargeReelle entities, already
 *                                  filtered on deleted = false and already
 *                                  loaded with JOIN FETCH on project and user
 *                                  and LEFT JOIN FETCH on validatedBy
 *         ChargeReelleMapper   ->  THIS FILE: ChargeReelle entity ==>
 *                                  ChargeReelleResponse
 *           Jackson            ->  writes the record as JSON
 *             Angular          ->  reads it as the "ChargeReelle" interface in
 *                                  core/models/workload.model.ts, through
 *                                  core/services/workload.service.ts
 *                                  (listChargesReelles / submitCharge /
 *                                  validateCharge), and prints it in
 *                                  features/workload/workload.component.ts.
 *
 * This file calls nothing itself, except its own two helpers at the bottom,
 * fullName() and userId(). MapStruct (the annotation processor declared in
 * pom.xml, ADR-018) reads this interface while the project compiles and writes
 * the real class ChargeReelleMapperImpl; Spring injects that generated class
 * into ChargeReelleService.
 *
 * NOT everything that reads a ChargeReelle goes through this mapper. KpiService
 * calls chargeReelleRepository.findValidatedByProjectId(projectId) and works on
 * the ENTITIES directly, because it only needs to add up actual_days to compute
 * the EVM indicators - it never sends those rows to the browser. This mapper is
 * only for the HTTP answer.
 *
 * ============================================================================
 * WHY IT EXISTS (what would break if this file were deleted)
 * ============================================================================
 *  1. A ChargeReelle holds TWO whole User objects (user = the person who did
 *     the work, validatedBy = the manager who approved it). User holds
 *     passwordHash (the encrypted password), email, the active and firstLogin
 *     flags, tokenVersion (the counter used to revoke every session of that
 *     person at once) and the whole Role with its permission list. Returning
 *     the entity would put all of that in the JSON of a simple workload list.
 *     This mapper lets exactly two values out of each User: the id and the
 *     printed name.
 *  2. A ChargeReelle also holds a whole Project object, so a workload list
 *     would publish the budget, the client and the dates of the project to
 *     anyone allowed to read the workload.
 *  3. project, user and validatedBy are all @ManyToOne(fetch = FetchType.LAZY),
 *     so they are only placeholders until Hibernate loads them. If Jackson
 *     tried to read one after the transaction is closed, the call would fail
 *     with LazyInitializationException instead of returning the list.
 *  4. The database stores ONE date column, "period", always set to the 1st of
 *     the month (see V7 and ChargeReelleService, which builds
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
 *    permission-based: hasAuthority('VIEW_WORKLOAD') and the two others are
 *    applied on the SERVICE methods, never on a role name and never on the
 *    controller. On top of that, ProjectScopeInterceptor checks, for every URL
 *    matching /api/projects/{id}/**, that this user may see THIS project
 *    (ADR-021: holding the permission is not enough on its own). All five URLs
 *    of this feature are inside that pattern, so both checks apply to them.
 *  - It never decides WHOSE rows are returned. BR-062..064 say a developer sees
 *    only their own workload while a holder of VALIDATE_WORKLOAD or
 *    VIEW_ALL_PROJECTS sees the whole team. That choice is made in
 *    ChargeReelleService.canSeeAllWorkload(), which picks between two
 *    repository queries. By the time a row reaches this mapper, the decision is
 *    already taken and the mapper translates whatever it is given.
 *  - It never hides deleted rows. Deletion in PMS is "soft": the row stays and
 *    the boolean column "deleted" is set to true (ChargeReelleService.delete
 *    calls setDeleted(true) and saves - it never runs a SQL DELETE). The filter
 *    "AND cr.deleted = false" lives in the repository queries, not here.
 *  - It never enforces a business rule. "You cannot change a charge that is
 *    already validated", "a developer can only act on their own charges"
 *    (BR-033) and "the person must be an active member of the project team"
 *    (H-8) are all in ChargeReelleService. The database doubles the last line
 *    of defence with chk_cr_days (actual_days between 0 and 31) and the partial
 *    unique index uk_cr_active on (project_id, user_id, period) WHERE
 *    deleted = FALSE, both from V7.
 *
 * ============================================================================
 * SISTER FILES
 * ============================================================================
 * PlanChargeMapper, right next to this one, does the same job for the PLANNED
 * workload (table plan_charges). The two are deliberately kept separate and
 * almost identical: planned and actual are two different tables, two different
 * DTOs and two different permissions, and the actual one carries four extra
 * fields for the submit/validate trail that the planned one has no use for.
 * Six mappers of this project flatten a User the very same way, with the same
 * qualifiedByName = "fullName" helper: MissionMapper, PlanChargeMapper,
 * ResourceMapper and TeamAssignmentMapper on a field also called "user",
 * ProjectMapper twice (director and chefProjet), DemandeChangementMapper on
 * demandeur. The shared behaviour that really matters - how a full name is
 * built - lives in one single place, User.getFullName().
 */

/**
 * Entity-to-DTO rules for the actual workload (charges reelles).
 *
 * <p>Why an interface with no body rather than a class written by hand:
 * MapStruct generates the body while the project is compiled, so the compiler
 * checks every field. Add a field to ChargeReelleResponse and forget to say
 * where it comes from, and the BUILD warns immediately; a hand-written mapper
 * would compile fine and quietly send null to the browser. ADR-018 makes
 * MapStruct mandatory in this project for exactly that reason.
 */
// @Mapper tells MapStruct: "generate the implementation of this interface".
// componentModel = "spring" makes it put @Component on the generated class.
// Why: ChargeReelleService receives a ChargeReelleMapper through its
// constructor (Lombok @RequiredArgsConstructor), so Spring has to hold one
// instance of it. Without componentModel = "spring" the generated class is an
// ordinary class, not a Spring bean; nobody can inject it, and the application
// refuses to start with "NoSuchBeanDefinitionException: no qualifying bean of
// type ChargeReelleMapper".
@Mapper(componentModel = "spring")
public interface ChargeReelleMapper {

    /*
     * WHAT IT DOES: turns one ChargeReelle row into one ChargeReelleResponse
     * record, ready to be serialised to JSON. It builds a new object and never
     * modifies the entity it is given. Given null it gives back null: the
     * generated code starts with "if (chargeReelle == null) return null;", so a
     * missing row never becomes a NullPointerException here.
     *
     * WHO CALLS IT DIRECTLY: the write methods of ChargeReelleService (submit,
     * update, validate), each time on the object returned by repository.save(),
     * so that the browser gets the saved row back with its database-generated
     * id and its refreshed timestamps. The paged read passes it as a method
     * reference to Page.map(...), which applies it to every row of the page and
     * keeps the page metadata (total number of rows, page number) untouched.
     *
     * FIELDS COPIED AUTOMATICALLY, because the names match on both sides: id,
     * actualDays, submittedAt and validatedAt. Only the eight names that do not
     * match are declared below.
     *
     * ABOUT actualDays: it is a BigDecimal, not a double. The column is
     * NUMERIC(5,2) in V7 and the entity repeats precision = 5, scale = 2.
     * BigDecimal keeps exact decimals; a double cannot hold 0.1 exactly, so
     * adding twenty half-days of a double would slowly drift and the monthly
     * total shown on the workload screen would end in ...9999. The value is
     * copied as it is, with no rounding and no unit change - it is a number of
     * DAYS, not hours.
     *
     * ABOUT submittedAt AND validatedAt: both are LocalDateTime and both may be
     * null. validatedAt = null is the normal state of a charge waiting for its
     * manager, and the workload screen uses exactly that to build its "to
     * validate" list (it filters on !c.validatedAt). So null here is real
     * information, not a missing value, and the mapper must let it through
     * untouched.
     *
     * THE EIGHT @Mapping LINES BELOW must stay glued to the method signature:
     * an annotation always describes the element written right after it, so a
     * line of code inserted between them and the method would not compile.
     *
     *  (1) target = "projectId", source = "project.id"
     *      WHAT: reads chargeReelle.getProject().getId() and puts it in the
     *            flat field projectId of the response.
     *      WHY:  the response is deliberately flat. The front end needs the
     *            project number to build its URLs and to check that a row
     *            belongs to the project it is showing, not the whole project.
     *      WITHOUT IT: MapStruct finds no property called "projectId" on the
     *            entity, prints the compile warning "Unmapped target property:
     *            projectId", and the browser receives projectId: null.
     *
     *  (2) target = "projectCode", source = "project.code"
     *      WHAT: reads chargeReelle.getProject().getCode(), the short readable
     *            project code (for example "PRJ-2026-004"), and copies it.
     *      WHY:  it makes one row readable on its own, outside the screen that
     *            asked for it - in an export, in a log, or in a future "all my
     *            charges" list where rows of several projects are mixed.
     *      WITHOUT IT: a row taken alone would carry only a number, and the
     *            reader would have to call /api/projects/{id} once per row just
     *            to print one short string.
     *      NOTE: the workload screen does not print it today, because it
     *            already knows which project it asked for. It costs nothing
     *            extra, because the project is already loaded by the JOIN FETCH
     *            in the repository query.
     *
     *  (3) target = "userId", source = "user.id"
     *      WHAT: reads chargeReelle.getUser().getId() - the primary key of the
     *            person who declared the work - and puts it in the flat field
     *            userId.
     *      WHY:  the front end needs that id as a key, not as decoration. The
     *            workload screen groups the rows of the occupation table by
     *            person, and the "declare a charge" form sends this same id
     *            back as the userId of the new charge. The server then checks
     *            it again: ChargeReelleService.assertTeamMembership calls
     *            TeamAssignmentRepository
     *            .existsByProjectIdAndUserIdAndDeletedFalse (H-8), so a
     *            hand-made HTTP call cannot record work for somebody outside
     *            the team.
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
     *            charges would mean twenty extra HTTP calls (the "N+1 calls"
     *            problem) just to print twenty names.
     *      WHY qualifiedByName RATHER THAN NOTHING: it names the helper
     *            explicitly instead of letting MapStruct look for any method
     *            able to turn a User into a String. This file happens to
     *            contain a second User-based helper, userId(User), which
     *            returns a Long, so there is no clash today; but as soon as
     *            somebody adds another User-to-String method the build would
     *            stop with "Ambiguous mapping methods found". Naming the helper
     *            removes that guesswork for good.
     *      WHY ONLY THE NAME: see point 1 of the file header. Passing the User
     *            object straight into the response would publish the password
     *            hash, the email, the token version and the whole Role with its
     *            permissions.
     *      WITHOUT IT: the browser gets userFullName: null, and the workload
     *            screen crashes while sorting its "to validate" list, because
     *            that sort calls a.userFullName.localeCompare(b.userFullName).
     *
     *  (5) target = "year",
     *      expression = "java(chargeReelle.getPeriod().getYear())"
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
     *            screen - which builds its list of years out of this field -
     *            would show an empty selector and an empty occupation table.
     *      THE TRAP TO KNOW: the text inside expression = "java(...)" is copied
     *            into the generated file AS IT IS, so "chargeReelle" must stay
     *            the exact name of the method parameter below. Rename that
     *            parameter to "cr" and this string still compiles as a string,
     *            but the GENERATED class no longer compiles: "cannot find
     *            symbol: variable chargeReelle". That is why the parameter here
     *            keeps its long name.
     *      ONE HONEST WARNING: this expression calls getPeriod() with no null
     *            check, so a ChargeReelle whose period is null would throw a
     *            NullPointerException. It cannot happen through the normal
     *            path: the column is NOT NULL in V7, the entity repeats
     *            nullable = false, and the service always fills it. It is only
     *            a risk for an object built by hand in a test.
     *
     *  (6) target = "month",
     *      expression = "java(chargeReelle.getPeriod().getMonthValue())"
     *      WHAT: the same idea for the month. getMonthValue() gives the number
     *            1..12, so 2026-03-01 becomes 3.
     *      WHY getMonthValue() AND NOT getMonth(): getMonth() returns the enum
     *            java.time.Month, which Jackson would write as the English text
     *            "MARCH". The screen translates month names itself with
     *            Transloco, in the language of the user, so it wants the plain
     *            number. Sending "MARCH" would force English on a French
     *            screen and would break the sort, which compares numbers.
     *      WITHOUT IT: month: null, and every row of the occupation table would
     *            fall outside the twelve month columns, so the table would look
     *            empty even though the rows arrived.
     *
     *  (7) target = "validatedById", source = "validatedBy",
     *      qualifiedByName = "userId"
     *      WHAT: reads the whole User object "validatedBy" - the manager who
     *            approved the charge - and keeps only their id, through the
     *            helper labelled "userId" at the bottom of this file.
     *      WHY:  validatedBy is the audit trail of the validation step: it
     *            answers "who approved these days?". The id is what a future
     *            screen would use to link to that person; the name in (8) is
     *            what a human reads.
     *      WHY A HELPER AND NOT source = "validatedBy.id": both spellings end
     *            up null-safe - for a dotted path MapStruct generates a small
     *            private method that checks every step for null. The helper is
     *            used here so that the two validatedBy lines, (7) and (8), read
     *            the same way: same source object, same kind of helper, one
     *            single place to look if the rule ever changes.
     *      WHY THE NULL SAFETY IS NOT OPTIONAL HERE, unlike (3) and (4): the
     *            column validated_by has NO NOT NULL in V7, and the entity has
     *            no nullable = false on it. A charge that has been submitted
     *            but not yet approved has validatedBy = null, and that is the
     *            most common state on the screen. Reading .getId() on it
     *            without a check would answer 500 on the very list the manager
     *            opens to do the validation.
     *
     *  (8) target = "validatedByName", source = "validatedBy",
     *      qualifiedByName = "fullName"
     *      WHAT: the same object through the "fullName" helper, giving the
     *            printed name of the manager, or null when nobody validated
     *            yet.
     *      WHY:  same reason as (4) - the name travels with the id so that no
     *            extra HTTP call is needed to print it.
     *      NOTE: the Angular interface declares validatedById and
     *            validatedByName as optional ("?"), and no screen prints them
     *            today. They are kept in the answer because "who validated"
     *            belongs to the same audit trail as validatedAt, which the
     *            screen DOES use; splitting that trail across two endpoints
     *            would be worse than sending two fields nobody prints yet.
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
     * project, user and validatedBy are all LAZY, so each may still be a proxy
     * (a shell object that only knows its id). getId() on a proxy costs
     * nothing, Hibernate already has that value; but getCode() and
     * getFullName() read real columns, so each one forces a trip to the
     * database. That is why the read queries of ChargeReelleRepository write
     * "JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy":
     * the three sides arrive already loaded and this mapping costs zero extra
     * query. Remove those clauses and the code still works - the service
     * methods are @Transactional, so the Hibernate session is still open - but
     * a page of twenty charges silently fires up to sixty extra SELECT
     * statements. This is the classic "N+1 queries" problem.
     * And note WHICH join is used on the validator: LEFT JOIN FETCH, not JOIN
     * FETCH. A plain JOIN FETCH is an inner join, so it would return only the
     * rows that already have a validator and would silently drop every charge
     * still waiting for approval - exactly the rows the manager opens the
     * screen to find.
     */
    @Mapping(target = "projectId",      source = "project.id")
    @Mapping(target = "projectCode",    source = "project.code")
    @Mapping(target = "userId",         source = "user.id")
    @Mapping(target = "userFullName",   source = "user",        qualifiedByName = "fullName")
    @Mapping(target = "year",           expression = "java(chargeReelle.getPeriod().getYear())")
    @Mapping(target = "month",          expression = "java(chargeReelle.getPeriod().getMonthValue())")
    @Mapping(target = "validatedById",  source = "validatedBy", qualifiedByName = "userId")
    @Mapping(target = "validatedByName",source = "validatedBy", qualifiedByName = "fullName")
    ChargeReelleResponse toResponse(ChargeReelle chargeReelle);

    /*
     * WHAT IT DOES: maps a whole list in one call. MapStruct generates the loop
     * for us: create an ArrayList of the right size, walk the source list, call
     * toResponse on each element, add the result. It returns null for a null
     * input instead of throwing a NullPointerException.
     *
     * WHY DECLARE IT HERE instead of writing
     * list.stream().map(mapper::toResponse).toList() inside the service: the
     * generated loop reuses the very same eight @Mapping rules as the single
     * method above, so the list version can never drift away from it.
     *
     * WHO CALLS IT: the non-paged ChargeReelleService.findByProject(projectId),
     * both branches of it - the "whole team" branch and the "own rows only"
     * branch (BR-062..064). No controller method uses that overload today; the
     * REST endpoint uses the paged one, which maps row by row with
     * Page.map(chargeReelleMapper::toResponse). The plain-list overload is kept
     * because "give me every charge of this project" is a question the service
     * must be able to answer without paging - an export or a whole-project
     * computation needs the full set, not one page of twenty.
     *
     * A NOTE ON ORDER: this loop keeps the order it is given and sorts nothing.
     * The order comes from the SQL: findActiveByProjectId ends with
     * "ORDER BY cr.period, cr.user.lastName" and findActiveByProjectIdAndUserId
     * with "ORDER BY cr.period". Sorting here instead would hide the rule in a
     * translator and would make the work be done twice.
     */
    List<ChargeReelleResponse> toResponseList(List<ChargeReelle> list);

    /*
     * WHAT IT DOES: takes a User and gives back the name to print - first name
     * plus a space plus last name, since that is what User.getFullName()
     * builds. It gives back null when the User is null.
     *
     * WHY @Named("fullName"): it puts a label on this method so that a @Mapping
     * line can ask for it by name with qualifiedByName = "fullName" (rules (4)
     * and (8) above). Without the label MapStruct would have to guess which
     * method to use from the types alone.
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
     * chargeReelle.getValidatedBy() WITHOUT testing it first - it trusts the
     * method to cope with null. validatedBy IS null for every charge not yet
     * approved, so without this check the list a manager opens to validate
     * would answer 500 instead of showing the rows waiting for them.
     */
    @Named("fullName")
    default String fullName(User user) {
        return user != null ? user.getFullName() : null;
    }

    /*
     * WHAT IT DOES: takes a User and gives back only their primary key, or null
     * when the User is null. It exists for rule (7), which needs the id of the
     * validator without letting the rest of the User object out.
     *
     * WHY IT IS NEEDED AT ALL, when userId in rule (3) uses the dotted path
     * "user.id": the dotted form would work here too. This helper keeps the two
     * validatedBy rules symmetrical and, above all, makes the null case
     * explicit and visible in this file, right next to the comment explaining
     * that a charge waiting for approval has no validator.
     *
     * WHY @Named("userId") AND NOT JUST A PLAIN METHOD: without the label,
     * MapStruct would treat this as a candidate for ANY User-to-Long mapping it
     * has to perform, and could apply it where it was not wanted. The label
     * means "only use me when a @Mapping line asks for me by this name".
     *
     * A NOTE ON THE NAME: the Java method is called userId and the label is
     * "userId", while the field it fills is called validatedById. The label
     * describes what the helper EXTRACTS (the id of a user), not the field it
     * happens to fill, which is why the same helper could serve any other
     * User-shaped field later.
     */
    @Named("userId")
    default Long userId(User user) {
        return user != null ? user.getId() : null;
    }
}
