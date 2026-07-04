package com.pms.billing.controller;

import com.pms.billing.dto.*;
import com.pms.billing.service.AvenantService;
import com.pms.billing.service.JalonService;
import com.pms.billing.service.PaiementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class BillingController {

    private final JalonService    jalonService;
    private final PaiementService paiementService;
    private final AvenantService  avenantService;

    // ── Jalons ───────────────────────────────────────────────────

    @GetMapping("/jalons")
    public ResponseEntity<List<JalonResponse>> listJalons(@PathVariable Long projectId) {
        return ResponseEntity.ok(jalonService.findByProject(projectId));
    }

    @PostMapping("/jalons")
    public ResponseEntity<JalonResponse> createJalon(@PathVariable Long projectId,
                                                      @Valid @RequestBody JalonRequest request) {
        JalonResponse created = jalonService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/jalons/{id}")
    public ResponseEntity<JalonResponse> updateJalon(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody JalonRequest request) {
        return ResponseEntity.ok(jalonService.update(projectId, id, request));
    }

    @PatchMapping("/jalons/{id}/facturer")
    public ResponseEntity<JalonResponse> facturer(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody FacturerRequest request) {
        return ResponseEntity.ok(jalonService.facturer(projectId, id, request));
    }

    @DeleteMapping("/jalons/{id}")
    public ResponseEntity<Void> deleteJalon(@PathVariable Long projectId, @PathVariable Long id) {
        jalonService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Paiements ─────────────────────────────────────────────────

    @GetMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<List<PaiementResponse>> listPaiements(@PathVariable Long projectId,
                                                                  @PathVariable Long jalonId) {
        return ResponseEntity.ok(paiementService.findByJalon(projectId, jalonId));
    }

    @PostMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<PaiementResponse> createPaiement(@PathVariable Long projectId,
                                                             @PathVariable Long jalonId,
                                                             @Valid @RequestBody PaiementRequest request) {
        PaiementResponse created = paiementService.create(projectId, jalonId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    @DeleteMapping("/jalons/{jalonId}/paiements/{id}")
    public ResponseEntity<Void> deletePaiement(@PathVariable Long projectId,
                                                @PathVariable Long jalonId,
                                                @PathVariable Long id) {
        paiementService.delete(projectId, jalonId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Avenants ──────────────────────────────────────────────────

    @GetMapping("/avenants")
    public ResponseEntity<List<AvenantResponse>> listAvenants(@PathVariable Long projectId) {
        return ResponseEntity.ok(avenantService.findByProject(projectId));
    }

    @PostMapping("/avenants")
    public ResponseEntity<AvenantResponse> createAvenant(@PathVariable Long projectId,
                                                          @Valid @RequestBody AvenantRequest request) {
        AvenantResponse created = avenantService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    @DeleteMapping("/avenants/{id}")
    public ResponseEntity<Void> deleteAvenant(@PathVariable Long projectId, @PathVariable Long id) {
        avenantService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
