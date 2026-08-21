package com.pms.user.controller;

import com.pms.user.dto.ResourceRequest;
import com.pms.user.dto.ResourceResponse;
import com.pms.user.dto.TccAnnuelDto;
import com.pms.user.service.ResourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Tag(name = "Ressources", description = "CRUD ressources humaines, tarifs journaliers et historique TCC annuel")
@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @GetMapping
    public ResponseEntity<List<ResourceResponse>> list() {
        return ResponseEntity.ok(resourceService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody ResourceRequest request) {
        ResourceResponse created = resourceService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.ok(resourceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── TCC par année ─────────────────────────────────────────────

    @GetMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> tccAnnuels(@PathVariable Long id) {
        return ResponseEntity.ok(resourceService.findTccAnnuels(id));
    }

    @PutMapping("/{id}/tcc")
    public ResponseEntity<List<TccAnnuelDto>> replaceTccAnnuels(@PathVariable Long id,
                                                                @RequestBody List<@Valid TccAnnuelDto> rates) {
        return ResponseEntity.ok(resourceService.replaceTccAnnuels(id, rates));
    }
}
