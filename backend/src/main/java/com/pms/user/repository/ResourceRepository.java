package com.pms.user.repository;

import com.pms.user.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: resources. One row of that table is the MONEY side of a
 * person - the daily rate the company sells his day for, the TCC rate, and the dates
 * between which he is staffed. A repository is the only place in the application that
 * talks to the database for that table. It carries no business rule and no permission
 * check; both live in the services above it.
 *
 * WHAT "TCC" MEANS, BECAUSE EVERY METHOD BELOW IS ABOUT IT
 * TCC is the loading coefficient the company adds on top of a daily rate to get what a
 * man-day really costs (employer charges, structure costs...). resources.tcc_rate is
 * stored as a fraction, for example 0.4200 for 42 %, and the real cost of one day is
 * daily_rate x (1 + tcc_rate). These are the most sensitive figures of the referential,
 * which is why the read itself is guarded by a permission and by a data scope - see
 * below.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> ResourceController (/api/resources)
 *           -> ResourceService    (@PreAuthorize VIEW_RESOURCES / MANAGE_RESOURCES)
 *           -> ResourceRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table resources
 * The Resource rows never leave the server: ResourceMapper (MapStruct) turns them into
 * the ResourceResponse record, and that record is what becomes JSON.
 * There is a second caller, and it is the heavy one: KpiService calls
 * findActiveByUserIdIn to price a whole project at once - every planned day and every
 * declared day has to be multiplied by the rate of the person who worked it.
 *
 * WHY THERE IS A "resources" TABLE NEXT TO "users" (ADR-022)
 * A User is the ACCOUNT (who can sign in). A Resource is what a person COSTS. They are
 * deliberately not merged: an assistant needs an account but has no billable rate, and a
 * rate must keep existing for past cost calculations even after the account has been
 * switched off. The link is one-to-one and mandatory on this side (resources.user_id is
 * NOT NULL), which is why every query below can fetch the user with a plain inner join.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Three things: the TCC referential screen would disappear; KpiService could not cost a
 * single day, so every margin, every EVM indicator and the whole KPI screen would be
 * wrong or empty; and the project manager's data scope on rates (the last two methods of
 * this file) would have nowhere to live.
 *
 * THE PART OF THIS FILE THAT IS REALLY A SECURITY RULE (ADR-021)
 * ADR-021 says that ProjectScopeInterceptor enforces BOTH the permission AND the project
 * scope on the URLs /api/projects/{id}/**. Holding the permission is not enough. But
 * /api/resources/{id} carries a RESOURCE id, not a project id, so that interceptor cannot
 * see anything to check. The same idea therefore had to be written by hand here, as two
 * queries:
 *   * findVisibleToProjectManager        - the LIST a project manager may see;
 *   * isResourceVisibleToProjectManager  - the "may he open THIS one?" test.
 * ResourceService applies them only to a caller who does NOT hold MANAGE_RESOURCES:
 * an administrator or a director sees the whole referential, a project manager
 * (VIEW_RESOURCES alone) sees only the people of the projects he manages, plus himself.
 * BOTH of those queries take the manager's own id, and ResourceService always computes it
 * from the e-mail carried by the token (currentUserId()), never from something the
 * browser sent. If an id from a request parameter were passed instead, any project
 * manager could read the rates of any other manager's team.
 *
 * SECURITY - the protection that is NOT written in this file
 * @PreAuthorize("hasAuthority('VIEW_RESOURCES')") and
 * @PreAuthorize("hasAuthority('MANAGE_RESOURCES')") sit on the SERVICE methods of
 * ResourceService, never on the controller and never here. The code tests a permission
 * code, never a role name (ADR-001). So a method of this file is not safe on its own:
 * called from a new place without going through the service, it would skip the permission
 * AND the scope, and hand out everybody's rates.
 */
