package com.pms.governance.repository;

import com.pms.governance.entity.PartiePrenante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: parties_prenantes (the stakeholders of a project -
 * the people and organisations involved, with their influence and their interest).
 * A repository is the only place in the application that talks to the database for
 * that table. It carries no business rule and no permission check; both live in the
 * service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> PartiePrenanteController (/api/projects/{projectId}/parties-prenantes)
 *           -> PartiePrenanteService    (permission, transaction)
 *           -> PartiePrenanteRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table parties_prenantes
 * The PartiePrenante rows returned here are given to PartiePrenanteMapper, which turns
 * them into the PartiePrenanteResponse record sent to the browser as JSON. The entity
 * itself never leaves the server.
 * Second caller: the demo loaders (shared/config/DemoDataSeeder and
 * EnterpriseDataSeeder) call the inherited save() to create the sample stakeholders.
 *
 * ONE POINT OF CARE: this table holds personal data
 * A stakeholder row carries a name, a job title, an e-mail address and a phone number.
 * That is the most personal data of the whole governance package. It is one more
 * reason why the two reads below are the ONLY way into this table: they always filter
 * on one project, so nobody can pull the full contact list of the company in one call,
 * and the permission plus the project scope are checked before they are reached.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * PartiePrenanteService would not compile, so the stakeholders tab of the governance
 * screen would disappear. The two methods below add exactly what the built-in
 * JpaRepository methods cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project in the SAME SQL query, which the mapper needs right after.
 *
 * HOW THIS FILE RELATES TO THE THREE OTHERS IN THE PACKAGE
 * RiskRepository, LivrableRepository and DemandeChangementRepository follow the same
 * pattern: same two method names (findActiveByProjectId / findActiveById), same
 * "deleted = false" filter, same JOIN FETCH of the project. Only the sort order
 * changes, because each list is read for a different reason. This one is the simplest
 * of the four: a stakeholder has no state machine, so its service has no approve, no
 * deliver and no validate method - only create, update and delete.
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
 * going through the service would skip both checks - and here that would also mean
 * handing out contact details.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation itself
// (a "proxy" object). It writes the SQL from the @Query text and hands that object to
// PartiePrenanteService, which asked for a PartiePrenanteRepository.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > tell the proxy what to work on:
//   PartiePrenante = the entity, so the table read is parties_prenantes,
//   Long           = the type of the @Id field, so findById takes a Long.
// Example of what these generics buy: findById(1L) gives back an
// Optional<PartiePrenante> already typed. Without them it would return Object and every
// caller would need a cast, with a ClassCastException waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() a lot but never deleteById(): deleting a stakeholder
// means setting deleted = true, never erasing the row.
public interface PartiePrenanteRepository extends JpaRepository<PartiePrenante, Long> {

