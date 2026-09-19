package com.pms.governance.repository;

import com.pms.governance.entity.DemandeChangement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: demandes_changement (the change requests of a
 * project - somebody asks for a change, a manager approves it or rejects it).
 * A repository is the only place in the application that talks to the database for
 * that table. It carries no business rule and no permission check; both live in the
 * service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> DemandeChangementController (/api/projects/{projectId}/demandes-changement)
 *           -> DemandeChangementService    (permission, state rules, transaction)
 *           -> DemandeChangementRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table demandes_changement
 * The DemandeChangement rows that come out of here are given to
 * DemandeChangementMapper, which turns them into the DemandeChangementResponse
 * record sent to the browser as JSON. The entity itself never leaves the server.
 * Second caller: the demo loaders (shared/config/DemoDataSeeder and
 * EnterpriseDataSeeder) call the inherited save() to create the sample requests.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * DemandeChangementService would not compile, so the change-request tab of the
 * governance screen would disappear. The two methods declared below add exactly what
 * the built-in JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project and the author in the SAME SQL query, which the mapper
 *      needs immediately after.
 *
 * HOW THIS FILE RELATES TO THE THREE OTHERS IN THE PACKAGE
 * RiskRepository, LivrableRepository and PartiePrenanteRepository follow the same
 * pattern: same two method names (findActiveByProjectId / findActiveById), same
 * "deleted = false" filter, same JOIN FETCH of the project. One shape repeated four
 * times means that whoever understands one file understands the four. This file is
 * the only one that fetches a SECOND link (the author, demandeur), because
 * demandes_changement is the only one of the four tables with a second foreign key.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") or
 *    'MANAGE_GOVERNANCE' sits on the SERVICE methods, never on the controller and
 *    never here. The code tests a permission code, never a role name, so an
 *    administrator can change which role owns which permission while the application
 *    is running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the
 *    URL /api/projects/{id}/** and answers 403 when that project is outside the
 *    perimeter of the caller. Having the permission is NOT enough.
 * So a repository method is never safe on its own: calling it from a new place
 * without going through the service would skip both checks.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation
// itself (a "proxy" object). It writes the SQL from the @Query text and hands that
// object to DemandeChangementService, which asked for a DemandeChangementRepository.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > tell the proxy what to work on:
//   DemandeChangement = the entity, so the table read is demandes_changement,
//   Long              = the type of the @Id field, so findById takes a Long.
// Example of what these generics buy: findById(1L) gives back an
// Optional<DemandeChangement> already typed. Without them it would return Object and
// every caller would need a cast, with a ClassCastException waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() a lot but never deleteById(): deleting here means
// setting deleted = true, never erasing the row.
public interface DemandeChangementRepository extends JpaRepository<DemandeChangement, Long> {

    // WHAT: gives back every live change request of ONE project, newest request date
    //       first, with the project row and the author row already loaded.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "DemandeChangement d"
    //       is the entity name, not the table name. Hibernate translates it into real SQL.)
    //
    //   (a) "d.project.id = :projectId" - the list is per project, because the screen
    //       is always opened inside one project. The filter is written on the property
    //       path d.project.id, which Hibernate resolves to the foreign key column
    //       project_id: it does NOT add another join to the projects table.
    //       WITHOUT IT: the tab would show the change requests of every project of the
    //       company on the same page.
    //
    //   (b) "d.deleted = false" - a change request is never really erased. Deleting it
    //       only sets the boolean column deleted to true (the field comes from
    //       BaseEntity, the parent class of DemandeChangement). This is a "soft
    //       delete": the row stays in the database for the audit trail, so every read
    //       has to filter it out.
    //       WITHOUT IT: a request deleted this morning would be back in the list this
    //       afternoon, and the manager would be asked to approve it a second time.
    //
    //   (c) "JOIN FETCH d.project JOIN FETCH d.demandeur" - both links are mapped
    //       FetchType.LAZY on the entity, so Hibernate would normally leave them as
    //       empty placeholders and run one extra SELECT the first time each one is
    //       read. DemandeChangementMapper reads project.id, project.code, demandeur.id
    //       and demandeur.getFullName() for EVERY row. JOIN FETCH brings those two rows
    //       in the same statement.
    //       WITHOUT IT: 50 requests means 1 query plus up to 100 more (one project and
    //       one user per row) - the classic "N+1 select" problem, and a page that takes
    //       seconds instead of milliseconds. Worse, application.yml sets
    //       "open-in-view: false", so a lazy link can only be loaded while the service
    //       transaction is still open; any mapping done after the service returned
    //       would fail with LazyInitializationException.
    //       These are plain JOINs, not LEFT JOINs, and that is correct here: project_id
    //       and demandeur_id are both NOT NULL in migration V11, so no row can be
    //       silently dropped by the join.
    //       Note that the author is NOT filtered on deleted: if the user account was
    //       deactivated later, the request still shows who asked for it. That is
    //       exactly what a governance log is for.
    //
    //   (d) "ORDER BY d.dateDemande DESC" - most recent request first, because that is
    //       what a manager looks for when the tab opens: what is waiting now.
    //       WITHOUT IT: the database returns the rows in no guaranteed order, and the
    //       list could reshuffle between two page loads.
    //       Known limit: dateDemande is a DATE, so two requests made on the same day
    //       have no tie-breaker and their order between them is not guaranteed.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name.
    // No @Param annotation is needed because Spring Boot compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the text: the value travels to
    // PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V11 creates the matching partial index
    // "CREATE INDEX idx_dc_project ON demandes_changement(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the filter above
    // exactly, so PostgreSQL jumps straight to the rows of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: DemandeChangementService.findByProject, which first loads the
    // project (to answer 404 on an unknown project) and then maps the result.
    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.project.id = :projectId AND d.deleted = false ORDER BY d.dateDemande DESC")
    List<DemandeChangement> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live change request by its id, with its project and its author
    //       already loaded. It gives back an Optional: a box that either holds the
    //       request or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of
    //       silently working with null. DemandeChangementService.loadDC calls
    //       .orElseThrow(...) and turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null, the next dc.getStatut() would
    //       throw a NullPointerException, and the user would get a 500 error instead of
    //       a proper "not found".
    // WHY not the inherited findById(id): findById ignores the soft-delete flag and
    //       loads nothing else, so it would happily reopen a request that the users had
    //       already deleted, and the mapper would then need two more queries.
    //
    // WHY the JOIN FETCH of the project matters even more on this method: every caller
    // compares the project of the loaded request with the projectId taken from the URL
    // and answers 404 when they differ (see DemandeChangementService.loadDC). So the
    // project is read on every single call - update, approve, reject, delete.
    // Concrete example of what that comparison stops: a user who works on project A
    // calls PATCH /api/projects/A/demandes-changement/77/approuver while request 77
    // belongs to project B. The permission check passes, the scope check on A passes,
    // and only this comparison catches it. The JOIN FETCH is what makes that check cost
    // zero extra queries.
    // (It answers 404 and not 403 on purpose: a 403 would confirm that request 77
    // exists somewhere, which already tells an attacker something.)
    // SPEED on this second method: it does NOT use the partial index shown above. It
    // reads one row by its primary key, and "id BIGSERIAL PRIMARY KEY" (migration V11)
    // makes PostgreSQL build a unique index on that column on its own, so the database
    // jumps straight to that single row and only then checks deleted = false on it.
    // The two JOIN FETCH below are just as cheap: they follow project_id to projects(id)
    // and demandeur_id to users(id), and both of those are primary keys, so PostgreSQL
    // already keeps an index on them.
    // Why this is worth saying: a reader who has just seen idx_dc_project could think
    // every read needs an index written by hand. A read by id never does, and adding
    // a second index on demandes_changement(id) would only waste disk space and slow every
    // insert down.
    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.id = :id AND d.deleted = false")
    Optional<DemandeChangement> findActiveById(Long id);
}
