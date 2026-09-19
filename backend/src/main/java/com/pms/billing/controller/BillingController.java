package com.pms.billing.controller;

import com.pms.billing.dto.*;
import com.pms.billing.service.AvenantService;
import com.pms.billing.service.JalonService;
import com.pms.billing.service.PaiementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// ============================================================================
// FILE: BillingController.java
// ----------------------------------------------------------------------------
// WHAT THIS FILE IS
//   The single REST entry door for the billing area of a project. It exposes
//   three families of data:
//     - "jalons"    = billing milestones (the payment schedule of the contract)
//     - "paiements" = the money actually received against a milestone
//     - "avenants"  = signed contract amendments that raise or lower the sold
//                     amount
//
// WHERE IT SITS IN THE FLOW
//   Angular BillingService
//     (frontend/pms-frontend/src/app/core/services/billing.service.ts)
//       -> HTTP request carrying the JWT access token (a JWT is a short signed
//          text token that the browser sends in the Authorization header)
//       -> Spring Security filter chain (is the caller logged in? -> 401)
//       -> ProjectScopeInterceptor (ADR-021: is THIS project inside the
//          caller's perimeter? -> 403). It applies because every URL of this
//          class starts with /api/projects/{projectId}.
//       -> this controller
//       -> JalonService / PaiementService / AvenantService  (the permission
//          checks and all the business rules live there)
//       -> repositories -> PostgreSQL
//   What comes back is a DTO record. DTO = "Data Transfer Object", a small
//   read-only object built only to be turned into JSON, so the database entity
//   is never exposed to the outside.
//
// WHY IT EXISTS - what breaks if you delete it
//   The three billing services would have no HTTP address at all. The Angular
//   billing screen could no longer list milestones, mark one as invoiced,
//   record a payment or add an amendment. The rules would still be written in
//   Java, but nothing outside the server could reach them.
//
// DESIGN CHOICE: THIS CONTROLLER IS DELIBERATELY "THIN"
//   Every method below does only three things: read the URL parts, hand them
//   to a service, wrap the answer in the right HTTP status. There is no "if",
//   no computation and no @PreAuthorize in this file, and that is on purpose:
//     - Permissions sit on the SERVICE methods (VIEW_BILLING to read,
//       MANAGE_BILLING to write). Why there and not here: a service is also
//       called from another service and from tests, not only from HTTP. If the
//       check lived in the controller, any second caller reaching JalonService
//       directly would walk straight past it.
//     - The project perimeter (ADR-021) is checked once, for all these URLs,
//       by ProjectScopeInterceptor. Holding the permission is not enough: a
//       project manager with MANAGE_BILLING must still be refused on a project
//       that is not his.
//   Concrete gain: nobody has to remember to repeat a perimeter test in eleven
//   methods, and forgetting it once would expose another client's amounts.
//
// WHY THERE IS NO try/catch HERE
//   The services throw NotFoundException and BusinessRuleException.
//   GlobalExceptionHandler turns them into a clean JSON error body:
//     404 = not found, 422 = a business rule said no, 400 = the @Valid check
//     failed, 403 = permission refused or project out of perimeter.
//   Example: posting a milestone percentage that would push the project above
//   100% comes back as 422 with a readable message, not as a 500 stack trace.
//
// NOTE ON THE FRENCH NAMES
//   The domain words stay in French because the company and the contracts use
//   them: jalon = milestone, facturer = to invoice, paiement = payment,
//   avenant = contract amendment, montant = amount, pourcentage = percentage.
//
// THE ELEVEN ROUTES OF THIS FILE, AND THE PERMISSION EACH ONE NEEDS
//   The permission is never written in this file. It is the @PreAuthorize
//   carried by the service method that each route calls. The list is repeated
//   here only so that the whole picture is readable in one place.
//     GET    /jalons                              -> VIEW_BILLING
//     POST   /jalons                              -> MANAGE_BILLING
//     PUT    /jalons/{id}                         -> MANAGE_BILLING
//     PATCH  /jalons/{id}/facturer                -> MANAGE_BILLING
//     DELETE /jalons/{id}                         -> MANAGE_BILLING
//     GET    /jalons/{jalonId}/paiements          -> VIEW_BILLING
//     POST   /jalons/{jalonId}/paiements          -> MANAGE_BILLING
//     DELETE /jalons/{jalonId}/paiements/{id}     -> MANAGE_BILLING
//     GET    /avenants                            -> VIEW_BILLING
//     POST   /avenants                            -> MANAGE_BILLING
//     DELETE /avenants/{id}                       -> MANAGE_BILLING
//   Every path above is relative to the class prefix /api/projects/{projectId},
//   so all eleven of them go through the ADR-021 perimeter check.
//
// NOTE ON COVERAGE
//   The current Angular service (core/services/billing.service.ts) calls nine
//   of these eleven routes. The PUT on a milestone and the DELETE of a payment
//   are part of the API (and of the permission matrix) but no screen uses them
//   today.
// ============================================================================

