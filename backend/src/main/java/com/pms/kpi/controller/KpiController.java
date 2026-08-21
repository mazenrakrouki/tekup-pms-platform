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

@Tag(name = "KPI", description = "Calcul live des indicateurs financiers (EAC, marge, EVM) + snapshots mensuels")
@RestController
@RequestMapping("/api/projects/{projectId}/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiService kpiService;

    @GetMapping
    public ResponseEntity<KpiResponse> live(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.computeLive(projectId));
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<KpiResponse>> snapshots(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.findSnapshots(projectId));
    }

    @PostMapping("/snapshots")
    public ResponseEntity<KpiResponse> createSnapshot(@PathVariable Long projectId,
                                                      @Valid @RequestBody(required = false) SnapshotRequest request) {
        KpiResponse snapshot = kpiService.createSnapshot(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(snapshot.snapshotId()).toUri();
        return ResponseEntity.created(location).body(snapshot);
    }
}
