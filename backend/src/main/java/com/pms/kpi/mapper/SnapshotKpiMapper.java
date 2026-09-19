package com.pms.kpi.mapper;

// =====================================================================================
// FILE: SnapshotKpiMapper.java
//
// WHAT THIS FILE IS
//   A MapStruct mapper. MapStruct is a library that writes the boring "copy field by
//   field" code for us, at build time. From this empty interface it generates a real
//   class named SnapshotKpiMapperImpl. That class turns one saved KPI snapshot row
//   (SnapshotKpi, table snapshot_kpis) into the KpiResponse object that the REST API
//   sends to the Angular front end. KpiResponse is a DTO: a Data Transfer Object, a
//   plain object whose only job is to carry data out of the API as JSON.
//
// WHERE IT SITS IN THE FLOW
//   Angular
//     -> KpiController          GET  /api/projects/{projectId}/kpi/snapshots
//                               POST /api/projects/{projectId}/kpi/snapshots
//     -> KpiService             findSnapshots() and createSnapshot()
//     -> SnapshotKpiRepository  reads or saves rows of snapshot_kpis
//     -> THIS MAPPER            SnapshotKpi entity  ->  KpiResponse DTO
//     -> JSON answer
//   KpiService is the only caller. It receives this interface in its constructor (the
//   "snapshotKpiMapper" field), and Spring gives it the generated implementation.
//   Note that the live KPI path does NOT pass here: KpiService.buildKpi() builds its
//   KpiResponse by hand, because nothing is stored in the database in that case.
//
// WHY IT EXISTS - what would break without it
//   1. The entity and the DTO do not have the same shape. The entity carries "id", the
//      DTO wants "snapshotId". The entity points to a whole Project object, the DTO
//      wants only projectId and projectCode. And the DTO has two fields that no column
//      of snapshot_kpis stores at all (margeVenduePct and warnings). Something has to
//      bridge that gap, and this file is that bridge.
//   2. Without it, KpiService would have to call the 23-argument KpiResponse
//      constructor by hand, in two different methods. Two BigDecimal arguments swapped
//      by mistake (for example "marge" put where "eac" is expected) still compiles, and
//      the screen would show a wrong amount of money with no error anywhere.
//   3. Sending the entity straight to JSON is not an option either: it would pull the
//      whole Project object into the answer and expose internal columns such as
//      deleted, createdBy and updatedBy, which the front end must never see.
// =====================================================================================

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.entity.SnapshotKpi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Converts saved KPI snapshots into the KpiResponse records returned by the KPI API.
 *
 * WHY AN INTERFACE WITH NO CODE INSIDE, INSTEAD OF A NORMAL CLASS
 *   The rules below are read at compile time and the copy code is generated once, so the
 *   mapping is checked by the build. If a field named in a @Mapping rule below is renamed
 *   (for example project.code), the build fails right away with a clear message. For the
 *   fields that are copied automatically, a rename gives a build WARNING saying the target
 *   field is not mapped - that is MapStruct's default setting - so the warnings of the
 *   build must be read. Hand-written copy code would say nothing at all.
 *
 * WHY THE TARGET IS A RECORD
 *   KpiResponse is a Java record, so it has no setters. MapStruct handles that by
 *   calling its constructor with every value at once, which is also the reason the
 *   returned object can never be half-filled.
 */
// @Mapper asks the MapStruct annotation processor to generate SnapshotKpiMapperImpl when
// the project is compiled.
// componentModel = "spring" makes that generated class a Spring @Component, so Spring can
// inject it into KpiService. Why it matters: without this setting the class is still
// generated, but it is not a Spring bean, and the application refuses to start with
// "No qualifying bean of type SnapshotKpiMapper" as soon as KpiService is created.
@Mapper(componentModel = "spring")
public interface SnapshotKpiMapper {

