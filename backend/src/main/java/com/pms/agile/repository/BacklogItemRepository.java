package com.pms.agile.repository;

import com.pms.agile.entity.BacklogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * WHAT THIS FILE IS
 * Database access for one table: backlog_items (the product backlog cards of the
 * agile module). It is a "repository": the only layer allowed to talk to the
 * database for this table. It decides no business rule, it only reads rows.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser  -> BacklogItemController  (/api/projects/{projectId}/backlog)
 *            -> BacklogItemService     (permission check + business rules)
 *            -> BacklogItemRepository  (THIS FILE)
 *            -> Spring Data JPA / Hibernate -> PostgreSQL table backlog_items
 * The rows that come back go to BacklogItemMapper, which turns a BacklogItem entity
 * into a BacklogItemResponse record sent as JSON.
 * SprintService also uses this file: when a sprint is deleted it calls
 * findActiveBySprintId to detach the cards of that sprint.
 * The demo data loader (shared/config/AgileDemoSeeder) calls findActiveByProjectId too.
 *
 * WHY IT EXISTS - what would break if you deleted it
 * Without this interface the service has no way to reach the table, so the whole
 * backlog screen stops working. The three methods below are not free extras: each one
 * adds the "deleted = false" filter and the JOIN FETCH that the built-in JpaRepository
 * methods (findAll, findById) do not have. Using findById instead of findActiveById
 * would return a card that the users already deleted.
 *
 * SECURITY - the two protections that are NOT in this file
 * 1. Permission: @PreAuthorize("hasAuthority('VIEW_AGILE')") for reading and
 *    'MANAGE_AGILE' for writing sit on the SERVICE methods of BacklogItemService,
 *    never here and never on the controller. The code never tests a role name,
 *    only a permission code, so an administrator can change who may do what without a
 *    new release.
 * 2. Project scope (ADR-021): ProjectScopeInterceptor reads the {projectId} in the URL
 *    /api/projects/{id}/** and refuses with 403 a project the user is not attached to.
 *    Holding the MANAGE_AGILE permission is NOT enough by itself.
 * The queries here therefore receive a projectId and trust it, because two guards
 * upstream already checked it. That is why this repository stays this small.
 */
public interface BacklogItemRepository extends JpaRepository<BacklogItem, Long> {

    // WHAT: reads every live backlog card of one project, oldest id first, and loads the
    //       linked project row and sprint row inside the SAME SQL query.
    // WHY : this single query solves four separate problems.
    //
    //   (a) "b.deleted = false" - this application never really erases a row. Deleting a
    //       card only sets the boolean column `deleted` to true (see BaseEntity, the parent
    //       class of BacklogItem). This is a "soft delete": the row stays in the table for
    //       the audit trail. So every read must filter it out.
    //       WITHOUT IT: a card the project manager deleted yesterday comes back on the board
    //       this morning, and the sprint looks fuller than it really is.
    //
    //   (b) "JOIN FETCH b.project" - BacklogItem.project is mapped FetchType.LAZY, so
    //       Hibernate normally leaves it as an empty placeholder and fires one extra SELECT
    //       the first time somebody reads it. BacklogItemMapper reads project.id and
    //       project.code for every single card. JOIN FETCH brings the project row along in
    //       the same SQL statement.
    //       WITHOUT IT: 50 cards on the board means 1 query for the cards plus up to 50 more
    //       for the project - the classic "N+1 select" problem. Opening the board would hit
    //       the database dozens of times for data that one join already carries.
    //       Second reason it matters here: application.yml sets "open-in-view: false", so a
    //       lazy link can only be loaded while the service transaction is still open. Today
    //       the mapping runs inside that transaction, so it works; but any future code that
    //       maps these entities after the service returns would get a
    //       LazyInitializationException. Fetching now removes that trap.
    //
    //   (c) "LEFT JOIN FETCH b.sprint" - LEFT, not a plain JOIN. sprint_id is nullable on
    //       purpose: a NULL sprint means the card is still in the product backlog and has
    //       not been committed to any sprint. A plain (inner) JOIN keeps only the rows that
    //       HAVE a matching sprint.
    //       WITHOUT THE "LEFT": every not-yet-planned card disappears from the screen,
    //       silently and with no error - the product backlog column simply looks empty.
    //
    //   (d) "ORDER BY b.id ASC" - a stable order. Ids grow with time, so the oldest card is
    //       shown first.
    //       WITHOUT IT: the database is free to return the rows in any order, and the cards
    //       would jump around between two refreshes of the same page.
    //
    // WHAT IS NOT FETCHED: b.assignee. It is lazy like the other two, and BacklogItemMapper
    // does read assignee.id and assignee.fullName. It still works because the mapping runs
    // inside the read-only transaction of the service, so Hibernate can load each assignee
    // on demand - but that is one extra query per distinct assignee of the board.
    //
    // ":projectId" is a named parameter. Spring matches it to the Java argument called
    // projectId because Spring Boot compiles with the -parameters flag, which keeps the real
    // argument names in the .class file. That is why no @Param annotation is needed here.
    //
    // SPEED: migration V27 creates the matching partial index
    // "CREATE INDEX idx_backlog_project ON backlog_items(project_id) WHERE deleted = FALSE".
    // Partial means it indexes only the live rows, so it stays small and fits the filter above.
    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.project.id = :projectId AND b.deleted = false ORDER BY b.id ASC")
    List<BacklogItem> findActiveByProjectId(Long projectId);

