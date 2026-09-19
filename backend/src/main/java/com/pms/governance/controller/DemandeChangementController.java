package com.pms.governance.controller;

import com.pms.governance.dto.DemandeChangementRequest;
import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.service.DemandeChangementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// ============================================================================
// FILE: DemandeChangementController.java
// ----------------------------------------------------------------------------
// WHAT THIS FILE IS
//   The HTTP entry door for the CHANGE REQUESTS of one project ("demandes de
//   changement"). One demande = somebody asks for a change in the scope of the
//   project: a title, a description, how urgent it is (priorite), who asked
//   (demandeur), the day they asked (dateDemande). The project manager then
//   approves it or rejects it, and the day of that answer is kept
//   (dateDecision). This class exposes six routes: list, create, update,
//   delete, and two routes that carry the decision: approuver, rejeter.
//
// THE DECISION FLOW, WHICH IS THE POINT OF THIS FILE
//   EN_ATTENTE (waiting) -> APPROUVE (accepted) or REJETE (refused). The rules
//   are enforced in DemandeChangementService, not here:
//     - approuver and rejeter: allowed only while the request is EN_ATTENTE,
//       and both write dateDecision = today.
//     - update and delete: refused as soon as the request has been decided.
//   A refused move throws BusinessRuleException, which comes back as HTTP 422
//   with a readable sentence, not as a crash.
//   WHY two small PATCH routes instead of letting PUT write the status: the
//   status would then be an ordinary field of the body, and the very person
//   asking for the change could send {"statut":"APPROUVE"} and approve their
//   own request. Notice that DemandeChangementRequest carries no "statut" and
//   no "dateDecision" field at all, which is what makes these two routes the
//   only way in.
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
//       -> DemandeChangementService (permissions, transactions, decision rules;
//          it also turns demandeurId into a real User, or answers 404)
//       -> DemandeChangementRepository -> table "demandes_changement"
//          (Flyway migration V11)
//       -> DemandeChangementMapper turns the entity into the response record
//   A DTO (Data Transfer Object) such as DemandeChangementRequest / Response is
//   a small object built only to travel as JSON, so the database entity never
//   leaves the server.
//
// WHY IT EXISTS - what breaks if you delete it
//   DemandeChangementService would still hold every rule, but nothing outside
//   the server could reach it: the governance screen could no longer show, open,
//   approve or reject a change request. The six URLs would answer 404.
//
// ITS THREE SISTER FILES IN THIS PACKAGE
//   RiskController, LivrableController and PartiePrenanteController are built
//   exactly the same way, on the same /api/projects/{projectId}/... prefix and
//   on the same pair of permissions (VIEW_GOVERNANCE to read,
//   MANAGE_GOVERNANCE to write). LivrableController has the same kind of PATCH
//   routes (demarrer / livrer / valider); the two others have none, because
//   their rows have no state machine. They are four classes and not one
//   "GovernanceController" because each owns its own table, DTOs and service;
//   splitting them keeps each Swagger tag readable and lets one register change
//   alone.
//
// DESIGN CHOICE: THIS CONTROLLER IS DELIBERATELY "THIN"
//   Every method below only reads the URL parts, calls the service and wraps
//   the answer in the right HTTP status. There is no "if" and no @PreAuthorize
//   in this file, on purpose:
//     - Permissions sit on the SERVICE methods
//       (@PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") on the read,
//        @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on every write, the
//        two PATCH methods included).
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
//   The service throws NotFoundException and BusinessRuleException;
//   GlobalExceptionHandler turns exceptions into a clean JSON error body:
//     404 = not found (unknown project, unknown request, unknown demandeur),
//     422 = a business rule said no (for example "the request was already
//     decided"), 400 = the @Valid check failed or the URL id was not a number,
//     403 = permission refused or project out of perimeter, 401 = not logged in.
//
// NOTE ON THE FRENCH NAMES
//   The domain words stay in French because the company and its documents use
//   them: demande de changement = change request, demandeur = the person asking,
//   priorite = how urgent, approuver = approve, rejeter = reject,
//   EN_ATTENTE = waiting for a decision.
//
// NOTE ON COVERAGE
//   The Angular screen calls five of these six routes (list, create, delete,
//   approuver, rejeter). The PUT is part of the API and of the permission
//   matrix, but no screen uses it yet.
// ============================================================================

