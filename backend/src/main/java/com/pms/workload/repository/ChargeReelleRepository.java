package com.pms.workload.repository;

import com.pms.workload.entity.ChargeReelle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/*
 * Spring Data repository for charges_reelles (actual workload). No business rule or permission
 * check lives here — both are in ChargeReelleService, so calling a method directly from elsewhere
 * would skip them. Own-only twins of each list serve BR-062..064 (non-validators see only their rows).
 */

/**
 * Spring Data repository for the ChargeReelle entity. Spring builds the implementation from this
 * interface at startup. save() is also used for soft delete (sets deleted=true); never deleteById().
 */
public interface ChargeReelleRepository extends JpaRepository<ChargeReelle, Long> {

    // Every live row of one project, project/user/validator preloaded (JOIN FETCH; LEFT for
    // validator since it's null until approved) to avoid N+1; oldest month first, then by last name.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false ORDER BY cr.period, cr.user.lastName")
    List<ChargeReelle> findActiveByProjectId(Long projectId);

    // One live row by id, preloaded the same way; entry point for update/validate/delete. deleted=false
    // so a soft-deleted line can't be re-validated back into the budget through a stale id.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.id = :id AND cr.deleted = false")
    Optional<ChargeReelle> findActiveById(Long id);

    // Friendly pre-check for "already declared this month" (submit() -> 409); the real guarantee is
    // the uk_cr_active unique index, which catches the race if two requests land at the same instant.
    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    // Paged twin of findActiveByProjectId for the HTTP list endpoint. Hand-written countQuery: a
    // derived count can't carry JOIN FETCH and doesn't need the extra joins anyway.
    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    // Only approved rows (validatedAt set) — the cost input for KpiService; an unapproved declaration
    // is a claim, not a fact, and must never move the budget. Fetches user only, not project, since
    // KpiService already holds the Project it called this with.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.user WHERE cr.project.id = :projectId AND cr.validatedAt IS NOT NULL AND cr.deleted = false")
    List<ChargeReelle> findValidatedByProjectId(Long projectId);

    // Own-only twins (BR-062..064), used when the caller holds neither VALIDATE_WORKLOAD nor
    // VIEW_ALL_PROJECTS. Filtered in SQL, not after loading, so paging and totals stay honest;
    // ChargeReelleService.canSeeAllWorkload() picks between these and the queries above.

    // Own-only twin of findActiveByProjectId, filtered by user.id too.
    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false ORDER BY cr.period")
    List<ChargeReelle> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    // Own-only twin of findActiveByProjectIdPaged; countQuery repeats the user filter so the total
    // never leaks how many rows belong to the rest of the team.
    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}
