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

@Tag(name = "Gouvernance — Demandes de Changement", description = "Registre des demandes de changement (EN_ATTENTE → APPROUVE/REJETE)")
@RestController
@RequestMapping("/api/projects/{projectId}/demandes-changement")
@RequiredArgsConstructor
public class DemandeChangementController {

    private final DemandeChangementService dcService;

    @GetMapping
    public ResponseEntity<List<DemandeChangementResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(dcService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<DemandeChangementResponse> create(@PathVariable Long projectId,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        DemandeChangementResponse created = dcService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DemandeChangementResponse> update(@PathVariable Long projectId,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        return ResponseEntity.ok(dcService.update(projectId, id, request));
    }

    @PatchMapping("/{id}/approuver")
    public ResponseEntity<DemandeChangementResponse> approuver(@PathVariable Long projectId,
                                                                @PathVariable Long id) {
        return ResponseEntity.ok(dcService.approuver(projectId, id));
    }

    @PatchMapping("/{id}/rejeter")
    public ResponseEntity<DemandeChangementResponse> rejeter(@PathVariable Long projectId,
                                                              @PathVariable Long id) {
        return ResponseEntity.ok(dcService.rejeter(projectId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        dcService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