// CLASS DemandeChangementController - the four annotations below, one by one.
//
// @Tag(name = "Gouvernance - Demandes de Changement", ...)
//   WHAT: an OpenAPI / Swagger label. It groups every route of this class under
//         one heading in the generated API documentation page.
//   WHY:  the API has many controllers; a reader must find the four governance
//         registers next to each other.
//   WITHOUT IT: the documentation shows a default
//         "demande-changement-controller" bucket and a reviewer has to guess
//         what it covers.
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
// @RequestMapping("/api/projects/{projectId}/demandes-changement")
//   WHAT: the common prefix of every route below. {projectId} is a placeholder
//         filled from the real URL.
//   WHY:  a change request only makes sense inside a project. This exact URL
//         shape is what ProjectScopeInterceptor matches (its pattern is
//         ^/api/projects/(\d+)(/.*)?$ ), so the ADR-021 perimeter check covers
//         all six routes for free.
//   WITHOUT IT: a flat route such as /api/demandes-changement/42 would not match
//         that pattern, and any user holding VIEW_GOVERNANCE could read the
//         change requests of a project he has nothing to do with.
//
// @RequiredArgsConstructor (Lombok)
//   WHAT: generates, at compile time, a constructor taking every "final" field.
//   WHY:  Spring injects DemandeChangementService through that constructor, and
//         the field stays final, so nothing can swap the service at runtime.
//   WITHOUT IT: you write the constructor by hand; the day you add a second
//         service and forget its parameter, the field stays null and the first
//         request fails with a NullPointerException.
@Tag(name = "Gouvernance — Demandes de Changement", description = "Registre des demandes de changement (EN_ATTENTE → APPROUVE/REJETE)")
@RestController
@RequestMapping("/api/projects/{projectId}/demandes-changement")
@RequiredArgsConstructor
public class DemandeChangementController {

    // The only collaborator of this class, injected by the generated constructor.
    // The short name "dcService" is the one used everywhere in this package for
    // DemandeChangementService.
    // WHY go through a service and not straight to the repository: the permission
    // check, the transaction and the decision rules live on the service methods.
    // Reaching the repository from here would skip all three, so a request could
    // be approved twice, or approved after it was already rejected.
    private final DemandeChangementService dcService;

