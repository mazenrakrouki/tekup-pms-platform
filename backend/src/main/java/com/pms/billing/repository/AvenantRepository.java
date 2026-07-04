package com.pms.billing.repository;

import com.pms.billing.entity.Avenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AvenantRepository extends JpaRepository<Avenant, Long> {

    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.project.id = :projectId AND a.deleted = false ORDER BY a.dateAvenant")
    List<Avenant> findActiveByProjectId(Long projectId);

    @Query("SELECT a FROM Avenant a JOIN FETCH a.project WHERE a.id = :id AND a.deleted = false")
    Optional<Avenant> findActiveById(Long id);
}
