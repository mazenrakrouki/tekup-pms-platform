package com.pms.agile.controller;

import com.pms.agile.dto.BacklogItemMoveRequest;
import com.pms.agile.dto.BacklogItemRequest;
import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.service.BacklogItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST entry point for the product backlog of one project: the work items
 * (user stories, tasks) that the Agile board draws in its columns.
 *
 * Where it sits in the request flow:
 *   Angular AgileService (listBacklog, createItem, updateItem, moveItem, deleteItem)
 *     -> HTTP call on /api/projects/{projectId}/backlog
 *     -> JwtAuthenticationFilter puts the logged-in user in the security context
 *     -> ProjectScopeInterceptor checks that this user may touch THIS project (ADR-021)
 *     -> this controller: it only reads the URL and the JSON body, then chooses the
 *        right HTTP status code for the answer
 *     -> BacklogItemService: it checks the permission and applies the business rules
 *     -> BacklogItemRepository + BacklogItemMapper -> BacklogItemResponse sent back as JSON.
 *
 * Why this file exists: it is the only door between the Angular board and the backlog
 * data. Delete it and Spring answers 404 on every /backlog URL, so the board screen
 * stays empty even for a user who holds every permission.
 *
 * What it deliberately does NOT contain: no permission test and no business rule.
 * Authorization in PMS is dynamic and permission-based, and the hasAuthority checks
 * sit on the BacklogItemService methods (VIEW_AGILE to read, MANAGE_AGILE to write).
 * Keeping them there means the rule is written in one single place: another caller
 * (a test, a scheduled job, another service) cannot skip it by not passing through
 * this controller. The code never tests a role name, only a permission.
 *
 * Sister class: SprintController, same package, same URL shape, same rules, for the
 * iterations the items below are attached to.
 */
// @Tag is documentation only: springdoc reads it and groups the five endpoints of this
// class under one heading in the generated OpenAPI / Swagger UI page. It changes
// nothing at runtime.
// Why it is needed: without it the API page lists these URLs under an automatic name
// such as "backlog-item-controller", mixed with every other endpoint of the
// application, and a reader cannot find the Agile part. The text is in French because
// every @Tag of the backend is written in French, so the generated API page stays in
// one single language from end to end.
@Tag(name = "Agile — Backlog", description = "Backlog produit et affectation des éléments aux sprints")
// @RestController = @Controller + @ResponseBody. It tells Spring that this class
// answers HTTP requests, and that every object a method returns must be written
// directly as JSON in the body of the answer.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an
// HTML page to render, look for a template file called "BacklogItemResponse" and fail,
// instead of sending the JSON the Angular board expects.
@RestController
// Base URL shared by every method below. {projectId} is a placeholder: the real number
// travels in the URL and is read by @PathVariable in each method.
// Why the project id sits IN the path and not in the JSON body: this exact shape,
// /api/projects/{id}/**, is the one ProjectScopeInterceptor matches to run the project
// scope check of ADR-021 before any method here starts.
// Example of what that prevents: a project manager of project 7 who holds MANAGE_AGILE
// calls POST /api/projects/9/backlog. The permission alone would let him in, because he
// really does hold MANAGE_AGILE. The interceptor reads 9 in the path, sees that project
// 9 is outside his scope, and answers 403 before this class is ever reached. Permission
// alone is not enough; permission AND scope are both required.
@RequestMapping("/api/projects/{projectId}/backlog")
// Lombok writes, at compile time, the constructor that takes every final field of the
// class (here only backlogItemService). Spring then uses that constructor to inject the
// service when it builds the bean.
// Why constructor injection rather than @Autowired on the field: the field can stay
// final, so nothing can replace the service after start-up, and a unit test can build
// the controller with a fake service in one line, without starting Spring at all.
@RequiredArgsConstructor
public class BacklogItemController {

    // The single collaborator. Everything that is a decision (permission, validation of
    // the sprint and of the assignee, soft delete) happens on the other side of this
    // field; the controller only translates HTTP into a method call and back.
    private final BacklogItemService backlogItemService;

    /**
     * GET /api/projects/{projectId}/backlog - gives back every item of the project that
     * is not soft-deleted, in creation order, as a JSON array. 200 OK, and an empty
     * array (not 404) when the project simply has no item yet.
     *
     * Why a plain list and not a paged answer: the backlog of one project stays small,
     * a few tens of items, and the board needs all of them at the same time to fill its
     * columns. Paging would force the screen to ask page after page just to draw itself,
     * and a card dropped on page 2 would be invisible while page 1 is displayed.
     */
    // @GetMapping with no value: this method answers GET on the base URL of the class.
    // Why GET and not POST: GET is the read verb, so repeating it changes nothing and a
    // refresh, the back button or a cache may replay it freely. If reading the board were
    // a POST, the browser would ask "resend the form?" on every refresh and no cache
    // could ever serve the answer.
    // @PathVariable copies the number written in the URL into the parameter; the name
    // projectId matches the {projectId} placeholder declared on the class.
    // Why it is needed: without it the parameter stays null, the service looks for a
    // project with id null, and a perfectly valid URL answers 404.
    @GetMapping
    public ResponseEntity<List<BacklogItemResponse>> list(@PathVariable Long projectId) {
        // ResponseEntity.ok(...) = HTTP 200 with this body. The permission check
        // (VIEW_AGILE) and the loading both happen inside findByProject.
        return ResponseEntity.ok(backlogItemService.findByProject(projectId));
    }

