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

/*
 * ===========================================================================
 * FILE HEADER - TeamController.java
 * ===========================================================================
 * WHAT THIS FILE IS
 *   The HTTP door of the "team" feature: who works on which project. It
 *   publishes five REST endpoints (list a team, add a member, change a
 *   member, remove a member, and read all the projects of one person) and
 *   turns each HTTP call into exactly one call to TeamAssignmentService.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular TeamService
 *   (frontend/pms-frontend/src/app/core/services/team.service.ts)
 *     -> HTTP request carrying the short-lived JWT access token in the
 *        Authorization header
 *     -> JwtAuthenticationFilter (com.pms.auth.security) reads that token and
 *        puts the user and his permissions into the SecurityContext
 *     -> ProjectScopeInterceptor (com.pms.shared.config, ADR-021) checks the
 *        project perimeter, but only for URLs shaped /api/projects/{id}/**
 *     -> THIS CONTROLLER maps verb + URL to a service method and picks the
 *        HTTP status code
 *     -> TeamAssignmentService holds the permission checks
 *        (hasAuthority VIEW_TEAM / ASSIGN_DEVELOPER), the business rules and
 *        the transaction
 *     -> TeamAssignmentRepository reads or writes the table team_assignments,
 *        TeamAssignmentMapper turns the TeamAssignment entity into the
 *        TeamAssignmentResponse record
 *     -> JSON answer back to Angular.
 *   Nothing else in the backend calls this class: a controller is an entry
 *   point, never a helper for other server code.
 *
 * WHY IT EXISTS
 *   Delete this file and the team feature loses its address on the network.
 *   TeamAssignmentService would still compile and still work, but no URL
 *   would reach it, so "POST /api/projects/7/team" would answer 404 and the
 *   project detail screen could no longer show the team, add a developer or
 *   remove one.
 *   It also keeps the layering rule of docs/ARCHITECTURE.md ("controllers
 *   never touch repositories directly; entities never leave the service
 *   layer"): only the two DTO records - a DTO is a small object that carries
 *   the data of one request or one answer and nothing else - travel through
 *   this file. The TeamAssignment entity never appears here, so a lazy
 *   relation can never be read outside its transaction.
 * ===========================================================================
 */

/**
 * REST controller for team assignments. One assignment row = one person on
 * one project, with a role and a period.
 *
 * <p>It is deliberately thin: every method does at most three things -
 * receive the request, delegate to the service, choose the HTTP status.
 * There is no rule, no database call and no permission test in this class.
 * Why this way: the authorization of this project is permission based and
 * lives on the SERVICE methods, so that any other caller (a scheduled job, a
 * unit test, a future screen) is guarded by the same check. A check written
 * in a controller would simply be skipped by those callers.
 *
 * <p>There is NO class level {@code @RequestMapping} here, unlike most
 * controllers of this project. Why: this class answers on two different URL
 * roots, {@code /api/projects/{projectId}/team...} and
 * {@code /api/users/{userId}/assignments}. A class level prefix such as
 * {@code @RequestMapping("/api/projects")} would make the second URL
 * impossible to declare in this class, so each method carries its full path.
 * The practical consequence is explained on listByUser below: only the
 * project-rooted URLs are seen by ProjectScopeInterceptor.
 */
// What: gives this group of endpoints a readable name and description in the
// generated API page (Swagger UI, /swagger-ui.html). The text stays in French
// because it is displayed to the reader of the API, it is not a comment.
// Why: the backend publishes around eighteen controllers. Without this tag,
// springdoc invents the group name "team-controller" with no description, and
// someone opening the API page to test the team endpoints has to guess which
// technical name hides them.
@Tag(name = "Équipe", description = "Affectation / retrait des membres d'équipe + historique par projet")
// What: @RestController = @Controller (Spring scans this class at startup and
// registers its URL mappings) + @ResponseBody (what a method returns is
// written straight into the response body as JSON).
// Why: without a stereotype annotation Spring never sees this class at all.
// Example: every call to /api/projects/7/team would answer 404 "Route
// introuvable", even though the code below is perfectly correct.
@RestController
// What: Lombok generates, at compile time, a constructor with one parameter
// per final field - here the single TeamAssignmentService. Spring sees one
// constructor and injects the service into it.
// Why: it keeps the field final (no one can swap the service at runtime) and
// it lets a test build the controller by hand with a mock service.
// Example: remove this line and no constructor gives a value to the final
// field, so the class does not even compile.
@RequiredArgsConstructor
public class TeamController {