    // ------------------------------------------------------------------------
    // GET /api/projects/{projectId}/demandes-changement
    //
    // WHAT IT DOES: gives back every live change request of one project as a JSON
    //   array of DemandeChangementResponse. Rows marked deleted are already
    //   filtered out by the repository query (deleted = false). An empty array
    //   with status 200 is a normal answer, not an error.
    // WHO CALLS IT: the Angular governance screen (tab "Demandes de changement"),
    //   through GovernanceService.listChanges().
    // WHY IT IS WRITTEN THIS WAY: it returns the service answer untouched. The
    //   VIEW_GOVERNANCE check, the read-only transaction and the 404 on an
    //   unknown project all happen inside the service.
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
    //         a Long. In /api/projects/42/demandes-changement, it becomes 42.
    //   WHY:  the register belongs to one project, and the id must be in the URL
    //         so ProjectScopeInterceptor (ADR-021) can read it as well.
    //   WITHOUT IT: the parameter would stay null and the service would look for
    //         the change requests of "no project".
    //   IF THE VALUE IS WRONG: /api/projects/abc/demandes-changement cannot be
    //         converted and GlobalExceptionHandler answers 400, not 500.
    //
    // ResponseEntity<List<DemandeChangementResponse>>
    //   WHAT: the part between < > is a generic; it tells the compiler and the
    //         Swagger page that the body is a list of DemandeChangementResponse.
    //   WITHOUT the generic: the documentation would show an array of "object"
    //         and the Angular model would have nothing to line up with.
    // ------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<DemandeChangementResponse>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(dcService.findByProject(projectId));
    }

    // ------------------------------------------------------------------------
    // POST /api/projects/{projectId}/demandes-changement
    //
    // WHAT IT DOES: opens one change request on this project and gives it back,
    //   id included, with status 201 Created. The new row always starts at
    //   EN_ATTENTE and with an empty dateDecision: that default sits on the
    //   DemandeChangement entity, and the request DTO has no "statut" field, so
    //   nobody can create a request that is already approved.
    // WHY 201 PLUS A "Location" HEADER instead of a plain 200: 201 is the HTTP
    //   way of saying "a new resource now exists", and Location gives its
    //   address, for example /api/projects/42/demandes-changement/57, so a client
    //   that kept only the header can read it again without guessing how the URL
    //   is built.
    //
    // @PostMapping
    //   WHAT: binds this method to HTTP POST on the class prefix.
    //   WHY:  creating changes the state of the server, so it must not be a GET.
    //   WITHOUT IT (say, as a GET): a reload or a link preview would fire the call
    //         again and the same change request would be opened twice, which the
    //         project manager would then have to decide on twice.
    //
    // @Valid @RequestBody DemandeChangementRequest request
    //   @RequestBody WHAT: Jackson, the JSON library, reads the body of the
    //         request and builds a DemandeChangementRequest record from it. That
    //         record is a DTO (Data Transfer Object): an object made only to
    //         travel, so the database entity is never built from outside data.
    //   @Valid WHAT: runs the Bean Validation rules written inside that record
    //         (@NotNull on demandeurId, @NotBlank on titre, @NotNull on priorite
    //         and on dateDemande) BEFORE the first line of this method. A failure
    //         becomes a 400 naming the bad fields, produced by
    //         GlobalExceptionHandler.
    //   WHY:  @Valid is the switch; the rules live in the DTO but nothing runs
    //         them unless a controller parameter asks for it.
    //   WITHOUT @Valid: a request with no demandeurId would travel down to the
    //         service, which would call findById(null), and the caller would get
    //         a 500 instead of a clear "demandeurId is required". A change
    //         request with nobody attached to it also means nobody to go back to
    //         when the scope is discussed.
    //   NOTE: demandeurId is only a number here. The service turns it into a real
    //         User and answers 404 if that user does not exist or was deleted, so
    //         a client cannot invent a person who asked for the change.
    // ------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<DemandeChangementResponse> create(@PathVariable Long projectId,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        // The service checks MANAGE_GOVERNANCE, opens the transaction, loads the
        // project and the demandeur, saves the row, and gives back the DTO built
        // by DemandeChangementMapper with the new id inside.
        DemandeChangementResponse created = dcService.create(projectId, request);
        // WHAT: rebuilds the address of the request being handled
        //   (/api/projects/42/demandes-changement), adds "/{id}" and fills it with
        //   the id the database just produced.
        // WHY built from the live request instead of a hand-written string: the
        //   host, the port and any context path stay the ones the caller used, so
        //   the address is still right when the app is not on localhost.
        // WITHOUT IT: no Location header, so a client that dropped the body would
        //   have no address for what it just created.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // 201 Created, the Location header, and the created request as body. The
        // body is sent too so the Angular table can show the new line at once,
        // without a second GET of the whole register.
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------------
    // PUT /api/projects/{projectId}/demandes-changement/{id}
    //
    // WHAT IT DOES: overwrites the editable fields of one change request
    //   (demandeur, titre, description, priorite, dateDemande) with what the body
    //   carries, and gives the updated row back with status 200.
    // THE RULE, CHECKED IN THE SERVICE: only a request still EN_ATTENTE can be
    //   edited; on a request already approved or rejected the service raises
    //   BusinessRuleException -> 422 ("Impossible de modifier une demande déjà
    //   traitée"). EXAMPLE of what this stops: getting a small change approved,
    //   then rewriting its description into a much bigger change while keeping
    //   the approval that was given for the small one.
    // WHAT IT CANNOT DO: touch statut or dateDecision. Those two fields are not
    //   in the request DTO at all; only the two PATCH routes below write them.
    // WHY PUT AND NOT PATCH: the body is a full DemandeChangementRequest and the
    //   service copies every field of it onto the row, which is a replacement,
    //   and that is what PUT means. Calling it PATCH would promise "send only
    //   what changes", and a caller who sent just {"priorite":"CRITIQUE"} would
    //   silently erase the title and the description, because the missing fields
    //   arrive null.
    // WHY BOTH projectId AND id IN THE URL: the id alone would find the row. The
    //   projectId is there because the service compares the project of the loaded
    //   request with the one in the URL and answers 404 when they differ.
    //   WITHOUT that pair: a user allowed on project 42 could send
    //   /api/projects/42/demandes-changement/57 while row 57 belongs to project
    //   99; the perimeter check only reads the 42, so it would say yes and
    //   another client's change request would be rewritten.
    //
    // @PutMapping("/{id}")
    //   WHAT: binds HTTP PUT on the class prefix plus /{id}, giving the route
    //         /api/projects/{projectId}/demandes-changement/{id}. The two
    //         placeholders are matched by NAME to the two @PathVariable
    //         parameters below.
    //
    // NOTE ON USE: no Angular screen calls this route today; the governance
    //   screen uses list, create, delete, approuver and rejeter. It stays part of
    //   the API and of the permission matrix (MANAGE_GOVERNANCE).
    // ------------------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<DemandeChangementResponse> update(@PathVariable Long projectId,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody DemandeChangementRequest request) {
        return ResponseEntity.ok(dcService.update(projectId, id, request));
    }

    // ------------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/demandes-changement/{id}/approuver
    //
    // WHAT IT DOES: accepts one change request. The service sets statut =
    //   APPROUVE and dateDecision = today, then gives the updated row back with
    //   status 200.
    // THE RULE, CHECKED IN THE SERVICE: only a request still EN_ATTENTE can be
    //   decided; anything else raises BusinessRuleException -> 422 ("La demande a
    //   déjà été traitée"). EXAMPLE of what this stops: approving a request that
    //   was rejected last week. Without the check, statut would flip to APPROUVE
    //   and dateDecision would be overwritten with today, so the register would
    //   no longer show that a refusal ever happened.
    // WHY A ROUTE OF ITS OWN, AND NOT A FIELD IN THE PUT BODY: the decision would
    //   then be data like any other, and the person who opened the request could
    //   approve it themselves in the same call that creates or edits it. Here the
    //   only door is this URL, guarded by MANAGE_GOVERNANCE on the service, and
    //   dateDecision is written by the server, never sent by the client.
    //
    // @PatchMapping("/{id}/approuver")
    //   WHAT: binds HTTP PATCH on the class prefix plus /{id}/approuver.
    //   WHY PATCH: the call changes one part of an existing resource, not the
    //         whole of it. POST would read as "create a new thing", PUT as
    //         "replace the whole thing", and both would lie about what happens.
    //   WHY THE URL ENDS WITH A VERB: it names the decision rather than the
    //         field, which is the point - the caller asks for a decision, it does
    //         not write a value.
    //
    // NO @RequestBody HERE
    //   WHAT: the method takes no body; the Angular call sends an empty {}.
    //   WHY:  everything needed is already in the URL (which project, which
    //         request), and the outcome is fixed by the route itself.
    //   WITHOUT this choice (a body carrying the target status): the client would
    //         pick the decision, and the approval step would be decoration.
    //
    // WHO CALLS IT: the Angular governance screen, through
    //   GovernanceService.approuverChangement().
    // ------------------------------------------------------------------------
    @PatchMapping("/{id}/approuver")
    public ResponseEntity<DemandeChangementResponse> approuver(@PathVariable Long projectId,
                                                                @PathVariable Long id) {
        return ResponseEntity.ok(dcService.approuver(projectId, id));
    }

    // ------------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/demandes-changement/{id}/rejeter
    //
    // WHAT IT DOES: refuses one change request. The service sets statut = REJETE
    //   and dateDecision = today, then gives the updated row back with status 200.
    //   It is the exact mirror of approuver: same rule, same guard, other
    //   outcome.
    // THE RULE, CHECKED IN THE SERVICE: only a request still EN_ATTENTE can be
    //   decided; anything else raises BusinessRuleException -> 422 ("La demande a
    //   déjà été traitée"). EXAMPLE of what this stops: rejecting a change that
    //   was approved and already built, which would leave the register saying the
    //   work was never agreed to.
    // WHY TWO ROUTES AND NOT ONE ROUTE WITH THE DECISION IN THE BODY: two URLs
    //   read plainly in the access log and in the Swagger page, each one can be
    //   given its own permission later if the rules change, and neither of them
    //   lets the client choose a value the server should own.
    //
    // @PatchMapping("/{id}/rejeter")
    //   WHAT: binds HTTP PATCH on the class prefix plus /{id}/rejeter.
    //   WHY PATCH: one part of an existing resource changes, with no body sent.
    //
    // WHO CALLS IT: the Angular governance screen, through
    //   GovernanceService.rejeterChangement().
    // ------------------------------------------------------------------------
    @PatchMapping("/{id}/rejeter")
    public ResponseEntity<DemandeChangementResponse> rejeter(@PathVariable Long projectId,
                                                              @PathVariable Long id) {
        return ResponseEntity.ok(dcService.rejeter(projectId, id));
    }

    // ------------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/demandes-changement/{id}
    //
    // WHAT IT DOES: takes one change request out of the register and answers 204
    //   No Content.
    // THE RULE, CHECKED IN THE SERVICE: a request that was already approved or
    //   rejected cannot be deleted; the service raises BusinessRuleException ->
    //   422 ("Impossible de supprimer une demande déjà traitée"). EXAMPLE of what
    //   this stops: making an approved change disappear after a disagreement over
    //   who asked for the extra work and who pays for it.
    // IMPORTANT - THE ROW IS NOT ERASED: the service only sets deleted = true (a
    //   "soft delete") and every repository query filters on deleted = false, so
    //   the line leaves the screens but stays in the table. WHY: the register can
    //   be audited months later, and the audit columns of V19 point at that row;
    //   a real DELETE would lose the history for good.
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
        dcService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
