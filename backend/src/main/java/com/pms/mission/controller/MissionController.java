package com.pms.mission.controller;

import com.pms.mission.dto.*;
import com.pms.mission.service.ComposanteService;
import com.pms.mission.service.MissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * The HTTP door into the mission module: work trips and their cost lines (composantes: per
 * diem, ticket, stamp, transport, stay). Permission checks (VIEW_MISSION/MANAGE_MISSION) stay
 * on the services, not here, so every caller is covered; ProjectScopeInterceptor (ADR-021)
 * enforces project scope before this class runs.
 */
// Names and describes this endpoint group on the generated Swagger page.
@Tag(name = "Missions", description = "Missions + composantes (per diem, billet, transport, séjour, timbre)")
// @ResponseBody (bundled in @RestController) serializes return values as JSON instead of
// resolving a view name.
@RestController
// projectId sits in the path so ProjectScopeInterceptor can match /api/projects/{id}/** and
// enforce ADR-021 scope for the whole module.
@RequestMapping("/api/projects/{projectId}/missions")
// Lombok constructor injection: fields stay final, and a unit test can build this with mocks.
@RequiredArgsConstructor
public class MissionController {

    // Thin controller: permission checks, transactions and rules live in these two services.
    private final MissionService    missionService;
    private final ComposanteService composanteService;

    // ── Missions ──────────────────────────────────────────────────

    /**
     * GET: missions of a project that are not soft-deleted. The UC-21 own-vs-all filtering is
     * applied inside MissionService, not here, so every caller gets it.
     */
    @GetMapping
    public ResponseEntity<List<MissionResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(missionService.findByProject(projectId));
    }

    /**
     * POST: creates a mission in the project. 201 Created with a Location header for the new
     * row. Validation and business errors are mapped by GlobalExceptionHandler (400/404/403/422).
     */
    @PostMapping
    // @Valid runs MissionRequest's Bean Validation rules before the method body starts;
    // @RequestBody binds the JSON body to the record.
    public ResponseEntity<MissionResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody MissionRequest request) {
        MissionResponse created = missionService.create(projectId, request);
        // Builds the new mission's URL from the current request so it stays correct behind a
        // reverse proxy and across environments.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT: replaces a mission's changeable fields, 200 with the updated mission. Full-body PUT
     * (not PATCH) avoids ambiguity between "field absent" and "field cleared"; the service
     * re-checks that the mission belongs to projectId.
     */
    @PutMapping("/{id}")
    public ResponseEntity<MissionResponse> update(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody MissionRequest request) {
        return ResponseEntity.ok(missionService.update(projectId, id, request));
    }

    /**
     * DELETE: soft-deletes a mission, 204 No Content. A mission is part of project cost
     * history, so it is flagged, never erased.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        missionService.delete(projectId, id);
        // ResponseEntity<Void>: the compiler refuses a body added here later by mistake.
        return ResponseEntity.noContent().build();
    }

    // ── Composantes de coût ───────────────────────────────────────
    // Cost lines of a mission, under the mission URL. Soft-deleting a mission does not flag its
    // lines, but every composante endpoint loads the mission first, so they become unreachable.

    /**
     * GET: cost lines of a mission that are not soft-deleted. The service re-checks that the
     * mission belongs to projectId, so a guessed missionId cannot leak another project's costs.
     */
    @GetMapping("/{missionId}/composantes")
    public ResponseEntity<List<ComposanteResponse>> listComposantes(@PathVariable Long projectId,
                                                                      @PathVariable Long missionId) {
        return ResponseEntity.ok(composanteService.findByMission(projectId, missionId));
    }

    /**
     * POST: adds a cost line to a mission, 201 Created with a Location header. The currency
     * code is validated as exactly 3 characters; the service upper-cases it before saving.
     */
    @PostMapping("/{missionId}/composantes")
    public ResponseEntity<ComposanteResponse> createComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @Valid @RequestBody ComposanteRequest request) {
        ComposanteResponse created = composanteService.create(projectId, missionId, request);
        // Same idea as create(): builds this cost line's URL from the current request.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // Same effect as ResponseEntity.created(location).body(...), written explicitly.
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    /**
     * PUT: replaces a cost line's fields, 200. The service walks project -> mission -> line and
     * answers 404 on the first mismatch, so a guessed id cannot reach another project's amounts.
     */
    @PutMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<ComposanteResponse> updateComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody ComposanteRequest request) {
        return ResponseEntity.ok(composanteService.update(projectId, missionId, id, request));
    }

    /**
     * DELETE: soft-deletes a cost line, 204 No Content, so past mission spending stays
     * explainable later.
     */
    @DeleteMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<Void> deleteComposante(@PathVariable Long projectId,
                                                  @PathVariable Long missionId,
                                                  @PathVariable Long id) {
        composanteService.delete(projectId, missionId, id);
        return ResponseEntity.noContent().build();
    }
}
