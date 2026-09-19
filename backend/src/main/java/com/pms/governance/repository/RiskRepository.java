package com.pms.governance.repository;

import com.pms.governance.entity.Risk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: risks (the risk register of a project - what could go
 * wrong, how likely it is, how bad it would be, and what is planned against it).
 * A repository is the only place in the application that talks to the database for that
 * table. It carries no business rule and no permission check; both live in the service
 * above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser -> RiskController (/api/projects/{projectId}/risks)
 *           -> RiskService    (permission, transaction)
 *           -> RiskRepository (THIS FILE)
 *           -> Spring Data JPA / Hibernate -> PostgreSQL table risks
 * The Risk rows returned here are given to RiskMapper, which turns them into the
 * RiskResponse record sent to the browser as JSON. The entity itself never leaves the
 * server.
 * Second caller: the demo loaders (shared/config/DemoDataSeeder and
 * EnterpriseDataSeeder) call the inherited save() to create the sample risks.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * RiskService would not compile, so the risks tab of the governance screen would
 * disappear. The two methods below add exactly what the built-in JpaRepository methods
 * cannot do:
 *   1. they hide the rows flagged deleted = true (soft delete),
 *   2. they load the project in the SAME SQL query, which the mapper needs right after.
 *
 * HOW THIS FILE RELATES TO THE THREE OTHERS IN THE PACKAGE
 * LivrableRepository, PartiePrenanteRepository and DemandeChangementRepository follow
 * the same pattern: same two method names (findActiveByProjectId / findActiveById),
 * same "deleted = false" filter, same JOIN FETCH of the project. Only the sort order
 * changes, because each list is read for a different reason. A risk, like a
 * stakeholder and unlike a deliverable or a change request, has no state machine: its
 * statut (OUVERT / MITIGE / FERME) is sent straight in the request body and saved by
 * RiskService.update, so there is no approve or validate method to protect.
 *
 * SECURITY - the two protections that are NOT written in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") or
 *    'MANAGE_GOVERNANCE' sits on the SERVICE methods, never on the controller and
 *    never here. The code tests a permission code, never a role name, so an
 *    administrator can change which role owns which permission while the application is
 *    running.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when that project is outside the perimeter
 *    of the caller. Having the permission is NOT enough.
 * So a repository method is never safe on its own: calling it from a new place without
 * going through the service would skip both checks.
 */
// No class implements this interface anywhere in the project, and that is normal:
// Spring Data JPA reads the interface at startup and builds the implementation itself
// (a "proxy" object). It writes the SQL from the @Query text and hands that object to
// RiskService, which asked for a RiskRepository.
// Why it is done this way: the alternative is to write by hand, for every method, an
// EntityManager, a createQuery call, the parameter binding and the result list.
// The two types between < > tell the proxy what to work on:
//   Risk = the entity, so the table read is risks,
//   Long = the type of the @Id field, so findById takes a Long.
// Example of what these generics buy: findById(1L) gives back an Optional<Risk> already
// typed. Without them it would return Object and every caller would need a cast, with a
// ClassCastException waiting at runtime.
// JpaRepository also brings in, for free, save(), findAll(), count(), deleteById()...
// The application uses save() a lot but never deleteById(): deleting a risk means
// setting deleted = true, never erasing the row.
public interface RiskRepository extends JpaRepository<Risk, Long> {

