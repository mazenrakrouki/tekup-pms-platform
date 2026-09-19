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
 * WHAT THIS FILE IS
 * The REST entry point for the projects themselves: list them, read one, create one, change
 * one, move one through its life cycle (status, chef de projet, archiving) and soft-delete
 * one. Everything else in PMS hangs under a project, so this is the door to the central
 * object of the application.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular ProjectService (list, listAll, listArchived, get, create, update, archive,
 *   unarchive, assignChef, changeStatus, delete)
 *     -> HTTP call on /api/projects...
 *     -> JwtAuthenticationFilter puts the logged-in user in the security context
 *     -> ProjectScopeInterceptor: for the URLs that carry a project number
 *        (/api/projects/7 and /api/projects/7/...) it checks that this user is allowed on
 *        THIS project (ADR-021), before the method below is even entered
 *     -> this controller: reads the URL, the query parameters and the JSON body, and picks
 *        the HTTP status code
 *     -> ProjectService: checks the permission (@PreAuthorize), applies the business rules,
 *        and filters the two lists that the interceptor cannot cover
 *     -> ProjectRepository + ProjectMapper -> ProjectResponse sent back as JSON.
 *
 * WHY IT EXISTS
 * Delete this file and the product has no root object: no project can be created, so there is
 * nothing for sprints, timesheets, invoicing milestones, missions, risks or KPI to attach
 * themselves to. Almost every other controller of the backend answers on a URL that starts
 * with /api/projects/{id}, and that id can only come from here.
 *
 * WHAT IT DELIBERATELY DOES NOT CONTAIN
 * No permission test and no business rule. Authorization is dynamic and permission-based: the
 * hasAuthority checks sit on the ProjectService methods (VIEW_PROJECT to read, CREATE_PROJECT
 * to create, EDIT_PROJECT to change, DELETE_PROJECT to remove, ASSIGN_CHEF_PROJET to name a
 * project manager). The code never tests a role name, so giving a capability to another role
 * is one row added in role_permission, with no change in this file.
 *
 * TWO LEVELS OF PROTECTION, AND WHY BOTH ARE NEEDED (ADR-021)
 * The permission answers "may this person do this kind of action at all?". The scope answers
 * "on which projects?". They are separate on purpose. A project manager really does hold
 * EDIT_PROJECT, so a permission check alone would let him send PUT /api/projects/9 and rewrite
 * a project that belongs to a colleague. ProjectScopeInterceptor reads the 9 in the URL, sees
 * that it is outside his perimeter, and answers 403 before this class runs.
 * Careful with the two URLs that carry no number: GET /api/projects and
 * GET /api/projects/archived do not match the interceptor pattern, because there is no single
 * project to point at. For those two, the perimeter is applied inside ProjectService, which
 * keeps only the projects the caller manages or is assigned to - unless the caller holds
 * VIEW_ALL_PROJECTS, the capability that lifts the filter and opens the whole portfolio.
 *
 * ONE MORE FILTER, INSIDE THE SERVICE (BR-050)
 * ProjectService.toResponse() strips every amount and margin out of the answer when the caller
 * does not hold VIEW_KPI. So two users can call the very same URL here and get the same
 * project with different fields filled in. That is also why this controller never builds a
 * response by itself: going around the service would leak the budget of a project to a
 * developer.
 */
// @Tag is documentation only: springdoc reads it and groups the endpoints of this class under
// one heading in the generated OpenAPI / Swagger UI page. Nothing changes at run time.
// Why it is needed: without it the API page lists these URLs under an automatic name such as
// "project-controller", and a reader cannot tell them apart from the other /api/projects/...
// groups (Sprints, Backlog, Billing, KPI, Devis Interne) that share the same URL prefix. The
// text is in French because every @Tag of the backend is written in French, so the generated
// API page stays in one single language from end to end.
@Tag(name = "Projets", description = "CRUD projets, cycle de vie, fiche d'identification, archivage")
// @RestController = @Controller + @ResponseBody. Spring routes the HTTP requests to this class
// and writes every returned object straight into the answer as JSON.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an HTML
// page to render, look for a template file and fail, instead of sending the JSON the Angular
// application expects.
@RestController
// Base URL shared by every method below; each method adds its own suffix on top of it.
// Why this exact shape matters: ProjectScopeInterceptor matches /api/projects/{number} and
// /api/projects/{number}/..., so putting the project id in the PATH - and not in the body or
// in a query parameter - is what makes the ADR-021 scope check fire automatically. A URL such
// as /api/projects/update?id=9 would carry the same information, would be invisible to the
// interceptor, and the perimeter would silently stop being enforced on that call.
@RequestMapping("/api/projects")
// Lombok writes, at compile time, the constructor taking every final field (here only
// projectService). Spring uses that constructor to inject the service.
// Why constructor injection rather than @Autowired on the field: the field stays final, so
// nothing can swap the service after start-up, and a unit test can build the controller with a
// fake service in one line without starting Spring at all.
@RequiredArgsConstructor
public class ProjectController {

