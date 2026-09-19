package com.pms.governance.controller;

import com.pms.governance.dto.LivrableRequest;
import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.service.LivrableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// ============================================================================
// FILE: LivrableController.java
// ----------------------------------------------------------------------------
// WHAT THIS FILE IS
//   The HTTP entry door for the DELIVERABLES of one project ("livrables").
//   One livrable = one thing the project has promised to hand over: a document,
//   a module, a training session. Each one has a title, a description, a due
//   date (dateEcheance) and a status that moves along a small state machine.
//   This class exposes seven routes: list, create, update, delete, and three
//   routes that move the status: demarrer (start), livrer (deliver), valider
//   (accept).
//
// THE STATE MACHINE, WHICH IS THE POINT OF THIS FILE
//   EN_ATTENTE (waiting) -> EN_COURS (in progress) -> LIVRE (delivered)
//   -> VALIDE (accepted by the client). The rules are enforced in
//   LivrableService, not here:
//     - demarrer: allowed only from EN_ATTENTE.
//     - livrer:   allowed from any status except VALIDE.
//     - valider:  allowed only from LIVRE.
//     - update and delete: refused once the livrable is VALIDE.
//   A refused move throws BusinessRuleException, which comes back as HTTP 422
//   with a readable sentence, not as a crash.
//   WHY three small PATCH routes instead of letting PUT write the status: the
//   status would then be just another field of the body, and anyone could send
//   {"statut":"VALIDE"} and declare a deliverable accepted without it ever
//   being delivered. Notice that LivrableRequest carries no "statut" field at
//   all, which is what makes these three routes the only way in.
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
//       -> LivrableService (permissions, transactions, state machine rules)
//       -> LivrableRepository -> table "livrables" (Flyway migration V11)
//       -> LivrableMapper turns the Livrable entity into a LivrableResponse
//   A DTO (Data Transfer Object) such as LivrableRequest / LivrableResponse is
//   a small object built only to travel as JSON, so the database entity never
//   leaves the server.
//
// WHY IT EXISTS - what breaks if you delete it
//   LivrableService would still hold every rule, but nothing outside the server
//   could reach it: the governance screen could no longer show a deliverable,
//   add one, or move it from "delivered" to "accepted". The seven URLs would
//   answer 404.
//
// ITS THREE SISTER FILES IN THIS PACKAGE
//   RiskController, DemandeChangementController and PartiePrenanteController
//   are built exactly the same way, on the same /api/projects/{projectId}/...
//   prefix and on the same pair of permissions (VIEW_GOVERNANCE to read,
//   MANAGE_GOVERNANCE to write). DemandeChangementController has the same kind
//   of PATCH routes (approuver / rejeter); RiskController and
//   PartiePrenanteController have none, because their rows have no state
//   machine. They are four classes and not one "GovernanceController" because
//   each owns its own table, DTOs and service; splitting them keeps each
//   Swagger tag readable and lets one register change alone.
//
// DESIGN CHOICE: THIS CONTROLLER IS DELIBERATELY "THIN"
//   Every method below only reads the URL parts, calls LivrableService and
//   wraps the answer in the right HTTP status. There is no "if" and no
//   @PreAuthorize in this file, on purpose:
//     - Permissions sit on the SERVICE methods
//       (@PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") on the read,
//        @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on every write,
//        the three PATCH methods included).
//       Why there and not here: a service can also be called from another
//       service or from a test, not only from HTTP. A check placed in the
//       controller would be walked past by any second caller.
//       The checks name a PERMISSION, never a role name. That is the dynamic
//       authorization rule of the project: an administrator can add
//       MANAGE_GOVERNANCE to a role at runtime and it works, with no redeploy.
//     - The project perimeter (ADR-021) is checked once for all these URLs by
//       ProjectScopeInterceptor. Holding the permission is NOT enough: a
//       project manager with MANAGE_GOVERNANCE must still be refused on a
//       project that is not his.
//
// WHY THERE IS NO try/catch HERE
//   LivrableService throws NotFoundException and BusinessRuleException;
//   GlobalExceptionHandler turns exceptions into a clean JSON error body:
//     404 = not found, 422 = a business rule said no (for example "only a
//     delivered item can be accepted"), 400 = the @Valid check failed or the
//     URL id was not a number, 403 = permission refused or project out of
//     perimeter, 401 = not logged in.
//
// NOTE ON THE FRENCH NAMES
//   The domain words stay in French because the company and its documents use
//   them: livrable = deliverable, titre = title, date d'echeance = due date,
//   demarrer = start, livrer = deliver, valider = accept/sign off.
//
// NOTE ON COVERAGE
//   The Angular screen calls six of these seven routes (list, create, delete,
//   demarrer, livrer, valider). The PUT is part of the API and of the
//   permission matrix, but no screen uses it yet.
// ============================================================================

