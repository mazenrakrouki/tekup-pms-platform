package com.pms.kpi.controller;

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

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
    public ResponseEntity<KpiResponse> createSnapshot(@PathVariable Long projectId) {
        KpiResponse snapshot = kpiService.createSnapshot(projectId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(snapshot.snapshotId()).toUri();
        return ResponseEntity.created(location).body(snapshot);
    }
}
