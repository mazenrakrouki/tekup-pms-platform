package com.pms.governance.controller;

import com.pms.governance.dto.RiskRequest;
import com.pms.governance.dto.RiskResponse;
import com.pms.governance.service.RiskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// HTTP entry door for a project's risk register: probabilite, impact, planMitigation, statut.
// Deliberately thin — permissions and transactions live in RiskService. A risk has no state
// machine (any status can be set at any time), unlike livrables or demandes de changement.
@Tag(name = "Gouvernance — Risques", description = "Registre des risques : probabilité, sévérité, traitement")
@RestController
@RequestMapping("/api/projects/{projectId}/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    /** Whole risk register of one project, newest first. */
    @GetMapping
    public ResponseEntity<List<RiskResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(riskService.findByProject(projectId));
    }

    /** Adds a risk; 201 + Location header. */
    @PostMapping
    public ResponseEntity<RiskResponse> create(@PathVariable Long projectId,
                                                @Valid @RequestBody RiskRequest request) {
        RiskResponse created = riskService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces all editable fields (description, probabilite, impact, planMitigation, statut).
     * Not used by the Angular screen today, but stays part of the API/permission matrix.
     */
    @PutMapping("/{id}")
    public ResponseEntity<RiskResponse> update(@PathVariable Long projectId,
                                                @PathVariable Long id,
                                                @Valid @RequestBody RiskRequest request) {
        return ResponseEntity.ok(riskService.update(projectId, id, request));
    }

    /** Soft-deletes a risk. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        riskService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
