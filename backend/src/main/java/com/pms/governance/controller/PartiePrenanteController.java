package com.pms.governance.controller;

import com.pms.governance.dto.PartiePrenanteRequest;
import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.service.PartiePrenanteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// ============================================================================
// FILE: PartiePrenanteController.java
// ----------------------------------------------------------------------------
// WHAT THIS FILE IS
//   The HTTP entry door for the STAKEHOLDER REGISTER of one project ("parties
//   prenantes"). One partie prenante = one person or body who affects the
//   project or is affected by it: the client sponsor, an end user, a supplier,
//   an internal department. Each one has a name (nom), a job (fonction), an
//   email, a phone number (telephone), and two levels: how much weight they
//   carry (influence) and how much they care (interet). This class exposes four
//   routes: list, create, update, delete.
//
// WHY influence AND interet MATTER
//   Together they place the person on the classic influence/interest grid, and
//   that grid decides how the project manager talks to them: high influence +
//   high interest = manage closely, low + low = keep informed and no more. Both
//   fields reuse the NiveauRisque enum (FAIBLE, MOYEN, ELEVE) that the risk
//   register already uses, so the whole governance module shows the same three
//   levels and the same three colours on screen.
//
// WHERE IT SITS IN THE FLOW
//   Angular GovernanceService
//     (frontend/pms-frontend/src/app/core/services/governance.service.ts)
//       -> HTTP request carrying the JWT access token. A JWT is a short signed
//          text token; the browser sends it in the "Authorization" header and
//          the server trusts it because it checks the signature.
//       -> Spring Security filter chain: is the caller logged in? If not, 401.
//       -> ProjectScopeInterceptor (ADR-021): is THIS project inside the
//          caller's perimeter? If not, 403. It applies because every URL of
//          this class starts with /api/projects/{projectId}.
//       -> this controller
//       -> PartiePrenanteService (permissions, transactions, business rules)
//       -> PartiePrenanteRepository -> table "parties_prenantes" (Flyway
//          migration V11)
//       -> PartiePrenanteMapper turns the entity into a PartiePrenanteResponse
//   A DTO (Data Transfer Object) such as PartiePrenanteRequest / Response is a
//   small object built only to travel as JSON, so the database entity never
//   leaves the server.
//
// WHY IT EXISTS - what breaks if you delete it
//   PartiePrenanteService would still hold every rule, but nothing outside the
//   server could reach it: the governance screen could no longer show the
//   stakeholders, add one or remove one. The four URLs would answer 404.
//
// ITS THREE SISTER FILES IN THIS PACKAGE
//   RiskController, LivrableController and DemandeChangementController are
//   built exactly the same way, on the same /api/projects/{projectId}/... prefix
//   and on the same pair of permissions (VIEW_GOVERNANCE to read,
//   MANAGE_GOVERNANCE to write). This one is the simplest of the four: a
//   stakeholder has no state machine, so there is no PATCH route here, while
//   LivrableController has three (demarrer / livrer / valider) and
//   DemandeChangementController has two (approuver / rejeter). They are four
//   classes and not one "GovernanceController" because each owns its own table,
//   DTOs and service; splitting them keeps each Swagger tag readable and lets
//   one register change alone.
//
// DESIGN CHOICE: THIS CONTROLLER IS DELIBERATELY "THIN"
//   Every method below only reads the URL parts, calls PartiePrenanteService
//   and wraps the answer in the right HTTP status. There is no "if" and no
//   @PreAuthorize in this file, on purpose:
//     - Permissions sit on the SERVICE methods
//       (@PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") on the read,
//        @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on the writes).
//       Why there and not here: a service can also be called from another
//       service or from a test, not only from HTTP. A check placed in the
//       controller would be walked past by any second caller.
//       The checks name a PERMISSION, never a role name. That is the dynamic
//       authorization rule of the project: an administrator can add
//       VIEW_GOVERNANCE to a role at runtime and it works, with no redeploy.
//     - The project perimeter (ADR-021) is checked once for all these URLs by
//       ProjectScopeInterceptor. Holding the permission is NOT enough: a
//       project manager with MANAGE_GOVERNANCE must still be refused on a
//       project that is not his. That matters here more than elsewhere, because
//       these rows hold the names, the emails and the phone numbers of the
//       client's own people.
//
// WHY THERE IS NO try/catch HERE
//   PartiePrenanteService throws NotFoundException; GlobalExceptionHandler
//   turns exceptions into a clean JSON error body:
//     404 = not found, 400 = the @Valid check failed or the URL id was not a
//     number, 403 = permission refused or project out of perimeter, 401 = not
//     logged in.
//   Example: DELETE on an id that does not exist comes back as a readable 404,
//   not as a 500 stack trace.
//
// NOTE ON THE FRENCH NAMES
//   The domain words stay in French because the company and its documents use
//   them: partie prenante = stakeholder, nom = name, fonction = job title,
//   telephone = phone number, influence = how much weight the person carries,
//   interet = how interested the person is in the project.
//
// NOTE ON COVERAGE
//   The Angular screen calls three of these four routes (list, create, delete).
//   The PUT is part of the API and of the permission matrix, but no screen uses
//   it yet.
// ============================================================================

