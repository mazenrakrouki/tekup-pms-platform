package com.pms.mission.repository;

import com.pms.mission.entity.ComposanteMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for table composantes_mission (mission cost lines: per diem, ticket, stamp,
 * transport, stay). Only reads are declared here; writes come from JpaRepository, and every
 * business rule / permission check lives in ComposanteService. Sibling of MissionRepository:
 * that file reads the trip, this one reads what it costs, and a read here is always preceded by
 * a read there (ComposanteService checks the mission's project before trusting the URL's
 * missionId). Permission (VIEW_MISSION/MANAGE_MISSION) and project scope (ADR-021) are enforced
 * above this file, not in it.
 */
public interface ComposanteMissionRepository extends JpaRepository<ComposanteMission, Long> {

    // Live cost lines of one mission, fixed order, parent mission loaded in the same query.
    //  - c.deleted = false: soft delete, so every read must filter it out itself.
    //  - JOIN FETCH c.mission: avoids N+1 selects and a LazyInitializationException once
    //    open-in-view (false) closes the session; the mapper and update/delete checks both need
    //    mission.id on every row.
    //  - ORDER BY c.typeComposante: stable order (alphabetical, since the enum is stored as
    //    text), so the same trip's cost table looks the same on every reload.
    // Matching partial index: idx_comp_mission (V10), WHERE deleted = FALSE.
    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.mission.id = :missionId AND c.deleted = false ORDER BY c.typeComposante")
    List<ComposanteMission> findActiveByMissionId(Long missionId);

    // One live cost line by id, mission preloaded. Optional forces callers to handle "not
    // found" (ComposanteService turns it into a 404) instead of a NullPointerException.
    // findById() is not used here because it ignores the soft-delete flag and doesn't load the
    // mission needed by update()/delete() to confirm the cost line belongs to the URL's mission.
    @Query("SELECT c FROM ComposanteMission c JOIN FETCH c.mission WHERE c.id = :id AND c.deleted = false")
    Optional<ComposanteMission> findActiveById(Long id);
}
