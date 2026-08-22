package com.pms.agile.controller;

import com.pms.agile.dto.SprintRequest;
import com.pms.agile.dto.SprintResponse;
import com.pms.agile.service.SprintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Agile — Sprints", description = "Itérations d'un projet : objectif, dates, statut")
@RestController
@RequestMapping("/api/projects/{projectId}/sprints")
@RequiredArgsConstructor
public class SprintController {

    private final SprintService sprintService;

    @GetMapping
    public ResponseEntity<List<SprintResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(sprintService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<SprintResponse> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody SprintRequest request) {
        SprintResponse created = sprintService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SprintResponse> update(@PathVariable Long projectId,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody SprintRequest request) {
        return ResponseEntity.ok(sprintService.update(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        sprintService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
