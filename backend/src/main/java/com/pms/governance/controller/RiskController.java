package com.pms.governance.controller;

import com.pms.governance.dto.RiskRequest;
import com.pms.governance.dto.RiskResponse;
import com.pms.governance.service.RiskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// ============================================================================
// FILE: RiskController.java
// ----------------------------------------------------------------------------
// WHAT THIS FILE IS
//   The HTTP entry door for the RISK REGISTER of one project ("registre des
//   risques"). One risk = one thing that could go wrong on the project, with
//   how likely it is (probabilite), how bad it would be (impact), the plan to
//   reduce it (planMitigation) and whether it is still open (statut).
//   This class exposes four routes: list, create, update, delete.
//
// WHERE IT SITS IN THE FLOW
//   Angular GovernanceService
//     (frontend/pms-frontend/src/app/core/services/governance.service.ts)
//       -> HTTP request carrying the JWT access token. A JWT is a short signed
//          text token; the browser sends it in the "Authorization" header, and
//          the server trusts it because it checks the signature.
//       -> Spring Security filter chain: is the caller logged in? If not, 401.
//       -> ProjectScopeInterceptor (ADR-021): is THIS project inside the
//          caller's perimeter? If not, 403. It applies because every URL of
//          this class starts with /api/projects/{projectId}.
//       -> this controller
//       -> RiskService (the permission checks, the transactions and all the
//          business rules live there)
//       -> RiskRepository -> table "risks" (Flyway migration V11)
//       -> RiskMapper turns the Risk entity into a RiskResponse record
//   A DTO (Data Transfer Object) such as RiskRequest / RiskResponse is a small
//   object built only to travel as JSON, so the database entity never leaves
//   the server.
//
// WHY IT EXISTS - what breaks if you delete it
//   RiskService would still hold every rule, but nothing outside the server
//   could reach it: the governance screen could no longer show, add, edit or
//   close a risk. The four URLs would simply answer 404.
//
// ITS THREE SISTER FILES IN THIS PACKAGE
//   LivrableController, DemandeChangementController and PartiePrenanteController
//   are built exactly the same way, on the same /api/projects/{projectId}/...
//   prefix and on the same pair of permissions (VIEW_GOVERNANCE to read,
//   MANAGE_GOVERNANCE to write). They are four separate classes and not one
//   "GovernanceController" because each one owns its own table, its own DTOs
//   and its own service; splitting them keeps each Swagger tag readable and
//   lets one register change without touching the other three.
//
// DESIGN CHOICE: THIS CONTROLLER IS DELIBERATELY "THIN"
//   Every method below does only three things: read the URL parts, hand them
//   to RiskService, wrap the answer in the right HTTP status. There is no
//   "if", no computation and no @PreAuthorize in this file, and that is on
//   purpose:
//     - Permissions sit on the SERVICE methods
//       (@PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") on the read,
//        @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on the writes).
//       Why there and not here: a service can also be called from another
//       service or from a test, not only from HTTP. If the check lived in the
//       controller, any second caller reaching RiskService directly would walk
//       straight past it.
//       Note the checks name a PERMISSION, never a role name. That is the
//       dynamic authorization rule of the project: an administrator can add
//       VIEW_GOVERNANCE to a role at runtime and it works, with no redeploy.
//     - The project perimeter (ADR-021) is checked once, for all these URLs,
//       by ProjectScopeInterceptor. Holding the permission is NOT enough: a
//       project manager with MANAGE_GOVERNANCE must still be refused on a
//       project that is not his.
//   Concrete gain: nobody has to remember to repeat a perimeter test in four
//   methods, and forgetting it once would expose another client's risks.
//
// WHY THERE IS NO try/catch HERE
//   RiskService throws NotFoundException; GlobalExceptionHandler turns the
//   exceptions into a clean JSON error body. The codes it can produce are:
//     404 = not found, 422 = a business rule said no, 400 = the @Valid check
//     failed or the URL id was not a number, 403 = permission refused or
//     project out of perimeter, 401 = not logged in.
//   The 422 is in that list for completeness: a risk has no state machine, so
//   RiskService raises no BusinessRuleException today, unlike LivrableService
//   and DemandeChangementService.
//   Example: DELETE on a risk id that does not exist comes back as a readable
//   404, not as a 500 stack trace.
//
// NOTE ON THE FRENCH NAMES
//   The domain words stay in French because the company and its documents use
//   them: risque = risk, probabilite = likelihood, impact = severity,
//   plan de mitigation = the plan to reduce the risk, statut = status.
//
// NOTE ON COVERAGE
//   Today the Angular screen calls three of these four routes (list, create,
//   delete). The PUT is part of the API and of the permission matrix, but no
//   screen uses it yet.
// ============================================================================

