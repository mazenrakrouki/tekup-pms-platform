package com.pms.workload.repository;

import com.pms.workload.entity.ChargeReelle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChargeReelleRepository extends JpaRepository<ChargeReelle, Long> {

    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false ORDER BY cr.period, cr.user.lastName")
    List<ChargeReelle> findActiveByProjectId(Long projectId);

    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.id = :id AND cr.deleted = false")
    Optional<ChargeReelle> findActiveById(Long id);

    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.user WHERE cr.project.id = :projectId AND cr.validatedAt IS NOT NULL AND cr.deleted = false")
    List<ChargeReelle> findValidatedByProjectId(Long projectId);

    // ── Périmètre « own only » (BR-062…064) : lectures d'un porteur sans capacité de vue élargie ──

    @Query("SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false ORDER BY cr.period")
    List<ChargeReelle> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    @Query(value = "SELECT cr FROM ChargeReelle cr JOIN FETCH cr.project JOIN FETCH cr.user LEFT JOIN FETCH cr.validatedBy WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false",
           countQuery = "SELECT COUNT(cr) FROM ChargeReelle cr WHERE cr.project.id = :projectId AND cr.user.id = :userId AND cr.deleted = false")
    Page<ChargeReelle> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}