    // WHAT: reads ONE live card by its id, with its project and its sprint already loaded.
    //       It gives back an Optional<BacklogItem>: a small box that either holds the card
    //       or is empty.
    // WHY Optional instead of the entity directly: it forces the caller to deal with the
    //       "not found" case. BacklogItemService.loadItem calls .orElseThrow(...) on it and
    //       turns the empty box into a clean 404 NotFoundException.
    //       WITHOUT IT: the method would return null, and the first item.getTitle() in the
    //       service would fail with a NullPointerException, which the user sees as an
    //       unexplained 500 error instead of a readable "item not found".
    // WHY not the built-in findById(id): findById ignores the soft-delete flag and does not
    //       fetch the associations. It would hand back a deleted card, so the update and
    //       delete endpoints would let somebody edit something that no longer exists for
    //       the users.
    // WHY the same LEFT JOIN FETCH as above: same reason - an item still in the product
    //       backlog has no sprint, and an inner join would make this query find nothing for
    //       it, so editing a not-yet-planned card would answer 404.
    //
    // IMPORTANT: this query filters on the id only, NOT on the project. The project check is
    // the caller's job: BacklogItemService.loadItem compares item.getProject().getId() with
    // the projectId taken from the URL and throws 404 when they differ. That comparison is
    // also why "JOIN FETCH b.project" is here - the project is needed immediately.
    // WITHOUT THAT CHECK IN THE SERVICE: knowing a card id would be enough to edit a card of
    // another project, simply by putting your own project id in the URL.
    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.id = :id AND b.deleted = false")
    Optional<BacklogItem> findActiveById(Long id);

    // WHAT: all the live cards that are currently committed to one given sprint.
    // WHY IT EXISTS: SprintService.delete calls it. Deleting a sprint must not delete the
    //       work committed to it, so the service sets sprint = null on each of these cards
    //       and they go back to the product backlog.
    //       WITHOUT THIS METHOD: those cards would keep a foreign key pointing at a sprint
    //       flagged as deleted. The board would show work attached to a sprint nobody can
    //       open any more, and committed-but-unfinished items would disappear from view.
    // WHY NO JOIN FETCH HERE, unlike the two methods above: the caller never reads the
    //       project or the sprint of these rows, it only does item.setSprint(null) and saves.
    //       Joining two more tables for data nobody uses would only cost time.
    // WHY NO ORDER BY: the caller just loops over the list to modify every row, nothing is
    //       displayed, so the order carries no meaning here.
    // NOTE: "b.sprint.id" reads the foreign key column sprint_id directly. Hibernate already
    //       holds that value on the card, so it does not need to load the sprint row to
    //       compare it - no extra join is generated.
    // SPEED: migration V27 creates
    //       "CREATE INDEX idx_backlog_sprint ON backlog_items(sprint_id) WHERE deleted = FALSE",
    //       so deleting a sprint does not force a full scan of the backlog table.
    @Query("SELECT b FROM BacklogItem b WHERE b.sprint.id = :sprintId AND b.deleted = false")
    List<BacklogItem> findActiveBySprintId(Long sprintId);
}