    // The only collaborator of this controller. The repository is NOT injected
    // here on purpose: a controller that reads the database directly would
    // bypass the permission checks and the transaction of the service layer.
    private final TeamAssignmentService teamAssignmentService;

    /**
     * Lists the active members of one project.
     *
     * <p>Gives back HTTP 200 and a JSON array of TeamAssignmentResponse, empty
     * when nobody is assigned; 404 when the project does not exist or has been
     * soft deleted; 403 when the caller is not allowed (see below).
     *
     * <p>Why no security code here (ADR-021): two independent checks already
     * run before and after this line, and BOTH must pass.
     * (1) ProjectScopeInterceptor runs before the method, because the URL
     * matches /api/projects/{id}/**, and refuses a project outside the
     * caller's perimeter.
     * (2) TeamAssignmentService.findByProject carries
     * {@code hasAuthority('VIEW_TEAM')}.
     * Example: a developer who holds VIEW_TEAM but is not a member of project
     * 42 calls GET /api/projects/42/team and receives 403, not the team list.
     * Holding the permission is never enough on a project resource.
     *
     * <p>The service also loads the project first, so an unknown project gives
     * a clean 404 instead of a silently empty array.
     */
    // What: binds HTTP GET on this exact URL shape to this method; {projectId}
    // is a placeholder that matches one URL segment.
    // Why the full path instead of a short one: this class has no class level
    // prefix (see the class comment), so each method spells out its own URL.
    // Example: without this annotation the method is never reached and the
    // request falls through to a 404.
    @GetMapping("/api/projects/{projectId}/team")
    // What: @PathVariable copies the text found in the {projectId} slot of the
    // URL and converts it into a Long. The parameter name must match the
    // placeholder name.
    // Why: the service needs a real number to query the database, not text.
    // Example: GET /api/projects/abc/team cannot be converted, Spring raises
    // MethodArgumentTypeMismatchException and GlobalExceptionHandler turns it
    // into a readable 400 "Valeur invalide pour le paramètre 'projectId'"
    // instead of an ugly 500.
    public ResponseEntity<List<TeamAssignmentResponse>> listByProject(@PathVariable Long projectId) {
        // ResponseEntity.ok(...) = status 200 plus this body. The list comes
        // back already mapped to records by the service, so no entity and no
        // lazy relation can leak into the JSON.
        return ResponseEntity.ok(teamAssignmentService.findByProject(projectId));
    }

