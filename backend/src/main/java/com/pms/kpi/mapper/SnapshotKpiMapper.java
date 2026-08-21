package com.pms.kpi.mapper;

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.entity.SnapshotKpi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SnapshotKpiMapper {

    @Mapping(target = "snapshotId",  source = "id")
    @Mapping(target = "projectId",   source = "project.id")
    @Mapping(target = "projectCode", source = "project.code")
    @Mapping(target = "margeVenduePct", source = "project.margeNetteVendue") // baseline figée au moment du snapshot côté projet
    @Mapping(target = "warnings",    expression = "java(java.util.List.of())")
    KpiResponse toResponse(SnapshotKpi snapshot);

    List<KpiResponse> toResponseList(List<SnapshotKpi> snapshots);
}
