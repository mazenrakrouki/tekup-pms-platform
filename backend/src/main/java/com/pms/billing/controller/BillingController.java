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

// REST entry door for a project's billing: "jalons" (billing milestones), "paiements"
// (payments received against a milestone), "avenants" (signed contract amendments). Deliberately
// thin — no @PreAuthorize, no business logic here. Permissions (VIEW_BILLING / MANAGE_BILLING)
// sit on the service methods, since services are also called from other services and tests, not
// only HTTP; the project perimeter (ADR-021) is checked once for all these routes by
// ProjectScopeInterceptor because every URL starts with /api/projects/{projectId}. Domain words
// stay French (jalon, facturer, paiement, avenant, montant, pourcentage) to match the contracts.
//
// Permission each route needs (enforced by the service, listed here for one place to read):
//   GET/POST/PUT/PATCH/DELETE /jalons...           -> VIEW_BILLING / MANAGE_BILLING
//   GET/POST/DELETE /jalons/{jalonId}/paiements...  -> VIEW_BILLING / MANAGE_BILLING
//   GET/POST/DELETE /avenants...                    -> VIEW_BILLING / MANAGE_BILLING
// The Angular billing.service.ts currently calls nine of the eleven routes; PUT on a milestone
// and DELETE on a payment exist in the API but no screen uses them yet.
@Tag(name = "Facturation", description = "Jalons de facturation, paiements et avenants contractuels")
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class BillingController {

    // One service per table/rule set; they already call each other where needed
    // (PaiementService asks JalonService to refresh a milestone status, AvenantService asks it
    // to recompute amounts after a budget change, H-4), so this controller never orchestrates.
    private final JalonService    jalonService;
    private final PaiementService paiementService;
    private final AvenantService  avenantService;

    // ── Jalons ───────────────────────────────────────────────────
    // Billing milestones: the contract's payment schedule ("30% at signature, 40% at delivery,
    // 30% at acceptance"). Each stores a percentage; JalonService turns it into an amount from
    // the project's effective budget. Status walks PREVU -> FACTURE -> PAYE, and once a
    // milestone leaves PREVU it can no longer be edited or deleted (its invoice is already booked).

    // GET /jalons: every live milestone of the project. Empty array (200), not 404, when none exist yet.
    @GetMapping("/jalons")
    public ResponseEntity<List<JalonResponse>> listJalons(@PathVariable Long projectId) {
        return ResponseEntity.ok(jalonService.findByProject(projectId));
    }

    // POST /jalons: creates a milestone, 201 + Location header. @Valid enforces label/percentage
    // rules at the door; the "percentages must not exceed 100%" rule needs the project's other
    // milestones, so it lives in JalonService and comes back as 422.
    @PostMapping("/jalons")
    public ResponseEntity<JalonResponse> createJalon(@PathVariable Long projectId,
                                                      @Valid @RequestBody JalonRequest request) {
        JalonResponse created = jalonService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // PUT /jalons/{id}: replaces a milestone's editable fields, 200 + updated row. JalonService
    // also compares the milestone's own project with projectId (404 if they differ) — the
    // interceptor only proved the caller may work on projectId, not that this milestone id is in it.
    @PutMapping("/jalons/{id}")
    public ResponseEntity<JalonResponse> updateJalon(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @Valid @RequestBody JalonRequest request) {
        return ResponseEntity.ok(jalonService.update(projectId, id, request));
    }

    // PATCH /jalons/{id}/facturer: moves a milestone PREVU -> FACTURE and records the invoice
    // date. A dedicated route rather than folding into PUT, so the Angular "Invoice" button
    // can't accidentally overwrite the label/percentage with a stale form value.
    @PatchMapping("/jalons/{id}/facturer")
    public ResponseEntity<JalonResponse> facturer(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody FacturerRequest request) {
        return ResponseEntity.ok(jalonService.facturer(projectId, id, request));
    }

    // DELETE /jalons/{id}: soft delete (204), since billing data is accounting history that an
    // audit may still need. "Already invoiced/paid can't be deleted" comes from JalonService as 422.
    @DeleteMapping("/jalons/{id}")
    public ResponseEntity<Void> deleteJalon(@PathVariable Long projectId, @PathVariable Long id) {
        jalonService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Paiements ─────────────────────────────────────────────────
    // Payments actually received, nested under a milestone since a payment always settles part
    // or all of one invoiced jalon. Create/delete both trigger JalonService.recalculerStatut, so
    // a milestone flips to PAYE once its payments cover its amount (and back on cancellation).

    // GET .../paiements: payments of one milestone. PaiementService checks the milestone really
    // belongs to projectId and answers 404 (not 403) otherwise, so a foreign milestone id can't
    // be probed for existence.
    @GetMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<List<PaiementResponse>> listPaiements(@PathVariable Long projectId,
                                                                  @PathVariable Long jalonId) {
        return ResponseEntity.ok(paiementService.findByJalon(projectId, jalonId));
    }

    // POST .../paiements: records a payment, 201 + saved row. @Positive on montantRecu keeps a
    // negative amount from ever being stored. The milestone must already be invoiced (not PREVU)
    // or this is refused as 422. Note: the returned row doesn't reflect a status flip to PAYE
    // triggered as a side effect, which is why Angular reloads the milestone list after a payment.
    @PostMapping("/jalons/{jalonId}/paiements")
    public ResponseEntity<PaiementResponse> createPaiement(@PathVariable Long projectId,
                                                             @PathVariable Long jalonId,
                                                             @Valid @RequestBody PaiementRequest request) {
        PaiementResponse created = paiementService.create(projectId, jalonId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    // DELETE .../paiements/{id}: cancels a payment (204). Service checks the whole chain
    // (milestone belongs to project, payment belongs to milestone) and recomputes the milestone
    // status again — if it was PAYE only thanks to this payment, it drops back to FACTURE.
    @DeleteMapping("/jalons/{jalonId}/paiements/{id}")
    public ResponseEntity<Void> deletePaiement(@PathVariable Long projectId,
                                                @PathVariable Long jalonId,
                                                @PathVariable Long id) {
        paiementService.delete(projectId, jalonId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Avenants ──────────────────────────────────────────────────
    // Contract amendments that raise or lower the sold amount after the project has started.
    // Hang on the project, not a milestone. AvenantService is the only place allowed to write
    // the project's revised budget (marker C-1); creating/deleting one also recomputes the
    // amount of milestones still in PREVU (marker H-4) — FACTURE/PAYE milestones are closed and untouched.

    // GET /avenants: live amendments of the project. VIEW_BILLING, not MANAGE_BILLING — reading
    // financial figures and changing the contract value are different rights on purpose.
    @GetMapping("/avenants")
    public ResponseEntity<List<AvenantResponse>> listAvenants(@PathVariable Long projectId) {
        return ResponseEntity.ok(avenantService.findByProject(projectId));
    }

    // POST /avenants: registers an amendment, 201 + saved row. montant is NOT forced positive —
    // a negative amendment is a legitimate contract reduction. Inside AvenantService, one
    // transaction covers the budget change, the PREVU milestones' recompute (H-4), and the
    // amendment row itself, so a crash mid-way can't leave a raised budget with no amendment to justify it.
    @PostMapping("/avenants")
    public ResponseEntity<AvenantResponse> createAvenant(@PathVariable Long projectId,
                                                          @Valid @RequestBody AvenantRequest request) {
        AvenantResponse created = avenantService.create(projectId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(created);
    }

    // DELETE /avenants/{id}: cancels an amendment (204). Reverses the budget change first, then
    // recomputes PREVU milestones (H-4), then soft-deletes — recomputing before correcting the
    // budget would rebuild amounts from a figure that still includes the cancelled amendment.
    @DeleteMapping("/avenants/{id}")
    public ResponseEntity<Void> deleteAvenant(@PathVariable Long projectId, @PathVariable Long id) {
        avenantService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
