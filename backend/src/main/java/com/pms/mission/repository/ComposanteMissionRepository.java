package com.pms.mission.repository;

import com.pms.mission.entity.ComposanteMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ComposanteMissionRepository extends JpaRepository<ComposanteMission, Long> {

    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.mission.id = :missionId AND c.deleted = false ORDER BY c.typeComposante")
    List<ComposanteMission> findActiveByMissionId(Long missionId);

    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.id = :id AND c.deleted = false")
    Optional<ComposanteMission> findActiveById(Long id);
}
