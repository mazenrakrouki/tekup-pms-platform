package com.pms.kpi.repository;

import com.pms.kpi.entity.SnapshotKpi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SnapshotKpiRepository extends JpaRepository<SnapshotKpi, Long> {

    @Query("SELECT k FROM SnapshotKpi k JOIN FETCH k.project WHERE k.project.id = :projectId AND k.deleted = false ORDER BY k.snapshotDate DESC")
    List<SnapshotKpi> findActiveByProjectId(Long projectId);

    boolean existsByProjectIdAndSnapshotDateAndDeletedFalse(Long projectId, LocalDate snapshotDate);
}