// CLASS LivrableController - the four annotations below, one by one.
//
// @Tag(name = "Gouvernance - Livrables", ...)
//   WHAT: an OpenAPI / Swagger label. It groups every route of this class under
//         one heading in the generated API documentation page.
//   WHY:  the API has many controllers; a reader must find the four governance
//         registers next to each other.
//   WITHOUT IT: the documentation shows a default "livrable-controller" bucket
//         and a reviewer has to guess what it covers.
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
// @RequestMapping("/api/projects/{projectId}/livrables")
//   WHAT: the common prefix of every route below. {projectId} is a placeholder
//         filled from the real URL.
//   WHY:  a deliverable only makes sense inside a project. This exact URL shape
//         is what ProjectScopeInterceptor matches (its pattern is
//         ^/api/projects/(\d+)(/.*)?$ ), so the ADR-021 perimeter check covers
//         all seven routes for free.
//   WITHOUT IT: a flat route such as /api/livrables/42 would not match that
//         pattern, and any user holding VIEW_GOVERNANCE could read the
//         deliverables of a project he has nothing to do with.
//
// @RequiredArgsConstructor (Lombok)
//   WHAT: generates, at compile time, a constructor taking every "final" field.
//   WHY:  Spring injects LivrableService through that constructor, and the field
//         stays final, so nothing can swap the service at runtime.
//   WITHOUT IT: you write the constructor by hand; the day you add a second
//         service and forget its parameter, the field stays null and the first
//         request fails with a NullPointerException.
@Tag(name = "Gouvernance — Livrables", description = "Suivi des livrables + machine à états (EN_ATTENTE → LIVRE → VALIDE)")
@RestController
@RequestMapping("/api/projects/{projectId}/livrables")
@RequiredArgsConstructor
public class LivrableController {

    // The only collaborator of this class, injected by the generated constructor.
    // WHY go through a service and not straight to LivrableRepository: the
    // permission check, the transaction and the whole state machine live on the
    // LivrableService methods. Reaching the repository from here would skip all
    // three, so a caller could set a deliverable to VALIDE without ever passing
    // through LIVRE.
    private final LivrableService livrableService;

