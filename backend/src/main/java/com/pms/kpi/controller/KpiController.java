package com.pms.kpi.controller;

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.dto.SnapshotRequest;
import com.pms.kpi.service.KpiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST entry point for one project's KPIs (Key Performance Indicators): the live calculation,
 * the list of snapshots already taken, and the creation of a new snapshot at the monthly review.
 *
 * GET /kpi recomputes everything from today's data (snapshotId/snapshotDate null, warnings may
 * carry messages). GET /kpi/snapshots returns frozen rows, newest first (snapshotId/snapshotDate
 * filled, warnings always empty — a stored row can't rebuild a warning from another day).
 *
 * No permission check and no formula live here: hasAuthority checks sit on KpiService
 * (VIEW_KPI to read, EDIT_PROJECT to create a snapshot), and every derived amount is computed
 * there too.
 */
// @Tag groups the three endpoints under one heading in the generated OpenAPI page.
@Tag(name = "KPI", description = "Calcul live des indicateurs financiers (EAC, marge, EVM) + snapshots mensuels")
@RestController
// {projectId} sits in the path so ProjectScopeInterceptor (ADR-021) can match /api/projects/{id}/**
// and check the caller's scope before any method here runs.
@RequestMapping("/api/projects/{projectId}/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiService kpiService;

    /**
     * GET /api/projects/{projectId}/kpi — recomputes every indicator from current data and
     * answers 200 with a KpiResponse whose snapshotId/snapshotDate are null.
     *
     * Recomputes rather than serving the last snapshot so a timesheet validated this morning is
     * visible at once. The one exception is EV %, reused from the most recent snapshot since it
     * is a human judgement, not something the database can derive.
     */
    @GetMapping
    public ResponseEntity<KpiResponse> live(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.computeLive(projectId));
    }

    /**
     * GET /api/projects/{projectId}/kpi/snapshots — returns the project's frozen indicator rows,
     * newest first, or 200 with an empty list when no review has been done yet. A plain List, not
     * a page: reviews are monthly, so the row count per project stays small.
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<KpiResponse>> snapshots(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.findSnapshots(projectId));
    }

    /**
     * POST /api/projects/{projectId}/kpi/snapshots — freezes today's indicators into a new
     * SnapshotKpi row and answers 201 Created. This is the monthly review gesture of F-AFF-13
     * ("Situation actuelle"): the manager gives the EV %, the estimated end date and the notable
     * facts, and the engine stores them next to the amounts computed at that instant.
     *
     * Gated on EDIT_PROJECT, not VIEW_KPI, since it changes the official history of the project.
     * @Valid enforces evPct 0–100 and faitsMarquants ≤ 2000 chars before the body runs.
     * @RequestBody(required = false) makes the body optional: an empty POST means "freeze today's
     * figures with the EV we already know", and KpiService reuses the previous snapshot's EV.
     * A second snapshot on the same day is refused by the service with 409 Conflict.
     */
    @PostMapping("/snapshots")
    public ResponseEntity<KpiResponse> createSnapshot(@PathVariable Long projectId,
                                                      @Valid @RequestBody(required = false) SnapshotRequest request) {
        KpiResponse snapshot = kpiService.createSnapshot(projectId, request);
        // Builds the created row's address from the current request (scheme/host/port), so it is
        // correct on any environment instead of hard-coding a host.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(snapshot.snapshotId()).toUri();
        // 201 + Location signals "created, and here it is", body included so no second round trip
        // is needed.
        return ResponseEntity.created(location).body(snapshot);
    }
}
