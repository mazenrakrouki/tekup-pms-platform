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

// Business service for "paiements": money actually received against one billing milestone (a
// jalon can receive several payments, e.g. a deposit then the balance). This class never writes
// the milestone status itself — it always asks JalonService, so PREVU/FACTURE/PAYE stays defined
// in one single place, and every write here is followed by a status recompute there.
@Service
@RequiredArgsConstructor
public class PaiementService {

    private final PaiementRepository paiementRepository;
    private final JalonService       jalonService;
    private final PaiementMapper     paiementMapper;

    /** Every payment recorded against one milestone, oldest first. */
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<PaiementResponse> findByJalon(Long projectId, Long jalonId) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        // ADR-021 only proves the caller may act on {projectId}; this blocks id mixing between two
        // projects the caller can see. 404, not 403, so the reply doesn't leak the row exists elsewhere.
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        return paiementMapper.toResponseList(paiementRepository.findActiveByJalonId(jalonId));
    }

    /**
     * Records one payment and lets the milestone status follow. The payment is saved first, then
     * JalonService recomputes the status from the sum of all payments — never set directly here.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public PaiementResponse create(Long projectId, Long jalonId, PaiementRequest request) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        // No payment before an invoice: prevents a payment silently jumping a PREVU milestone to PAYE.
        if (jalon.getStatut() == JalonStatut.PREVU) {
            throw new BusinessRuleException("Le jalon doit être facturé avant d'enregistrer un paiement");
        }

        Paiement paiement = Paiement.builder()
                .jalon(jalon)
                .montantRecu(request.montantRecu())
                .datePaiement(request.datePaiement())
                .reference(request.reference())
                .build();

        PaiementResponse response = paiementMapper.toResponse(paiementRepository.save(paiement));
        // Must run after the save (in the same transaction) so the SUM counts this payment too.
        jalonService.recalculerStatut(jalon);
        return response;
    }

    /** Cancels a payment (soft delete) and lets the milestone status fall back accordingly. */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long jalonId, Long id) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }

        Paiement paiement = paiementRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable : " + id));
        // The payment must belong to THIS milestone — one project owns several milestones, so the
        // project check above alone is not enough.
        if (!paiement.getJalon().getId().equals(jalonId)) {
            throw new NotFoundException("Paiement introuvable : " + id);
        }

        paiement.setDeleted(true);
        paiementRepository.save(paiement);
        // Recompute after the flag is set, so the SUM stops counting this payment.
        jalonService.recalculerStatut(jalon);
    }
}
