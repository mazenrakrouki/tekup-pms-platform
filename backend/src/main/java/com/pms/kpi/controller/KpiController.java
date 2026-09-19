package com.pms.kpi.controller;

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.dto.SnapshotRequest;
import com.pms.kpi.service.KpiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST entry point for the indicators (KPI = Key Performance Indicator, a figure that says
 * how a project is doing) of ONE project. It exposes three things: the live calculation,
 * the list of the snapshots already taken, and the creation of a new snapshot during the
 * monthly project review.
 *
 * Where it sits in the request flow:
 *   Angular ProjectService (getLiveKpi, getSnapshots, createSnapshot)
 *     -> HTTP call on /api/projects/{projectId}/kpi and /api/projects/{projectId}/kpi/snapshots
 *     -> JwtAuthenticationFilter reads the short-lived access token and puts the logged-in
 *        user in the security context
 *     -> ProjectScopeInterceptor checks that this user may touch THIS project (ADR-021)
 *     -> this controller: reads the URL and the JSON body, picks the HTTP status code
 *     -> KpiService: checks the permission, loads the data, does the arithmetic
 *     -> SnapshotKpiRepository + SnapshotKpiMapper -> KpiResponse sent back as JSON.
 *
 * Why this file exists: it is the only door to the KPI engine. Delete it and the project
 * detail screen shows no budget, no EAC (Estimate At Completion, the forecast of the final
 * cost), no margin and no EVM figure, and the monthly review can no longer freeze the
 * situation of the month, so the history of the project stays empty for ever.
 *
 * The two GET endpoints look alike but are not the same thing:
 *  - GET /kpi recomputes everything from the data that exists today. In the answer
 *    snapshotId and snapshotDate are null, and the warnings list can carry messages
 *    (H-3: without such a message, a team member with no daily rate would be counted
 *    at cost 0 and nobody would see it).
 *  - GET /kpi/snapshots returns rows frozen on the day they were created, newest first.
 *    There snapshotId and snapshotDate are filled and warnings is always empty, because
 *    SnapshotKpiMapper cannot rebuild a warning that belonged to another day.
 *  Both are needed: the live call answers "where are we now", the snapshots answer "what
 *  did we say last month", and only stored rows let the project manager compare two dates.
 *
 * What this file deliberately does NOT contain: no permission test and no formula.
 * Authorization is dynamic and permission-based, and the hasAuthority checks sit on the
 * KpiService methods (VIEW_KPI to read the two GET endpoints, EDIT_PROJECT to create a
 * snapshot). The code never tests a role name. Every amount (EAC, margin, CA production,
 * FAE...) is derived inside KpiService when it is read, never stored in a column and never
 * recomputed here.
 *
 * Link with the rest of the slice: the two DTOs (Data Transfer Object, a plain object used
 * only to carry data in and out of the API) are SnapshotRequest coming in and KpiResponse
 * going out. SnapshotRequest carries the three values that only a human can give - the
 * EV % (Earned Value, the share of the work the project manager judges done), the estimated
 * end date, and the notable facts of the month. KpiResponse carries everything the engine
 * derives from them, and it is also what SnapshotKpiMapper produces from a stored
 * SnapshotKpi row, so the Angular screen reads live figures and frozen figures with one
 * single shape.
 */
// @Tag is documentation only: springdoc reads it and groups the three endpoints of this
// class under one heading in the generated OpenAPI / Swagger UI page. Nothing changes at
// runtime.
// Why it is needed: without it the API page lists these URLs under an automatic name such
// as "kpi-controller", and a reader cannot see that the live calculation and the snapshots
// belong to the same feature. The text is in French because every @Tag of the backend is
// written in French, so the generated API page stays in one single language end to end.
@Tag(name = "KPI", description = "Calcul live des indicateurs financiers (EAC, marge, EVM) + snapshots mensuels")
// @RestController = @Controller + @ResponseBody. Spring routes HTTP requests to this class
// and writes every returned object straight into the answer as JSON.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an
// HTML page to render, look for a template file and fail, instead of sending the JSON that
// the Angular project screen expects.
@RestController
// Base URL shared by the three methods below. {projectId} is a placeholder filled from the
// real URL and read by @PathVariable.
// Why the project id sits IN the path and not in the body: this exact shape,
// /api/projects/{id}/**, is what ProjectScopeInterceptor matches to run the project scope
// check of ADR-021 before any method of this class starts.
// Example of what that prevents: a project manager holds VIEW_KPI and calls
// GET /api/projects/9/kpi for a project he does not manage. His permission is real, so a
// permission check alone would hand him the budget, the margin and the forecast cost of
// somebody else's project. The interceptor reads 9 in the path, sees it is outside his
// scope and answers 403 first. Permission AND scope are both required.
@RequestMapping("/api/projects/{projectId}/kpi")
// Lombok writes, at compile time, the constructor that takes every final field (here only
// kpiService), and Spring uses that single constructor to inject the service.
// Why not @Autowired on the field: a field injected that way cannot be final, so nothing
// stops it from being replaced later, and a plain unit test cannot pass a fake service in.
// With the generated constructor the dependency is required, set once, and visible.
@RequiredArgsConstructor
public class KpiController {

    private final KpiService kpiService;

