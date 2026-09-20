package com.pms.team.controller;

import com.pms.team.dto.TeamAssignmentRequest;
import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.service.TeamAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

// HTTP entry point for the team feature (who works on which project). Thin by design: all
// permission checks and business rules live in TeamAssignmentService, not here, so any other
// caller (job, test, future screen) is guarded the same way.

/**
 * REST controller for team assignments (one person on one project, with a role and a period).
 *
 * <p>No class-level {@code @RequestMapping}: this class answers on two different URL roots,
 * {@code /api/projects/{projectId}/team...} and {@code /api/users/{userId}/assignments}, so
 * each method spells out its full path. Only the project-rooted URLs are seen by
 * ProjectScopeInterceptor (see listByUser below).
 */
@Tag(name = "Équipe", description = "Affectation / retrait des membres d'équipe + historique par projet")
@RestController
@RequiredArgsConstructor
public class TeamController {

    // Repository is NOT injected here: reading the DB directly would bypass the service layer's permission checks and transaction.
    private final TeamAssignmentService teamAssignmentService;

    /**
     * Lists the active members of one project. Guarded twice per ADR-021: ProjectScopeInterceptor
     * checks the perimeter, then TeamAssignmentService.findByProject checks VIEW_TEAM — holding
     * the permission alone is not enough on a project resource.
     */
    @GetMapping("/api/projects/{projectId}/team")
    public ResponseEntity<List<TeamAssignmentResponse>> listByProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(teamAssignmentService.findByProject(projectId));
    }

    /**
     * Adds one person to the team of a project -> 201 with a Location header. Other statuses
     * (400/404/409/422/403) come from validation and the service; see GlobalExceptionHandler.
     */
    @PostMapping("/api/projects/{projectId}/team")
    public ResponseEntity<TeamAssignmentResponse> assign(@PathVariable Long projectId,
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        TeamAssignmentResponse created = teamAssignmentService.assign(projectId, request);
        // Builds the Location header for the new row, e.g. http://.../api/projects/7/team/135.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Updates an assignment's role and period -> 200. The service re-checks that {id} really
     * belongs to {projectId} (ProjectScopeInterceptor only validated projectId), so an id
     * from another project answers 404 instead of leaking cross-project edits. userId in the
     * body is ignored: an assignment is never moved to another person.
     *
     * <p>Not used by the frontend today (TeamService offers only list/assign/remove); kept so
     * the team resource exposes the full REST set with the same guards as the other methods.
     */
    @PutMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<TeamAssignmentResponse> update(@PathVariable Long projectId,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        return ResponseEntity.ok(teamAssignmentService.update(projectId, id, request));
    }

    /**
     * Takes one person off the team -> 204. Soft delete only (sets deleted = true), so the
     * work history stays readable and other rows referencing this assignment stay valid.
     */
    @DeleteMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long projectId, @PathVariable Long id) {
        // projectId proves the assignment really belongs to a project the caller was allowed to touch.
        teamAssignmentService.remove(projectId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Every active assignment of one person, across all projects -> 200.
     *
     * <p>SECURITY: this URL starts with /api/users/, not /api/projects/, so ProjectScopeInterceptor
     * (registered only on /api/projects/**) does NOT run here — the single guard is
     * {@code hasAuthority('VIEW_TEAM')}, so any holder can read any user's assignments across
     * all projects, including outside their own perimeter.
     */
    @GetMapping("/api/users/{userId}/assignments")
    public ResponseEntity<List<TeamAssignmentResponse>> listByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(teamAssignmentService.findByUser(userId));
    }
}