// Nothing implements this interface by hand, and that is normal: at start-up Spring Data
// JPA reads the interfaces that extend JpaRepository, turns each @Query text below into a
// real SQL statement, and builds the implementation itself (a "proxy" object) which it
// hands to the services that asked for a ResourceRepository. No @Repository annotation is
// needed, because extending JpaRepository is already the signal Spring looks for.
// A useful side effect: the @Query texts are parsed at start-up, so a typo in one of them
// stops the application immediately instead of failing the day somebody opens the TCC
// screen.
// The two types between < > are generics: Resource = the entity, so the table read is
// resources; Long = the type of the @Id field (inherited from BaseEntity), so findById
// takes a Long. Without them the methods would return Object and every caller would need
// a cast, with a ClassCastException waiting at run time.
// JpaRepository also brings in save(), findById(), findAll()... ResourceService uses
// save() for create and update, and its own findById(...).filter(r -> !r.isDeleted())
// helper for a single row. It never calls a delete method: deleting a resource means
// setting deleted = true, because KPI history must still be able to explain a past cost.
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    // WHAT: the whole live TCC referential - every resource that is not soft-deleted,
    //       with the person behind it already loaded, sorted by last name.
    // WHY "JOIN FETCH r.user u" - this is the important part of the line. Resource.user is
    //       mapped @OneToOne(fetch = FetchType.LAZY), so Hibernate would normally leave it
    //       as an empty placeholder and run one extra SELECT the first time it is read.
    //       ResourceMapper reads user.getId() AND user.getFullName() for EVERY row.
    //       WITHOUT the fetch: 60 resources means 1 query plus 60 more - the classic
    //       "N+1 selects" problem. And because application.yml sets "open-in-view:
    //       false", the database session is already closed when MapStruct builds the HTTP
    //       response, so the mapping would not just be slow, it would fail with
    //       LazyInitializationException.
    // WHY a plain JOIN and not a LEFT JOIN: resources.user_id is NOT NULL
    //       (V3__schema_user_resource.sql), so a resource always has a user and no row
    //       can be lost by the inner join. A LEFT join here would only be a slower way to
    //       get the same rows.
    // WHY "r.deleted = false": soft delete. A resource whose person left the company is
    //       kept so that past KPI figures stay explainable, but it must not appear in the
    //       referential screen any more.
    // WHY "ORDER BY u.lastName": the screen is a list of people, and people are looked up
    //       by last name.
    //       WITHOUT any ORDER BY: PostgreSQL returns the rows in no guaranteed order and
    //       the list could reshuffle between two page loads. Note the sort has only one
    //       key here, so two colleagues sharing a last name are not in a guaranteed order
    //       between themselves.
    //
    // CAREFUL - this is the WHOLE referential, every rate of the company. ResourceService
    // only calls it when the caller holds MANAGE_RESOURCES; a project manager is sent to
    // findVisibleToProjectManager instead.
    //
    // WHO CALLS IT: ResourceService.findAll(), in the full-access branch.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user u WHERE r.deleted = false ORDER BY u.lastName")
    List<Resource> findAllActive();

    // WHAT: the live resource attached to one given user account, as an Optional: a box
    //       that either holds the resource or is empty.
    // WHY it is asked by USER id and not by resource id: the caller is starting from a
    //       person ("I want to give Marwa a daily rate") and wants to know whether that
    //       person already has one. ResourceService.create writes
    //       "if (findActiveByUserId(...).isPresent()) throw ..." - the Optional is used
    //       as a yes-or-no answer.
    // WHY it can hold at most one row: V4__add_unique_resource_user.sql declares
    //       "CONSTRAINT uk_resources_user_id UNIQUE (user_id)".
    //       WITHOUT that constraint, a query returning a single Optional would throw
    //       IncorrectResultSizeDataAccessException the day one user had two rate rows -
    //       and, worse, nobody would know which of the two rates the KPI used.
    // A CONSEQUENCE WORTH KNOWING, BECAUSE IT IS NOT OBVIOUS: uk_resources_user_id is an
    //       ABSOLUTE unique constraint. It was never replaced by a partial index the way
    //       users.email was in V18, so it also counts the rows flagged deleted = true. A
    //       resource that has been soft-deleted therefore still occupies its user_id in
    //       the table, while this method - which filters on r.deleted = false - reports
    //       the user as free.
    // WHY "JOIN FETCH r.user" on a query that is only used as a presence test: the method
    //       is written like its neighbours so that a future caller who does need the
    //       person gets him without an extra query. It costs one join on a single row.
    //
    // WHO CALLS IT: ResourceService.create, as the "this user already has a rate" guard.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id = :userId AND r.deleted = false")
    Optional<Resource> findActiveByUserId(Long userId);

    // WHAT: the same read as above, but for MANY users at once: give it a bag of user
    //       ids, it gives back the live resources of those users.
    // WHY IT EXISTS - it is a performance rule, not a convenience. KpiService prices a
    //       project by walking every planned month and every declared month of every
    //       member. A project with 10 people over 12 months is around 240 rows. Calling
    //       findActiveByUserId once per row would be 240 round trips to PostgreSQL for a
    //       screen that must open instantly; with this method it is exactly ONE, whatever
    //       the size of the project. KpiService then turns the result into a Map keyed by
    //       user id, so each of the 240 rows finds its rate in memory.
    //
    // "r.user.id IN :userIds" - one parameter holding the whole collection. Hibernate
    //       expands it into "IN (?, ?, ?...)".
    //       WATCH OUT: an empty collection would produce "IN ()", which is not valid SQL.
    //       That is why KpiService guards the call with "userIds.isEmpty() ? Map.of() :
    //       ..." - a brand new project with no plan and no timesheet takes that branch
    //       every time.
    // WHY it is written "r.user.id" and not on the joined alias: the id of a to-one link
    //       is already in the resources table, in the foreign key column user_id, so
    //       Hibernate reads it without adding a join for the filter itself.
    // WHY the JOIN FETCH is still there: the caller builds a Map keyed by
    //       r.getUser().getId(), so the user object has to be loaded - same
    //       LazyInitializationException risk as on findAllActive.
    // WHY the argument is written "java.util.Collection<Long>" in full rather than
    //       imported: a Collection accepts a Set as well as a List, and KpiService passes
    //       a Set - the ids are collected with Collectors.toSet() precisely so that a
    //       person appearing in twelve monthly rows is asked for only once.
    //
    // WHO CALLS IT: KpiService, when it builds the userId -> Resource map used to cost a
    // project.
    @Query("SELECT r FROM Resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false")
    List<Resource> findActiveByUserIdIn(java.util.Collection<Long> userIds);

    // WHAT: the resources a project manager is allowed to see - the people who are active
    //       members of a team on a project HE manages, plus himself. This is the project
    //       manager's data scope (ADR-021) applied to the TCC referential.
    // WHY IT EXISTS: rates are sensitive. A project manager needs the cost of the people
    //       he staffs, and has no business reading the rate of a colleague on somebody
    //       else's project.
    //       WITHOUT it: either he holds VIEW_RESOURCES and sees every rate of the
    //       company, or he sees none and cannot follow the cost of his own project.
    //       Neither is acceptable.
    //
    // THE THREE LEVELS, READ FROM THE INSIDE OUT - this is the part to be able to explain:
    //   1. "SELECT p.id FROM Project p WHERE p.deleted = false AND p.chefProjet.id =
    //      :pmUserId" gives the ids of the projects this person MANAGES. p.chefProjet.id
    //      reads the foreign key column chef_projet_id directly, without joining users.
    //   2. "SELECT ta.user.id FROM TeamAssignment ta WHERE ta.deleted = false AND
    //      ta.project.id IN (...)" turns those project ids into the ids of the PEOPLE
    //      assigned to those projects.
    //   3. The outer query keeps the live resources whose user is one of those people -
    //      "u.id IN (...)" - OR the manager himself - "u.id = :pmUserId".
    // WHY the manager himself is included: he is not always a member of his own project
    //       team, and his own rate is part of the cost of the project he is accountable
    //       for. Without that branch, a manager who is not staffed on his own project
    //       would simply not see his own line.
    //
    // THE THREE "deleted = false" ARE THREE DIFFERENT QUESTIONS, and dropping any one of
    // them leaks data:
    //   * r.deleted  = false - do not show a rate row that was removed from the
    //                          referential;
    //   * ta.deleted = false - leaving a team is a SOFT delete, so without this a person
    //                          who left the project six months ago would still have his
    //                          rate exposed to that manager;
    //   * p.deleted  = false - a manager would keep the perimeter of a project that was
    //                          deleted.
    //
    // WHY "DISTINCT": as the query stands today no duplicate can appear - "IN
    //       (sub-select)" tests a membership and cannot multiply a row, and the fetched
    //       link user is to-one. DISTINCT is the safety net for the day somebody rewrites
    //       the sub-select as a join, where a person staffed on three of the manager's
    //       projects would otherwise come back three times and the screen would show him
    //       three times.
    // WHY sub-selects rather than joins: the question really is a membership test ("is
    //       this person on one of my projects?"), and writing it as joins would multiply
    //       the rows and force the DISTINCT to do real work.
    // WHY the JOIN FETCH and the ORDER BY are here: exactly the same reasons as on
    //       findAllActive above - the mapper reads the person's name, and the list must
    //       keep a stable order.
    //
    // WHO CALLS IT: ResourceService.findAll(), in the branch taken when the caller does
    // NOT hold MANAGE_RESOURCES. The :pmUserId it passes comes from the authenticated
    // e-mail, never from the request.
    @Query("""
            SELECT DISTINCT r FROM Resource r JOIN FETCH r.user u
            WHERE r.deleted = false AND (
                u.id = :pmUserId
                OR u.id IN (
                    SELECT ta.user.id FROM TeamAssignment ta
                    WHERE ta.deleted = false AND ta.project.id IN (
                        SELECT p.id FROM Project p
                        WHERE p.deleted = false AND p.chefProjet.id = :pmUserId
                    )
                )
            )
            ORDER BY u.lastName
            """)
    List<Resource> findVisibleToProjectManager(Long pmUserId);

    // WHAT: answers true when ONE given resource is inside the TCC perimeter of ONE given
    //       project manager, false otherwise. It is the single-row twin of the list
    //       above, and it uses exactly the same three levels - manage a project, be
    //       assigned to it, or be the manager himself.
    // WHY a second query instead of reusing the list: the caller only needs a yes or a
    //       no, on one identified row. Loading the manager's whole perimeter and
    //       searching it in Java would move every rate he is allowed to see just to check
    //       one of them - and would do it on every single GET /api/resources/{id} and
    //       GET /api/resources/{id}/tcc.
    // WHY the two queries MUST stay in step: they are the same rule written twice. If one
    //       of them ever gains a condition the other does not have, a resource could be
    //       missing from the list yet openable by its id, or listed yet refused when
    //       clicked. That is the price of writing the scope by hand instead of letting
    //       ProjectScopeInterceptor do it - and the reason is in the header: the URL
    //       carries no project id for the interceptor to work on.
    //
    // "SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END" - why it is written this
    //       way rather than just selecting the row: COUNT is an aggregate, so the query
    //       always produces EXACTLY ONE row, even when nothing matches (the count is then
    //       0 and the CASE gives false). That is what allows the return type to be a
    //       plain boolean.
    //       WITHOUT the aggregate, a query that matched nothing would give back no row at
    //       all, Spring Data would hand Hibernate a null, and unboxing null into a
    //       primitive boolean would throw a NullPointerException - on a security check,
    //       which is the worst possible place for one.
    // WHY the return type is boolean and not Optional: "outside the perimeter" is not a
    //       missing value, it is a real answer. ResourceService turns a false into an
    //       AccessDeniedException, which Spring Security reports as HTTP 403.
    //
    // NOTE ON THE ORDER OF THE CHECKS IN ResourceService: findById runs BEFORE this test,
    // so a resource id that does not exist gives 404 and one that exists but is out of
    // reach gives 403. A jury may ask whether that tells an attacker something: it tells
    // him the row exists, but not whose it is, nor any figure it carries.
    //
    // WHO CALLS IT: ResourceService.assertVisible(resourceId), reached from findById and
    // from findTccAnnuels, and only when the caller does not hold MANAGE_RESOURCES.
    @Query("""
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Resource r
            WHERE r.id = :resourceId AND r.deleted = false AND (
                r.user.id = :pmUserId
                OR r.user.id IN (
                    SELECT ta.user.id FROM TeamAssignment ta
                    WHERE ta.deleted = false AND ta.project.id IN (
                        SELECT p.id FROM Project p
                        WHERE p.deleted = false AND p.chefProjet.id = :pmUserId
                    )
                )
            )
            """)
    boolean isResourceVisibleToProjectManager(Long resourceId, Long pmUserId);
}
