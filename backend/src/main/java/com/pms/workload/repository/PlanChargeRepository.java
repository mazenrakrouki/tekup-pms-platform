package com.pms.workload.repository;

import com.pms.workload.entity.PlanCharge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/*
 * Spring Data repository for plan_charges (planned workload). No business rule or permission check
 * lives here — both are in PlanChargeService. Unlike its twin ChargeReelleRepository, a plan line
 * has no approval step, so queries here fetch two links (project, user), not three.
 */

/**
 * Spring Data repository for the PlanCharge entity. Spring builds the implementation from this
 * interface at startup. save() is also used for soft delete (sets deleted=true); never deleteById().
 */
public interface PlanChargeRepository extends JpaRepository<PlanCharge, Long> {

    // Every live row of one project, project/user preloaded (JOIN FETCH) to avoid N+1; oldest month
    // first, then by last name. Also used unpaged by KpiService.buildKpi(), which needs the whole
    // plan at once to compute the budget — paging here would silently understate it.
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false ORDER BY pc.period, pc.user.lastName")
    List<PlanCharge> findActiveByProjectId(Long projectId);

    // One live row by id, preloaded the same way; entry point for update/delete. deleted=false so a
    // cancelled line can't be edited back into the plan through a stale id.
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.id = :id AND pc.deleted = false")
    Optional<PlanCharge> findActiveById(Long id);

    // Friendly pre-check for "already planned this month" (create() -> 409); the real guarantee is
    // the uk_pc_active unique index, which catches the race if two managers save at the same instant.
    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    // Paged twin of findActiveByProjectId for the HTTP list endpoint. Hand-written countQuery: a
    // derived count can't carry JOIN FETCH and doesn't need the extra joins anyway.
    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    // Own-only twins (BR-062..064), used when the caller holds neither VALIDATE_WORKLOAD nor
    // VIEW_ALL_PROJECTS. Filtered in SQL, not after loading, so paging and totals stay honest;
    // PlanChargeService.canSeeAllWorkload() picks between these and the queries above.

    // Own-only twin of findActiveByProjectId, filtered by user.id too.
    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false ORDER BY pc.period")
    List<PlanCharge> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    // Own-only twin of findActiveByProjectIdPaged; countQuery repeats the user filter so the total
    // never leaks how many months are planned for the rest of the team.
    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}
