package com.pms.workload.controller;

import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.dto.PlanChargeRequest;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.service.ChargeReelleService;
import com.pms.workload.service.PlanChargeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Tag(name = "Charges de travail", description = "Plan de charge mensuel + charges réelles (soumettre / valider)")
@RestController
@RequiredArgsConstructor
public class WorkloadController {

    private final PlanChargeService planChargeService;
    private final ChargeReelleService chargeReelleService;

    // ── Charges planifiées ────────────────────────────────────────

    @GetMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<Page<PlanChargeResponse>> listPlanCharges(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(planChargeService.findByProject(projectId, pageable));
    }

    @PostMapping("/api/projects/{projectId}/plan-charges")
    public ResponseEntity<PlanChargeResponse> createPlanCharge(@PathVariable Long projectId,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        PlanChargeResponse created = planChargeService.create(projectId, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<PlanChargeResponse> updatePlanCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody PlanChargeRequest request) {
        return ResponseEntity.ok(planChargeService.update(projectId, id, request));
    }

    @DeleteMapping("/api/projects/{projectId}/plan-charges/{id}")
    public ResponseEntity<Void> deletePlanCharge(@PathVariable Long projectId, @PathVariable Long id) {
        planChargeService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Charges réelles ───────────────────────────────────────────

    @GetMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<Page<ChargeReelleResponse>> listChargesReelles(
            @PathVariable Long projectId,
            @PageableDefault(size = 20, sort = "period", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(chargeReelleService.findByProject(projectId, pageable));
    }

    @PostMapping("/api/projects/{projectId}/charges-reelles")
    public ResponseEntity<ChargeReelleResponse> submitCharge(@PathVariable Long projectId,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        ChargeReelleResponse created = chargeReelleService.submit(projectId, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<ChargeReelleResponse> updateCharge(@PathVariable Long projectId,
                                                              @PathVariable Long id,
                                                              @Valid @RequestBody ChargeReelleRequest request) {
        return ResponseEntity.ok(chargeReelleService.update(projectId, id, request));
    }

    @PatchMapping("/api/projects/{projectId}/charges-reelles/{id}/validate")
    public ResponseEntity<ChargeReelleResponse> validateCharge(@PathVariable Long projectId,
                                                                @PathVariable Long id,
                                                                @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(chargeReelleService.validate(projectId, id, email));
    }

    @DeleteMapping("/api/projects/{projectId}/charges-reelles/{id}")
    public ResponseEntity<Void> deleteCharge(@PathVariable Long projectId, @PathVariable Long id) {
        chargeReelleService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
