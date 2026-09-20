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

/**
 * REST entry point for a project's sprints (iterations): name, goal, start/end dates, status.
 * No permission or business rule lives here: hasAuthority checks (VIEW_AGILE / MANAGE_AGILE)
 * sit on SprintService. The sprint ids returned here are what BacklogItemController.move/update
 * accept as sprintId; deleting a sprint detaches its items back to the product backlog first.
 */
// @Tag groups these endpoints under one heading in the generated Swagger page (French, like
// every @Tag in the backend).
@Tag(name = "Agile — Sprints", description = "Itérations d'un projet : objectif, dates, statut")
@RestController
// {projectId} is matched by ProjectScopeInterceptor (ADR-021) against /api/projects/{id}/**,
// so a user with MANAGE_AGILE but out of scope for this project is rejected before this class runs.
@RequestMapping("/api/projects/{projectId}/sprints")
@RequiredArgsConstructor
public class SprintController {

    private final SprintService sprintService;

    /**
     * GET /api/projects/{projectId}/sprints — all non-deleted sprints of the project, ordered
     * by start date so the board reads left to right in time.
     */
    @GetMapping
    public ResponseEntity<List<SprintResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(sprintService.findByProject(projectId));
    }

    /**
     * POST /api/projects/{projectId}/sprints — creates one sprint, answers 201 with the saved
     * sprint and its URL in the Location header.
     */
    // @Valid checks each SprintRequest field on its own (name, startDate, endDate, status not
    // null); the cross-field rule "end not before start" is in SprintService.validateDates,
    // which answers 422 instead of 400. DTO, not the entity, so a caller can't smuggle in
    // "project": {"id": 9}.
    @PostMapping
    public ResponseEntity<SprintResponse> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody SprintRequest request) {
        SprintResponse created = sprintService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/sprints/{id} — replaces the whole sprint and answers 200.
     * Full-body PUT so clearing the goal is an explicit action, not a guessed omission.
     */
    // projectId and id are both checked by the service: a sprint whose actual project isn't
    // projectId is refused, closing the gap the ADR-021 scope check alone wouldn't catch.
    @PutMapping("/{id}")
    public ResponseEntity<SprintResponse> update(@PathVariable Long projectId,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody SprintRequest request) {
        return ResponseEntity.ok(sprintService.update(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/sprints/{id} — soft deletes the sprint. In one
     * transaction, its items are detached to the product backlog first so nothing is left
     * pointing at a sprint that no longer shows on the board. Answers 204 No Content.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        sprintService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
