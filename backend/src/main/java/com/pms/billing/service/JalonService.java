package com.pms.billing.service;

import com.pms.billing.dto.FacturerRequest;
import com.pms.billing.dto.JalonRequest;
import com.pms.billing.dto.JalonResponse;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.mapper.JalonMapper;
import com.pms.billing.repository.JalonFacturationRepository;
import com.pms.billing.repository.PaiementRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JalonService {

    private final JalonFacturationRepository jalonRepository;
    private final PaiementRepository         paiementRepository;
    private final ProjectRepository          projectRepository;
    private final JalonMapper                jalonMapper;

    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<JalonResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return jalonMapper.toResponseList(jalonRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse create(Long projectId, JalonRequest request) {
        Project project = loadProject(projectId);
        validatePourcentageSum(projectId, null, request.pourcentage());

        BigDecimal montant = computeMontant(project, request.pourcentage());

        JalonFacturation jalon = JalonFacturation.builder()
                .project(project)
                .label(request.label())
                .pourcentage(request.pourcentage())
                .montant(montant)
                .datePrevue(request.datePrevue())
                .build();

        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse update(Long projectId, Long id, JalonRequest request) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new IllegalArgumentException("Impossible de modifier un jalon déjà facturé ou payé");
        }

        validatePourcentageSum(projectId, jalon.getPourcentage(), request.pourcentage());

        Project project = jalon.getProject();
        jalon.setLabel(request.label());
        jalon.setPourcentage(request.pourcentage());
        jalon.setMontant(computeMontant(project, request.pourcentage()));
        jalon.setDatePrevue(request.datePrevue());

        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse facturer(Long projectId, Long id, FacturerRequest request) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new IllegalArgumentException("Seul un jalon en statut PREVU peut être facturé");
        }

        jalon.setStatut(JalonStatut.FACTURE);
        jalon.setDateFacture(request.dateFacture());
        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long id) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new IllegalArgumentException("Impossible de supprimer un jalon déjà facturé ou payé");
        }

        jalon.setDeleted(true);
        jalonRepository.save(jalon);
    }

    // ── Appelé par PaiementService après chaque paiement ─────────

    void recalculerStatut(JalonFacturation jalon) {
        if (jalon.getMontant() == null) return;
        BigDecimal total = paiementRepository.sumMontantByJalonId(jalon.getId());
        if (total.compareTo(jalon.getMontant()) >= 0) {
            jalon.setStatut(JalonStatut.PAYE);
        } else if (jalon.getStatut() == JalonStatut.PAYE) {
            jalon.setStatut(jalon.getDateFacture() != null ? JalonStatut.FACTURE : JalonStatut.PREVU);
        }
        jalonRepository.save(jalon);
    }

    // ── Utilitaires ───────────────────────────────────────────────

    private void validatePourcentageSum(Long projectId, BigDecimal oldPct, BigDecimal newPct) {
        BigDecimal current = jalonRepository.sumPourcentageByProjectId(projectId);
        BigDecimal effective = current.subtract(oldPct != null ? oldPct : BigDecimal.ZERO).add(newPct);
        if (effective.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(
                    "La somme des pourcentages dépasserait 100% (actuel : " + current.toPlainString() + "%)");
        }
    }

    private BigDecimal computeMontant(Project project, BigDecimal pourcentage) {
        BigDecimal budget = project.getEffectiveBudget();
        if (budget == null) return null;
        return budget.multiply(pourcentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private void checkBelongsToProject(JalonFacturation jalon, Long projectId) {
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalon.getId());
        }
    }

    JalonFacturation loadJalon(Long id) {
        return jalonRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Jalon introuvable : " + id));
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
