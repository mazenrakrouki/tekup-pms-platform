package com.pms.team.repository;

import com.pms.team.entity.TeamAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Database access for team_assignments (who works on which project, with which role and
 * period). No business rule or permission check here — those live in TeamAssignmentService.
 *
 * <p>existsByProjectIdAndUserIdAndDeletedFalse is also called from BacklogItemService,
 * PlanChargeService and ChargeReelleService (H-8) to confirm someone is really on the team
 * before assigning a card or recording hours; those two workload services add their own
 * exception for the project's chef de projet. findActiveByProjectId is also used by
 * AgileDemoSeeder. This table also feeds the ADR-021 perimeter check, but through
 * ProjectRepository.findAccessibleProjectIdsByEmail, not from here.
 */
public interface TeamAssignmentRepository extends JpaRepository<TeamAssignment, Long> {

    // JOIN FETCH loads project + user in one query (needed by the mapper); AND deleted = false
    // excludes soft-deleted rows so a removed member can't be edited back to life via PUT.
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.project JOIN FETCH ta.user WHERE ta.id = :id AND ta.deleted = false")
    Optional<TeamAssignment> findActiveById(Long id);

    // Team tab of one project. Indexed by the partial index idx_ta_project_id (V6, WHERE deleted = FALSE).
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.project.id = :projectId AND ta.deleted = false")
    List<TeamAssignment> findActiveByProjectId(Long projectId);

    // Mirror of the above, filtered by user. Feeds GET /api/users/{userId}/assignments, which is
    // NOT covered by ProjectScopeInterceptor (ADR-021) — VIEW_TEAM on the service is the only gate.
    @Query("SELECT ta FROM TeamAssignment ta JOIN FETCH ta.user JOIN FETCH ta.project WHERE ta.user.id = :userId AND ta.deleted = false")
    List<TeamAssignment> findActiveByUserId(Long userId);

    // Cheap existence check reused by assign() and by three other modules before letting someone
    // be assigned work. Only a readable-message helper: the real duplicate guard is the partial
    // unique index uk_ta_project_user_active (V6, WHERE deleted = FALSE).
    boolean existsByProjectIdAndUserIdAndDeletedFalse(Long projectId, Long userId);
}
