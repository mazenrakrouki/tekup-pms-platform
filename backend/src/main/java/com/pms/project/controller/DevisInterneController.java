package com.pms.project.controller;

import com.pms.project.dto.DevisInterneResponse;
import com.pms.project.dto.LigneDiRequest;
import com.pms.project.service.DevisInterneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Devis Interne", description = "Structure DI vide + calcul marges (accès MANAGE_DI — Directeur uniquement)")
@RestController
@RequestMapping("/api/projects/{projectId}/devis-interne")
@RequiredArgsConstructor
public class DevisInterneController {

    private final DevisInterneService devisInterneService;

    @GetMapping
    public ResponseEntity<DevisInterneResponse> get(@PathVariable Long projectId) {
        return ResponseEntity.ok(devisInterneService.getDevisInterne(projectId));
    }

    @PostMapping("/lignes")
    public ResponseEntity<DevisInterneResponse> addLigne(@PathVariable Long projectId,
                                                         @Valid @RequestBody LigneDiRequest request) {
        return ResponseEntity.ok(devisInterneService.addLigne(projectId, request));
    }

    @PutMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> updateLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId,
                                                            @Valid @RequestBody LigneDiRequest request) {
        return ResponseEntity.ok(devisInterneService.updateLigne(projectId, ligneId, request));
    }

    @DeleteMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> deleteLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId) {
        return ResponseEntity.ok(devisInterneService.deleteLigne(projectId, ligneId));
    }
}