// CLASS PartiePrenanteController - the four annotations below, one by one.
//
// @Tag(name = "Gouvernance - Parties Prenantes", ...)
//   WHAT: an OpenAPI / Swagger label. It groups every route of this class under
//         one heading in the generated API documentation page.
//   WHY:  the API has many controllers; a reader must find the four governance
//         registers next to each other.
//   WITHOUT IT: the documentation shows a default "partie-prenante-controller"
//         bucket and a reviewer has to guess what it covers.
//
// @RestController
//   WHAT: marks the class as a Spring web component AND says "whatever a method
//         returns IS the response body", written out as JSON. It is
//         @Controller + @ResponseBody in one annotation.
//   WHY:  this is a JSON API, not a server-rendered web site.
//   WITHOUT IT: with a plain @Controller, a returned object would be read as the
//         NAME OF AN HTML VIEW and Spring would answer 404, because no such
//         template exists.
//
// @RequestMapping("/api/projects/{projectId}/parties-prenantes")
//   WHAT: the common prefix of every route below. {projectId} is a placeholder
//         filled from the real URL.
//   WHY:  a stakeholder is registered for one project. This exact URL shape is
//         what ProjectScopeInterceptor matches (its pattern is
//         ^/api/projects/(\d+)(/.*)?$ ), so the ADR-021 perimeter check covers
//         all four routes for free.
//   WITHOUT IT: a flat route such as /api/parties-prenantes/42 would not match
//         that pattern, and any user holding VIEW_GOVERNANCE could read the
//         names, emails and phone numbers of another client's people.
//
// @RequiredArgsConstructor (Lombok)
//   WHAT: generates, at compile time, a constructor taking every "final" field.
//   WHY:  Spring injects PartiePrenanteService through that constructor, and the
//         field stays final, so nothing can swap the service at runtime.
//   WITHOUT IT: you write the constructor by hand; the day you add a second
//         service and forget its parameter, the field stays null and the first
//         request fails with a NullPointerException.
@Tag(name = "Gouvernance — Parties Prenantes", description = "Registre des parties prenantes : influence, intérêt, stratégie")
@RestController
@RequestMapping("/api/projects/{projectId}/parties-prenantes")
@RequiredArgsConstructor
public class PartiePrenanteController {

    // The only collaborator of this class, injected by the generated constructor.
    // The short name "ppService" is the one used everywhere in this package for
    // PartiePrenanteService.
    // WHY go through a service and not straight to PartiePrenanteRepository: the
    // permission check (@PreAuthorize) and the transaction boundary live on the
    // service methods. Calling the repository from here would skip both, so
    // anyone able to reach the URL would read and write the client's contact
    // details with no permission test.
    private final PartiePrenanteService ppService;

