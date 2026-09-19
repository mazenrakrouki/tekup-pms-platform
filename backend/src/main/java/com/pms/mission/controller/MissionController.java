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
 * WHAT THIS FILE IS
 * The only HTTP door into the "mission" module. A mission is a work trip made for a project
 * (the person, the purpose, the place, the start and end dates). Each mission owns a list of
 * "composantes de cout" (cost lines): per diem, plane ticket, stamp duty, transport, stay.
 * This class turns HTTP calls into service calls, and service answers back into HTTP answers.
 *
 * WHERE IT SITS IN THE FLOW
 * Angular frontend (mission pages)
 *   -> Spring Security filter chain: the JWT access token is read and the caller is
 *      authenticated. (JWT = JSON Web Token, a short-lived signed string that proves who the
 *      caller is.)
 *   -> ProjectScopeInterceptor (ADR-021): every URL of the form /api/projects/{id}/** is checked
 *      against the caller's project scope BEFORE this controller runs.
 *   -> this controller: reads the URL parts and the JSON body, then calls a service.
 *   -> MissionService / ComposanteService: this is where the permission check, the transactions
 *      and the business rules live; they call the mappers and the repositories, which talk to
 *      PostgreSQL.
 *   -> back here: the service returns a DTO and we wrap it in an HTTP status code.
 *      (DTO = Data Transfer Object, a small flat object used only to travel over HTTP. Here the
 *      DTOs are Java records: MissionRequest / MissionResponse / ComposanteRequest /
 *      ComposanteResponse. A JPA entity is never sent to the browser.)
 *
 * WHY IT EXISTS
 * Without this file the mission feature would have no HTTP surface at all: the services would
 * still compile, but the Angular pages would get 404 on every mission URL, and nobody could
 * create, read, change or remove a mission or one of its cost lines.
 *
 * WHY THERE IS NO SECURITY ANNOTATION IN THIS FILE
 * Authorization in PMS is dynamic and permission-based, and Spring's @PreAuthorize sits on the
 * SERVICE methods, not here (VIEW_MISSION to read, MANAGE_MISSION to write). The code never
 * tests a role name. Two reasons this matters: every other caller of MissionService (a test,
 * a scheduled job, another service) is protected too, and the permissions of a role can be
 * changed from the admin screens without touching Java code. Example of what a check written
 * here would not cover: a second caller of MissionService.create added next month would be
 * completely unguarded, because it would never pass through this controller.
 * Note also that the permission alone is not enough (ADR-021): a project manager who holds
 * MANAGE_MISSION but is not on project 42 is stopped by ProjectScopeInterceptor with 403,
 * before the service permission check is even reached.
 */
// @Tag is springdoc/OpenAPI only: it names and describes this group of endpoints on the
// generated Swagger page. Why: the API documentation is read by the frontend developer and by
// the jury. Without it these endpoints would be filed under a machine name like
// "mission-controller" and nobody would know what they cover.
@Tag(name = "Missions", description = "Missions + composantes (per diem, billet, transport, séjour, timbre)")
// @RestController = @Controller + @ResponseBody: Spring registers this class as a web handler AND
// turns every returned object into JSON automatically (Jackson). Why: without the @ResponseBody
// part, Spring would read the returned value as the name of an HTML view to render, and the call
// would fail with a "view not found" error instead of returning JSON.
@RestController
// Every URL below starts with /api/projects/{projectId}/missions. Why the project id sits in the
// path and not in the body: it makes a mission a child resource of a project, and above all it
// lets ProjectScopeInterceptor (ADR-021) match the pattern /api/projects/{id}/** and refuse
// out-of-scope projects for the whole module in one single place. Example of what goes wrong
// with a flat URL such as /api/missions?projectId=42: the interceptor pattern would not match,
// the scope would have to be re-checked by hand inside every service method, and one forgotten
// line would let a manager read the missions of a project that is not his.
@RequestMapping("/api/projects/{projectId}/missions")
// Lombok writes a constructor that takes every final field, and Spring uses that single
// constructor to inject the two services. Why constructor injection rather than @Autowired on
// the fields: the fields stay final, so the object can never exist half-built, and a plain unit
// test can build the controller with two mocks using "new". Without it we would have to write
// and maintain that constructor by hand.
@RequiredArgsConstructor
public class MissionController {

