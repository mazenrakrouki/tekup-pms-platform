package com.pms.agile.controller;

import com.pms.agile.dto.BacklogItemMoveRequest;
import com.pms.agile.dto.BacklogItemRequest;
import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.service.BacklogItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST entry point for a project's product backlog (the items the Agile board draws in its
 * columns). No permission or business rule lives here: hasAuthority checks (VIEW_AGILE /
 * MANAGE_AGILE) sit on BacklogItemService so no other caller can bypass them.
 * Sister class: SprintController, for the iterations these items attach to.
 */
// @Tag groups these endpoints under one heading in the generated Swagger page (French, like
// every @Tag in the backend).
@Tag(name = "Agile — Backlog", description = "Backlog produit et affectation des éléments aux sprints")
@RestController
// {projectId} is matched by ProjectScopeInterceptor (ADR-021) against /api/projects/{id}/**,
// so a user with MANAGE_AGILE but out of scope for this project is rejected before this class runs.
@RequestMapping("/api/projects/{projectId}/backlog")
@RequiredArgsConstructor
public class BacklogItemController {

    private final BacklogItemService backlogItemService;

    /**
     * GET /api/projects/{projectId}/backlog — all non-deleted items of the project, in
     * creation order. A plain list, not paged: the backlog stays small and the board needs
     * every item at once to fill its columns.
     */
    @GetMapping
    public ResponseEntity<List<BacklogItemResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(backlogItemService.findByProject(projectId));
    }

    /**
     * POST /api/projects/{projectId}/backlog — creates one item, answers 201 with the saved
     * item and its URL in the Location header.
     */
    // @Valid runs the BacklogItemRequest constraints (title, priority, status, estimateDays)
    // before the method body; GlobalExceptionHandler turns a failure into 400.
    // @RequestBody uses a DTO rather than the entity so a caller can't smuggle in fields like
    // "project": {"id": 9} to move an item into another project.
    @PostMapping
    public ResponseEntity<BacklogItemResponse> create(@PathVariable Long projectId,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        // MANAGE_AGILE and the sprint/assignee-belongs-to-project checks happen inside create().
        BacklogItemResponse created = backlogItemService.create(projectId, request);
        // Builds the new item's URL from the current request so scheme/host/port/context-path
        // stay correct behind a prefix or in production.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/backlog/{id} — replaces the whole item and answers 200.
     * Full-body PUT so the server never has to guess whether a missing field means "unchanged".
     */
    // projectId and id are both checked by the service: an item whose actual project isn't
    // projectId is refused, closing the gap the ADR-021 scope check alone wouldn't catch.
    @PutMapping("/{id}")
    public ResponseEntity<BacklogItemResponse> update(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        return ResponseEntity.ok(backlogItemService.update(projectId, id, request));
    }

    /**
     * PATCH /api/projects/{projectId}/backlog/{id}/move — board drag-and-drop: new status and
     * optionally new sprint, without resending title/description/estimate (which could
     * overwrite a concurrent edit).
     */
    // sprintId is allowed to be null on purpose: null means "back to the product backlog".
    @PatchMapping("/{id}/move")
    public ResponseEntity<BacklogItemResponse> move(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody BacklogItemMoveRequest request) {
        return ResponseEntity.ok(backlogItemService.move(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/backlog/{id} — soft delete (deleted = true, row kept)
     * so history and any references to the item survive. Answers 204 No Content.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        backlogItemService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
