package com.pms.mission.controller;

import com.pms.mission.dto.*;
import com.pms.mission.service.ComposanteService;
import com.pms.mission.service.MissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Missions", description = "Missions + composantes (per diem, billet, transport, séjour, timbre)")
@RestController
@RequestMapping("/api/projects/{projectId}/missions")
@RequiredArgsConstructor
public class MissionController {

    private final MissionService    missionService;
    private final ComposanteService composanteService;

    // ── Missions ──────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<MissionResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(missionService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<MissionResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody MissionRequest request) {
        MissionResponse created = missionService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MissionResponse> update(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody MissionRequest request) {
        return ResponseEntity.ok(missionService.update(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        missionService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Composantes de coût ───────────────────────────────────────

    @GetMapping("/{missionId}/composantes")
    public ResponseEntity<List<ComposanteResponse>> listComposantes(@PathVariable Long projectId,
                                                                      @PathVariable Long missionId) {
        return ResponseEntity.ok(composanteService.findByMission(projectId, missionId));
    }

    @PostMapping("/{missionId}/composantes")
    public ResponseEntity<ComposanteResponse> createComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @Valid @RequestBody ComposanteRequest request) {
        ComposanteResponse created = composanteService.create(projectId, missionId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    @PutMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<ComposanteResponse> updateComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody ComposanteRequest request) {
        return ResponseEntity.ok(composanteService.update(projectId, missionId, id, request));
    }

    @DeleteMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<Void> deleteComposante(@PathVariable Long projectId,
                                                  @PathVariable Long missionId,
                                                  @PathVariable Long id) {
        composanteService.delete(projectId, missionId, id);
        return ResponseEntity.noContent().build();
    }
}
