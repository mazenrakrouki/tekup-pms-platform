package com.pms.project.controller;

import com.pms.project.dto.ProjectRequest;
import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.ProjectStatus;
import com.pms.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Tag(name = "Projets", description = "CRUD projets, cycle de vie, fiche d'identification, archivage")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<Page<ProjectResponse>> list(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(projectService.findAll(pageable));
    }

    @GetMapping("/archived")
    public ResponseEntity<List<ProjectResponse>> listArchived() {
        return ResponseEntity.ok(projectService.findArchived());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse created = projectService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.ok(projectService.update(id, request));
    }

    @PatchMapping("/{id}/assign-chef")
    public ResponseEntity<ProjectResponse> assignChef(@PathVariable Long id,
                                                      @RequestParam Long userId) {
        return ResponseEntity.ok(projectService.assignChefProjet(id, userId));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> changeStatus(@PathVariable Long id,
                                                        @RequestParam ProjectStatus status) {
        return ResponseEntity.ok(projectService.changeStatus(id, status));
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.archive(id));
    }

    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<ProjectResponse> unarchive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.unarchive(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
