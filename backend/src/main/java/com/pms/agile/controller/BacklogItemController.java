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

@Tag(name = "Agile — Backlog", description = "Backlog produit et affectation des éléments aux sprints")
@RestController
@RequestMapping("/api/projects/{projectId}/backlog")
@RequiredArgsConstructor
public class BacklogItemController {

    private final BacklogItemService backlogItemService;

    @GetMapping
    public ResponseEntity<List<BacklogItemResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(backlogItemService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<BacklogItemResponse> create(@PathVariable Long projectId,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        BacklogItemResponse created = backlogItemService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BacklogItemResponse> update(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        return ResponseEntity.ok(backlogItemService.update(projectId, id, request));
    }

    /** Déplacement d'une carte sur le tableau, sans renvoyer l'élément complet. */
    @PatchMapping("/{id}/move")
    public ResponseEntity<BacklogItemResponse> move(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody BacklogItemMoveRequest request) {
        return ResponseEntity.ok(backlogItemService.move(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        backlogItemService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
