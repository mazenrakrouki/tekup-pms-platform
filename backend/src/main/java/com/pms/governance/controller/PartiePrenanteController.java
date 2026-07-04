package com.pms.governance.controller;

import com.pms.governance.dto.PartiePrenanteRequest;
import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.service.PartiePrenanteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/parties-prenantes")
@RequiredArgsConstructor
public class PartiePrenanteController {

    private final PartiePrenanteService ppService;

    @GetMapping
    public ResponseEntity<List<PartiePrenanteResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(ppService.findByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<PartiePrenanteResponse> create(@PathVariable Long projectId,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        PartiePrenanteResponse created = ppService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PartiePrenanteResponse> update(@PathVariable Long projectId,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        return ResponseEntity.ok(ppService.update(projectId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        ppService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
