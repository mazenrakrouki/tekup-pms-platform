package com.pms.team.controller;

import com.pms.team.dto.TeamAssignmentRequest;
import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.service.TeamAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TeamController {

    private final TeamAssignmentService teamAssignmentService;

    @GetMapping("/api/projects/{projectId}/team")
    public ResponseEntity<List<TeamAssignmentResponse>> listByProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(teamAssignmentService.findByProject(projectId));
    }

    @PostMapping("/api/projects/{projectId}/team")
    public ResponseEntity<TeamAssignmentResponse> assign(@PathVariable Long projectId,
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        TeamAssignmentResponse created = teamAssignmentService.assign(projectId, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<TeamAssignmentResponse> update(@PathVariable Long projectId,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        return ResponseEntity.ok(teamAssignmentService.update(projectId, id, request));
    }

    @DeleteMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long projectId, @PathVariable Long id) {
        teamAssignmentService.remove(projectId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/users/{userId}/assignments")
    public ResponseEntity<List<TeamAssignmentResponse>> listByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(teamAssignmentService.findByUser(userId));
    }
}