// CLASS RiskController - the four annotations below, one by one.
//
// @Tag(name = "Gouvernance - Risques", ...)
//   WHAT: an OpenAPI / Swagger label. It groups every route of this class
//         under one heading in the generated API documentation page.
//   WHY:  the API has many controllers; a reader must find the four governance
//         registers next to each other.
//   WITHOUT IT: the documentation page shows a default "risk-controller"
//         bucket, and a reviewer has to guess what it covers.
//
// @RestController
//   WHAT: marks the class as a Spring web component AND says "whatever a
//         method returns IS the response body", written out as JSON.
//         It is @Controller + @ResponseBody in one annotation.
//   WHY:  this is a JSON API, not a server-rendered web site.
//   WITHOUT IT: with a plain @Controller, a returned object would be read as
//         the NAME OF AN HTML VIEW, and Spring would answer 404 because no
//         such template exists.
//
// @RequestMapping("/api/projects/{projectId}/risks")
//   WHAT: the common prefix of every route below. {projectId} is a
//         placeholder filled from the real URL.
//   WHY:  a risk only makes sense inside a project. This exact URL shape is
//         what ProjectScopeInterceptor matches (its pattern is
//         ^/api/projects/(\d+)(/.*)?$ ), so the ADR-021 perimeter check is
//         applied to all four routes for free.
//   WITHOUT IT: a flat route such as /api/risks/42 would not match that
//         pattern, and any user holding VIEW_GOVERNANCE could read the risks
//         of a project he has nothing to do with.
//
// @RequiredArgsConstructor (Lombok)
//   WHAT: generates, at compile time, a constructor that takes every "final"
//         field of the class.
//   WHY:  Spring injects RiskService through that constructor, and the field
//         stays final, so nothing can swap the service at runtime.
//   WITHOUT IT: you write the constructor by hand; the day you add a second
//         service and forget its parameter, the field stays null and the first
//         request fails with a NullPointerException.
@Tag(name = "Gouvernance — Risques", description = "Registre des risques : probabilité, sévérité, traitement")
@RestController
@RequestMapping("/api/projects/{projectId}/risks")
@RequiredArgsConstructor
public class RiskController {

    // The only collaborator of this class. The field is "final" and Spring fills
    // it once, at start-up, through the constructor that Lombok generated above.
    // WHY go through a service and not straight to RiskRepository: the permission
    // check (@PreAuthorize) and the transaction boundary live on the RiskService
    // methods. Calling the repository from here would jump over both, so anyone
    // able to reach the URL would read and write risks with no permission test.
    private final RiskService riskService;

