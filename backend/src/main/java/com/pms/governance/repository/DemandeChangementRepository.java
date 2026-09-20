package com.pms.governance.repository;

import com.pms.governance.entity.DemandeChangement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for demandes_changement (change requests: filed, then approved or rejected by
 * a manager). No business rule or permission check here — see DemandeChangementService.
 */
public interface DemandeChangementRepository extends JpaRepository<DemandeChangement, Long> {

    // Live requests of one project, newest first, with project and author pre-fetched (JOIN FETCH
    // avoids N+1 selects; required since open-in-view is false). Matches partial index idx_dc_project.
    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.project.id = :projectId AND d.deleted = false ORDER BY d.dateDemande DESC")
    List<DemandeChangement> findActiveByProjectId(Long projectId);

    // Live request by id, with project and author loaded — lets the service verify project
    // ownership before any mutation (see DemandeChangementService.loadDC).
    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.id = :id AND d.deleted = false")
    Optional<DemandeChangement> findActiveById(Long id);
}