    // WHAT: gives back every live stakeholder of ONE project, sorted by name, with the
    //       project row already loaded.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "PartiePrenante p"
    //       is the entity name, not the table name. Hibernate translates it into real SQL.)
    //
    //   (a) "p.project.id = :projectId" - the list is per project, because the screen is
    //       always opened inside one project. The filter is written on the property path
    //       p.project.id, which Hibernate resolves to the foreign key column project_id:
    //       it does NOT add another join to the projects table.
    //       WITHOUT IT: one GET would return the name, e-mail and phone number of every
    //       stakeholder of every project of the company.
    //
    //   (b) "p.deleted = false" - a stakeholder is never really erased. Deleting one
    //       only sets the boolean column deleted to true (the field comes from
    //       BaseEntity, the parent class of PartiePrenante). This is a "soft delete":
    //       the row stays in the database for the audit trail, so every read has to
    //       filter it out.
    //       WITHOUT IT: a contact who left the client company would stay in the list,
    //       and the team would keep writing to somebody who is no longer concerned.
    //
    //   (c) "JOIN FETCH p.project" - PartiePrenante.project is mapped FetchType.LAZY, so
    //       Hibernate would normally leave it as an empty placeholder and run one extra
    //       SELECT the first time it is read. PartiePrenanteMapper reads project.id and
    //       project.code for every row. JOIN FETCH brings the project row in the same
    //       statement.
    //       WITHOUT IT: 20 stakeholders means 1 query plus up to 20 more just for the
    //       project - the classic "N+1 select" problem. And because application.yml sets
    //       "open-in-view: false", a lazy link can only be loaded while the service
    //       transaction is still open; any mapping done after the service returned would
    //       fail with LazyInitializationException.
    //       This is a plain JOIN, not a LEFT JOIN, and that is correct here: project_id
    //       is NOT NULL on the parties_prenantes table (migration V11), so every
    //       stakeholder always has a project and no row can be dropped by the join.
    //
    //   (d) "ORDER BY p.nom" - alphabetical order by name (ORDER BY is ascending when
    //       nothing else is written). This list is used as a directory: the reader is
    //       looking for one person, so the name is the only key that lets the eye find a
    //       row quickly.
    //       WITHOUT IT: the database returns the rows in no guaranteed order, the list
    //       could reshuffle between two page loads, and finding "Ben Salah" among thirty
    //       contacts would mean reading them all.
    //       Note: the sort is done by PostgreSQL using the collation of the database, so
    //       accented names are placed by the database rules, not by Java.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name.
    // No @Param annotation is needed because Spring Boot compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the text: the value travels to
    // PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V11 creates the matching partial index
    // "CREATE INDEX idx_pp_project ON parties_prenantes(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the filter above
    // exactly, so PostgreSQL jumps straight to the rows of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: PartiePrenanteService.findByProject, which first loads the project
    // (to answer 404 on an unknown project) and then maps the result.
    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.project.id = :projectId AND p.deleted = false ORDER BY p.nom")
    List<PartiePrenante> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live stakeholder by its id, with its project already loaded. It
    //       gives back an Optional: a box that either holds the stakeholder or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of
    //       silently working with null. PartiePrenanteService.loadPP calls
    //       .orElseThrow(...) and turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null, the next pp.setNom(...) would throw
    //       a NullPointerException, and the user would get a 500 error instead of a
    //       proper "not found".
    // WHY not the inherited findById(id): findById ignores the soft-delete flag and
    //       loads nothing else, so it would let somebody edit a contact that the users
    //       had already deleted.
    //
    // WHY the JOIN FETCH of the project matters even more on this method: both callers
    // compare the project of the loaded stakeholder with the projectId taken from the
    // URL and answer 404 when they differ (see PartiePrenanteService.loadPP). So the
    // project is read on every single call - update and delete.
    // Concrete example of what that comparison stops: a user who works on project A
    // calls PUT /api/projects/A/parties-prenantes/58 while stakeholder 58 belongs to
    // project B. The permission check passes, the scope check on A passes, and only this
    // comparison catches it - otherwise the contact details of a person tied to project
    // B could be read and overwritten from project A. The JOIN FETCH is what makes that
    // check cost zero extra queries.
    // (It answers 404 and not 403 on purpose: a 403 would confirm that stakeholder 58
    // exists somewhere, which already tells an attacker something.)
    // SPEED on this second method: it does NOT use the partial index shown above. It
    // reads one row by its primary key, and "id BIGSERIAL PRIMARY KEY" (migration V11)
    // makes PostgreSQL build a unique index on that column on its own, so the database
    // jumps straight to that single row and only then checks deleted = false on it.
    // Why this is worth saying: a reader who has just seen idx_pp_project could think
    // every read needs an index written by hand. A read by id never does, and adding
    // a second index on parties_prenantes(id) would only waste disk space and slow every
    // insert down.
    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.id = :id AND p.deleted = false")
    Optional<PartiePrenante> findActiveById(Long id);
}
