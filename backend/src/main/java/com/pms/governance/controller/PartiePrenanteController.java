package com.pms.governance.controller;

import com.pms.governance.dto.PartiePrenanteRequest;
import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.service.PartiePrenanteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// HTTP entry door for a project's stakeholder register (parties prenantes): who affects or is
// affected by the project, placed on an influence/interet grid (both reuse the NiveauRisque
// enum so the whole governance module shares the same three levels and colours). Deliberately
// thin — permissions and transactions live in PartiePrenanteService. Simplest of the four
// governance controllers: a stakeholder has no state machine, so there is no PATCH route here.
@Tag(name = "Gouvernance — Parties Prenantes", description = "Registre des parties prenantes : influence, intérêt, stratégie")
@RestController
@RequestMapping("/api/projects/{projectId}/parties-prenantes")
@RequiredArgsConstructor
public class PartiePrenanteController {

    private final PartiePrenanteService ppService;

    /** Whole stakeholder register of one project. */
    @GetMapping
    public ResponseEntity<List<PartiePrenanteResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(ppService.findByProject(projectId));
    }

    /** Adds a stakeholder; 201 + Location header. */
    @PostMapping
    public ResponseEntity<PartiePrenanteResponse> create(@PathVariable Long projectId,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        PartiePrenanteResponse created = ppService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Replaces all editable fields (nom, fonction, email, telephone, influence, interet). Not
     * used by the Angular screen today, but stays part of the API/permission matrix.
     */
    @PutMapping("/{id}")
    public ResponseEntity<PartiePrenanteResponse> update(@PathVariable Long projectId,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        return ResponseEntity.ok(ppService.update(projectId, id, request));
    }

    /** Soft-deletes a stakeholder; no business rule blocks it (people do leave a project). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        ppService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
