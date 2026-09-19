package com.pms.agile.controller;

import com.pms.agile.dto.SprintRequest;
import com.pms.agile.dto.SprintResponse;
import com.pms.agile.service.SprintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST entry point for the sprints (iterations) of one project: name, goal, start and
 * end dates, status. A sprint is the time box that backlog items are attached to.
 *
 * Where it sits in the request flow:
 *   Angular AgileService (listSprints, createSprint, updateSprint, deleteSprint)
 *     -> HTTP call on /api/projects/{projectId}/sprints
 *     -> JwtAuthenticationFilter puts the logged-in user in the security context
 *     -> ProjectScopeInterceptor checks that this user may touch THIS project (ADR-021)
 *     -> this controller: reads the URL and the JSON body, chooses the HTTP status code
 *     -> SprintService: checks the permission, checks the dates, applies the rules
 *     -> SprintRepository + SprintMapper -> SprintResponse sent back as JSON.
 *
 * Why this file exists: without it there is no way to create or list iterations, so the
 * board has no columns of time to drop cards into. BacklogItemController would then only
 * ever receive sprintId = null, and the whole planning part of the Agile module would be
 * gone.
 *
 * What it deliberately does NOT contain: no permission test and no business rule.
 * Authorization is dynamic and permission-based, and the hasAuthority checks sit on the
 * SprintService methods (VIEW_AGILE to read, MANAGE_AGILE to write). The code never
 * tests a role name.
 *
 * Link with the rest of the slice: the sprint ids that this class returns are exactly
 * the values the board sends back in BacklogItemController.move(...) and
 * BacklogItemController.update(...) as sprintId. When a sprint is deleted here, the
 * service first detaches its items so they fall back into the product backlog instead of
 * pointing at a sprint that is gone.
 */
// @Tag is documentation only: springdoc reads it and groups the four endpoints of this
// class under one heading in the generated OpenAPI / Swagger UI page. Nothing changes at
// runtime.
// Why it is needed: without it the API page lists these URLs under an automatic name such
// as "sprint-controller", and a reader cannot tell the Sprint endpoints from the Backlog
// ones. The text is in French because every @Tag of the backend is written in French, so
// the generated API page stays in one single language from end to end.
@Tag(name = "Agile — Sprints", description = "Itérations d'un projet : objectif, dates, statut")
// @RestController = @Controller + @ResponseBody. Spring routes HTTP requests to this
// class and writes every returned object straight into the answer as JSON.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an
// HTML page to render, hunt for a template file and fail, instead of sending the JSON the
// Angular board expects.
@RestController
// Base URL shared by the four methods below. {projectId} is a placeholder filled from the
// real URL and read by @PathVariable.
// Why the project id sits IN the path and not in the body: this exact shape,
// /api/projects/{id}/**, is what ProjectScopeInterceptor matches to run the project scope
// check of ADR-021 before any method here starts.
// Example of what that prevents: a user who holds MANAGE_AGILE on project 7 calls
// DELETE /api/projects/9/sprints/5. His permission is real, so a permission check alone
// would let him delete an iteration of a project he has no business in. The interceptor
// reads 9 in the path, sees it is outside his scope, and answers 403 first. Permission
// AND scope are both required.
@RequestMapping("/api/projects/{projectId}/sprints")
// Lombok writes, at compile time, the constructor taking every final field (here only
// sprintService). Spring uses that constructor to inject the service.
// Why constructor injection rather than @Autowired on the field: the field stays final,
// so nothing can swap the service after start-up, and a unit test can build the
// controller with a fake service in one line without starting Spring.
@RequiredArgsConstructor
public class SprintController {

    // The single collaborator. Every decision (permission, "end date not before start
    // date", detaching the items of a deleted sprint) lives on the other side of this
    // field; the controller only turns HTTP into a method call and back.
    private final SprintService sprintService;

    /**
     * GET /api/projects/{projectId}/sprints - gives back every sprint of the project that
     * is not soft-deleted, ordered by start date, as a JSON array. 200 OK, and an empty
     * array (not 404) when the project has no iteration yet.
     *
     * Why the order by start date matters to the caller: the board is read from left to
     * right in time. Sorted by creation id instead, a sprint added late but planned early
     * would appear at the far right, after the iterations that come after it.
     */
    // @GetMapping with no value: this method answers GET on the base URL of the class.
    // Why GET: it is the read verb, so repeating it changes nothing and a refresh, the
    // back button or a cache may replay it freely. Behind a POST, the browser would ask
    // "resend the form?" on every refresh of the board.
    // @PathVariable copies the number written in the URL into the parameter; the name
    // projectId matches the {projectId} placeholder declared on the class.
    // Why it is needed: without it the parameter stays null, the service looks for a
    // project with id null, and a valid URL answers 404.
    @GetMapping
    public ResponseEntity<List<SprintResponse>> list(@PathVariable Long projectId) {
        // ResponseEntity.ok(...) = HTTP 200 with this body. The VIEW_AGILE check and the
        // loading both happen inside findByProject.
        return ResponseEntity.ok(sprintService.findByProject(projectId));
    }

