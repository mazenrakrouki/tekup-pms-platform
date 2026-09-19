package com.pms.billing.repository;

/*
 * ============================================================================
 *  AvenantRepository - the database door for contract amendments ("avenants").
 * ============================================================================
 *
 *  WHAT THIS FILE IS
 *  An "avenant" (contract amendment) is a signed document in which the client
 *  agrees to add money to, or take money out of, the sold budget of a project.
 *  This interface is the only place in the whole application that reads the
 *  "avenants" table.
 *
 *  WHERE IT SITS IN THE FLOW (who calls it, what it calls next)
 *    Angular billing page
 *      -> BillingController, mapped on /api/projects/{projectId}/avenants
 *      -> ProjectScopeInterceptor. ADR-021: on every URL that looks like
 *         /api/projects/{id}/ followed by anything, the interceptor checks BOTH that
 *         the caller has the permission AND that this caller is allowed to touch
 *         THIS project.
 *         Having the permission alone is not enough.
 *      -> AvenantService. The permission test lives there, on the service method
 *         (hasAuthority('VIEW_BILLING') to read, hasAuthority('MANAGE_BILLING')
 *         to write), never on the controller.
 *         Authorization in this project is dynamic and permission-based: the code
 *         never asks "is this user an ADMIN?", it asks "does this user hold this
 *         permission?", and an administrator can move permissions between roles
 *         at runtime without a redeploy.
 *      -> AvenantRepository (this file)
 *      -> PostgreSQL table "avenants", created by the Flyway migration
 *         V9__schema_billing.sql (column workload_days added later by V15).
 *  What comes back is an Avenant entity. AvenantService passes it to AvenantMapper,
 *  which builds the AvenantResponse record that Angular finally receives.
 *
 *  WHY IT EXISTS (what breaks if you delete it)
 *  Spring Data JPA writes the implementation of this interface for us when the
 *  application starts, so nobody in the project has to write SQL for amendments by
 *  hand. Without this file AvenantService does not compile: a project budget could
 *  no longer be raised or lowered by an amendment, and the "Avenants" tab of a
 *  project would stay empty forever.
 *
 *  ONE IDEA RUNS THROUGH THE WHOLE FILE: SOFT DELETE
 *  Rows are never really removed. AvenantService.delete() only sets the flag
 *  deleted = true (the "deleted" column is inherited from BaseEntity). Money history
 *  must stay auditable, so nothing about money is ever erased. The consequence for
 *  this file: every query written here has to filter "deleted = false" on its own.
 *  The methods we inherit for free (findAll, findById, count) know nothing about that
 *  flag, so they would cheerfully return an amendment the user believes he cancelled -
 *  and its amount would be counted a second time in the revised budget.
 */

import com.pms.billing.entity.Avenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Read and write access to the Avenant entity. Returns Avenant objects, either one
 * at a time (wrapped in Optional) or as a List.
 *
 * WHY AN INTERFACE THAT CONTAINS NO CODE AT ALL
 * We extend JpaRepository&lt;Avenant, Long&gt;. Those two types between the angle
 * brackets are "generics": they tell Spring which entity this repository manages
 * (Avenant) and what type its primary key has (Long, which is BaseEntity.id). At
 * start-up Spring Data JPA generates a hidden class that implements every method
 * declared here, and we inherit save(), saveAll(), findById(), count() and the rest
 * without writing them.
 * The obvious alternative would be a hand-written DAO class holding an EntityManager
 * and SQL strings. We do not do that for two reasons. First, it would mean around
 * eighty lines of repetitive code per entity. Second, a mistake in a field name inside
 * a query below is detected while the application boots, whereas a mistake inside a raw
 * SQL string would only explode months later in front of a user.
 */
public interface AvenantRepository extends JpaRepository<Avenant, Long> {

