package com.pms.governance.controller;

import com.pms.governance.dto.LivrableRequest;
import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.service.LivrableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Gouvernance — Livrables", description = "Suivi des livrables + machine à états (EN_ATTENTE → LIVRE → VALIDE)")
@RestController
@RequestMapping("/api/projects/{projectId}/livrables")
@RequiredArgsConstructor
public class LivrableController {

    private final LivrableService livrableService;

    @GetMapping
    public ResponseEntity<List<LivrableResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(livrableService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<LivrableResponse> create(@PathVariable Long projectId,
                                                    @Valid @RequestBody LivrableRequest request) {
        LivrableResponse created = livrableService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<LivrableResponse> update(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody LivrableRequest request) {
        return ResponseEntity.ok(livrableService.update(projectId, id, request));
    }

    @PatchMapping("/{id}/demarrer")
    public ResponseEntity<LivrableResponse> demarrer(@PathVariable Long projectId,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.demarrer(projectId, id));
    }

    @PatchMapping("/{id}/livrer")
    public ResponseEntity<LivrableResponse> livrer(@PathVariable Long projectId,
                                                    @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.livrer(projectId, id));
    }

    @PatchMapping("/{id}/valider")
    public ResponseEntity<LivrableResponse> valider(@PathVariable Long projectId,
                                                     @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.valider(projectId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        livrableService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
