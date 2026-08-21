package com.pms.workload.repository;

import com.pms.workload.entity.PlanCharge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlanChargeRepository extends JpaRepository<PlanCharge, Long> {

    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false ORDER BY pc.period, pc.user.lastName")
    List<PlanCharge> findActiveByProjectId(Long projectId);

    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.id = :id AND pc.deleted = false")
    Optional<PlanCharge> findActiveById(Long id);

    boolean existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(Long projectId, Long userId, LocalDate period);

    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdPaged(Long projectId, Pageable pageable);

    // ── Périmètre « own only » (BR-062…064) : lectures d'un porteur sans capacité de vue élargie ──

    @Query("SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false ORDER BY pc.period")
    List<PlanCharge> findActiveByProjectIdAndUserId(Long projectId, Long userId);

    @Query(value = "SELECT pc FROM PlanCharge pc JOIN FETCH pc.project JOIN FETCH pc.user WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false",
           countQuery = "SELECT COUNT(pc) FROM PlanCharge pc WHERE pc.project.id = :projectId AND pc.user.id = :userId AND pc.deleted = false")
    Page<PlanCharge> findActiveByProjectIdAndUserIdPaged(Long projectId, Long userId, Pageable pageable);
}