    /**
     * Adds one person to the team of a project.
     *
     * <p>Gives back HTTP 201 Created, the saved row (with its new id) in the
     * body, and a Location header pointing at that new row. Other answers,
     * all decided by the service and translated by GlobalExceptionHandler:
     * 400 when a field of the body is missing or too long, 404 when the
     * project or the user is unknown, 409 when this person is already an
     * active member of this project, 422 when the end date is before the
     * start date, 403 when the caller misses ASSIGN_DEVELOPER or the project
     * is outside his perimeter.
     *
     * <p>Why 201 and not 200: 201 is the HTTP answer that means "a new
     * resource now exists", and it is the status the Angular side and any
     * REST client expect after a creation.
     */
    // What: binds HTTP POST on the team collection of one project to this
    // method. Same URL as the GET above: the verb is what separates them.
    // Why: in REST, "create inside a collection" is POST on the collection
    // URL, and the server chooses the id of the new row.
    @PostMapping("/api/projects/{projectId}/team")
    public ResponseEntity<TeamAssignmentResponse> assign(@PathVariable Long projectId,
                                                         // What: @Valid switches on the validation rules written inside the
                                                         // TeamAssignmentRequest record: userId not null, roleInTeam not blank
                                                         // and at most 50 characters, startDate not null. @RequestBody tells
                                                         // Spring to read the JSON body of the request and build that record
                                                         // from it.
                                                         // Why @Valid is not optional: without it the rules written on the
                                                         // record are simply ignored, and the null reaches the service.
                                                         // Example 1: a body with an endDate but no startDate makes the
                                                         // service run endDate.isBefore(startDate) on a null value, so the
                                                         // caller gets a 500 (NullPointerException).
                                                         // Example 2: a body with neither date passes that test and dies on
                                                         // the NOT NULL column start_date, which GlobalExceptionHandler
                                                         // reports as 409 "contrainte de données".
                                                         // Both answers hide the real cause; @Valid gives a clear 400 that
                                                         // names the missing field.
                                                         // Why @RequestBody is needed: without it Spring would look for the
                                                         // values in the query string (?userId=3) and every field would be
                                                         // null.
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        // The whole job is done by the service: it checks the permission, loads
        // the project and the user, refuses a duplicate member, refuses
        // inverted dates, saves, and maps the row to a record.
        TeamAssignmentResponse created = teamAssignmentService.assign(projectId, request);
        // What: builds the absolute address of the row that was just created,
        // for example http://localhost:8080/api/projects/7/team/135.
        // fromCurrentRequest() takes the URL of this POST
        // (/api/projects/7/team), path("/{id}") appends one segment, and
        // buildAndExpand(created.id()) fills that segment with the id given by
        // the database. "var" only means the compiler reads the type itself,
        // here java.net.URI.
        // Why: the HTTP rule for 201 is to tell the client where the new
        // resource lives. Example: without it a client that just added a
        // member would have to rebuild the URL by hand, and would break the
        // day the URL shape changes.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        // created(location) sets status 201 and the Location header in one go;
        // body(created) also sends the saved row back.
        // Why send the body too: the screen that added the member needs the
        // generated id straight away, to be able to remove or edit that member
        // without reloading the whole list.
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Changes an existing assignment: the role inside the team and the period.
     *
     * <p>Gives back HTTP 200 and the updated row. 404 when the assignment does
     * not exist, is soft deleted, OR does not belong to the project named in
     * the URL. 422 when the end date is before the start date. 403 when the
     * caller misses ASSIGN_DEVELOPER or the project is outside his perimeter.
     *
     * <p>Why the URL carries BOTH ids and why both are sent to the service:
     * ProjectScopeInterceptor only proved that the caller may work on
     * {projectId}. It knows nothing about {id}. The service therefore
     * re-checks that assignment {id} really belongs to project {projectId}.
     * Example of what this stops: a project manager allowed on project 7 sends
     * PUT /api/projects/7/team/135 while assignment 135 belongs to project 9.
     * The perimeter check passes (project 7 is his), so without the pair check
     * in the service he would edit the team of a project he cannot even see.
     * With it, the answer is 404.
     *
     * <p>PUT, not PATCH, because the client sends the whole new state of the
     * assignment. Note that the service writes only roleInTeam, startDate and
     * endDate: the userId present in the body is not used here, an assignment
     * is never moved from one person to another.
     *
     * <p>State of the code today, to be said plainly rather than guessed: the
     * Angular TeamService offers only list, assign and remove, so nothing in
     * the front end sends this PUT, and TeamControllerTest does not cover it
     * either. The route exists so that the team resource offers the four REST
     * operations on the same URL, and the service method behind it carries the
     * same permission and the same project check as the others.
     */
    // What: binds HTTP PUT on one single assignment (the collection URL plus
    // its id) to this method.
    // Why PUT on the item URL: the row already exists and keeps its id, so
    // this is a replacement of its content, not a creation.
    @PutMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<TeamAssignmentResponse> update(@PathVariable Long projectId,
                                                         // The parameter name must be the same word as the placeholder, here
                                                         // {id}. Why: that name is how Spring matches the URL segment to the
                                                         // parameter. Example: rename this parameter to assignmentId without
                                                         // writing @PathVariable("id") and the application fails to serve the
                                                         // route because no {assignmentId} placeholder exists in the path.
                                                         @PathVariable Long id,
                                                         // Same validation rules as the creation: the record is reused, so an
                                                         // empty role or a missing start date is refused with 400 before any
                                                         // database work happens.
                                                         @Valid @RequestBody TeamAssignmentRequest request) {
        return ResponseEntity.ok(teamAssignmentService.update(projectId, id, request));
    }

    /**
     * Takes one person off the team of a project.
     *
     * <p>Gives back HTTP 204 No Content with an empty body. 404 when the
     * assignment is unknown or belongs to another project (same pair check as
     * update above). 403 when the caller misses ASSIGN_DEVELOPER or the
     * project is outside his perimeter.
     *
     * <p>Important: the row is NOT erased. The service sets the flag
     * {@code deleted = true} (a soft delete) and saves. Why: the history of
     * who worked on a project must stay readable, and other rows may still
     * point at this assignment. Example of what a real DELETE would break:
     * once the row is gone, a past team list can no longer be rebuilt, and any
     * record that references it would point at nothing.
     *
     * <p>{@code ResponseEntity<Void>} plus 204 rather than 200: there is
     * nothing useful to send back, and 204 tells the client not to try to read
     * a body. A 200 with an empty body makes some HTTP clients fail while
     * parsing an empty string as JSON.
     */
    @DeleteMapping("/api/projects/{projectId}/team/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long projectId, @PathVariable Long id) {
        // projectId is passed on purpose even though the assignment id alone
        // would find the row: the service uses it to prove the row really
        // belongs to the project the caller was allowed to touch.
        teamAssignmentService.remove(projectId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reads the same table from the other side: every active assignment of one
     * person, across all projects. It answers the question "which projects is
     * this developer on, and with which role".
     *
     * <p>Gives back HTTP 200 and a JSON array (empty when the person is on no
     * project), 404 when the user does not exist or is deleted, 403 when the
     * caller does not hold VIEW_TEAM. Each item still carries projectId,
     * projectCode and projectName, because the mapper fills them from the
     * project of the row, so the screen can show a readable project name
     * without a second call.
     *
     * <p>Why this endpoint lives in TeamController and not in UserController:
     * it reads the table team_assignments through the very same service and
     * returns the very same record. Putting it in the user package would make
     * UserController depend on the team service and would duplicate the
     * mapping for no gain.
     *
     * <p>State of the code today: no Angular service builds this URL, so the
     * only thing that calls it is the integration test TeamControllerTest
     * ("GET /users/{userId}/assignments -> 200 + historique affectations").
     *
     * <p>SECURITY, to be honest about it: this URL starts with /api/users/,
     * not with /api/projects/, and WebMvcConfig registers
     * ProjectScopeInterceptor only on /api/projects/**. So the ADR-021
     * perimeter check does NOT run here. The single guard is
     * {@code hasAuthority('VIEW_TEAM')} on
     * TeamAssignmentService.findByUser. Concretely, anybody holding VIEW_TEAM
     * can read the assignments of any user, including rows that belong to
     * projects outside his own perimeter.
     */
    // Note the URL root: this is the only method of this class that is not
    // under /api/projects/... , which is exactly why the class carries no
    // class level @RequestMapping.
    @GetMapping("/api/users/{userId}/assignments")
    public ResponseEntity<List<TeamAssignmentResponse>> listByUser(@PathVariable Long userId) {
        // The service loads the user first, so an unknown or deleted user gives
        // 404 rather than an empty list that would look like "this person works
        // on nothing".
        return ResponseEntity.ok(teamAssignmentService.findByUser(userId));
    }
}