    // The annotation below lets us write the request ourselves in JPQL (a query language
    // that talks about Java classes and fields, not about tables and columns - "Avenant a"
    // is the entity, "a.dateAvenant" is the Java field, and Hibernate translates them into
    // the SQL table "avenants" and its column "date_avenant").
    // Why not let Spring derive the query from the method name, the way
    // findByProjectIdAndDeletedFalseOrderByDateAvenant would? Because a derived name
    // cannot express JOIN FETCH, and JOIN FETCH is the whole point of this query.
    //
    // JOIN FETCH a.project: load the parent Project inside this same single request.
    // Why: Avenant.project is declared with fetch = FetchType.LAZY, so by default
    // Hibernate does not load the project, it puts an empty stand-in object (a "proxy")
    // in its place and goes back to the database only when someone reads a real field of
    // it. AvenantMapper does exactly that - it reads project.code to fill
    // AvenantResponse.projectCode.
    // Example of what goes wrong without it: a project carrying 20 amendments would run
    // 1 query for the amendments plus 20 more, one per row, just to fetch the same
    // project code twenty times. That is the classic "N+1 queries" problem: the page
    // still shows the right numbers, so nobody notices until the list grows and the
    // screen takes seconds to open. And if the mapping ever moved outside the
    // transactional service method, the proxy would have no open session left and would
    // throw LazyInitializationException instead.
    //
    // WHERE a.project.id = :projectId: keep only the amendments of the project asked for.
    // Note that reading a.project.id does NOT force a second join - the value project_id
    // already sits in the avenants row itself, so Hibernate reads it locally.
    // Example without it: opening project A would list the amendments of every project in
    // the company, and its revised budget would look completely wrong.
    //
    // AND a.deleted = false: hide the soft-deleted rows (see the header block).
    // Example without it: an amendment of +50 000 TND that a project manager cancelled
    // last month would come back in the list and be counted a second time.
    //
    // :projectId is a named parameter. Spring binds it to the method argument called
    // projectId, and Hibernate sends the value to PostgreSQL separately instead of gluing
    // text into the query - which is also what makes SQL injection impossible here.
    // It works without a @Param("projectId") annotation only because Spring Boot compiles
    // with the -parameters flag, so the real argument names survive into the .class file.
    // Example without that flag: the application refuses to start with
    // "Could not find parameter named projectId".
    //
    // ORDER BY a.dateAvenant: oldest amendment first.
    // Why: a database returns rows in no guaranteed order. Example without it, two
    // identical page reloads could show amendment number 2 above amendment number 1, and
    // a reader trying to follow how the budget grew over time would be lost.
    /**
     * Every amendment that is still alive for one project, oldest signature date first,
     * with the parent Project already loaded.
     *
     * Called by AvenantService.findByProject(), which feeds the "Avenants" list of the
     * billing screen.
     */
    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.project.id = :projectId AND a.deleted = false ORDER BY a.dateAvenant")
    List<Avenant> findActiveByProjectId(Long projectId);

    // Same ingredients as above, for a single row: JOIN FETCH so the Project comes along,
    // and a.deleted = false so a cancelled amendment can never be cancelled twice.
    //
    // Optional<Avenant> instead of a plain Avenant: Optional is a small box that either
    // holds a value or is empty, and the compiler forces the caller to open it.
    // Why: "not found" is a normal answer here, not a bug. AvenantService.delete() writes
    // .orElseThrow(() -> new NotFoundException(...)) and the user gets a clean 404.
    // Example without Optional: the method would return null, one caller would forget to
    // test it, and the user would get a raw NullPointerException 500 page instead of the
    // readable message "Avenant introuvable".
    //
    // Careful, and this is on purpose: this query filters on the amendment id only, not on
    // the project. That is why AvenantService.delete() checks right afterwards that
    // avenant.getProject().getId() really equals the projectId taken from the URL, and
    // throws NotFoundException if it does not. Together with ADR-021 that makes two
    // independent barriers. Example without that second check: a user allowed on project A
    // could call /api/projects/A/avenants/{id of an amendment of project B} and remove
    // money from a project he is not even allowed to see.
    /**
     * One amendment by its id, only if it has not been soft-deleted.
     *
     * Called by AvenantService.delete() before it flips the deleted flag and subtracts the
     * amount back out of the revised budget of the project.
     */
    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.id = :id AND a.deleted = false")
    Optional<Avenant> findActiveById(Long id);
}
