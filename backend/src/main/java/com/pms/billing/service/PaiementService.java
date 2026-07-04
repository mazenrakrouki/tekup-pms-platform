package com.pms.billing.service;

import com.pms.billing.dto.PaiementRequest;
import com.pms.billing.dto.PaiementResponse;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.entity.Paiement;
import com.pms.billing.mapper.PaiementMapper;
import com.pms.billing.repository.PaiementRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaiementService {

    private final PaiementRepository paiementRepository;
    private final JalonService       jalonService;
    private final PaiementMapper     paiementMapper;

    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<PaiementResponse> findByJalon(Long projectId, Long jalonId) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        return paiementMapper.toResponseList(paiementRepository.findActiveByJalonId(jalonId));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public PaiementResponse create(Long projectId, Long jalonId, PaiementRequest request) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }
        if (jalon.getStatut() == JalonStatut.PREVU) {
            throw new IllegalArgumentException("Le jalon doit être facturé avant d'enregistrer un paiement");
        }

        Paiement paiement = Paiement.builder()
                .jalon(jalon)
                .montantRecu(request.montantRecu())
                .datePaiement(request.datePaiement())
                .reference(request.reference())
                .build();

        PaiementResponse response = paiementMapper.toResponse(paiementRepository.save(paiement));
        jalonService.recalculerStatut(jalon);
        return response;
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long jalonId, Long id) {
        JalonFacturation jalon = jalonService.loadJalon(jalonId);
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalonId);
        }

        Paiement paiement = paiementRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable : " + id));
        if (!paiement.getJalon().getId().equals(jalonId)) {
            throw new NotFoundException("Paiement introuvable : " + id);
        }

        paiement.setDeleted(true);
        paiementRepository.save(paiement);
        jalonService.recalculerStatut(jalon);
    }
}
