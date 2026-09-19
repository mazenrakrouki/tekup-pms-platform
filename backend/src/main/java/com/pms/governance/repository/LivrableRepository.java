package com.pms.governance.repository;

import com.pms.governance.entity.Livrable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: livrables (the deliverables of a project - what the
 * company promised to hand over to the client). A repository is the only place in the
 * application that talks to the database for that table. It carries no business rule
 * and no permission check; both live in the service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> LivrableController (/api/projects/{projectId}/livrables)
 *           -> LivrableService    (permission, state rules, transaction)
 *           -> LivrableRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table livrables
 * The Livrable rows returned here are given to LivrableMapper, which turns them into
 * the LivrableResponse record sent to the browser as JSON. The entity itself never
 * leaves the server.
 *
 * THIS FILE HAS A SECOND, IMPORTANT CALLER: KpiService
 * KpiService calls findActiveByProjectId(projectId) to compute the delivery percentage
 * of a project: number of deliverables in state LIVRE or VALIDE, divided by the total
 * number of deliverables. So this file does not only feed a list on a screen, it feeds
 * one of the project indicators. Two consequences to keep in mind:
 *   - the "deleted = false" filter below decides what counts in that percentage. A
 *     deliverable deleted by mistake, if it were still returned, would lower the score
 *     of the project for everybody;
 *   - the ORDER BY costs nothing to KpiService (it counts, it does not read the order),
 *     it is there for the screen.
 * Third caller: the demo loaders (shared/config/DemoDataSeeder and
 * EnterpriseDataSeeder) call the inherited save() to create the sample deliverables.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * LivrableService and KpiService would not compile: the deliverables tab of the
 * governance screen would disappear AND the delivery indicator would be lost. The two
 * methods below add exactly what the built-in JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project in the SAME SQL query, which the mapper needs right after.
 *
 * HOW THIS FILE RELATES TO THE THREE OTHERS IN THE PACKAGE
 * RiskRepository, PartiePrenanteRepository and DemandeChangementRepository follow the
 * same pattern: same two method names (findActiveByProjectId / findActiveById), same
 * "deleted = false" filter, same JOIN FETCH of the project. Only the sort order
 * changes, because each list is read for a different reason: deliverables by due date
 * (what is coming next), stakeholders by name (a directory), change requests and risks
 * by date, newest first (what is new).
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") or
 *    'MANAGE_GOVERNANCE' sits on the SERVICE methods, never on the controller and
 *    never here. The code tests a permission code, never a role name, so an
 *    administrator can change which role owns which permission while the application
 *    is running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the perimeter
 *    of the caller. Having the permission is NOT enough.
 * So a repository method is never safe on its own: calling it from a new place without
 * going through the service would skip both checks.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation itself
// (a "proxy" object). It writes the SQL from the @Query text and hands that object to
// LivrableService and KpiService, which both asked for a LivrableRepository.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > tell the proxy what to work on:
//   Livrable = the entity, so the table read is livrables,
//   Long     = the type of the @Id field, so findById takes a Long.
// Example of what these generics buy: findById(1L) gives back an Optional<Livrable>
// already typed. Without them it would return Object and every caller would need a
// cast, with a ClassCastException waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() a lot but never deleteById(): deleting a deliverable
// means setting deleted = true, never erasing the row.
public interface LivrableRepository extends JpaRepository<Livrable, Long> {