    // The single collaborator. Every decision - permission, perimeter, "only a finished project
    // can be archived", "this status change is not allowed", hiding the amounts - lives on the
    // other side of this field. This class only turns HTTP into a method call and back.
    private final ProjectService projectService;

    /**
     * GET /api/projects - one page of the active projects (soft-deleted and archived ones
     * excluded), as a JSON object holding content, totalElements, totalPages and number.
     * Always 200 OK, with an empty content array when the caller can see no project.
     *
     * Why a page and not a plain list: the portfolio grows without limit and the projects
     * screen shows a table with paging. Sending every project on every screen opening would
     * make the answer heavier and heavier, and the browser would build a table of hundreds of
     * rows that nobody scrolls through.
     * The caller can still ask for a big page on purpose: the Angular listAll() asks for
     * size=1000 to fill a project picker in one single call.
     */
    // @GetMapping with no value: this method answers GET on the base URL of the class.
    // Why GET: it is the read verb, so a refresh, the back button or a cache may replay it
    // freely. Behind a POST, the browser would ask "resend the form?" on every refresh.
    // @PageableDefault supplies the values used when the caller sends no page, size or sort
    // parameter; Spring reads ?page=&size=&sort= from the URL and builds the Pageable object.
    // Why the sort default is the important part: without sort = "code", PostgreSQL is free to
    // return the rows in whatever order suits it, and that order can change between two calls.
    // The same project could then appear on page 1 and again on page 2 while another one is
    // never shown at all. Sorting on the project code, which is unique, makes the paging stable
    // and gives the user the alphabetical order he expects.
    @GetMapping
    public ResponseEntity<Page<ProjectResponse>> list(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        // The perimeter filter happens inside findAll(pageable), not here: this URL carries no
        // project number, so ProjectScopeInterceptor cannot help. The service asks
        // ProjectScopeService for the ids the caller may see and pages over those ids only.
        return ResponseEntity.ok(projectService.findAll(pageable));
    }

    /**
     * GET /api/projects/archived - the projects that were archived, as a plain JSON array.
     * 200 OK, and an empty array (not 404) when nothing has been archived yet.
     *
     * Why a plain list and not a page like the method above: archiving is the exception, the
     * number of archived projects stays small, and the screen that shows them is a simple
     * read-only list with no paging controls. Wrapping it in a page object would add a level of
     * nesting for nothing.
     *
     * Reminder on what "archived" means here: it is a separate true/false flag on the project,
     * not a status and not a delete. The project keeps its COMPLETED status, simply leaves the
     * everyday lists, stays fully readable, and can come back through unarchive() below.
     */
    // A literal path segment, "/archived", on the same class whose next method maps "/{id}".
    // Why that is not a clash: Spring compares the two patterns and always prefers the exact
    // text over a placeholder, so /api/projects/archived reaches this method and never
    // getById(). Without that rule the word "archived" would be handed to getById() as the id,
    // Spring would fail to turn it into a Long, and a perfectly valid URL would answer 400.
    // The scope interceptor also lets this URL through, because "archived" is not a number and
    // the ADR-021 pattern only matches digits; the perimeter is applied inside findArchived().
    @GetMapping("/archived")
    public ResponseEntity<List<ProjectResponse>> listArchived() {
        return ResponseEntity.ok(projectService.findArchived());
    }