    // ------------------------------------------------------------------------
    // GET /api/projects/{projectId}/parties-prenantes
    //
    // WHAT IT DOES: gives back the whole stakeholder register of one project as a
    //   JSON array of PartiePrenanteResponse. Rows marked deleted are already
    //   filtered out by the repository query (deleted = false). An empty array
    //   with status 200 is a normal answer, not an error.
    // WHO CALLS IT: the Angular governance screen (tab "Parties prenantes"),
    //   through GovernanceService.listParties().
    // WHY IT IS WRITTEN THIS WAY: it returns the service answer untouched. The
    //   VIEW_GOVERNANCE check, the read-only transaction and the 404 on an
    //   unknown project all happen inside PartiePrenanteService.findByProject.
    //
    // @GetMapping
    //   WHAT: binds this method to HTTP GET on the class prefix, with nothing
    //         after it.
    //   WHY:  reading changes nothing, so a browser or a proxy may repeat it.
    //   WITHOUT IT: Spring would not connect the method to any URL and the GET
    //         would answer 404.
    //
    // @PathVariable Long projectId
    //   WHAT: takes the {projectId} piece of the URL and converts that text into
    //         a Long. In /api/projects/42/parties-prenantes, it becomes 42.
    //   WHY:  the register belongs to one project, and the id must be in the URL
    //         so ProjectScopeInterceptor (ADR-021) can read it as well.
    //   WITHOUT IT: the parameter would stay null and the service would look for
    //         the stakeholders of "no project".
    //   IF THE VALUE IS WRONG: /api/projects/abc/parties-prenantes cannot be
    //         converted and GlobalExceptionHandler answers 400, not 500.
    //
    // ResponseEntity<List<PartiePrenanteResponse>>
    //   WHAT: the part between < > is a generic; it tells the compiler and the
    //         Swagger page that the body is a list of PartiePrenanteResponse and
    //         of nothing else.
    //   WITHOUT the generic: the documentation would show an array of "object"
    //         and the Angular PartiePrenante model would have nothing to line up
    //         with.
    // ------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<PartiePrenanteResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(ppService.findByProject(projectId));
    }

    // ------------------------------------------------------------------------
    // POST /api/projects/{projectId}/parties-prenantes
    //
    // WHAT IT DOES: adds one stakeholder to the register of this project and
    //   gives the saved row back, id included, with status 201 Created.
    // WHY 201 PLUS A "Location" HEADER instead of a plain 200: 201 is the HTTP
    //   way of saying "a new resource now exists", and Location gives its
    //   address, for example /api/projects/42/parties-prenantes/57, so a client
    //   that kept only the header can read it again without guessing how the URL
    //   is built.
    //
    // @PostMapping
    //   WHAT: binds this method to HTTP POST on the class prefix.
    //   WHY:  creating changes the state of the server, so it must not be a GET.
    //   WITHOUT IT (say, as a GET): a reload or a link preview would fire the call
    //         again and the same person would appear twice in the register, which
    //         also means they would be counted twice on the influence/interest
    //         grid.
    //
    // @Valid @RequestBody PartiePrenanteRequest request
    //   @RequestBody WHAT: Jackson, the JSON library, reads the body of the
    //         request and builds a PartiePrenanteRequest record from it. That
    //         record is a DTO (Data Transfer Object): an object made only to
    //         travel, so the PartiePrenante database entity is never built from
    //         outside data.
    //   @Valid WHAT: runs the Bean Validation rules written inside that record
    //         (@NotBlank on nom, @Email and @Size(max = 255) on email, @NotNull
    //         on influence and interet) BEFORE the first line of this method. A
    //         failure becomes a 400 naming the bad fields, produced by
    //         GlobalExceptionHandler.
    //   WHY:  @Valid is the switch; the rules live in the DTO but nothing runs
    //         them unless a controller parameter asks for it.
    //   WITHOUT @Valid: an email such as "jean.dupont" would be stored as it is,
    //         and any later mail sent to the stakeholder would silently fail; an
    //         address longer than 255 characters would be refused by Postgres and
    //         come back as a 500 instead of a clear 400.
    // ------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<PartiePrenanteResponse> create(@PathVariable Long projectId,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        // The service checks MANAGE_GOVERNANCE, opens the transaction, attaches
        // the stakeholder to the project and saves the row; it gives back the DTO
        // built by PartiePrenanteMapper, with the new id inside.
        PartiePrenanteResponse created = ppService.create(projectId, request);
        // WHAT: rebuilds the address of the request being handled
        //   (/api/projects/42/parties-prenantes), adds "/{id}" and fills that
        //   placeholder with the id the database just produced.
        // WHY built from the live request instead of a hand-written string: the
        //   host, the port and any context path stay the ones the caller used, so
        //   the address is still right when the app is not on localhost.
        // WITHOUT IT: no Location header, so a client that dropped the body would
        //   have no address for what it just created.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created stakeholder as body.
        // The body is sent too so the Angular table can add the line at once,
        // instead of reloading the whole register.
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------------
    // PUT /api/projects/{projectId}/parties-prenantes/{id}
    //
    // WHAT IT DOES: overwrites the six editable fields of one stakeholder (nom,
    //   fonction, email, telephone, influence, interet) with what the body
    //   carries, and gives the updated row back with status 200.
    // WHY PUT AND NOT PATCH: the body is a full PartiePrenanteRequest and the
    //   service copies every field of it onto the row, which is a replacement,
    //   and that is what PUT means. Calling it PATCH would promise "send only
    //   what changes", and a caller who sent just {"interet":"ELEVE"} would
    //   silently erase the email and the phone number, because the missing fields
    //   arrive null.
    //   A stakeholder has no state machine, unlike a livrable or a demande de
    //   changement, so there is no transition rule to check and no PATCH route in
    //   this class: any field can be corrected at any time.
    // WHY BOTH projectId AND id IN THE URL: the id alone would find the row. The
    //   projectId is there because PartiePrenanteService compares the project of
    //   the loaded stakeholder with the one in the URL and answers 404 when they
    //   differ. WITHOUT that pair: a user allowed on project 42 could send
    //   /api/projects/42/parties-prenantes/57 while row 57 belongs to project 99;
    //   the perimeter check only reads the 42, so it would say yes and another
    //   client's contact would be rewritten.
    //
    // @PutMapping("/{id}")
    //   WHAT: binds HTTP PUT on the class prefix plus /{id}, giving the route
    //         /api/projects/{projectId}/parties-prenantes/{id}. The two
    //         placeholders are matched by NAME to the two @PathVariable
    //         parameters below.
    //
    // NOTE ON USE: no Angular screen calls this route today; the governance
    //   screen uses list, create and delete. It stays part of the API and of the
    //   permission matrix (MANAGE_GOVERNANCE on PartiePrenanteService.update).
    // ------------------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<PartiePrenanteResponse> update(@PathVariable Long projectId,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody PartiePrenanteRequest request) {
        return ResponseEntity.ok(ppService.update(projectId, id, request));
    }

    // ------------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/parties-prenantes/{id}
    //
    // WHAT IT DOES: takes one stakeholder out of the register and answers 204 No
    //   Content. There is no business rule to stop it: unlike a validated
    //   livrable or a decided demande de changement, a stakeholder can be removed
    //   at any time, because people do leave a project.
    // IMPORTANT - THE ROW IS NOT ERASED: PartiePrenanteService only sets
    //   deleted = true (a "soft delete") and every repository query filters on
    //   deleted = false, so the person leaves the screens while the line stays in
    //   the table. WHY: the register can be audited months later, and the audit
    //   columns of V19 point at that row; a real DELETE would lose the history
    //   for good, and with it the proof of who was consulted and when.
    // WHY 204 AND AN EMPTY BODY: there is nothing useful left to send back; the
    //   caller already knows which id it deleted, and returning the deleted
    //   person would only invite a client to keep showing them.
    //
    // ResponseEntity<Void>
    //   WHAT: Void as the generic means "no body at all"; .noContent().build()
    //         produces the 204, build() being what creates the ResponseEntity
    //         since no body is given.
    //   WITHOUT IT (returning the object with 200): the Angular deletePartie()
    //         call is typed <void>, so the body would be thrown away, and the
    //         contract shown in Swagger would stop matching the code.
    // ------------------------------------------------------------------------
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        ppService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
