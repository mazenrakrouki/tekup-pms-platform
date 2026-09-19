package com.pms.billing.service;

import com.pms.billing.dto.PaiementRequest;
import com.pms.billing.dto.PaiementResponse;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.entity.Paiement;
import com.pms.billing.mapper.PaiementMapper;
import com.pms.billing.repository.PaiementRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * WHAT THIS FILE IS
 * -----------------
 * Business service for "paiements" (payments received from the client).
 * One payment is money actually received against ONE billing milestone (a "jalon"): an amount, the
 * date it arrived, and an optional bank reference. A milestone can receive several payments, for
 * example a deposit and then the balance.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   HTTP request -> BillingController (/api/projects/{projectId}/jalons/{jalonId}/paiements)
 *                -> ProjectScopeInterceptor (ADR-021: caller may act on THIS project)
 *                -> PaiementService (this file: permission check + business rules)
 *                -> JalonService.loadJalon(...)        (loads the parent milestone)
 *                -> PaiementRepository                  (reads/writes the "paiements" table)
 *                -> JalonService.recalculerStatut(...)  (re-derives the milestone status)
 *                -> PaiementMapper                      (entity -> PaiementResponse DTO)
 *
 * WHY IT EXISTS
 * -------------
 * It holds the link between "money received" and "milestone status". Two rules live only here:
 *   1. a payment can only be recorded on a milestone that has already been invoiced;
 *   2. every write (create or cancel) must be followed by a status recompute of the milestone.
 * Delete this class and the PAYE status would never be reached by itself: a project manager would
 * have to set it by hand, and the status would drift away from the money actually received.
 *
 * Note that this class never writes the milestone status itself. It always asks JalonService, so
 * the PREVU / FACTURE / PAYE rules stay written in one single place.
 */
@Service
// Lombok builds the constructor over the three `final` fields; Spring injects them through it.
@RequiredArgsConstructor
public class PaiementService {

    private final PaiementRepository paiementRepository;
    private final JalonService       jalonService;
    private final PaiementMapper     paiementMapper;

    /**
     * Returns every payment recorded against one milestone, oldest payment date first.
     *
     * Why the projectId parameter is there although only jalonId is needed to query: it is used to
     * prove that the milestone hangs under the project in the URL. See the check inside.
     */
    // VIEW_BILLING: reading payments means reading how much the client has already paid.
    // The check sits on the service and tests a permission, never a role name, so the database-driven
    // permission matrix stays the single source of truth.
    // Without it: any authenticated user in the project scope could read the cash-in history.
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    // readOnly = true: one transaction, nothing written. It also keeps the entity attached while the
    // mapper reads it.
    @Transactional(readOnly = true)
    public List<PaiementResponse> findByJalon(Long projectId, Long jalonId) {
        // loadJalon() is package-private in JalonService and reused here so both services apply the
        // same `deleted = false` filter and the same 404 wording.
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        // ADR-021 has already proved the caller may act on {projectId}; it says nothing about
        // {jalonId}. This second check blocks id mixing between two projects the caller can both see.
        // .equals() and not ==: both sides are Long objects, and == compares references. Java caches
        // small Long values, so == would appear to work for id 5 and break silently for id 1000.
        // It answers 404 rather than 403 so the reply does not confirm that the row exists elsewhere.
        // Without it: GET /api/projects/5/jalons/42/paiements would return the payments of project 9.
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        return paiementMapper.toResponseList(paiementRepository.findActiveByJalonId(jalonId));
    }

