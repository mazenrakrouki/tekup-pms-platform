package com.pms.governance.repository;

import com.pms.governance.entity.Risk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for risks (the project risk register). No business rule or permission check
 * here — see RiskService.
 */
public interface RiskRepository extends JpaRepository<Risk, Long> {

    // Live risks of one project, newest first, with the project pre-fetched. Matches partial
    // index idx_risk_project. Sorted by createdAt, not by statut/impact: those enums are stored
    // as TEXT, so ordering on them would sort alphabetically instead of by real severity.
    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.project.id = :projectId AND r.deleted = false ORDER BY r.createdAt DESC")
    List<Risk> findActiveByProjectId(Long projectId);

    // Live risk by id, with project loaded — lets the service verify project ownership before
    // any mutation (see RiskService.loadRisk).
    @Query("SELECT r FROM Risk r JOIN FETCH r.project WHERE r.id = :id AND r.deleted = false")
    Optional<Risk> findActiveById(Long id);
}
