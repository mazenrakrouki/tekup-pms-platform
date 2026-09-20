package com.pms.governance.repository;

import com.pms.governance.entity.Livrable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for livrables (project deliverables). No business rule or permission check
 * here — see LivrableService. KpiService also reads findActiveByProjectId to compute delivery %.
 */
public interface LivrableRepository extends JpaRepository<Livrable, Long> {

    // Live deliverables of one project, closest due date first (NULLS LAST, then title as
    // tie-breaker), with the project pre-fetched. Matches partial index idx_livrable_project.
    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.dateEcheance NULLS LAST, l.titre")
    List<Livrable> findActiveByProjectId(Long projectId);

    // Live deliverable by id, with project loaded — lets the service verify project ownership
    // before any mutation (see LivrableService.loadLivrable).
    @Query("SELECT l FROM Livrable l JOIN FETCH l.project WHERE l.id = :id AND l.deleted = false")
    Optional<Livrable> findActiveById(Long id);
}