    /**
     * GET /api/projects/{projectId}/kpi - recomputes every indicator of the project from
     * the data that exists right now and answers 200 with one KpiResponse whose snapshotId
     * and snapshotDate are null.
     *
     * Why it recomputes instead of reading the last stored row: the sensitive amounts are
     * always derived when they are read. A timesheet line validated this morning, or a
     * daily rate corrected yesterday, must be visible at once. If this endpoint served the
     * last snapshot, the screen would show the figures of the last review and the project
     * manager would take a decision on numbers that are weeks old.
     *
     * The one value that is NOT recomputed is the EV %: KpiService reuses the EV of the
     * most recent snapshot, because EV is a human judgement given once a month, not
     * something the database can deduce. As long as no snapshot exists, evPct stays null
     * and KpiService adds a warning saying that CA production, FAE and current margin
     * cannot be shown yet.
     *
     * The @PathVariable annotation tells Spring to take the number written in the URL and
     * give it to this parameter: GET /api/projects/7/kpi -> projectId = 7. Without it Spring
     * would stop reading the path and would look for the value in the query string
     * (?projectId=7), so every normal call would fail with a missing parameter error.
     *
     * ResponseEntity.ok(...) wraps the body in an answer with status 200. Returning the
     * bare object would work too, but the wrapper keeps the status code written in the code
     * and makes this method read the same way as createSnapshot below, which must return
     * 201 and a header.
     */
    @GetMapping
    public ResponseEntity<KpiResponse> live(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.computeLive(projectId));
    }

    /**
     * GET /api/projects/{projectId}/kpi/snapshots - returns the frozen indicator rows of
     * the project, newest date first (that ordering comes from the repository query, not
     * from this class), and 200 with an empty list when no review has been done yet.
     *
     * Why a plain List and not a page: the service refuses more than one snapshot per
     * project per day, and the review that creates them is monthly, so the number of rows
     * per project stays small. Paging here would add a page number, a total count and more
     * code on both sides for a list that fits on one screen.
     *
     * Why an unknown project gives 404 and not an empty list: KpiService loads the project
     * first, so GET /api/projects/999/kpi/snapshots answers "project not found". Without
     * that load the caller would get [] and believe the project exists but has no history.
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<KpiResponse>> snapshots(@PathVariable Long projectId) {
        return ResponseEntity.ok(kpiService.findSnapshots(projectId));
    }

    /**
     * POST /api/projects/{projectId}/kpi/snapshots - freezes today's indicators into a new
     * SnapshotKpi row and answers 201 Created with the stored figures. This is the monthly
     * review gesture of F-AFF-13 ("Situation actuelle"): the project manager gives the
     * EV %, the estimated end date and the notable facts, and the engine stores them next
     * to the amounts computed at that instant.
     *
     * Why the write path is gated on EDIT_PROJECT and not on VIEW_KPI: taking a snapshot
     * changes the official history of the project, which is an act of project management,
     * while VIEW_KPI only opens reading. With the seeded roles this costs nothing, because
     * DIRECTEUR and CHEF_PROJET hold both permissions. The check itself lives on
     * KpiService.createSnapshot, never here.
     *
     * The @Valid annotation asks Spring to run the rules declared inside SnapshotRequest
     * before the body of this method starts: evPct between 0 and 100, and faitsMarquants
     * at most 2000 characters. A broken rule raises MethodArgumentNotValidException, which
     * GlobalExceptionHandler turns into 400 Bad Request. Without it, an EV of 250 would be
     * accepted and stored, and CA production would be computed as two and a half times the
     * whole contract.
     *
     * The setting @RequestBody(required = false) makes the JSON body optional, so the
     * request parameter is null when the client posts nothing. That case is legitimate - it
     * means "freeze today's figures with the EV we already know" - and KpiService then
     * reuses the EV of the previous snapshot. Without it, Spring would answer 400 for an empty
     * POST even though the server has everything it needs.
     *
     * Two snapshots on the same day are refused by the service, which raises
     * IllegalArgumentException; GlobalExceptionHandler maps it to 409 Conflict, and the
     * Angular screen shows its "snapshot already exists" message exactly on that 409.
     * Why refuse: two rows on the same date would make the history show two different
     * truths for one day, and nothing would say which one the review approved.
     */
    @PostMapping("/snapshots")
    public ResponseEntity<KpiResponse> createSnapshot(@PathVariable Long projectId,
                                                      @Valid @RequestBody(required = false) SnapshotRequest request) {
        KpiResponse snapshot = kpiService.createSnapshot(projectId, request);
        // Builds the absolute address of the row that was just created: fromCurrentRequest
        // copies the scheme, host, port and path of the POST being served, then "/{id}" is
        // appended and filled with the id of the new snapshot.
        // Why build it from the current request instead of writing the URL by hand: the
        // host and the port are not the same on the developer machine, in Docker and on the
        // demo server, so a hard-coded "localhost:8080" would be wrong everywhere else.
        // Example: POST /api/projects/7/kpi/snapshots -> /api/projects/7/kpi/snapshots/42.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(snapshot.snapshotId()).toUri();
        // 201 Created plus a Location header is the HTTP way of saying "made, and it lives
        // there"; the body still carries the whole snapshot, so the caller does not need a
        // second round trip. Answering a plain 200 would hide the fact that a new row was
        // written, and a reader of the Swagger page could not tell this call apart from the
        // two GET methods above.
        return ResponseEntity.created(location).body(snapshot);
    }
}
