package com.pms.kpi.repository;

import com.pms.kpi.entity.SnapshotKpi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

/**
 * Database access for snapshot_kpis (frozen financial/progress figures of a project on one
 * day). No business rule, computation or permission check here — see KpiService. Snapshots are
 * the one deliberate exception to PMS's "never store a derived amount" rule: computeLive still
 * recomputes everything and never reads this table.
 */
public interface SnapshotKpiRepository extends JpaRepository<SnapshotKpi, Long> {

    // Live snapshots of one project, newest date first, with the project pre-fetched (JOIN FETCH
    // avoids N+1; open-in-view is false). Used both for the history screen and by
    // KpiService.latestSnapshotEv to find the most recent EV %, which the live screen reuses.
    // Matches partial index idx_kpi_project.
    @Query("SELECT k FROM SnapshotKpi k JOIN FETCH k.project WHERE k.project.id = :projectId AND k.deleted = false ORDER BY k.snapshotDate DESC")
    List<SnapshotKpi> findActiveByProjectId(Long projectId);

    // True when a live snapshot already exists for this project on this date. Derived query
    // (method name -> SQL): existsBy + ProjectId + And + SnapshotDate + And + DeletedFalse.
    // KpiService.createSnapshot calls it with today's date to refuse a duplicate with a clean
    // message; the real guarantee is the partial unique index uk_kpi_project_date (V8), which
    // this query also reads, so soft-deleting a snapshot frees that date again.
    boolean existsByProjectIdAndSnapshotDateAndDeletedFalse(Long projectId, LocalDate snapshotDate);
}