// CLASS BillingController - the four annotations below, one by one.
//
// @Tag(name = "Facturation", ...)
//   WHAT: an OpenAPI / Swagger label. It groups every route of this class
//         under one heading in the generated API documentation page.
//   WHY:  the API has many controllers; a reader needs to find the billing
//         routes together.
//   WITHOUT IT: the documentation page shows a default "billing-controller"
//         bucket mixed with the rest, and a reviewer has to guess which route
//         belongs to invoicing.
//
// @RestController
//   WHAT: marks the class as a Spring web component AND says "whatever a
//         method returns IS the response body", serialised to JSON.
//         It is @Controller + @ResponseBody in one annotation.
//   WHY:  this is a JSON API, not a server-rendered web site.
//   WITHOUT IT: with a plain @Controller, a returned object would be read as
//         the NAME OF AN HTML VIEW, and Spring would answer 404 because no
//         such template exists.
//
// @RequestMapping("/api/projects/{projectId}")
//   WHAT: the common prefix of every route below. {projectId} is a
//         placeholder filled from the real URL.
//   WHY:  billing lives UNDER a project on purpose. This exact URL shape is
//         what ProjectScopeInterceptor matches (its pattern is
//         ^/api/projects/(\d+)(/.*)?$ ), so the ADR-021 perimeter check is
//         applied to all eleven routes for free.
//         Reading that regular expression: ^ = start of the path, /api/projects/
//         is literal text, (\d+) captures one or more digits (the project id),
//         (/.*)? means "an optional rest of the path", $ = end. So it matches
//         /api/projects/7 and /api/projects/7/jalons/31/paiements alike, and
//         the interceptor reads the project id out of the captured digits.
//   WITHOUT IT: a flat route such as /api/jalons/42 would not match that
//         pattern, and any user holding VIEW_BILLING could read the
//         milestones of a project he has nothing to do with.
//
// @RequiredArgsConstructor (Lombok)
//   WHAT: generates, at compile time, a constructor that takes every "final"
//         field of the class.
//   WHY:  Spring injects the three services through that constructor, and the
//         fields stay final, so nothing can swap a service at runtime.
//   WITHOUT IT: you write the constructor by hand; the day you add a fourth
//         service and forget to add its parameter, the field stays null and
//         the first request fails with a NullPointerException.
@Tag(name = "Facturation", description = "Jalons de facturation, paiements et avenants contractuels")
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class BillingController {

    // The three services this controller delegates to. They are final, so
    // Lombok puts them in the generated constructor and Spring hands each one
    // its single shared instance at startup.
    // WHY three services instead of one big "BillingService": each one owns
    // one table and one set of rules (milestones, payments, amendments). They
    // already call each other where the business needs it - PaiementService
    // asks JalonService to refresh a milestone status, AvenantService asks it
    // to recompute amounts after a budget change (H-4) - so this controller
    // never has to orchestrate anything itself.
    // WITHOUT the "final" keyword: Lombok would leave the field out of the
    // generated constructor, the field would stay null, and the very first
    // request would fail with a NullPointerException.
    private final JalonService    jalonService;
    private final PaiementService paiementService;
    private final AvenantService  avenantService;

    // ── Jalons ───────────────────────────────────────────────────
    // =====================================================================
    // BILLING MILESTONES ("jalons de facturation")
    // The payment schedule written in the contract: "30% at signature, 40% at
    // delivery, 30% at acceptance". Each milestone stores a percentage, and
    // JalonService turns that percentage into an amount using the project's
    // effective budget, so nobody types the same money twice.
    // A milestone walks through three statuses:
    //   PREVU (planned) -> FACTURE (invoiced) -> PAYE (fully paid).
    // The status decides what is still allowed: once a milestone leaves PREVU
    // it can no longer be edited or deleted, because its invoice is already in
    // the accounts.

    // ---------------------------------------------------------------------
    // GET /api/projects/{projectId}/jalons
    // Gives back every live milestone of one project as a JSON array.
    //
    // @GetMapping("/jalons")
    //   WHAT: binds this method to HTTP GET on the class prefix + "/jalons",
    //         so the full path is /api/projects/{projectId}/jalons.
    //   WHY GET: reading changes nothing, so the browser, a proxy or a retry
    //         may repeat the call with no side effect.
    //   WITHOUT IT: Spring would not know the method exists and the URL would
    //         answer 404.
    //
    // @PathVariable Long projectId
    //   WHAT: copies the {projectId} part of the URL into the parameter and
    //         converts the text "42" into a Long.
    //   WHY:  the service needs that id to filter the rows of that project.
    //   WITHOUT IT: Spring would not fill the parameter from the URL, and the
    //         query would run on a null id.
    //   Side note: a non-numeric id such as /api/projects/abc/jalons never
    //         reaches this method. The conversion fails first and
    //         GlobalExceptionHandler answers 400.
    //
    // ResponseEntity.ok(...) -> HTTP 200 with the list in the body.
    // A project with no milestone yet answers 200 with an empty array, not
    // 404: "nothing planned yet" is a normal state, and the Angular table
    // shows its empty state instead of an error.
    //
    // Security recap for this route: jalonService.findByProject carries
    // @PreAuthorize("hasAuthority('VIEW_BILLING')"), and the project perimeter
    // was already checked by ProjectScopeInterceptor (ADR-021).
    // ---------------------------------------------------------------------
    @GetMapping("/jalons")
    public ResponseEntity<List<JalonResponse>> listJalons(@PathVariable Long projectId) {
        return ResponseEntity.ok(jalonService.findByProject(projectId));
    }

    // ---------------------------------------------------------------------
    // POST /api/projects/{projectId}/jalons
    // Creates one billing milestone and answers HTTP 201 Created with the row
    // that was saved (generated id included).
    //
    // @PostMapping: POST is used because each call creates a NEW row. Calling
    // it twice creates two milestones, so it is not idempotent ("idempotent"
    // means you can repeat the same call and the system ends up in the same
    // state). That is exactly why this must not be a GET or a PUT.
    //
    // @Valid @RequestBody JalonRequest request
    //   @RequestBody: reads the JSON sent by Angular and builds a JalonRequest
    //       record out of it.
    //   @Valid: runs the validation rules written inside JalonRequest
    //       (@NotBlank label, and @NotNull @Positive @DecimalMax("100") on the
    //       percentage) BEFORE the body of this method starts.
    //   WHY: never trust the browser form. The Angular form can be bypassed in
    //       ten seconds with curl or Postman.
    //   WITHOUT @Valid: a POST with pourcentage = -50 would be accepted here,
    //       the service would compute a negative amount, and the project would
    //       display a milestone that invoices minus 5 000 DT. With it, the
    //       request stops at 400 and the answer names the guilty field.
    //
    // What is deliberately NOT checked here: the rule "the percentages of one
    // project must not add up to more than 100%". That needs the other
    // milestones of the project, so it lives in
    // JalonService.validatePourcentageSum and comes back as 422.
    // ---------------------------------------------------------------------
    @PostMapping("/jalons")
    public ResponseEntity<JalonResponse> createJalon(@PathVariable Long projectId,
                                                      @Valid @RequestBody JalonRequest request) {
        JalonResponse created = jalonService.create(projectId, request);
        // Build the address of the row that was just created, for the HTTP
        // "Location" header.
        // HOW: fromCurrentRequest() takes the URL that was just called
        // (.../projects/7/jalons), .path("/{id}") appends a placeholder, and
        // buildAndExpand(created.id()) fills it, giving
        // /api/projects/7/jalons/31.
        // WHY: the HTTP rule for 201 Created is to tell the client where the
        // new thing now lives, instead of letting every client rebuild the URL
        // by gluing strings together.
        // WITHOUT IT: an API client (a future mobile app, a Postman test) that
        // wants to open the new milestone has to guess the path, and any
        // change of route silently breaks it.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // ResponseEntity.created(location) = status 201 + the Location header in
        // one call. (The two other POST methods below write the same thing the
        // long way, .status(HttpStatus.CREATED).location(location); the result
        // on the wire is identical.)
        // WHY 201 and not a plain 200: the Angular service and the tests can
        // tell "a row was created" apart from "the call went through but
        // nothing was added".
        return ResponseEntity.created(location).body(created);
    }

    // ---------------------------------------------------------------------
    // PUT /api/projects/{projectId}/jalons/{id}
    // Replaces the editable fields of one milestone (label, percentage,
    // planned date) and answers 200 with the updated row.
    //
    // WHY PUT and not PATCH: the body is a complete JalonRequest, so the
    // client sends the whole set of editable fields, not a few of them.
    // Sending the same PUT twice leaves exactly the same row: it is
    // idempotent, which is what PUT promises.
    //
    // TWO @PathVariable here, projectId AND id, and projectId is not
    // decoration: JalonService compares the milestone's own project id with
    // this projectId and answers 404 when they differ.
    // WHY that second check: ProjectScopeInterceptor only proved that the
    // caller may work on projectId; it knows nothing about the milestone id.
    // WITHOUT IT: a user whose perimeter covers project 3 could call
    // /api/projects/3/jalons/99 and edit milestone 99, which belongs to
    // project 8 and to another client.
    //
    // The refusal "a milestone already invoiced or paid cannot be modified" is
    // a business rule of JalonService and comes back as 422.
    // ---------------------------------------------------------------------
    @PutMapping("/jalons/{id}")
    public ResponseEntity<JalonResponse> updateJalon(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody JalonRequest request) {
        return ResponseEntity.ok(jalonService.update(projectId, id, request));
    }

    // ---------------------------------------------------------------------
    // PATCH /api/projects/{projectId}/jalons/{id}/facturer
    // Marks one milestone as invoiced ("facturer" = to issue the invoice) and
    // stores the invoice date. Answers 200 with the milestone.
    //
    // WHY PATCH plus a verb in the URL, rather than a plain PUT: this call
    // does not replace the milestone, it moves it from PREVU (planned) to
    // FACTURE (invoiced) and writes one date. Giving that transition its own
    // route keeps it a single, clearly named, permission-checked action.
    //   Example: the Angular screen has an "Invoice" button. With a plain PUT
    //   that button would also have to send the label and the percentage, and
    //   a stale form value could change the amount while the user only meant
    //   to invoice.
    //
    // FacturerRequest holds one single field, @NotNull LocalDate dateFacture,
    // so @Valid rejects a call without a date at 400.
    //   WITHOUT that rule: a milestone could sit in status FACTURE with no
    //   invoice date, and nobody could match it against the paper invoice
    //   during an audit.
    //
    // Order matters downstream: PaiementService refuses a payment while the
    // milestone is still PREVU, so this route has to be called first.
    // ---------------------------------------------------------------------
    @PatchMapping("/jalons/{id}/facturer")
    public ResponseEntity<JalonResponse> facturer(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody FacturerRequest request) {
        return ResponseEntity.ok(jalonService.facturer(projectId, id, request));
    }

    // ---------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/jalons/{id}
    // Removes one milestone and answers HTTP 204 No Content.
    //
    // The removal is a SOFT delete: JalonService only sets deleted = true, and
    // the repository reads filter those rows out.
    // WHY: billing data is accounting history. A real SQL DELETE would destroy
    // the trace of a milestone that other documents, or an audit, may still
    // refer to.
    //
    // noContent().build() -> 204: it worked, and there is nothing useful to
    // send back.
    // WHY not 200 with an empty body: 204 tells the Angular side "done, do not
    // try to parse a body". Returning the deleted row instead would tempt the
    // client to display something that no longer exists.
    //
    // The refusal "a milestone already invoiced or paid cannot be deleted"
    // comes from JalonService as 422.
    // ---------------------------------------------------------------------
    @DeleteMapping("/jalons/{id}")
    public ResponseEntity<Void> deleteJalon(@PathVariable Long projectId, @PathVariable Long id) {
        jalonService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Paiements ─────────────────────────────────────────────────
    // =====================================================================
    // PAYMENTS ("paiements") - the money actually received from the client.
    // They live UNDER a milestone in the URL (/jalons/{jalonId}/paiements)
    // because a payment has no meaning on its own: it always settles part or
    // all of one invoiced milestone.
    // Every call goes through PaiementService. The two write calls (create and
    // delete) end by calling JalonService.recalculerStatut, so the milestone
    // flips to PAYE as soon as the sum of its payments reaches its amount (and
    // back to FACTURE if a payment is cancelled). The read below only reads.

    // ---------------------------------------------------------------------
    // GET /api/projects/{projectId}/jalons/{jalonId}/paiements
    // Lists the payments recorded against one milestone. 200 + JSON array.
    //
    // Both ids are passed down. PaiementService loads the milestone and checks
    // that it really belongs to projectId, otherwise it throws NotFound.
    // WHY: the interceptor proved the caller may see projectId; it knows
    // nothing about jalonId.
    // WITHOUT that check: a user could read another client's payments simply
    // by putting his own project id in front of a foreign milestone id.
    // WHY the answer is 404 and not 403: saying "this exists but is not yours"
    // already tells the attacker that milestone 99 exists. A flat "not found"
    // leaks nothing.
    // ---------------------------------------------------------------------
    @GetMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<List<PaiementResponse>> listPaiements(@PathVariable Long projectId,
                                                                  @PathVariable Long jalonId) {
        return ResponseEntity.ok(paiementService.findByJalon(projectId, jalonId));
    }

    // ---------------------------------------------------------------------
    // POST /api/projects/{projectId}/jalons/{jalonId}/paiements
    // Records one payment received for a milestone. 201 + the saved row.
    //
    // @Valid on PaiementRequest enforces @NotNull @Positive montantRecu and
    // @NotNull datePaiement at the door.
    // WITHOUT @Positive: a payment of -200 would be stored, the milestone
    // would count less money than it really received, and it would never reach
    // status PAYE even though the client has paid.
    //
    // Business rule kept in the service, not here: the milestone must already
    // be invoiced (status different from PREVU) before a payment is accepted,
    // otherwise 422. Recording money against something that was never invoiced
    // would break the trail between the invoice and the bank statement.
    //
    // Side effect worth knowing when you read the response: right after
    // saving, PaiementService calls JalonService.recalculerStatut, which may
    // flip the milestone to PAYE. The PaiementResponse returned here does not
    // show that new status, which is why the Angular screen reloads the
    // milestone list after a successful payment.
    // ---------------------------------------------------------------------
    @PostMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<PaiementResponse> createPaiement(@PathVariable Long projectId,
                                                             @PathVariable Long jalonId,
                                                             @Valid @RequestBody PaiementRequest request) {
        PaiementResponse created = paiementService.create(projectId, jalonId, request);
        // Same Location header as in createJalon above, one level deeper: the
        // address of the payment that was just created, for example
        // /api/projects/7/jalons/31/paiements/12.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // .status(HttpStatus.CREATED).location(location) is the long form of the
        // ResponseEntity.created(location) used in createJalon: 201 plus the
        // Location header, exactly the same answer on the wire.
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    // ---------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/jalons/{jalonId}/paiements/{id}
    // Cancels one recorded payment. Answers 204.
    //
    // Three ids, and the service checks the whole chain: the milestone must
    // belong to the project AND the payment must belong to that milestone,
    // otherwise 404. Same reasoning as the read above, one level deeper.
    //
    // Soft delete again (deleted = true), then recalculerStatut runs once
    // more.
    // WHY that recompute matters: if the milestone was PAYE only thanks to
    // this payment, removing it must send the milestone back to FACTURE.
    // WITHOUT it, the project would keep reporting money it does not have, and
    // the cash-in figures shown to the director would be false.
    // ---------------------------------------------------------------------
    @DeleteMapping("/jalons/{jalonId}/paiements/{id}")
    public ResponseEntity<Void> deletePaiement(@PathVariable Long projectId,
                                                @PathVariable Long jalonId,
                                                @PathVariable Long id) {
        paiementService.delete(projectId, jalonId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Avenants ──────────────────────────────────────────────────
    // =====================================================================
    // AMENDMENTS ("avenants") - a signed change to the contract that adds
    // money to it, or removes money from it, after the project has started.
    // They hang on the project, not on a milestone, because they change the
    // contract as a whole.
    // Creating or deleting one moves the project's revised budget - and
    // AvenantService is the ONLY place allowed to write that column (audit
    // marker C-1) - then asks JalonService to recompute the amount of the
    // milestones still in PREVU (marker H-4). Milestones already FACTURE or
    // PAYE keep their amount: their accounting is closed.

    // ---------------------------------------------------------------------
    // GET /api/projects/{projectId}/avenants
    // Lists the live amendments of the project. 200 + JSON array.
    //
    // Read-only, so AvenantService asks for VIEW_BILLING and not
    // MANAGE_BILLING.
    // WHY the two permissions are split: a director may look at the financial
    // figures of the projects he follows, but must not be able to change the
    // contract value. One single BILLING permission would give him both.
    // ---------------------------------------------------------------------
    @GetMapping("/avenants")
    public ResponseEntity<List<AvenantResponse>> listAvenants(@PathVariable Long projectId) {
        return ResponseEntity.ok(avenantService.findByProject(projectId));
    }

    // ---------------------------------------------------------------------
    // POST /api/projects/{projectId}/avenants
    // Registers one contract amendment. 201 + the saved row.
    //
    // @Valid on AvenantRequest requires @NotBlank numero, @NotNull montant and
    // @NotNull dateAvenant. The amount is intentionally NOT forced positive: a
    // negative amendment is a legitimate reduction of the contract.
    //
    // This is the heaviest write of the whole file, even though the body is
    // two lines long. Inside AvenantService, and inside ONE transaction: the
    // project's revised budget is moved by the amendment amount, the
    // milestones still in PREVU get their amount recomputed (marker H-4), then
    // the amendment row itself is saved.
    // WHY one single transaction: if the server died between two of those
    // writes, the project would carry a raised budget with no amendment row to
    // justify it - money appearing from nowhere in the accounts, with no way
    // to tell where it came from.
    // ---------------------------------------------------------------------
    @PostMapping("/avenants")
    public ResponseEntity<AvenantResponse> createAvenant(@PathVariable Long projectId,
                                                          @Valid @RequestBody AvenantRequest request) {
        AvenantResponse created = avenantService.create(projectId, request);
        // Same Location header as in the two creations above: the address of the
        // amendment that was just created, for example
        // /api/projects/7/avenants/4.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        // .status(HttpStatus.CREATED).location(location) is the long form of the
        // ResponseEntity.created(location) used in createJalon: 201 plus the
        // Location header, exactly the same answer on the wire.
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    // ---------------------------------------------------------------------
    // DELETE /api/projects/{projectId}/avenants/{id}
    // Cancels one amendment. Answers 204.
    //
    // AvenantService undoes what the creation did: it subtracts the amendment
    // amount from the revised budget, recomputes the PREVU milestones (H-4),
    // and only then soft-deletes the row.
    // WHY that order matters: the milestone amounts are derived from the
    // budget. Recomputing them before correcting the budget would rebuild them
    // from a figure that still contains the cancelled amendment, and the
    // payment schedule would stay too high.
    // ---------------------------------------------------------------------
    @DeleteMapping("/avenants/{id}")
    public ResponseEntity<Void> deleteAvenant(@PathVariable Long projectId, @PathVariable Long id) {
        avenantService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
