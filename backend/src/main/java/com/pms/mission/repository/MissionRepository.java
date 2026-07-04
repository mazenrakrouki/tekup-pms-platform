package com.pms.mission.repository;

import com.pms.mission.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.project.id = :projectId AND m.deleted = false ORDER BY m.dateDebut")
    List<Mission> findActiveByProjectId(Long projectId);

    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.id = :id AND m.deleted = false")
    Optional<Mission> findActiveById(Long id);
}