    // The two collaborators. They carry the permission checks, the transactions and the rules;
    // this class holds none of that on purpose (a thin controller means the same rules apply
    // whoever calls the service).
    private final MissionService    missionService;
    private final ComposanteService composanteService;

    // ── Missions ──────────────────────────────────────────────────

    /**
     * GET /api/projects/{projectId}/missions
     * Gives back the missions of one project that are not soft-deleted.
     * ("Soft delete" = the row stays in the table with a deleted flag set to true, instead of
     * being erased for good.)
     *
     * Answer: 200 with a JSON array of MissionResponse (id, project, user, purpose, place,
     * dates). A project with no mission gives 200 and an empty array, not 404.
     *
     * Why nothing is filtered here: MissionService.findByProject applies rule UC-21 itself. A
     * holder of MANAGE_MISSION (project manager) or VIEW_ALL_PROJECTS (director) sees the
     * missions of the whole team; anyone else sees only his own missions. The test is made on
     * the capability, never on the role name (ADR-001). Filtering in the controller would put a
     * business rule in the web layer, where any other caller of the service would miss it.
     */
    @GetMapping
    public ResponseEntity<List<MissionResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(missionService.findByProject(projectId));
    }

    /**
     * POST /api/projects/{projectId}/missions
     * Creates one mission inside the given project and gives it back.
     *
     * Input: projectId is read from the URL; the JSON body is bound to a MissionRequest record
     * (userId, objet, lieu, dateDebut, dateFin).
     *
     * Answer: 201 Created, the created mission in the body, and a Location header holding the
     * address of the new row. Why 201 + Location instead of a plain 200: 201 is the HTTP answer
     * that means "a new resource now exists", and Location tells the caller where it is, so a
     * client that only needs the address can read the header without parsing the body.
     *
     * Error paths, all turned into HTTP answers by GlobalExceptionHandler, not here: a body that
     * breaks a validation rule gives 400, an unknown project or user gives 404, a missing
     * permission or a project out of scope gives 403, and an end date placed before the start
     * date gives 422 (that rule is checked inside MissionService).
     */
    @PostMapping
    // @Valid on the parameter below runs the Jakarta Bean Validation rules declared on
    // MissionRequest (@NotNull userId, @NotBlank objet, @NotNull dateDebut and dateFin) before
    // the method body starts, and @RequestBody tells Spring to read the HTTP body as JSON and
    // build the record from it.
    // Why @Valid: bad input is stopped at the door with a clear 400 instead of reaching the
    // database. Without it a body with objet = "" would be saved and the mission list would show
    // an empty, unusable row.
    // Why @RequestBody: without it Spring would look for query parameters instead of the body,
    // and every field of the record would arrive null.
    public ResponseEntity<MissionResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody MissionRequest request) {
        MissionResponse created = missionService.create(projectId, request);
        // Builds the address of the new mission from the URL being served right now
        // (/api/projects/7/missions) plus the new id -> /api/projects/7/missions/12.
        // Why build it instead of writing the string by hand: the scheme, host, port and context
        // path are taken from the real request, so the header stays correct in every environment
        // and behind a reverse proxy. A hard-coded "http://localhost:8080/..." would send the
        // client back to the developer machine once the application is deployed.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/missions/{id}
     * Replaces the changeable fields of one mission (owner, purpose, place, start and end date)
     * and gives the updated mission back with 200.
     *
     * Why PUT and not PATCH: the body carries the complete picture of the mission, so sending it
     * twice leaves exactly the same result (this is what "idempotent" means - repeating the call
     * changes nothing more). A partial PATCH would force the service to tell "field absent" from
     * "field set on purpose to null", which is a known source of bugs.
     *
     * Why projectId is still handed to the service: the service answers 404 when mission {id}
     * does not belong to project {projectId}. Without that pairing check, a user working inside
     * project 7 could edit mission 99 of project 8 just by guessing its id, because the scope
     * interceptor only saw "project 7" in the URL and let the call through.
     */
    @PutMapping("/{id}")
    public ResponseEntity<MissionResponse> update(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody MissionRequest request) {
        return ResponseEntity.ok(missionService.update(projectId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/missions/{id}
     * Takes one mission out of the active data.
     *
     * Answer: 204 No Content - the work is done and there is nothing useful to send back.
     * Returning 200 with an empty body would make the frontend try to parse an empty string.
     *
     * Important: the service does a soft delete (it sets the deleted flag to true), it does not
     * erase the row. Why: a mission is part of the cost history of the project, and erasing it
     * would silently change past figures and break anything that still points at it.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        missionService.delete(projectId, id);
        // ResponseEntity<Void> is the typed way of saying "this answer has no body". The generic
        // parameter is what makes the compiler refuse a body added here later by mistake.
        return ResponseEntity.noContent().build();
    }

    // ── Composantes de coût ───────────────────────────────────────
    // Cost lines of a mission. They live under the mission URL
    // (/missions/{missionId}/composantes) because a cost line means nothing on its own: it
    // always belongs to exactly one mission. Note that soft-deleting a mission does not set the
    // deleted flag on its cost lines; they simply become unreachable, because every composante
    // endpoint loads the mission first and that load refuses a deleted mission.

    /**
     * GET /api/projects/{projectId}/missions/{missionId}/composantes
     * Gives back the cost lines of one mission that are not soft-deleted: type (PERDIEM, BILLET,
     * TIMBRE, TRANSPORT, SEJOUR), amount, three-letter currency code, free description.
     *
     * Why both ids travel to the service: ComposanteService checks again that the mission really
     * belongs to that project and answers 404 if it does not. Example of what goes wrong without
     * that check: a user allowed on project 7 asks for
     * /api/projects/7/missions/99/composantes while mission 99 lives in project 8; the scope
     * interceptor reads "project 7", agrees, and the costs of the other project are returned.
     */
    @GetMapping("/{missionId}/composantes")
    public ResponseEntity<List<ComposanteResponse>> listComposantes(@PathVariable Long projectId,
                                                                      @PathVariable Long missionId) {
        return ResponseEntity.ok(composanteService.findByMission(projectId, missionId));
    }

    /**
     * POST /api/projects/{projectId}/missions/{missionId}/composantes
     * Adds one cost line to a mission, and answers 201 Created with a Location header.
     *
     * The body (ComposanteRequest) is validated before the method body runs: the type is
     * required, the amount must be strictly positive, and the currency code must be exactly
     * three characters. Why the strict size: the service stores the code in upper case ("tnd"
     * becomes "TND"), so all the amounts of one currency group together. Without the rule,
     * "Dinar" and "TND" would be counted as two different currencies in the totals.
     */
    @PostMapping("/{missionId}/composantes")
    public ResponseEntity<ComposanteResponse> createComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @Valid @RequestBody ComposanteRequest request) {
        ComposanteResponse created = composanteService.create(projectId, missionId, request);
        // Same idea as in create(): URL being served now (.../missions/4/composantes) plus the
        // new id -> .../missions/4/composantes/31.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // This line says exactly the same thing as ResponseEntity.created(location).body(...)
        // used in create() above: HTTP 201 with a Location header and the new object as body.
        // It is simply written the long way, status first and location second.
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    /**
     * PUT /api/projects/{projectId}/missions/{missionId}/composantes/{id}
     * Replaces the four changeable fields of one cost line (type, amount, currency, description)
     * and gives it back with 200.
     *
     * Why all three ids are handed to the service: it walks the chain project -> mission -> cost
     * line and answers 404 as soon as one link does not match. With the cost line id alone, a
     * guessed number would be enough to change an amount belonging to another project, and these
     * amounts feed the project cost figures, so a wrong value here shows up in the money reports.
     */
    @PutMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<ComposanteResponse> updateComposante(@PathVariable Long projectId,
                                                                @PathVariable Long missionId,
                                                                @PathVariable Long id,
                                                                @Valid @RequestBody ComposanteRequest request) {
        return ResponseEntity.ok(composanteService.update(projectId, missionId, id, request));
    }

    /**
     * DELETE /api/projects/{projectId}/missions/{missionId}/composantes/{id}
     * Takes one cost line out of the active data and answers 204 No Content.
     * Like the mission itself this is a soft delete: the row stays and its deleted flag is set,
     * so the money spent on past missions can still be explained later.
     */
    @DeleteMapping("/{missionId}/composantes/{id}")
    public ResponseEntity<Void> deleteComposante(@PathVariable Long projectId,
                                                  @PathVariable Long missionId,
                                                  @PathVariable Long id) {
        composanteService.delete(projectId, missionId, id);
        return ResponseEntity.noContent().build();
    }
}
