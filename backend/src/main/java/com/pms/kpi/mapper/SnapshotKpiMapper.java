package com.pms.kpi.mapper;

// MapStruct mapper: generates SnapshotKpiMapperImpl at build time to turn a saved SnapshotKpi
// row into the KpiResponse DTO sent to Angular. KpiService.buildKpi() builds the live-path
// KpiResponse by hand instead, since nothing is stored in that case.

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.entity.SnapshotKpi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Converts saved KPI snapshots into the KpiResponse records returned by the KPI API. An empty
 * interface: MapStruct generates the copy code, so a renamed field fails the build instead of
 * silently mismatching (as a hand-built 23-argument constructor call could).
 */
// componentModel = "spring" makes the generated impl a Spring bean, injectable into KpiService.
@Mapper(componentModel = "spring")
public interface SnapshotKpiMapper {

    /**
     * Turns one stored snapshot row into the KpiResponse sent back as JSON. Only the fields below
     * need an explicit rule; everything else shares its name between entity and DTO and is
     * copied automatically.
     */
    // Names differ (id -> snapshotId), so MapStruct needs telling; KpiController builds the
    // POST's Location header from snapshot.snapshotId(), so a missed mapping would break that.
    @Mapping(target = "snapshotId",  source = "id")
    // Flattens the ManyToOne link to just the id, so the front end doesn't receive a whole Project.
    @Mapping(target = "projectId",   source = "project.id")
    // Same flattening for the readable code. project is LAZY, so this relies on
    // SnapshotKpiRepository's JOIN FETCH to avoid one extra SELECT per row (N+1).
    @Mapping(target = "projectCode", source = "project.code")
    // snapshot_kpis has no column for the sold-margin baseline, so it's read from the project
    // instead — meaning it reflects the CURRENT project value, not the value at snapshot time.
    // The live calculation prefers the DI-derived margin and falls back to this field only when
    // no DI exists; this mapper never looks at the DI, so the two paths can show different baselines.
    @Mapping(target = "margeVenduePct", source = "project.margeNetteVendue")
    // Warnings only exist while a KPI is being computed live (H-3, missing EV); a stored snapshot
    // has no such column, so this is always an empty (immutable) list, never null.
    @Mapping(target = "warnings",    expression = "java(java.util.List.of())")
    KpiResponse toResponse(SnapshotKpi snapshot);

    /**
     * Maps a whole history of snapshots in one call, keeping the given order (the repository
     * already sorts newest first). Generated as a loop over toResponse(), so every rule above
     * applies to each row — a hand-written loop in the service could drift and forget one.
     */
    List<KpiResponse> toResponseList(List<SnapshotKpi> snapshots);
}