    // ------------------------------------------------------------------------
    // GET /api/projects/{projectId}/livrables
    //
    // WHAT IT DOES: gives back every live deliverable of one project as a JSON
    //   array of LivrableResponse, ordered by the repository query. Rows marked
    //   deleted are already filtered out (deleted = false). An empty array with
    //   status 200 is a normal answer, not an error.
    // WHO CALLS IT: the Angular governance screen (tab "Livrables") and the
    //   project detail page, through GovernanceService.listLivrables().
    // WHY IT IS WRITTEN THIS WAY: it returns the service answer untouched. The
    //   VIEW_GOVERNANCE check, the read-only transaction and the 404 on an
    //   unknown project all happen inside LivrableService.findByProject.
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
    //         a Long. In /api/projects/42/livrables, projectId becomes 42.
    //   WHY:  the register belongs to one project, and the id must be in the URL
    //         so ProjectScopeInterceptor (ADR-021) can read it as well.
    //   WITHOUT IT: the parameter would stay null and the service would look for
    //         the deliverables of "no project".
    //   IF THE VALUE IS WRONG: /api/projects/abc/livrables cannot be converted
    //         and GlobalExceptionHandler answers 400, not 500.
    //
    // ResponseEntity<List<LivrableResponse>>
    //   WHAT: the part between < > is a generic; it tells the compiler and the
    //         Swagger page that the body is a list of LivrableResponse.
    //   WITHOUT the generic: the documentation would show an array of "object"
    //         and the Angular Livrable model would have nothing to line up with.
    // ------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<LivrableResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(livrableService.findByProject(projectId));
    }

    // ------------------------------------------------------------------------
    // POST /api/projects/{projectId}/livrables
    //
    // WHAT IT DOES: adds one deliverable to the project and gives it back, id
    //   included, with status 201 Created. The new row always starts at
    //   EN_ATTENTE: that default sits on the Livrable entity, and the request DTO
    //   has no "statut" field, so a caller cannot create something already
    //   accepted and skip the whole state machine.
    // WHY 201 PLUS A "Location" HEADER instead of a plain 200: 201 is the HTTP
    //   way of saying "a new resource now exists", and Location gives its
    //   address, for example /api/projects/42/livrables/57, so a client that kept
    //   only the header can read it again without guessing how the URL is built.
    //
    // @PostMapping
    //   WHAT: binds this method to HTTP POST on the class prefix.
    //   WHY:  creating changes the state of the server, so it must not be a GET.
    //   WITHOUT IT (say, as a GET): a reload or a link preview would fire the call
    //         again and the same deliverable would be written twice.
    //
    // @Valid @RequestBody LivrableRequest request
    //   @RequestBody WHAT: Jackson, the JSON library, reads the body of the
    //         request and builds a LivrableRequest record from it. That record is
    //         a DTO (Data Transfer Object): an object made only to travel, so the
    //         Livrable database entity is never built from outside data.
    //   @Valid WHAT: runs the Bean Validation rules written inside
    //         LivrableRequest (@NotBlank on titre) BEFORE the first line of this
    //         method. A failure becomes a 400 naming the bad field, produced by
    //         GlobalExceptionHandler.
    //   WHY:  @Valid is the switch; the rules live in the DTO but nothing runs
    //         them unless a controller parameter asks for it.
    //   WITHOUT @Valid: a deliverable sent with no title would reach Postgres,
    //         where the NOT NULL column "titre" answers with a 500 instead of a
    //         clear 400. And a title made only of spaces passes NOT NULL, so it
    //         would be saved and the governance table would show a nameless line.
    //         @NotBlank is what refuses both cases.
    // ------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<LivrableResponse> create(@PathVariable Long projectId,
                                                    @Valid @RequestBody LivrableRequest request) {
        // The service checks MANAGE_GOVERNANCE, opens the transaction, attaches
        // the deliverable to the project and saves it; it gives back the DTO built
        // by LivrableMapper, with the new id inside.
        LivrableResponse created = livrableService.create(projectId, request);
        // WHAT: rebuilds the address of the request being handled
        //   (/api/projects/42/livrables), adds "/{id}" and fills it with the id
        //   the database just produced: /api/projects/42/livrables/57.
        // WHY built from the live request instead of a hand-written string: the
        //   host, the port and any context path stay the ones the caller used, so
        //   the address is still right when the app is not on localhost.
        // WITHOUT IT: no Location header, so a client that dropped the body would
        //   have no address for what it just created.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created deliverable as body.
        // The body is sent too so the Angular table can add the line at once,
        // instead of reloading the whole register.
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------------
    // PUT /api/projects/{projectId}/livrables/{id}
    //
    // WHAT IT DOES: overwrites the three editable fields of one deliverable
    //   (titre, description, dateEcheance) with what the body carries, and gives
    //   the updated row back with status 200.
    // WHAT IT CANNOT DO: change the status. LivrableRequest has no "statut"
    //   field, and LivrableService.update refuses outright once the deliverable
    //   is VALIDE (422). WHY: a deliverable already accepted by the client is a
    //   signed fact; if its title or its due date could still be rewritten, the
    //   acceptance would no longer prove anything.
    // WHY PUT AND NOT PATCH: the body is a full LivrableRequest and the service
    //   copies every field of it onto the row, which is a replacement, and that
    //   is what PUT means. Calling it PATCH would promise "send only what
    //   changes", and a caller who sent just {"titre":"..."} would silently erase
    //   the description and the due date, because the missing fields arrive null.
    // WHY BOTH projectId AND id IN THE URL: the id alone would find the row. The
    //   projectId is there because LivrableService compares the project of the
    //   loaded deliverable with the one in the URL and answers 404 when they
    //   differ. WITHOUT that pair: a user allowed on project 42 could send
    //   /api/projects/42/livrables/57 while row 57 belongs to project 99; the
    //   perimeter check only reads the 42, so it would say yes and another
    //   client's deliverable would be rewritten.
    //
    // @PutMapping("/{id}")
    //   WHAT: binds HTTP PUT on the class prefix plus /{id}, giving the route
    //         /api/projects/{projectId}/livrables/{id}. The two placeholders are
    //         matched by NAME to the two @PathVariable parameters below.
    //
    // NOTE ON USE: no Angular screen calls this route today; the governance
    //   screen uses list, create, delete and the three PATCH routes. It stays
    //   part of the API and of the permission matrix (MANAGE_GOVERNANCE).
    // ------------------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<LivrableResponse> update(@PathVariable Long projectId,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody LivrableRequest request) {
        return ResponseEntity.ok(livrableService.update(projectId, id, request));
    }

    // ------------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/livrables/{id}/demarrer   (start work)
    //
    // WHAT IT DOES: moves one deliverable from EN_ATTENTE to EN_COURS and gives
    //   the updated row back with status 200.
    // THE RULE, CHECKED IN THE SERVICE: only a deliverable still EN_ATTENTE can
    //   be started. On anything else LivrableService throws
    //   BusinessRuleException, which GlobalExceptionHandler turns into a 422 with
    //   the sentence "Seul un livrable en attente peut être démarré".
    //   EXAMPLE of what this stops: clicking "start" twice, or starting something
    //   already accepted (VALIDE), which would send the deliverable backwards in
    //   the state machine and make the governance history unreadable.
    // WHY A ROUTE OF ITS OWN, AND NOT A FIELD IN THE PUT BODY: the status would
    //   then be data like any other, and a caller could jump straight from
    //   EN_ATTENTE to VALIDE. Here the only door is this URL, and behind it sits
    //   the transition rule.
    //
    // @PatchMapping("/{id}/demarrer")
    //   WHAT: binds HTTP PATCH on the class prefix plus /{id}/demarrer.
    //   WHY PATCH: the call changes one part of an existing resource, not the
    //         whole of it. POST would read as "create a new thing", PUT as
    //         "replace the whole thing", and both would lie about what happens.
    //   WHY THE URL ENDS WITH A VERB: it names the transition rather than the
    //         field, which is exactly the point - the caller asks for a move, it
    //         does not write a value.
    //
    // NO @RequestBody HERE
    //   WHAT: the method takes no body; the Angular call sends an empty {}.
    //   WHY:  everything needed is already in the URL (which project, which
    //         deliverable) and the target status is fixed by the route itself.
    //   WITHOUT this choice (a body carrying the target status): the client would
    //         pick the new status, and the state machine would be back to being a
    //         suggestion instead of a rule.
    // ------------------------------------------------------------------------
    @PatchMapping("/{id}/demarrer")
    public ResponseEntity<LivrableResponse> demarrer(@PathVariable Long projectId,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.demarrer(projectId, id));
    }

    // ------------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/livrables/{id}/livrer   (hand it over)
    //
    // WHAT IT DOES: sets the status of one deliverable to LIVRE, meaning it has
    //   been handed to the client and now waits for their answer. Gives the
    //   updated row back with status 200.
    // THE RULE, CHECKED IN THE SERVICE: everything is allowed except a
    //   deliverable already VALIDE, which raises BusinessRuleException -> 422
    //   ("Un livrable déjà validé ne peut pas être modifié"). So EN_ATTENTE can
    //   go straight to LIVRE without passing through EN_COURS. That is on
    //   purpose: a small deliverable is often done and handed over in one go, and
    //   forcing a "start" click first would only add noise.
    //   EXAMPLE of what the rule stops: re-delivering something the client has
    //   already accepted, which would quietly pull it back out of VALIDE and
    //   cancel a sign-off nobody asked to cancel.
    // WHY A ROUTE OF ITS OWN: same reason as demarrer. The status is never a
    //   field of the request body, so this URL is the only way to reach LIVRE,
    //   and the rule behind it cannot be skipped.
    //
    // @PatchMapping("/{id}/livrer")
    //   WHAT: binds HTTP PATCH on the class prefix plus /{id}/livrer.
    //   WHY PATCH and not PUT: only one part of the resource changes, and no body
    //         is sent; PUT would promise a full replacement of the deliverable.
    //
    // WHO CALLS IT: the Angular governance screen, through
    //   GovernanceService.livrerLivrable(), which sends an empty {} body.
    // ------------------------------------------------------------------------
    @PatchMapping("/{id}/livrer")
    public ResponseEntity<LivrableResponse> livrer(@PathVariable Long projectId,
                                                    @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.livrer(projectId, id));
    }

    // ------------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/livrables/{id}/valider   (accept it)
    //
    // WHAT IT DOES: moves one deliverable from LIVRE to VALIDE, the last and
    //   final state, and gives the updated row back with status 200.
    // THE RULE, CHECKED IN THE SERVICE: only a deliverable already LIVRE can be
    //   accepted; anything else raises BusinessRuleException -> 422 ("Seul un
    //   livrable livré peut être validé").
    //   EXAMPLE of what this stops: accepting a deliverable that was never handed
    //   over. The project would look finished on the dashboard while nothing was
    //   actually sent to the client.
    // WHY THIS ONE MATTERS MOST: VALIDE is a one-way door. Once it is set,
    //   update, delete and livrer are all refused by the service, so the row
    //   becomes read-only history. That is why the transition has its own URL and
    //   its own MANAGE_GOVERNANCE check, instead of being a value somebody can
    //   post in a body.
    //
    // @PatchMapping("/{id}/valider")
    //   WHAT: binds HTTP PATCH on the class prefix plus /{id}/valider.
    //   WHY PATCH: one part of an existing resource changes, with no body sent.
    // ------------------------------------------------------------------------
    @PatchMapping("/{id}/valider")
    public ResponseEntity<LivrableResponse> valider(@PathVariable Long projectId,
                                                     @PathVariable Long id) {
        return ResponseEntity.ok(livrableService.valider(projectId, id));
    }

    // ------------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/livrables/{id}
    //
    // WHAT IT DOES: takes one deliverable out of the register and answers 204 No
    //   Content.
    // THE RULE, CHECKED IN THE SERVICE: a VALIDE deliverable cannot be deleted;
    //   the service raises BusinessRuleException -> 422 ("Impossible de supprimer
    //   un livrable validé"). EXAMPLE of what this stops: making an accepted
    //   deliverable disappear from the register after a dispute with the client.
    // IMPORTANT - THE ROW IS NOT ERASED: LivrableService only sets deleted = true
    //   (a "soft delete") and every repository query filters on deleted = false,
    //   so the line leaves the screens but stays in the table. WHY: the register
    //   can be audited months later, and the audit columns of V19 point at that
    //   row; a real DELETE would lose the history for good.
    // WHY 204 AND AN EMPTY BODY: there is nothing useful to send back; the caller
    //   already knows which id it deleted.
    //
    // ResponseEntity<Void>
    //   WHAT: Void as the generic means "no body at all"; .noContent().build()
    //         produces the 204, build() being what creates the ResponseEntity
    //         since no body is given.
    //   WITHOUT IT (returning the object with 200): the Angular delete() call is
    //         typed <void>, so the body would be thrown away, and the contract in
    //         Swagger would stop matching the code.
    // ------------------------------------------------------------------------
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        livrableService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