    /**
     * POST /api/projects/{projectId}/backlog - creates one backlog item and answers
     * 201 Created, with the saved item (its generated id included) in the body and the
     * URL of that new item in the Location header.
     *
     * Why 201 plus a Location header rather than a bare 200: the caller learns where the
     * thing it just created now lives, without having to build that URL itself. If the
     * URL shape changes one day, a client that reads the header keeps working.
     */
    // @Valid asks Spring to run the checks declared on the BacklogItemRequest record
    // (title not blank, priority and status not null, estimateDays zero or more) BEFORE
    // the body of this method starts. A failure throws MethodArgumentNotValidException,
    // which GlobalExceptionHandler turns into 400 Bad Request naming the guilty fields.
    // Why it is needed: without @Valid, the constraints written on the record are never
    // executed. An item with an empty title would be saved, and the board would then show
    // a blank card that nobody can identify or search for.
    // @RequestBody turns the JSON body of the request into that record. DTO = Data
    // Transfer Object: a small object whose only job is to carry data between the browser
    // and the server.
    // Why a DTO instead of the BacklogItem entity itself: a caller could otherwise post
    // {"deleted": false} or {"project": {"id": 9}} and write fields the API never meant to
    // expose, moving an item into another project through a field that was never checked.
    @PostMapping
    public ResponseEntity<BacklogItemResponse> create(@PathVariable Long projectId,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        // MANAGE_AGILE is checked inside create(), together with the rules that the target
        // sprint and the assignee must belong to this same project.
        BacklogItemResponse created = backlogItemService.create(projectId, request);
        // Builds the URL of the new item from the URL of the call in progress:
        // POST /api/projects/7/backlog, plus "/{id}", with {id} replaced by the real
        // generated id -> /api/projects/7/backlog/42. That is what buildAndExpand does.
        // Why derive it from the current request instead of writing the string by hand:
        // scheme, host, port and context path are taken from the real call, so the header
        // is still correct when the application is served under a prefix such as /pms, or
        // on another port in production.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created item as the body so the board
        // can insert the new card without asking the server a second time.
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/backlog/{id} - replaces the whole content of one
     * item (title, description, priority, estimate, status, sprint, assignee) and
     * answers 200 OK with the updated item.
     *
     * Why PUT with a complete body rather than a partial update: the edit form of the
     * board always sends every field back, so the server never has to guess whether a
     * missing field means "leave it alone" or "clear it". A field sent empty really
     * means empty.
     */
    // Two path values are read here: projectId says which project, id says which item.
    // The service uses BOTH, and refuses an item whose project is not projectId.
    // Why that pair check is needed: the scope check of ADR-021 only looked at the
    // project written in the URL. Without the pair check, PUT /api/projects/7/backlog/42
    // would happily modify item 42 even if item 42 belongs to project 9 - a user allowed
    // on project 7 would be editing the work of a project he may not even see.
    @PutMapping("/{id}")
    public ResponseEntity<BacklogItemResponse> update(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody BacklogItemRequest request) {
        return ResponseEntity.ok(backlogItemService.update(projectId, id, request));
    }

    /**
     * PATCH /api/projects/{projectId}/backlog/{id}/move - moving one card on the board:
     * a new column (status) and, when the card was dropped on another iteration, a new
     * sprint. Answers 200 OK with the updated item.
     *
     * Why a dedicated endpoint instead of reusing PUT above: a move only changes the
     * column and the sprint, so the board does not have to send the title, the
     * description and the estimate again. Concrete gain: if a colleague renamed that card
     * one second ago, a move that resent the board's older full copy would silently put
     * the old title back. Here the move can only touch the two fields it is about.
     */
    // PATCH is the HTTP verb for a partial change, and /move under the item makes the
    // intention readable in the server log: it is a board move, not a content edit.
    // @Valid still runs: BacklogItemMoveRequest requires a status that is not null, while
    // sprintId is allowed to be null on purpose - null means "back to the product
    // backlog", outside of any sprint.
    // Why that null must stay allowed: without it a card could never leave an iteration,
    // and work pulled out of a sprint would stay attached to it for ever, counted in an
    // iteration that no longer contains it.
    @PatchMapping("/{id}/move")
    public ResponseEntity<BacklogItemResponse> move(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody BacklogItemMoveRequest request) {
        // Like update(), move() checks MANAGE_AGILE and checks that both the item and the
        // target sprint belong to projectId before saving anything.
        return ResponseEntity.ok(backlogItemService.move(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/backlog/{id} - takes one item off the board and
     * answers 204 No Content: it worked, and there is nothing to send back.
     *
     * The removal is a soft delete: the service only sets deleted = true, the row stays
     * in the database and the repository queries filter it out with
     * "AND b.deleted = false".
     * Why not a real SQL DELETE: an item that was written, estimated and discussed is a
     * trace of how the project went, and rows elsewhere may still point at it. Erasing it
     * for good would break those links and would make one wrong click impossible to undo.
     */
    // ResponseEntity<Void>: the answer carries no body at all.
    // Why 204 rather than 200 with an empty body: 204 tells the Angular client "it worked,
    // do not try to read a body". A client that parses every answer as JSON would
    // otherwise crash trying to parse an empty string.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        // projectId is passed on so the service can refuse an item that belongs to another
        // project, exactly as in update() and move().
        backlogItemService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
