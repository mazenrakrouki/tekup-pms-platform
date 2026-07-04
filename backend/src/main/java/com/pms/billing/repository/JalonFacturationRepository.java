package com.pms.billing.repository;

import com.pms.billing.entity.JalonFacturation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface JalonFacturationRepository extends JpaRepository<JalonFacturation, Long> {

    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.project.id = :projectId AND j.deleted = false ORDER BY j.datePrevue NULLS LAST, j.id")
    List<JalonFacturation> findActiveByProjectId(Long projectId);

    @Query("SELECT j FROM JalonFacturation j JOIN FETCH j.project WHERE j.id = :id AND j.deleted = false")
    Optional<JalonFacturation> findActiveById(Long id);

    @Query("SELECT COALESCE(SUM(j.pourcentage), 0) FROM JalonFacturation j WHERE j.project.id = :projectId AND j.deleted = false")
    BigDecimal sumPourcentageByProjectId(Long projectId);
}