    /**
     * Records one payment received and lets the milestone status follow.
     *
     * Returns the saved payment as a DTO (with its generated id).
     *
     * Why written this way: the status of the milestone is NOT set here. The payment is written
     * first, then JalonService recomputes the status from the sum of all payments. That way the
     * status can never claim "paid" for an amount that is not in the payments table.
     */
    // MANAGE_BILLING, not VIEW_BILLING: this records money and can flip a milestone to PAYE.
    // Per the authorization matrix (BR-038) billing is a project-manager capability.
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    // @Transactional makes the whole method one single unit of work in the database.
    // Why: two tables are written below - the payment row, then the milestone status inside
    // recalculerStatut(). Without it, a crash between the two would leave the money recorded while
    // the milestone still shows FACTURE, and the "outstanding amount" report would be wrong.
    @Transactional
    public PaiementResponse create(Long projectId, Long jalonId, PaiementRequest request) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        // Same ownership check as above: the milestone must belong to the project in the URL.
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        // Business rule: no payment before an invoice. A client does not pay a milestone that was
        // never invoiced, so such a record almost always means the user picked the wrong row.
        // BusinessRuleException maps to HTTP 422 (well-formed request, refused by the domain), which
        // the frontend shows as a readable message instead of a generic error.
        // Without it: a payment could land on a PREVU milestone, recalculerStatut() would jump it
        // straight to PAYE, and a milestone would appear paid while no invoice was ever sent.
        if (jalon.getStatut() == JalonStatut.PREVU) {
            throw new BusinessRuleException("Le jalon doit être facturé avant d'enregistrer un paiement");
        }

        // Builder (Lombok @Builder on the entity): named fields, so adding a column later cannot
        // silently shift positional arguments.
        // The parent milestone is attached here; that is what makes the SUM in recalculerStatut()
        // find this payment.
        Paiement paiement = Paiement.builder()
                .jalon(jalon)
                .montantRecu(request.montantRecu())
                .datePaiement(request.datePaiement())
                .reference(request.reference())
                .build();

        // save() returns the instance carrying the generated id, and that is the one mapped, so the
        // client gets the id it needs to cancel the payment later.
        PaiementResponse response = paiementMapper.toResponse(paiementRepository.save(paiement));
        // The status recompute runs AFTER the payment is saved, and inside the same transaction.
        // The SUM query it runs forces Hibernate to flush the pending INSERT first, so the payment
        // just created is counted. Calling it before the save would compare the old total and the
        // milestone would stay FACTURE even after the final payment.
        // Mapping the response before this call is safe: PaiementResponse carries the payment and the
        // jalon id only, never the milestone status.
        jalonService.recalculerStatut(jalon);
        return response;
    }

    /**
     * Cancels a payment and lets the milestone status fall back.
     *
     * Why written this way: it is a soft delete (a `deleted` flag), not a real DELETE, because cash
     * movements must stay auditable. And the status is recomputed afterwards, so a milestone that was
     * PAYE only thanks to this payment goes back to FACTURE by itself.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long jalonId, Long id) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        // The milestone must belong to the project in the URL (see findByJalon for the full reason).
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }

        // findActiveById() already skips rows whose `deleted` flag is true, so cancelling twice gives
        // a clean 404 instead of triggering a second, pointless status recompute.
        Paiement paiement = paiementRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable : " + id));
        // Third level of the same defence: the payment must really hang under THIS milestone.
        // The project check above is not enough, because one project owns several milestones.
        // getJalon() returns a lazy proxy here (the query does not JOIN FETCH it), but reading only
        // its id is served from the proxy itself and costs no extra SELECT.
        // Without it: DELETE .../jalons/7/paiements/42 would cancel payment 42 that belongs to
        // milestone 9, and then recompute the status of milestone 7, which did not change - so
        // milestone 9 would keep showing as paid with the money removed.
        if (!paiement.getJalon().getId().equals(jalonId)) {
            throw new NotFoundException("Paiement introuvable : " + id);
        }

        // Soft delete: the row stays in the table but every "findActive..." query and the SUM used by
        // recalculerStatut() ignore it from now on.
        paiement.setDeleted(true);
        paiementRepository.save(paiement);
        // Recompute after the flag is set, so the SUM no longer counts this payment and the milestone
        // can drop from PAYE back to FACTURE.
        jalonService.recalculerStatut(jalon);
    }
}
