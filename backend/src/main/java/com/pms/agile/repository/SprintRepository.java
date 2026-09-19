package com.pms.agile.repository;

import com.pms.agile.entity.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: sprints (the iterations of a project in the agile
 * module). Like every repository in this project it only reads rows; the business
 * rules and the permission checks live in the service above it.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser  -> SprintController  (/api/projects/{projectId}/sprints)
 *            -> SprintService     (permission check, date rule, delete rule)
 *            -> SprintRepository  (THIS FILE)
 *            -> Spring Data JPA / Hibernate -> PostgreSQL table sprints
 * The Sprint entities returned here go to SprintMapper, which builds the
 * SprintResponse record sent as JSON.
 * This file has a second caller: BacklogItemService uses findActiveById to check that
 * the sprint a card is being moved into really belongs to the same project.
 * The demo data loader (shared/config/AgileDemoSeeder) calls findActiveByProjectId.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * No sprint could be listed, opened, or used as a move target, so the whole agile
 * board would disappear. The two methods add what the built-in JpaRepository methods
 * do not do: they ignore soft-deleted rows and they load the project in one query.
 *
 * RELATION WITH BacklogItemRepository (the other file of this package)
 * The two tables are linked: backlog_items.sprint_id points at sprints.id, and that
 * column is nullable. So the pair works like this - SprintRepository lists the columns
 * of the board, BacklogItemRepository lists the cards inside them, and a card with a
 * NULL sprint stays in the product backlog. When SprintService deletes a sprint it
 * calls BacklogItemRepository.findActiveBySprintId first, to move the cards back to the
 * product backlog instead of losing them.
 *
 * SECURITY - the two protections that are NOT in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_AGILE')") or 'MANAGE_AGILE' sits on
 *    the SERVICE methods, not on the controller and not here. The code never tests a
 *    role name, only a permission code, so the mapping role -> permission can be changed
 *    by an administrator at runtime.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} of the URL
 *    /api/projects/{id}/** and answers 403 when the project is outside the caller's
 *    perimeter. The permission alone is not enough.
 */
public interface SprintRepository extends JpaRepository<Sprint, Long> {

    // WHAT: reads every live sprint of one project, earliest start date first, with the
    //       project row loaded in the same SQL query.
    // WHY : three things are packed into this one line.
    //
    //   (a) "s.deleted = false" - a sprint is never really erased. Deleting it only sets the
    //       boolean column `deleted` to true (see BaseEntity, the parent class of Sprint).
    //       This is a "soft delete": the row stays for the audit trail, so every read has to
    //       filter it out.
    //       WITHOUT IT: a sprint the project manager closed and deleted still shows up as a
    //       column on the board, and its old cards look planned again.
    //
    //   (b) "JOIN FETCH s.project" - Sprint.project is mapped FetchType.LAZY, so Hibernate
    //       would normally leave it as an empty placeholder and run one extra SELECT the
    //       first time it is read. SprintMapper reads project.id and project.code for every
    //       sprint. JOIN FETCH brings the project row in the same statement.
    //       WITHOUT IT: 10 sprints means 1 query plus up to 10 more just for the project -
    //       the classic "N+1 select" problem. And because application.yml sets
    //       "open-in-view: false", a lazy link can only be loaded while the service
    //       transaction is still open; it works today because the mapping happens inside
    //       that transaction, but any code that maps a Sprint after the service returns
    //       would get a LazyInitializationException. Fetching now removes that trap.
    //       Note this is a plain JOIN, not a LEFT JOIN, and that is correct here: project_id
    //       is NOT NULL on the sprints table (migration V27), so every sprint always has a
    //       project and no row can be dropped by the join.
    //
    //   (c) "ORDER BY s.startDate ASC" - chronological order. A board is read from left to
    //       right in time, not in the order the rows happened to be created.
    //       WITHOUT IT: a sprint added late but planned for January would appear after the
    //       March one, and the timeline would read backwards.
    //
    // ":projectId" is a named parameter matched to the Java argument of the same name;
    // Spring Boot compiles with the -parameters flag so no @Param annotation is needed.
    //
    // SPEED: migration V27 creates the matching partial index
    // "CREATE INDEX idx_sprint_project ON sprints(project_id) WHERE deleted = FALSE".
    // Partial means only the live rows are indexed, which matches the filter above exactly.
    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.project.id = :projectId AND s.deleted = false ORDER BY s.startDate ASC")
    List<Sprint> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live sprint by its id, with its project already loaded. It gives back
    //       an Optional<Sprint>: a box that either holds the sprint or is empty.
    // WHY Optional: it forces the caller to handle the "not found" case instead of silently
    //       working with null. SprintService.loadSprint calls .orElseThrow(...) and turns the
    //       empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null and the next sprint.setName(...) would
    //       fail with a NullPointerException, seen by the user as a 500 error.
    // WHY not the built-in findById(id): findById ignores the soft-delete flag and does not
    //       load the project, so it would let somebody edit a sprint that the users already
    //       deleted.
    //
    // WHY "JOIN FETCH s.project" matters even more on this method: both callers compare the
    // project of the loaded sprint with the projectId of the URL.
    //   - SprintService.loadSprint -> 404 if they differ.
    //   - BacklogItemService.resolveSprint -> 404 if they differ, which is the check that
    //     stops a card being attached to the sprint of ANOTHER project by guessing a sprint
    //     id. The card would then link two projects together.
    // So the project is read on every single call. Leaving it lazy would mean a second query
    // every time this method is used, including once per card being moved on the board.
    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.id = :id AND s.deleted = false")
    Optional<Sprint> findActiveById(Long id);
}
