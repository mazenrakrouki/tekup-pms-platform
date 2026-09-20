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

// HTTP entry door for a project's deliverables (livrables): EN_ATTENTE -> EN_COURS -> LIVRE ->
// VALIDE. Deliberately thin — permissions, transactions and the state machine all live in
// LivrableService. demarrer/livrer/valider are separate PATCH routes (statut isn't a field of
// LivrableRequest) precisely so a caller can't jump straight to VALIDE in a PUT body.
@Tag(name = "Gouvernance — Livrables", description = "Suivi des livrables + machine à états (EN_ATTENTE → LIVRE → VALIDE)")
@RestController
@RequestMapping("/api/projects/{projectId}/livrables")
@RequiredArgsConstructor
public class LivrableController {

    private final LivrableService livrableService;

    /** Every live deliverable of one project. */
    @GetMapping
    public ResponseEntity<List<LivrableResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(livrableService.findByProject(projectId));
    }

    /** Adds a deliverable (always starts EN_ATTENTE); 201 + Location header. */
    @PostMapping
    public ResponseEntity<LivrableResponse> create(@PathVariable Long projectId,
                                                    @Valid @RequestBody LivrableRequest request) {
        LivrableResponse created = livrableService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces titre/description/dateEcheance. Refused once VALIDE (service-side, 422). Not used
     * by the Angular screen today, but stays part of the API/permission matrix.
     */
    @PutMapping("/{id}")
    public ResponseEntity<LivrableResponse> update(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody LivrableRequest request) {
        return ResponseEntity.ok(livrableService.update(projectId, id, request));
    }

    /** EN_ATTENTE -> EN_COURS. */
    @PatchMapping("/{id}/demarrer")
    public ResponseEntity<LivrableResponse> demarrer(@PathVariable Long projectId,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.demarrer(projectId, id));
    }

    /** -> LIVRE (allowed from any status except VALIDE; EN_ATTENTE may skip straight to LIVRE). */
    @PatchMapping("/{id}/livrer")
    public ResponseEntity<LivrableResponse> livrer(@PathVariable Long projectId,
                                                    @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.livrer(projectId, id));
    }

    /** LIVRE -> VALIDE, the final one-way state: once set, update/delete/livrer are all refused. */
    @PatchMapping("/{id}/valider")
    public ResponseEntity<LivrableResponse> valider(@PathVariable Long projectId,
                                                     @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.valider(projectId, id));
    }

    /** Soft-deletes a deliverable; refused once VALIDE. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        livrableService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
