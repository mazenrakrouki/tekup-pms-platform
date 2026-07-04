package com.pms.governance.repository;

import com.pms.governance.entity.PartiePrenante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PartiePrenanteRepository extends JpaRepository<PartiePrenante, Long> {

    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.project.id = :projectId AND p.deleted = false ORDER BY p.nom")
    List<PartiePrenante> findActiveByProjectId(Long projectId);

    @Query("SELECT p FROM PartiePrenante p JOIN FETCH p.project WHERE p.id = :id AND p.deleted = false")
    Optional<PartiePrenante> findActiveById(Long id);
}
