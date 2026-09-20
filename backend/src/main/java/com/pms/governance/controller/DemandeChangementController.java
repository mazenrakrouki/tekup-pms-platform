package com.pms.governance.controller;

import com.pms.governance.dto.DemandeChangementRequest;
import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.service.DemandeChangementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// HTTP entry door for a project's change requests (demandes de changement): EN_ATTENTE ->
// APPROUVE/REJETE. Deliberately thin — permissions, transactions and the decision rules all
// live in DemandeChangementService, never here, so any other caller of that service is guarded
// too. approuver/rejeter are separate PATCH routes (not fields in the PUT body) precisely so a
// requester can't approve their own request by sending {"statut":"APPROUVE"}.
@Tag(name = "Gouvernance — Demandes de Changement", description = "Registre des demandes de changement (EN_ATTENTE → APPROUVE/REJETE)")
@RestController
@RequestMapping("/api/projects/{projectId}/demandes-changement")
@RequiredArgsConstructor
public class DemandeChangementController {

    private final DemandeChangementService dcService;

    /** Every live change request of one project. */
    @GetMapping
    public ResponseEntity<List<DemandeChangementResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(dcService.findByProject(projectId));
    }

    /** Opens a change request (always starts EN_ATTENTE); 201 + Location header. */
    @PostMapping
    public ResponseEntity<DemandeChangementResponse> create(@PathVariable Long projectId,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        DemandeChangementResponse created = dcService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces the editable fields of a request. Refused once it has been decided (service-side,
     * 422). Not used by the Angular screen today, but stays part of the API/permission matrix.
     */
    @PutMapping("/{id}")
    public ResponseEntity<DemandeChangementResponse> update(@PathVariable Long projectId,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        return ResponseEntity.ok(dcService.update(projectId, id, request));
    }

    /** Accepts a request still EN_ATTENTE; the service sets statut and dateDecision. */
    @PatchMapping("/{id}/approuver")
    public ResponseEntity<DemandeChangementResponse> approuver(@PathVariable Long projectId,
                                                                @PathVariable Long id) {
        return ResponseEntity.ok(dcService.approuver(projectId, id));
    }

    /** Refuses a request still EN_ATTENTE; mirror of approuver. */
    @PatchMapping("/{id}/rejeter")
    public ResponseEntity<DemandeChangementResponse> rejeter(@PathVariable Long projectId,
                                                              @PathVariable Long id) {
        return ResponseEntity.ok(dcService.rejeter(projectId, id));
    }

    /** Soft-deletes a request; refused once it has been decided. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        dcService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