    /**
     * GET /api/projects/{id} - one project with everything the detail screen needs: the
     * identification sheet, the dates, the people, plus the computed fields (duration, budget
     * in TND, PPR). 200 OK, or 404 when the id does not exist or points at a deleted project.
     *
     * Note that the same call answers differently for two users: without VIEW_KPI the service
     * blanks every amount and every margin (BR-050) before the JSON is written.
     */
    // @PathVariable copies the number written in the URL into the parameter; the name id
    // matches the {id} placeholder of the mapping.
    // Why it is needed: without it the parameter stays null, the service looks for a project
    // with id null, and a perfectly valid URL answers 404.
    // On this URL the perimeter is checked TWICE: once by ProjectScopeInterceptor before the
    // method starts, and once again inside findById(), which calls scopeService.assertCanAccess
    // itself. Why the repetition is on purpose: the check inside the service is the one that
    // still protects the data if this method is ever called from somewhere other than an HTTP
    // request - a seeder, a scheduled job, another service - where no interceptor ever runs.
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.findById(id));
    }

    /**
     * POST /api/projects - creates a project and answers 201 Created, with the saved project
     * (generated id included) in the body and its URL in the Location header.
     *
     * Why 201 plus a Location header rather than a bare 200: the caller learns where the new
     * project lives without building that URL itself, so a later change in the URL shape does
     * not break a client that reads the header.
     *
     * What the service adds on top: the project code is upper-cased and refused when another
     * live project already uses it (409 Conflict), the status falls back to DRAFT when none is
     * given, and the chef de projet is attached only if the caller also holds
     * ASSIGN_CHEF_PROJET - CREATE_PROJECT alone is not enough to name a project manager.
     */
    // @Valid runs the checks declared on the ProjectRequest record - code and name not blank
    // and within their length, amounts not negative, margin between -1 and 1 - BEFORE the body
    // of this method starts. A failure throws MethodArgumentNotValidException, which
    // GlobalExceptionHandler turns into 400 Bad Request naming the guilty fields.
    // Why it is needed: without @Valid those constraints are simply never executed. A project
    // with an empty code and a negative budget would be stored, and every screen built on that
    // project would show a nameless line carrying an impossible amount.
    // @RequestBody turns the JSON body into that record. DTO = Data Transfer Object, a small
    // object whose only job is to carry data between the browser and the server.
    // Why a DTO instead of the Project entity: a caller could otherwise post {"archived": true}
    // or {"revisedBudget": 999999} and write fields the API never meant to expose - the revised
    // budget is only supposed to move through a signed avenant, never through this form.
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        // CREATE_PROJECT is checked inside create(), together with the unique-code rule.
        ProjectResponse created = projectService.create(request);
        // Builds the URL of the new project from the URL of the call in progress:
        // POST /api/projects, plus "/{id}", with {id} replaced by the real generated id
        // -> /api/projects/42. That is what buildAndExpand does.
        // Why derive it from the current request instead of writing the string by hand: scheme,
        // host, port and context path all come from the real call, so the header stays correct
        // when the application runs behind a reverse proxy, on another port, or under a prefix.
        // var is only local type inference, decided at compile time; the real type here is
        // java.net.URI, which is what ResponseEntity.created() expects on the next line.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created project as the body so the screen
        // can open the new project without asking the server a second time.
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{id} - replaces the whole content of one project: identification
     * sheet, dates, budget, and the people when the caller is allowed to change them.
     * 200 OK with the updated project, 404 when it does not exist, 409 when the new code is
     * already taken by another live project.
     *
     * Why PUT with a complete body: the project form always sends every field back, so the
     * server never has to guess whether a missing field means "leave it alone" or "clear it".
     * Emptying the description or the funder is then a real, explicit action.
     *
     * One consequence to be aware of, handled inside the service (H-4): if the initial budget
     * changes while no avenant exists, the effective budget of the project changes too, and
     * JalonService recomputes the planned amounts of the invoicing milestones. So one single
     * PUT here can rewrite rows in another module. That is also why the service compares the
     * two budgets with compareTo and not equals - for BigDecimal, 100 and 100.00 are equal in
     * value but are not equal objects, and equals would trigger a pointless recomputation of
     * every milestone each time the form is saved.
     */
    // Two things are read from the request: the id in the path says which project, the JSON
    // body says what it should become. The perimeter for this id was already checked by
    // ProjectScopeInterceptor, and EDIT_PROJECT is checked inside update().
    // Why the service still re-reads directorId and chefProjetId under a capability test
    // instead of trusting this body: a project manager holding only EDIT_PROJECT can post any
    // JSON he likes. Without those tests he could send another directorId and quietly move his
    // project under a different director, or name himself chef de projet on it.
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.ok(projectService.update(id, request));
    }

    /**
     * PATCH /api/projects/{id}/assign-chef?userId=... - names, or replaces, the chef de projet
     * (project manager) of the project. 200 OK with the updated project, 404 when the project
     * or the user does not exist or has been deleted.
     *
     * Why PATCH on a sub-URL rather than reusing the PUT above: this is one single, meaningful
     * act of governance, guarded by its own capability ASSIGN_CHEF_PROJET. Going through PUT
     * would mean sending the whole project back just to change one person, and a user holding
     * only EDIT_PROJECT would be sending a chefProjetId he is not allowed to set. The service
     * does defend itself against that - it ignores chefProjetId unless the caller holds
     * ASSIGN_CHEF_PROJET - but a URL of its own makes the rule visible in the API itself, and
     * the Swagger page then shows exactly which capability the act needs.
     */
    // @RequestParam reads ?userId=... from the query string. It is required by default: when
    // the caller forgets it, Spring refuses the request before this method starts, so userId
    // can never arrive as null and the service never looks up a user with a null id.
    // Why the user id travels as a query parameter and not in a JSON body: the call carries one
    // single value, so a body would be an object with one field, and the Angular side already
    // sends an empty {} body. This stays acceptable only because a user id is not sensitive
    // data - anything that must not appear in a server log has no place in a URL.
    @PatchMapping("/{id}/assign-chef")
    public ResponseEntity<ProjectResponse> assignChef(@PathVariable Long id,
                                                      @RequestParam Long userId) {
        return ResponseEntity.ok(projectService.assignChefProjet(id, userId));
    }

    /**
     * PATCH /api/projects/{id}/status?status=... - moves the project through its life cycle.
     * 200 OK with the updated project, or 422 Unprocessable Entity when the move is not
     * allowed.
     *
     * The rule itself is neither here nor in the service: ProjectStatus.canTransitionTo carries
     * the map of legal moves (DRAFT -> ACTIVE or CANCELLED, ACTIVE -> ON_HOLD, COMPLETED or
     * CANCELLED, and so on), and the service turns a refusal into a BusinessRuleException.
     * Why illegal moves are refused rather than simply accepted: a project that jumped straight
     * from DRAFT to COMPLETED would carry invoicing milestones and KPI snapshots for a period
     * during which it was never running, and those figures would describe work that never
     * happened.
     * Re-sending the status the project already has is accepted on purpose, so that a double
     * click or a replayed request changes nothing instead of failing with an error.
     */
    // The query parameter is declared as the ProjectStatus enum, not as a String; Spring turns
    // the text of the URL into the matching constant by name.
    // Why that matters: an unknown word such as ?status=FINISHED is rejected during that
    // conversion, before any of our code runs, so an invalid value can never reach the entity
    // or the projects.status column - where the Flyway check constraint chk_status would refuse
    // it anyway, but much later and with a raw database error instead of a clear message.
    @PatchMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> changeStatus(@PathVariable Long id,
                                                        @RequestParam ProjectStatus status) {
        return ResponseEntity.ok(projectService.changeStatus(id, status));
    }

    /**
     * PATCH /api/projects/{id}/archive - takes a finished project out of the everyday lists.
     * 200 OK with the updated project, or 422 when the project is not COMPLETED.
     *
     * Archiving is a separate true/false flag, not a status and not a delete. The project keeps
     * its history, its figures and its documents, and stays readable through
     * GET /api/projects/archived and through unarchive() below.
     * Why the service refuses anything other than a COMPLETED project: archiving a project that
     * is still ACTIVE would hide live work from the portfolio screen while people keep booking
     * time on it, and the missing hours would only be noticed at the end of the month.
     */
    // PATCH, not PUT: one single field of the project changes and the request carries no body
    // at all. PUT is the verb for "here is the whole new content of the resource", which is not
    // what happens here. Using PUT would also suggest to any client that an empty body replaces
    // the project, which would be the exact opposite of what this call does.
    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.archive(id));
    }

    /**
     * PATCH /api/projects/{id}/unarchive - brings an archived project back into the everyday
     * lists. 200 OK with the updated project.
     *
     * Why there is no condition on the status here, unlike archive(): putting a project back
     * where everybody can see it can never hide anything nor lose anything, so a condition
     * would only trap a project that was archived by mistake. This is what makes archiving a
     * safe, reversible act, and it is the reason archiving exists next to the delete below.
     */
    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<ProjectResponse> unarchive(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.unarchive(id));
    }

    /**
     * DELETE /api/projects/{id} - removes a project and answers 204 No Content: it worked, and
     * there is nothing to send back.
     *
     * It is a SOFT delete. The service sets deleted = true and saves; the row stays in the
     * database and every read query filters it out.
     * Why not a real SQL delete: a project is the parent of timesheets, invoicing milestones,
     * payments, missions and KPI snapshots. A real delete would either destroy that history -
     * accounting data that has to be kept - or be refused by the foreign keys, so the call
     * would simply fail. The flag hides the project everywhere while the past stays auditable,
     * and it also frees the project code for reuse, because the unique-code check only looks at
     * projects that are not deleted.
     */
    // ResponseEntity<Void>: the answer carries no body at all.
    // Why 204 rather than 200 with an empty body: 204 tells the Angular client "it worked, do
    // not try to read a body". A client that parses every answer as JSON would otherwise crash
    // on an empty string.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // DELETE_PROJECT is checked inside delete(); the perimeter of this id was already
        // checked by ProjectScopeInterceptor before this method was entered.
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
