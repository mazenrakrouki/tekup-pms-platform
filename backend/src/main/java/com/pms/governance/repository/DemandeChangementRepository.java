package com.pms.governance.repository;

import com.pms.governance.entity.DemandeChangement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DemandeChangementRepository extends JpaRepository<DemandeChangement, Long> {

    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.project.id = :projectId AND d.deleted = false ORDER BY d.dateDemande DESC")
    List<DemandeChangement> findActiveByProjectId(Long projectId);

    @Query("SELECT d FROM DemandeChangement d JOIN FETCH d.project JOIN FETCH d.demandeur WHERE d.id = :id AND d.deleted = false")
    Optional<DemandeChangement> findActiveById(Long id);
}