    /**
     * POST /api/projects/{projectId}/sprints - creates one iteration and answers
     * 201 Created, with the saved sprint (generated id included) in the body and its URL
     * in the Location header.
     *
     * Why 201 plus a Location header rather than a bare 200: the caller learns where the
     * new sprint lives without building that URL itself, so a change in the URL shape
     * does not break a client that reads the header.
     */
    // @Valid runs the checks declared on the SprintRequest record (name not blank,
    // startDate, endDate and status not null) BEFORE the body of this method starts.
    // A failure throws MethodArgumentNotValidException, which GlobalExceptionHandler turns
    // into 400 Bad Request naming the guilty fields.
    // Why it is needed: without @Valid those constraints are never executed. A sprint with
    // no name and no dates would be stored, and the board would show a nameless column
    // that can never be placed on the timeline.
    // Note on the division of work: @Valid only checks each field on its own. The rule
    // that compares two fields - end date not before start date - cannot be expressed that
    // way and is checked in SprintService.validateDates, which answers 422 instead of 400.
    // @RequestBody turns the JSON body into that record. DTO = Data Transfer Object, a
    // small object whose only job is to carry data between the browser and the server.
    // Why a DTO instead of the Sprint entity: a caller could otherwise post
    // {"deleted": false} or {"project": {"id": 9}} and write fields the API never meant to
    // expose, attaching the new sprint to another project through an unchecked field.
    @PostMapping
    public ResponseEntity<SprintResponse> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody SprintRequest request) {
        // MANAGE_AGILE and the date rule are both checked inside create().
        SprintResponse created = sprintService.create(projectId, request);
        // Builds the URL of the new sprint from the URL of the call in progress:
        // POST /api/projects/7/sprints, plus "/{id}", with {id} replaced by the real
        // generated id -> /api/projects/7/sprints/5. That is what buildAndExpand does.
        // Why derive it from the current request instead of writing the string by hand:
        // scheme, host, port and context path come from the real call, so the header stays
        // correct when the application runs under a prefix such as /pms or on another port.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created sprint as the body so the board
        // can add the new column without asking the server a second time.
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/sprints/{id} - replaces the whole content of one
     * sprint (name, goal, dates, status) and answers 200 OK with the updated sprint.
     *
     * Why PUT with a complete body: the sprint form always sends every field back, so the
     * server never has to guess whether a missing field means "leave it alone" or "clear
     * it". Clearing the goal is then a real, explicit action.
     */
    // Two path values are read: projectId says which project, id says which sprint. The
    // service uses BOTH and refuses a sprint whose project is not projectId.
    // Why that pair check is needed: the ADR-021 scope check only looked at the project
    // written in the URL. Without the pair check, PUT /api/projects/7/sprints/5 would
    // rewrite sprint 5 even if sprint 5 belongs to project 9 - someone allowed on project
    // 7 would be changing the planning of a project he may not even be able to see.
    @PutMapping("/{id}")
    public ResponseEntity<SprintResponse> update(@PathVariable Long projectId,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody SprintRequest request) {
        return ResponseEntity.ok(sprintService.update(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/sprints/{id} - removes one iteration and answers
     * 204 No Content: it worked, and there is nothing to send back.
     *
     * This single call does more than it looks. Inside the service, and in one single
     * transaction, the items that were committed to this sprint are detached first - they
     * go back to the product backlog - and only then is the sprint marked deleted = true
     * (soft delete: the row stays, the queries filter it out).
     * Why it works that way: the work already written down is not lost with the iteration,
     * it simply becomes unplanned again, and no item is left pointing at a sprint that no
     * longer exists. Without that step the board would show cards attached to an
     * iteration that is not displayed anywhere, and nobody could move them back.
     */
    // ResponseEntity<Void>: the answer carries no body.
    // Why 204 rather than 200 with an empty body: 204 tells the Angular client "it worked,
    // do not try to read a body". A client that parses every answer as JSON would
    // otherwise crash on an empty string.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        // projectId is passed on so the service can refuse a sprint that belongs to another
        // project, exactly as in update().
        sprintService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