    // WHAT: gives back every live deliverable of ONE project, the closest due date
    //       first, with the project row already loaded.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "Livrable l" is the
    //       entity name, not the table name. Hibernate translates it into real SQL.)
    //
    //   (a) "l.project.id = :projectId" - the list is per project, because the screen is
    //       always opened inside one project. The filter is written on the property path
    //       l.project.id, which Hibernate resolves to the foreign key column project_id:
    //       it does NOT add another join to the projects table.
    //       WITHOUT IT: the tab would show the deliverables of every project of the
    //       company, and KpiService would compute the delivery percentage of the whole
    //       database instead of the percentage of one project.
    //
    //   (b) "l.deleted = false" - a deliverable is never really erased. Deleting it only
    //       sets the boolean column deleted to true (the field comes from BaseEntity,
    //       the parent class of Livrable). This is a "soft delete": the row stays in the
    //       database for the audit trail, so every read has to filter it out.
    //       WITHOUT IT: a deliverable cancelled by the client would still be counted in
    //       the delivery percentage, and a project could never reach 100 %.
    //
    //   (c) "JOIN FETCH l.project" - Livrable.project is mapped FetchType.LAZY, so
    //       Hibernate would normally leave it as an empty placeholder and run one extra
    //       SELECT the first time it is read. LivrableMapper reads project.id and
    //       project.code for every row. JOIN FETCH brings the project row in the same
    //       statement.
    //       WITHOUT IT: 30 deliverables means 1 query plus up to 30 more just for the
    //       project - the classic "N+1 select" problem. And because application.yml sets
    //       "open-in-view: false", a lazy link can only be loaded while the service
    //       transaction is still open; any mapping done after the service returned would
    //       fail with LazyInitializationException.
    //       This is a plain JOIN, not a LEFT JOIN, and that is correct here: project_id
    //       is NOT NULL on the livrables table (migration V11), so every deliverable
    //       always has a project and no row can be dropped by the join.
    //
    //   (d) "ORDER BY l.dateEcheance NULLS LAST, l.titre" - two sort keys, and the first
    //       one needs care because dateEcheance is allowed to be null (a deliverable
    //       with no agreed date yet).
    //       "NULLS LAST" pushes those undated rows to the bottom of the list. Why it is
    //       written explicitly: in PostgreSQL an ascending sort puts NULL LAST by
    //       default, but the rule is not the same in every database, and the code must
    //       not depend on it.
    //       WITHOUT IT (on a database that sorts NULL first): the deliverables with no
    //       date would sit on top of the list, above the one due tomorrow, and the team
    //       would read the wrong priority every morning.
    //       ", l.titre" is the tie-breaker: two deliverables due the same day are then
    //       ordered by title, so the list never reshuffles between two page loads.
    //       WITHOUT the tie-breaker: the same list could come back in a different order
    //       each time, which looks like a bug to the user.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name.
    // No @Param annotation is needed because Spring Boot compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the text: the value travels to
    // PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V11 creates the matching partial index
    // "CREATE INDEX idx_livrable_project ON livrables(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the filter above
    // exactly, so PostgreSQL jumps straight to the rows of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: LivrableService.findByProject (screen) and KpiService (delivery
    // percentage of the project).
    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.dateEcheance NULLS LAST, l.titre")
    List<Livrable> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live deliverable by its id, with its project already loaded. It
    //       gives back an Optional: a box that either holds the deliverable or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of
    //       silently working with null. LivrableService.loadLivrable calls
    //       .orElseThrow(...) and turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null, the next livrable.setStatut(...)
    //       would throw a NullPointerException, and the user would get a 500 error
    //       instead of a proper "not found".
    // WHY not the inherited findById(id): findById ignores the soft-delete flag and
    //       loads nothing else, so it would let somebody edit or validate a deliverable
    //       that the users had already deleted.
    //
    // WHY the JOIN FETCH of the project matters even more on this method: every caller
    // compares the project of the loaded deliverable with the projectId taken from the
    // URL and answers 404 when they differ (see LivrableService.loadLivrable). So the
    // project is read on every single call - update, start, deliver, validate, delete.
    // Concrete example of what that comparison stops: a user who works on project A
    // calls PATCH /api/projects/A/livrables/42/valider while deliverable 42 belongs to
    // project B. The permission check passes, the scope check on A passes, and only this
    // comparison catches it - otherwise a deliverable of B would be marked as accepted
    // by somebody who has no business on B, and the delivery indicator of B would move.
    // The JOIN FETCH is what makes that check cost zero extra queries.
    // (It answers 404 and not 403 on purpose: a 403 would confirm that deliverable 42
    // exists somewhere, which already tells an attacker something.)
    // SPEED on this second method: it does NOT use the partial index shown above. It
    // reads one row by its primary key, and "id BIGSERIAL PRIMARY KEY" (migration V11)
    // makes PostgreSQL build a unique index on that column on its own, so the database
    // jumps straight to that single row and only then checks deleted = false on it.
    // Why this is worth saying: a reader who has just seen idx_livrable_project could think
    // every read needs an index written by hand. A read by id never does, and adding
    // a second index on livrables(id) would only waste disk space and slow every
    // insert down.
    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.id = :id AND l.deleted = false")
    Optional<Livrable> findActiveById(Long id);
}