    // WHAT: gives back every live risk of ONE project, most recently added first, with
    //       the project row already loaded.
    // WHY : four separate needs are packed into this single line of JPQL.
    //       (JPQL looks like SQL but is written on the Java classes: "Risk r" is the
    //       entity name, not the table name. Hibernate translates it into real SQL.)
    //
    //   (a) "r.project.id = :projectId" - the list is per project, because the screen is
    //       always opened inside one project. The filter is written on the property path
    //       r.project.id, which Hibernate resolves to the foreign key column project_id:
    //       it does NOT add another join to the projects table.
    //       WITHOUT IT: the tab would show the risks of every project of the company,
    //       including the ones the reader must not see.
    //
    //   (b) "r.deleted = false" - a risk is never really erased. Deleting one only sets
    //       the boolean column deleted to true (the field comes from BaseEntity, the
    //       parent class of Risk). This is a "soft delete": the row stays in the
    //       database for the audit trail, so every read has to filter it out.
    //       WITHOUT IT: a risk the project manager removed would come back in the
    //       register, and a risk that was deleted for a good reason would still worry
    //       the team.
    //
    //   (c) "JOIN FETCH r.project" - Risk.project is mapped FetchType.LAZY, so Hibernate
    //       would normally leave it as an empty placeholder and run one extra SELECT the
    //       first time it is read. RiskMapper reads project.id and project.code for
    //       every row. JOIN FETCH brings the project row in the same statement.
    //       WITHOUT IT: 25 risks means 1 query plus up to 25 more just for the project -
    //       the classic "N+1 select" problem. And because application.yml sets
    //       "open-in-view: false", a lazy link can only be loaded while the service
    //       transaction is still open; any mapping done after the service returned would
    //       fail with LazyInitializationException.
    //       This is a plain JOIN, not a LEFT JOIN, and that is correct here: project_id
    //       is NOT NULL on the risks table (migration V11), so every risk always has a
    //       project and no row can be dropped by the join.
    //
    //   (d) "ORDER BY r.createdAt DESC" - sorted by creation date, the newest first.
    //       createdAt comes from BaseEntity and is filled by Spring Data auditing when
    //       the row is first saved, so this really means "the risk added most recently
    //       is on top", which is what a team wants to see when a new risk is raised.
    //       (This is the original comment of the file, translated and kept:)
    //       Because the enum fields are stored as TEXT (@Enumerated(EnumType.STRING)),
    //       an ORDER BY on statut or on impact would sort in alphabetical order, NOT by
    //       seriousness. Example: the three impact values would come back as ELEVE,
    //       FAIBLE, MOYEN - the highest impact first by pure luck, and for statut the
    //       order would be FERME, MITIGE, OUVERT, which puts the closed risks on top and
    //       the open ones at the bottom: exactly the wrong way round.
    //       So sorting by severity cannot be done with a simple ORDER BY on the column.
    //       It would need a CASE expression written in the application (turning each
    //       value into a number first), and that choice was not made here.
    //       Careful if you ever change this: the fix is NOT to switch the enums to
    //       EnumType.ORDINAL to get a numeric order. That would store 0, 1, 2 in the
    //       column, and inserting one new value in the middle of the enum would silently
    //       change the meaning of every row already saved.
    //       WITHOUT any ORDER BY at all: the database returns the rows in no guaranteed
    //       order and the register could reshuffle between two page loads.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name.
    // No @Param annotation is needed because Spring Boot compiles with the -parameters
    // flag, which keeps the real argument names inside the .class file.
    // Why a parameter instead of gluing the value into the text: the value travels to
    // PostgreSQL apart from the query, so it can never be read as SQL. That is what
    // blocks SQL injection.
    //
    // SPEED: migration V11 creates the matching partial index
    // "CREATE INDEX idx_risk_project ON risks(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the filter above
    // exactly, so PostgreSQL jumps straight to the rows of this project instead of
    // reading the whole table.
    //
    // WHO CALLS IT: RiskService.findByProject, which first loads the project (to answer
    // 404 on an unknown project) and then maps the result.
    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.project.id = :projectId AND r.deleted = false ORDER BY r.createdAt DESC")
    List<Risk> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live risk by its id, with its project already loaded. It gives
    //       back an Optional: a box that either holds the risk or is empty.
    // WHY Optional: it forces the caller to deal with the "not found" case instead of
    //       silently working with null. RiskService.loadRisk calls .orElseThrow(...) and
    //       turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null, the next risk.setDescription(...)
    //       would throw a NullPointerException, and the user would get a 500 error
    //       instead of a proper "not found".
    // WHY not the inherited findById(id): findById ignores the soft-delete flag and
    //       loads nothing else, so it would let somebody edit a risk that the users had
    //       already deleted.
    //
    // WHY the JOIN FETCH of the project matters even more on this method: both callers
    // compare the project of the loaded risk with the projectId taken from the URL and
    // answer 404 when they differ (see RiskService.loadRisk). So the project is read on
    // every single call - update and delete.
    // Concrete example of what that comparison stops: a user who works on project A
    // calls PUT /api/projects/A/risks/13 while risk 13 belongs to project B. The
    // permission check passes, the scope check on A passes, and only this comparison
    // catches it - otherwise a risk of B could be rewritten, or closed, from project A.
    // The JOIN FETCH is what makes that check cost zero extra queries.
    // (It answers 404 and not 403 on purpose: a 403 would confirm that risk 13 exists
    // somewhere, which already tells an attacker something.)
    // SPEED on this second method: it does NOT use the partial index shown above. It
    // reads one row by its primary key, and "id BIGSERIAL PRIMARY KEY" (migration V11)
    // makes PostgreSQL build a unique index on that column on its own, so the database
    // jumps straight to that single row and only then checks deleted = false on it.
    // Why this is worth saying: a reader who has just seen idx_risk_project could think
    // every read needs an index written by hand. A read by id never does, and adding
    // a second index on risks(id) would only waste disk space and slow every
    // insert down.
    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.id = :id AND r.deleted = false")
    Optional<Risk> findActiveById(Long id);
}
