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

/**
 * REST entry point for projects: list, read, create, update, move through the life cycle
 * (status, chef de projet, archiving), and soft-delete. Every other module hangs off a project id.
 * No permission/business-rule checks live here (those sit on ProjectService, permission-based
 * only) and ProjectScopeInterceptor enforces per-project scope (ADR-021) ahead of most methods.
 */
// Groups these endpoints under one heading in the generated OpenAPI page; French to match every
// other @Tag in the backend.
@Tag(name = "Projets", description = "CRUD projets, cycle de vie, fiche d'identification, archivage")
// @RestController = @Controller + @ResponseBody: return values are written as JSON, not
// resolved as a view name.
@RestController
// Project id lives in the path, not query/body, so ProjectScopeInterceptor can match
// /api/projects/{id} and enforce ADR-021 scope automatically.
@RequestMapping("/api/projects")
// Lombok-generated constructor injection: keeps the field final and lets tests build this
// controller with a fake service, no Spring context needed.
@RequiredArgsConstructor
public class ProjectController {

    // Only collaborator: every permission/business-rule decision lives in the service, not here.
    private final ProjectService projectService;

    /**
     * GET /api/projects - one page of active projects (deleted/archived excluded). 200 OK,
     * empty content array when nothing is visible. Paged because the portfolio has no limit;
     * Angular's listAll() can still request size=1000 for a picker.
     */
    // Sort defaults to code (unique) so paging stays stable; without it row order can drift
    // between calls and a project could appear on two pages or none.
    @GetMapping
    public ResponseEntity<Page<ProjectResponse>> list(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        // Perimeter filtering happens inside findAll(pageable): this URL carries no project id
        // for ProjectScopeInterceptor to check, so the service asks ProjectScopeService instead.
        return ResponseEntity.ok(projectService.findAll(pageable));
    }

    /**
     * GET /api/projects/archived - archived projects as a plain array. 200 OK, empty array (not
     * 404) when nothing is archived. Archiving is a separate flag, not a status or a delete.
     */
    // Spring prefers the exact "/archived" segment over the "/{id}" placeholder, so this URL
    // never reaches getById() with "archived" as an unparseable id.
    @GetMapping("/archived")
    public ResponseEntity<List<ProjectResponse>> listArchived() {
        return ResponseEntity.ok(projectService.findArchived());
    }

    /**
     * GET /api/projects/{id} - one project with everything the detail screen needs, including
     * computed fields. 200 OK, or 404 when missing/deleted. Amounts are blanked for callers
     * without VIEW_KPI (BR-050).
     */
    // Scope is checked twice: once by ProjectScopeInterceptor, and again inside findById()
    // itself, so the check still holds if this method is ever called outside an HTTP request.
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.findById(id));
    }

    /**
     * POST /api/projects - creates a project, 201 Created with the saved project and its
     * Location header. Code is upper-cased and refused if already taken (409); status defaults
     * to DRAFT; chef de projet is only attached if the caller also holds ASSIGN_CHEF_PROJET.
     */
    // @Valid runs ProjectRequest's Bean Validation before this method runs; a failure becomes
    // 400 via GlobalExceptionHandler, naming the guilty fields.
    // @RequestBody as a DTO, not the entity, so a caller can't set fields like archived or
    // revisedBudget that this form was never meant to expose.
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        // CREATE_PROJECT is checked inside create(), together with the unique-code rule.
        ProjectResponse created = projectService.create(request);
        // Builds the new project's URL from the in-progress request, so scheme/host/port/prefix
        // stay correct behind a proxy instead of being hardcoded.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{id} - replaces the project's content. 200 OK with the updated project,
     * 404 if missing, 409 if the new code is already taken. If initialBudget changes with no
     * avenant, the effective budget changes too and JalonService recomputes milestones (H-4).
     */
    // The service re-checks directorId/chefProjetId against the caller's own permissions rather
    // than trusting the body, so EDIT_PROJECT alone can't reassign director or chef de projet.
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.ok(projectService.update(id, request));
    }

    /**
     * PATCH /api/projects/{id}/assign-chef?userId=... - sets/replaces the chef de projet. 200
     * with the updated project, 404 if the project or user is missing/deleted. Its own endpoint
     * (and permission, ASSIGN_CHEF_PROJET) rather than folding this into PUT.
     */
    // userId travels as a query param since it's the one value carried; acceptable because a
    // user id isn't sensitive data, unlike what would belong in a request body here.
    @PatchMapping("/{id}/assign-chef")
    public ResponseEntity<ProjectResponse> assignChef(@PathVariable Long id,
                                                      @RequestParam Long userId) {
        return ResponseEntity.ok(projectService.assignChefProjet(id, userId));
    }

    /**
     * PATCH /api/projects/{id}/status?status=... - moves the project through its life cycle per
     * ProjectStatus.canTransitionTo(); 422 on an illegal move. Re-sending the current status is
     * accepted as a no-op, so a double click or replay doesn't error.
     */
    // Typed as the ProjectStatus enum, not String, so an unknown value is rejected before it
    // ever reaches the entity or the chk_status DB constraint.
    @PatchMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> changeStatus(@PathVariable Long id,
                                                        @RequestParam ProjectStatus status) {
        return ResponseEntity.ok(projectService.changeStatus(id, status));
    }

    /**
     * PATCH /api/projects/{id}/archive - moves a finished project out of the active lists. 200,
     * or 422 unless the project is COMPLETED (an ACTIVE project archived mid-work would hide
     * live hours from the portfolio screen).
     */
    // PATCH, not PUT: one field changes and the request carries no body at all.
    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.archive(id));
    }

    /**
     * PATCH /api/projects/{id}/unarchive - brings an archived project back into the active
     * lists. 200 OK. No status precondition, unlike archive(): restoring visibility can never
     * hide or lose anything, which is what makes archiving reversible in the first place.
     */
    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<ProjectResponse> unarchive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.unarchive(id));
    }

    /**
     * DELETE /api/projects/{id} - soft-deletes the project (sets deleted=true) and answers 204.
     * Not a real SQL delete: a project is the parent of timesheets, invoices and KPI history
     * that must stay auditable, and a real delete would be blocked by foreign keys anyway.
     */
    // 204, not 200 with an empty body, so a client that parses every response as JSON doesn't
    // crash on an empty string.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // DELETE_PROJECT is checked inside delete(); scope was already checked by the interceptor.
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
