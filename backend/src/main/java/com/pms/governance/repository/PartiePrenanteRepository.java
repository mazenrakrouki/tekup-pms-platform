package com.pms.governance.repository;

import com.pms.governance.entity.PartiePrenante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for parties_prenantes (project stakeholders — name, contact info, influence
 * and interest). No business rule or permission check here — see PartiePrenanteService. Rows
 * carry personal data, so both reads below always filter on one project.
 */
public interface PartiePrenanteRepository extends JpaRepository<PartiePrenante, Long> {

    // Live stakeholders of one project, alphabetical by name, with the project pre-fetched.
    // Matches partial index idx_pp_project.
    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.project.id = :projectId AND p.deleted = false ORDER BY p.nom")
    List<PartiePrenante> findActiveByProjectId(Long projectId);

    // Live stakeholder by id, with project loaded — lets the service verify project ownership
    // before any mutation (see PartiePrenanteService.loadPP).
    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.id = :id AND p.deleted = false")
    Optional<PartiePrenante> findActiveById(Long id);
}
