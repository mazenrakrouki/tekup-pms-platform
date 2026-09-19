package com.pms.project.controller;

import com.pms.project.dto.DevisInterneResponse;
import com.pms.project.dto.LigneDiRequest;
import com.pms.project.service.DevisInterneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * WHAT THIS FILE IS
 * The REST entry point for the Devis Interne of one project. DI (Devis Interne = internal
 * quote) is the sheet where the company puts what it SOLD to the client next to what the work
 * really COSTS it, line by line, and reads the margin that comes out of it. This class exposes
 * four calls: read the whole quote, add a line, change a line, remove a line.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular DiService (get, addLigne, updateLigne, deleteLigne), used by the
 *   devis-interne screen
 *     -> HTTP call on /api/projects/{projectId}/devis-interne...
 *     -> JwtAuthenticationFilter puts the logged-in user in the security context
 *     -> ProjectScopeInterceptor checks that this user may touch THIS project (ADR-021)
 *     -> this controller: reads the URL and the JSON body, picks the HTTP status code
 *     -> DevisInterneService: checks the MANAGE_DI permission, writes the line, then
 *        RECOMPUTES the whole quote
 *     -> LigneDiRepository + ProjectRepository -> DevisInterneResponse sent back as JSON.
 *
 * WHY IT EXISTS
 * Without this file the lignes_di table can never be filled, so the DI screen has nothing to
 * show and, more importantly, the baseline "marge nette vendue" of a project can never be
 * computed from real lines. KpiService asks DevisInterneService.computeMargeVenduePct for that
 * baseline; with no line saved it gets an empty answer and the margin indicator of the project
 * has nothing to compare the current figures against.
 *
 * THE MOST SENSITIVE AREA OF THE APPLICATION, AND WHAT PROTECTS IT
 * The DI shows internal daily costs (TCC), overheads and the real margin per line. Two guards
 * stand in front of it, and both are needed:
 *   1. the permission MANAGE_DI, checked with @PreAuthorize on each of the four
 *      DevisInterneService methods this controller calls - a permission that is deliberately
 *      narrow, and that the authorization matrix currently grants to one single role (see the
 *      @Tag note below). Be precise if a jury asks: one method of that service carries no
 *      @PreAuthorize, computeMargeVenduePct, and that is a deliberate exception. No endpoint
 *      maps to it, it is called inside the server by KpiService, and what it gives back is one
 *      aggregated margin percentage - a steering figure, never an internal cost price;
 *   2. the project perimeter of ADR-021, enforced by ProjectScopeInterceptor on the URL.
 * Example of why one alone is not enough: someone holding MANAGE_DI but working only on
 * project 7 calls GET /api/projects/9/devis-interne. His permission is real, so a permission
 * check on its own would hand him the cost structure and the margin of a project that is none
 * of his business. The interceptor reads the 9 in the path first and answers 403.
 *
 * NOTHING IS STORED, EVERYTHING IS DERIVED (F-AFF-13)
 * The database keeps only what a human typed: days sold, unit selling price, internal days,
 * internal daily cost, expenses, percentage rate. Every amount, every total and every margin
 * is recomputed by DevisInterneService.compute() each time the quote is read.
 * Why that choice rather than storing the totals in columns: the amounts depend on the
 * exchange rate of the project, and lines in the AUTRES_FRAIS block are priced as a percentage
 * of the whole sold total. A stored total would become wrong the moment the rate is corrected
 * or a line is added somewhere else in the quote, and nobody would notice that the margin
 * shown is stale. Derived figures cannot drift.
 *
 * WHAT IT DELIBERATELY DOES NOT CONTAIN
 * No permission test, no arithmetic, no check that the line really belongs to the project.
 * Authorization is dynamic and permission-based: the hasAuthority('MANAGE_DI') checks sit on
 * the DevisInterneService methods, and the code never tests a role name. The pairing check
 * (does this ligneId belong to this projectId?) is done in DevisInterneService.loadLigne.
 */
