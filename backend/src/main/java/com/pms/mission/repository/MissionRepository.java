package com.pms.mission.repository;

import com.pms.mission.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for table missions (one business trip: objet, lieu, start/end date). Only
 * reads are declared here; writes come from JpaRepository, and every business rule / permission
 * check lives in MissionService. Sibling of ComposanteMissionRepository: this file reads the
 * trip, the other reads what it costs. Soft delete does not cascade — MissionService.delete
 * only flags the mission, but its cost lines become unreachable once findActiveById hides it.
 * Permission (VIEW_MISSION/MANAGE_MISSION) and project scope (ADR-021) are enforced above this
 * file; the one row-level rule that DOES live here is UC-21 (own-missions-only), see below.
 */
public interface MissionRepository extends JpaRepository<Mission, Long> {

    // All live missions of a project (every employee), earliest start date first, project and
    // user preloaded. Used by MissionService.findByProject only when the caller can see all
    // missions (MANAGE_MISSION or VIEW_ALL_PROJECTS); otherwise findActiveByProjectIdAndUserId
    // below applies UC-21. JOIN FETCH avoids N+1 selects and a LazyInitializationException once
    // the transaction (open-in-view: false) closes. Matching partial index: idx_mission_project
    // (V10), WHERE deleted = FALSE.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.project.id = :projectId AND m.deleted = false ORDER BY m.dateDebut")
    List<Mission> findActiveByProjectId(Long projectId);

    // One live mission by id, project and user preloaded. Optional forces callers to handle
    // "not found" (MissionService turns it into a 404). findById() is not used because it
    // ignores the soft-delete flag and doesn't load the project. Every caller (update/delete
    // here and ComposanteService's cost-line methods) compares the loaded project with the
    // URL's projectId and 404s on mismatch — the check ADR-021's interceptor cannot do, since it
    // only sees the URL's projectId, not which project a given mission actually belongs to.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.id = :id AND m.deleted = false")
    Optional<Mission> findActiveById(Long id);

    /** "Own only" perimeter (UC-21: a developer sees only their own missions). */
    // Same as findActiveByProjectId but narrowed to one user, used when
    // MissionService.canSeeAllMissions() is false. Filtering in SQL (not in memory afterward)
    // means rows the caller may not see are never loaded — the audit gap this closed. Chosen by
    // capability, not role name (ADR-001). MissionService.currentUserId() returns -1 when the
    // account can't be resolved, so the query then safely returns nothing.
    @Query("SELECT m FROM Mission m JOIN FETCH m.project JOIN FETCH m.user WHERE m.project.id = :projectId AND m.user.id = :userId AND m.deleted = false ORDER BY m.dateDebut")
    List<Mission> findActiveByProjectIdAndUserId(Long projectId, Long userId);
}