    /**
     * Turns one stored snapshot row into the KpiResponse sent back as JSON.
     *
     * Gives back a fully built KpiResponse. Called by KpiService.findSnapshots() (for
     * each row of the history) and by KpiService.createSnapshot() (for the row that was
     * just saved, so the answer of the POST already carries the new id).
     *
     * Only five rules are listed below. Every other field (snapshotDate, budgetPlanifie,
     * budgetConsome, eac, marge, tauxConsommation, evPct, deliveryPct, consommeJh,
     * rafJh, deriveJh, caProduction, totalFacture, fae, margeActuelle, margeActuellePct,
     * dateFinEstimee, faitsMarquants) carries exactly the same name in the entity and in
     * the DTO, so MapStruct copies it on its own. Listing them again would be noise that
     * can go out of date.
     */
    // WHAT: fills the DTO field "snapshotId" from the entity field "id" (inherited from
    // BaseEntity). WHY: the two names are different, so MapStruct cannot guess the link.
    // WITHOUT IT: snapshotId stays null; KpiController builds the Location header of the
    // POST from snapshot.snapshotId(), so that header would end with "/null", and the
    // front end could no longer tell one snapshot of the history from another.
    // Side note: a null snapshotId is meaningful elsewhere. KpiService.buildKpi() passes
    // null there for the live calculation, so "snapshotId is null" means "computed now,
    // not stored".
    @Mapping(target = "snapshotId",  source = "id")
    // WHAT: reads project.getId() through the ManyToOne link and puts it in projectId.
    // This is called flattening: a value inside a nested object is turned into one flat
    // field. WHY: the front end only needs the number to build its links; it must not
    // receive the Project object itself.
    // WITHOUT IT: the DTO would have to hold a whole Project, and the JSON answer would
    // carry that project with all its own columns and links - heavy, and it exposes data
    // that has nothing to do with the KPI screen.
    @Mapping(target = "projectId",   source = "project.id")
    // WHAT: same flattening for the readable project code, for example "PRJ-2026-014".
    // WHY: the KPI screen shows that code in its title; without it the page would have
    // to make a second HTTP call just to display a label.
    // NOTE ON PERFORMANCE: project is mapped LAZY in SnapshotKpi, so reading it here
    // touches the database. SnapshotKpiRepository.findActiveByProjectId already writes
    // "JOIN FETCH k.project", which brings the project back in the same SELECT. Without
    // that fetch join, a history of 12 snapshots would fire 12 extra SELECT statements,
    // one per row - the classic N+1 query problem.
    @Mapping(target = "projectCode", source = "project.code")
    // WHAT: fills margeVenduePct from the project column margeNetteVendue. This is the
    // sold margin kept as the baseline, the figure the current margin is compared to.
    // It is a ratio, not a number out of 100: 0.4412 means 44.12 %.
    // WHY IT COMES FROM THE PROJECT: the table snapshot_kpis has no column for it, so
    // there is nowhere else to read it from.
    // BE CAREFUL, AND SAY THIS TO THE JURY THE RIGHT WAY: the value is read at the
    // moment the snapshot is returned, not at the moment the snapshot was taken.
    // Example: a snapshot of January is opened today; if somebody edited the sold margin
    // of the project in March, that January snapshot now shows the March baseline.
    // SECOND POINT: the live calculation in KpiService.buildKpi() prefers the margin
    // computed from the DI (Devis Interne, the internal quote) and falls back on
    // project.margeNetteVendue only when no DI exists. This mapper never looks at the
    // DI. So on a project that has a DI, a stored snapshot and the live KPI can show two
    // different baselines.
    @Mapping(target = "margeVenduePct", source = "project.margeNetteVendue")
    // WHAT: puts a fixed empty list in "warnings" instead of reading anything from the
    // entity. WHY: warnings are produced only while the KPI is being computed (H-3:
    // users with no daily rate, whose cost is silently counted as 0; plus the message
    // shown when the EV percentage was never entered). A stored snapshot keeps no such
    // column, so there is nothing to replay.
    // WHY AN EMPTY LIST AND NOT NULL: the Angular side loops over warnings and reads
    // warnings.length without checking first. With null it would crash the KPI page;
    // with List.of() it simply shows nothing. List.of() is also immutable, so no caller
    // can add a warning to a snapshot afterwards and make it look like a live result.
    @Mapping(target = "warnings",    expression = "java(java.util.List.of())")
    KpiResponse toResponse(SnapshotKpi snapshot);

    /**
     * Maps a whole history of snapshots in one call, keeping the order of the given list
     * (the repository already sorts it by snapshot date, newest first).
     *
     * Gives back a new list of KpiResponse. Used by KpiService.findSnapshots().
     *
     * WHY IT IS DECLARED HERE RATHER THAN LOOPING INSIDE THE SERVICE
     *   MapStruct sees that it converts List&lt;SnapshotKpi&gt; into List&lt;KpiResponse&gt;
     *   and generates a loop that calls toResponse() on every element, so the five rules
     *   above apply to each item. If the service looped by itself instead, a second
     *   place would later need the same loop and could easily forget one of the rules -
     *   for example returning a list where projectCode is null everywhere.
     *   The generated method answers null when the given list is null; in practice the
     *   repository always returns a list, empty at worst, so the answer is never null.
     */
    List<KpiResponse> toResponseList(List<SnapshotKpi> snapshots);
}