// @Tag is documentation only: springdoc reads it and groups the four endpoints of this class
// under one heading in the generated OpenAPI / Swagger UI page. Nothing changes at run time.
// Why it is needed: without it these URLs are listed under an automatic name such as
// "devis-interne-controller", mixed in with the other groups that share the /api/projects
// prefix, and a reader of the API page cannot see at a glance that this group is the sensitive
// one. The text is in French because every @Tag of the backend is written in French, so the
// generated API page stays in one single language from end to end.
// Careful when reading the French text "accès MANAGE_DI - Directeur uniquement": it is a note
// for the human reading the API page, saying which role currently holds MANAGE_DI in the
// authorization matrix. The code itself never tests that role name - it tests the capability
// MANAGE_DI. Granting that capability to another role tomorrow is one row added in
// role_permission and changes nothing in any Java file; only this sentence would need an update.
@Tag(name = "Devis Interne", description = "Structure DI vide + calcul marges (accès MANAGE_DI — Directeur uniquement)")
// @RestController = @Controller + @ResponseBody. Spring routes the HTTP requests to this class
// and writes every returned object straight into the answer as JSON.
// Why: with a plain @Controller, Spring would read the returned value as the NAME of an HTML
// page to render, look for a template file and fail, instead of sending the JSON the DI screen
// expects.
@RestController
// Base URL shared by the four methods below. {projectId} is a placeholder filled from the real
// URL and read by @PathVariable.
// Why the project id sits IN the path and not in the body: this exact shape,
// /api/projects/{number}/..., is what ProjectScopeInterceptor matches in order to run the
// ADR-021 perimeter check before any method here starts. A URL such as
// /api/devis-interne?projectId=9 would carry the same information and would be completely
// invisible to the interceptor, so the most sensitive screen of the application would be the
// one place where the perimeter is not enforced.
@RequestMapping("/api/projects/{projectId}/devis-interne")
// Lombok writes, at compile time, the constructor taking every final field (here only
// devisInterneService). Spring uses that constructor to inject the service.
// Why constructor injection rather than @Autowired on the field: the field stays final, so
// nothing can swap the service after start-up, and a unit test can build the controller with a
// fake service in one line without starting Spring at all.
@RequiredArgsConstructor
public class DevisInterneController {

    // The single collaborator. The MANAGE_DI check, the two-pass calculation engine, and the
    // rule that a line must belong to the project named in the URL all live on the other side
    // of this field. This class only turns HTTP into a method call and back.
    private final DevisInterneService devisInterneService;

    /**
     * GET /api/projects/{projectId}/devis-interne - the whole internal quote of the project:
     * its currency and exchange rate, every line with its computed amounts, and the totals
     * (sold in currency, sold in TND, days sold, internal days, final cost, net margin, margin
     * percentage). 200 OK, or 404 when the project does not exist or is deleted.
     *
     * A project with no line yet answers 200 with an empty list of lines and totals at zero,
     * not 404. Why: the DI is not a separate object that must be created first - it is simply
     * the set of lines attached to the project. The screen can therefore open an empty grid and
     * let the user type his first line, instead of having to ask for a quote to be created.
     * This is what the phrase "structure DI vide" in the @Tag above refers to: the shape of the
     * quote exists for every project, with no seeded data inside it.
     */
    // @GetMapping with no value: this method answers GET on the base URL of the class.
    // Why GET: it is the read verb, so a refresh, the back button or a cache may replay it
    // freely. Behind a POST, the browser would ask "resend the form?" on every refresh of the
    // DI screen.
    // @PathVariable copies the number written in the URL into the parameter; the name projectId
    // matches the {projectId} placeholder declared on the class.
    // Why it is needed: without it the parameter stays null, the service looks for a project
    // with id null, and a perfectly valid URL answers 404.
    @GetMapping
    public ResponseEntity<DevisInterneResponse> get(@PathVariable Long projectId) {
        // ResponseEntity.ok(...) = HTTP 200 with this body. The MANAGE_DI check, the loading of
        // the non-deleted lines and the whole calculation all happen inside getDevisInterne.
        return ResponseEntity.ok(devisInterneService.getDevisInterne(projectId));
    }