    // ------------------------------------------------------------------------
    // GET /api/projects/{projectId}/risks
    //
    // WHAT IT DOES: gives back the whole risk register of one project as a JSON
    //   array of RiskResponse, sorted newest first by the repository query. Rows
    //   marked deleted are already left out (the query filters deleted = false).
    //   An empty array with status 200 is a normal answer: a project with no risk
    //   yet is a normal project, not an error.
    // WHO CALLS IT: the Angular governance screen (tab "Risques") and the project
    //   detail page, both through GovernanceService.listRisks().
    // WHY IT IS WRITTEN THIS WAY: the method hands the answer of the service back
    //   without touching it. The VIEW_GOVERNANCE check, the read-only transaction
    //   and the 404 on an unknown project all happen inside
    //   RiskService.findByProject, so there is nothing left to decide here.
    //
    // @GetMapping
    //   WHAT: binds this method to HTTP GET on the class prefix, with nothing
    //         added after it.
    //   WHY:  reading changes nothing, so it must be a GET; a browser, a proxy or
    //         a "reload" may repeat a GET as many times as they want.
    //   WITHOUT IT: Spring would not know that this method answers a URL, and the
    //         GET would come back as 404 while the code sits here unused.
    //
    // @PathVariable Long projectId
    //   WHAT: takes the {projectId} piece of the URL and converts that text into a
    //         Long. In /api/projects/42/risks, projectId becomes 42.
    //   WHY:  a risk only exists inside a project, and the id must travel in the
    //         URL so that ProjectScopeInterceptor (ADR-021) can read it as well.
    //   WITHOUT IT: the parameter would stay null and the service would look for
    //         the risks of "no project".
    //   IF THE VALUE IS WRONG: /api/projects/abc/risks cannot be converted, and
    //         GlobalExceptionHandler turns that into a clean 400, not a 500.
    //
    // ResponseEntity<List<RiskResponse>>
    //   WHAT: the part between < > is a generic; it tells the compiler, and the
    //         Swagger page, that the body is a list of RiskResponse and of nothing
    //         else. ResponseEntity is the wrapper that carries body + status, and
    //         .ok(...) sets that status to 200.
    //   WITHOUT the generic: the body would be a list of unknown things, the
    //         generated documentation would show an array of "object", and the
    //         Angular side would have no shape to line its Risk model up with.
    // ------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<RiskResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(riskService.findByProject(projectId));
    }

    // ------------------------------------------------------------------------
    // POST /api/projects/{projectId}/risks
    //
    // WHAT IT DOES: adds one risk to the register of this project and gives back
    //   the saved risk, id included, with status 201 Created.
    // WHY 201 PLUS A "Location" HEADER instead of a plain 200: 201 is the HTTP
    //   way of saying "a new resource now exists", and Location gives its address,
    //   for example /api/projects/42/risks/57. A client that kept only the header
    //   can read the risk again later without having to guess how the URL is put
    //   together. It is also what a reviewer expects from a REST API.
    //
    // @PostMapping
    //   WHAT: binds this method to HTTP POST on the class prefix.
    //   WHY:  creating changes the state of the server, so it must never be a GET.
    //   WITHOUT IT (say, as a GET): a link preview, a bookmark or a page reload
    //         would fire the call again, and the register would fill up with the
    //         same risk written several times.
    //
    // @Valid @RequestBody RiskRequest request
    //   @RequestBody WHAT: Jackson, the JSON library, reads the body of the
    //         request and builds a RiskRequest record from it. RiskRequest is a
    //         DTO (Data Transfer Object): a small object made only to travel, so
    //         the Risk database entity is never built from outside data.
    //   @Valid WHAT: runs the Bean Validation rules written inside RiskRequest
    //         (@NotBlank on description, @NotNull on probabilite, impact and
    //         statut) BEFORE the first line of this method runs. A failure is
    //         turned by GlobalExceptionHandler into a 400 that names the bad
    //         fields.
    //   WHY:  @Valid is the switch. The rules live in the DTO, but nothing runs
    //         them unless a controller parameter asks for it.
    //   WITHOUT @Valid: every rule inside RiskRequest would be skipped in silence.
    //         A risk sent with no description would travel down to Postgres, where
    //         the NOT NULL column "description" answers with an ugly 500 instead
    //         of a readable 400. Worse, a description made only of spaces passes
    //         NOT NULL, so it would be saved, and the register would show a line
    //         that says nothing. @NotBlank is what refuses both cases.
    // ------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<RiskResponse> create(@PathVariable Long projectId,
                                                @Valid @RequestBody RiskRequest request) {
        // The service does the real work: it checks MANAGE_GOVERNANCE, opens the
        // transaction, attaches the risk to the project and saves the row. It
        // gives back the DTO, already built by RiskMapper, with the new id in it.
        RiskResponse created = riskService.create(projectId, request);
        // WHAT: rebuilds the address of the request being handled right now
        //   (/api/projects/42/risks), adds "/{id}" at the end and fills that
        //   placeholder with the id the database just produced, which gives
        //   /api/projects/42/risks/57.
        // WHY built from the live request and not from a string written by hand:
        //   the host, the port and any context path stay exactly the ones the
        //   caller used, so the address also works when the app is not on
        //   localhost.
        // WITHOUT IT: the answer would carry no Location header, and a client that
        //   did not keep the body would have no way to point at what it created.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created risk in the body. The
        // body is sent as well as the header so the Angular table can show the new
        // line at once, without paying for a second GET of the whole register.
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------------
    // PUT /api/projects/{projectId}/risks/{id}
    //
    // WHAT IT DOES: overwrites the five editable fields of one risk (description,
    //   probabilite, impact, planMitigation, statut) with what the body carries,
    //   and gives the updated risk back with status 200.
    // WHY PUT AND NOT PATCH: the body is a full RiskRequest and the service copies
    //   every field of it onto the row. That is a replacement, which is what PUT
    //   means. Calling it PATCH would promise "send me only the fields you want to
    //   change", and a caller who sent just {"statut":"FERME"} would in fact wipe
    //   the description, because the missing fields would arrive as null.
    //   Note that a risk has no state machine: unlike a livrable or a demande de
    //   changement, any status can be set at any time, so there is no transition
    //   rule to check here.
    // WHY BOTH projectId AND id IN THE URL: the id alone would already find the
    //   row. The projectId is there because RiskService compares the project of
    //   the risk it loaded with the projectId of the URL and answers 404 when they
    //   differ. WITHOUT that pair: a user allowed on project 42 could send
    //   /api/projects/42/risks/57 while risk 57 belongs to project 99; the
    //   perimeter check only reads the 42, so it would say yes, and another
    //   client's risk would be rewritten.
    //
    // @PutMapping("/{id}")
    //   WHAT: binds HTTP PUT on the class prefix plus /{id}, so the full route is
    //         /api/projects/{projectId}/risks/{id}. Both placeholders are read by
    //         the two @PathVariable parameters below, matched by name.
    //
    // NOTE ON USE: no Angular screen calls this route today; the governance screen
    //   uses list, create and delete. It stays part of the API and of the
    //   permission matrix (MANAGE_GOVERNANCE on RiskService.update).
    // ------------------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<RiskResponse> update(@PathVariable Long projectId,
                                                @PathVariable Long id,
                                                @Valid @RequestBody RiskRequest request) {
        return ResponseEntity.ok(riskService.update(projectId, id, request));
    }

    // ------------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/risks/{id}
    //
    // WHAT IT DOES: takes one risk out of the register and answers 204 No Content.
    // IMPORTANT - THE ROW IS NOT ERASED: RiskService only sets deleted = true (a
    //   "soft delete"), and every repository query filters on deleted = false, so
    //   the risk disappears from the screens while the line stays in the table.
    //   WHY: a risk register is a document that can be audited months later, and
    //   the row is pointed at by the audit columns of V19. A real DELETE would
    //   lose the history for good, and the day someone asks "who closed this risk
    //   and when?" there would be nothing left to read.
    // WHY 204 AND AN EMPTY BODY: there is nothing useful left to send back. The
    //   caller already knows which id it deleted, and returning the deleted object
    //   would only invite a client to keep showing it.
    //
    // ResponseEntity<Void>
    //   WHAT: Void as the generic says "this answer has no body at all". The pair
    //         .noContent().build() produces the 204 with an empty body - build()
    //         is what actually creates the ResponseEntity, since no body is given.
    //   WITHOUT IT (for example returning the object with 200): the Angular
    //         delete() call is typed <void>; a body would simply be thrown away,
    //         and the contract shown in Swagger would stop matching the code.
    // ------------------------------------------------------------------------
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        riskService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
