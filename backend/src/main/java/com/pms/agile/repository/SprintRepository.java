package com.pms.agile.repository;

import com.pms.agile.entity.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for the sprints table. Read-only queries; business rules live in
 * SprintService. BacklogItemService also calls findActiveById to verify a card's target sprint
 * belongs to the same project.
 */
public interface SprintRepository extends JpaRepository<Sprint, Long> {

    // Filters soft-deleted rows and JOIN FETCHes project to avoid N+1 (open-in-view=false means
    // a lazy load fails once the transaction closes). Plain JOIN, not LEFT: project_id is
    // NOT NULL on sprints (V27). Backed by partial index idx_sprint_project.
    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.project.id = :projectId AND s.deleted = false ORDER BY s.startDate ASC")
    List<Sprint> findActiveByProjectId(Long projectId);

    // Same live-row + fetch-join rules as above, for one sprint by id. Both callers
    // (SprintService.loadSprint, BacklogItemService.resolveSprint) compare the loaded project
    // against the URL's projectId, so fetching it eagerly avoids a second query on every call.
    @Query("SELECT s FROM Sprint s JOIN FETCH s.project WHERE s.id = :id AND s.deleted = false")
    Optional<Sprint> findActiveById(Long id);
}