    /**
     * POST /api/projects/{projectId}/devis-interne/lignes - adds one line to the quote and
     * answers 200 OK with the WHOLE recomputed quote, not only the line that was just created.
     *
     * Why the whole quote comes back, which is the key point of this class: the calculation
     * engine works in two passes. Pass one adds up everything that was sold; pass two prices
     * each line, and the lines of the AUTRES_FRAIS block that carry a percentage rate (local
     * taxes, registration fees, risk provision) are costed as that percentage of the sold total
     * in TND. So adding one single line of fees changes the cost of every percentage line, the
     * grand totals and the net margin.
     * What would go wrong by returning only the created line: the screen would add one row and
     * keep showing the old totals and the old margin. The user would read a margin of 44 % when
     * the real figure, after his own line, is 39 %, and he would take a commercial decision on
     * a number that is already wrong on his screen.
     *
     * Why 200 OK and not the usual 201 Created with a Location header: the body returned is not
     * the created resource, it is the recomputed quote. There is also no GET on a single DI
     * line anywhere in the API, so a Location header would point at a URL that answers 404.
     */
    // @Valid runs the checks declared on the LigneDiRequest record - section is required, the
    // text fields have a maximum length, the amounts cannot be negative, and tauxPourcentage
    // must stay between 0 and 1 - BEFORE the body of this method starts. A failure throws
    // MethodArgumentNotValidException, which GlobalExceptionHandler turns into 400 Bad Request
    // naming the guilty fields.
    // Why it is needed here more than anywhere else: these values feed an arithmetic engine. A
    // negative number of days sold would produce a negative sold amount, which would lower the
    // sold total in TND, which would in turn lower the cost of every percentage line. One bad
    // field would quietly move the margin of the whole quote. And a rate typed as 5 instead of
    // 0.05 would price a 5 % provision at five times the entire contract.
    // The rate must be written as a fraction: 0.05 means 5 %, which is why the bound is 1.
    // @RequestBody turns the JSON body into that record. DTO = Data Transfer Object, a small
    // object whose only job is to carry data between the browser and the server.
    // Why a DTO instead of the LigneDi entity: a caller could otherwise post {"deleted": true}
    // or {"project": {"id": 9}} and write fields the API never meant to expose - the second one
    // would attach his new line to the quote of another project, behind the perimeter check
    // that had just approved the project written in the URL.
    @PostMapping("/lignes")
    public ResponseEntity<DevisInterneResponse> addLigne(@PathVariable Long projectId,
                                                         @Valid @RequestBody LigneDiRequest request) {
        // MANAGE_DI, the save and the recomputation all happen inside addLigne, in one single
        // transaction, so the totals that come back always match the lines that were stored.
        return ResponseEntity.ok(devisInterneService.addLigne(projectId, request));
    }

    /**
     * PUT /api/projects/{projectId}/devis-interne/lignes/{ligneId} - replaces the whole content
     * of one line and answers 200 OK with the whole recomputed quote, for the same reason as
     * addLigne above.
     *
     * Why PUT with a complete body: the DI grid always sends every field of the edited line
     * back, so the server never has to guess whether a missing field means "leave it alone" or
     * "clear it". Emptying an expense field is then a real, explicit action - which matters
     * here, because a field left at its old value by accident would keep inflating the cost of
     * the line and understating the margin.
     */
    // Two path values are read: projectId says which project, ligneId says which line. The
    // service uses BOTH, and refuses a line whose project is not projectId.
    // Why that pair check is needed: the ADR-021 perimeter check only looked at the project
    // number written in the URL. Without the pair check, PUT on
    // /api/projects/7/devis-interne/lignes/5 would rewrite line 5 even when line 5 belongs to
    // project 9 - someone allowed on project 7 would be changing the cost structure of a quote
    // he is not even allowed to read.
    @PutMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> updateLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId,
                                                            @Valid @RequestBody LigneDiRequest request) {
        return ResponseEntity.ok(devisInterneService.updateLigne(projectId, ligneId, request));
    }

    /**
     * DELETE /api/projects/{projectId}/devis-interne/lignes/{ligneId} - removes one line from
     * the quote and answers 200 OK with the whole recomputed quote.
     *
     * It is a SOFT delete: the service sets deleted = true on the line and saves it. The row
     * stays in the table and the query that loads the quote filters it out.
     * Why not a real SQL delete: the DI is the document that justifies the price given to a
     * client, so how the quote was built - including a line that was tried and dropped - has to
     * stay auditable long after the project is closed.
     *
     * Why this DELETE answers 200 with a body instead of the usual 204 No Content: removing a
     * line changes the sold total in TND, and therefore the cost of every AUTRES_FRAIS line
     * priced as a percentage, and therefore the net margin of the project. With 204 the screen
     * would remove one row and keep displaying totals that no longer match the lines shown
     * right above them. The exception to the usual REST habit is deliberate and is the same
     * choice as in addLigne and updateLigne.
     */
    // projectId is passed on so the service can refuse a line that belongs to another project,
    // exactly as in updateLigne above. Without it, a line could be deleted from a quote that
    // the caller was never allowed to open.
    @DeleteMapping("/lignes/{ligneId}")
    public ResponseEntity<DevisInterneResponse> deleteLigne(@PathVariable Long projectId,
                                                            @PathVariable Long ligneId) {
        return ResponseEntity.ok(devisInterneService.deleteLigne(projectId, ligneId));
    }
}
