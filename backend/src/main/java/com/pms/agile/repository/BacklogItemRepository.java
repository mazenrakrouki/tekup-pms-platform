package com.pms.agile.repository;

import com.pms.agile.entity.BacklogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for the backlog_items table. Read-only queries; business rules live in
 * BacklogItemService. SprintService also calls findActiveBySprintId to detach cards when a
 * sprint is deleted.
 */
public interface BacklogItemRepository extends JpaRepository<BacklogItem, Long> {

    // Filters soft-deleted rows, JOIN FETCHes project/sprint to avoid N+1 (open-in-view=false
    // means lazy loads fail once the transaction closes), and LEFT JOINs sprint since an item
    // not yet planned has none. Partial index idx_backlog_project (V27) backs this query.
    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.project.id = :projectId AND b.deleted = false ORDER BY b.id ASC")
    List<BacklogItem> findActiveByProjectId(Long projectId);

    // Same live-row + fetch-join rules as above, for a single item by id. Filters only on id;
    // the caller (BacklogItemService.loadItem) checks the project matches to stop cross-project access.
    @Query("SELECT b FROM BacklogItem b JOIN FETCH b.project LEFT JOIN FETCH b.sprint "
         + "WHERE b.id = :id AND b.deleted = false")
    Optional<BacklogItem> findActiveById(Long id);

    // Used by SprintService.delete to detach items before a sprint disappears, so committed
    // work returns to the product backlog instead of keeping a dangling sprint reference.
    // No fetch joins: the caller only nulls the sprint field. Backed by idx_backlog_sprint (V27).
    @Query("SELECT b FROM BacklogItem b WHERE b.sprint.id = :sprintId AND b.deleted = false")
    List<BacklogItem> findActiveBySprintId(Long sprintId);
}
