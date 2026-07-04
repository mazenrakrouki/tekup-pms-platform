package com.pms.governance.controller;

import com.pms.governance.dto.RiskRequest;
import com.pms.governance.dto.RiskResponse;
import com.pms.governance.service.RiskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/risks")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    @GetMapping
    public ResponseEntity<List<RiskResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(riskService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<RiskResponse> create(@PathVariable Long projectId,
                                                @Valid @RequestBody RiskRequest request) {
        RiskResponse created = riskService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RiskResponse> update(@PathVariable Long projectId,
                                                @PathVariable Long id,
                                                @Valid @RequestBody RiskRequest request) {
        return ResponseEntity.ok(riskService.update(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        riskService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
