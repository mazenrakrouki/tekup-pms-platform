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
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// Business service for "jalons de facturation" (billing milestones): percentage, computed amount,
// planned date and a status lifecycle PREVU -> FACTURE -> PAYE. Only a PREVU jalon can be edited
// or removed; once invoiced the row is frozen. It is the only place enforcing that the percentages
// of a project never exceed 100% and that invoiced/paid jalons can't be changed or recomputed.
@Service
@RequiredArgsConstructor
public class JalonService {

    private final JalonFacturationRepository jalonRepository;
    private final PaiementRepository         paiementRepository;
    private final ProjectRepository          projectRepository;
    private final JalonMapper                jalonMapper;

    /** Payment plan of one project: every non-deleted jalon, ordered by planned date. */
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<JalonResponse> findByProject(Long projectId) {
        loadProject(projectId); // fails fast with 404 if unknown
        return jalonMapper.toResponseList(jalonRepository.findActiveByProjectId(projectId));
    }

    /** Adds one milestone to the payment plan; percentage is validated before anything is saved. */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse create(Long projectId, JalonRequest request) {
        Project project = loadProject(projectId);
        validatePourcentageSum(projectId, null, request.pourcentage()); // null: nothing replaced

        BigDecimal montant = computeMontant(project, request.pourcentage());

        // statut is left unset: the entity defaults to PREVU, the only legal starting point.
        JalonFacturation jalon = JalonFacturation.builder()
                .project(project)
                .label(request.label())
                .pourcentage(request.pourcentage())
                .montant(montant)
                .datePrevue(request.datePrevue())
                .build();

        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /**
     * Edits a milestone that has not been invoiced yet. Reloads from the database rather than
     * trusting the incoming JSON, so status and amount can't be forged by the client.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse update(Long projectId, Long id, JalonRequest request) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Impossible de modifier un jalon déjà facturé ou payé");
        }

        // oldPct is the value being replaced, not added on top of.
        validatePourcentageSum(projectId, jalon.getPourcentage(), request.pourcentage());

        Project project = jalon.getProject();
        jalon.setLabel(request.label());
        jalon.setPourcentage(request.pourcentage());
        jalon.setMontant(computeMontant(project, request.pourcentage()));
        jalon.setDatePrevue(request.datePrevue());

        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /**
     * Marks a milestone as invoiced. A separate method (not part of update()) because invoicing
     * is an accounting event that must only ever happen from PREVU, never as a side effect of a save.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public JalonResponse facturer(Long projectId, Long id, FacturerRequest request) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Seul un jalon en statut PREVU peut être facturé");
        }

        jalon.setStatut(JalonStatut.FACTURE);
        // Date comes from the request, not the server clock: often recorded a few days late.
        jalon.setDateFacture(request.dateFacture());
        return jalonMapper.toResponse(jalonRepository.save(jalon));
    }

    /** Removes a milestone from the payment plan, only while still PREVU. Soft delete for auditability. */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long id) {
        JalonFacturation jalon = loadJalon(id);
        checkBelongsToProject(jalon, projectId);

        if (jalon.getStatut() != JalonStatut.PREVU) {
            throw new BusinessRuleException("Impossible de supprimer un jalon déjà facturé ou payé");
        }

        jalon.setDeleted(true);
        jalonRepository.save(jalon);
    }

    // ── Called by AvenantService / ProjectService (H-4) ───────────

    /**
     * H-4: recomputes the montant of PREVU jalons when the effective budget changes; FACTURE and
     * PAYE jalons stay frozen. Not an API entry point (no @PreAuthorize/@Transactional): every
     * caller already checked its own permission and opened the transaction this joins.
     */
    public void recomputePrevuMontants(Project project) {
        // Filtering PREVU in the query itself (not with an `if` after) guarantees a FACTURE/PAYE
        // row is never even loaded.
        List<JalonFacturation> prevus = jalonRepository.findByProjectIdAndStatutAndDeletedFalse(
                project.getId(), JalonStatut.PREVU);
        if (prevus.isEmpty() || project.getEffectiveBudget() == null) return;
        prevus.forEach(j -> j.setMontant(computeMontant(project, j.getPourcentage())));
        jalonRepository.saveAll(prevus);
    }

    // ── Called by PaiementService after every payment ─────────────

    /**
     * Recomputes the status of one jalon from its recorded payments — the status is always
     * derived, never set by hand, so it can't drift from the money actually received.
     * Package-private: internal step of a payment, no permission check of its own needed.
     */
    void recalculerStatut(JalonFacturation jalon) {
        if (jalon.getMontant() == null) return; // no target amount to compare against
        BigDecimal total = paiementRepository.sumMontantByJalonId(jalon.getId());
        // compareTo, not equals: BigDecimal equals also compares scale (30000 != 30000.00).
        if (total.compareTo(jalon.getMontant()) >= 0) {
            jalon.setStatut(JalonStatut.PAYE);
        } else if (jalon.getStatut() == JalonStatut.PAYE) {
            // Downgrade after a payment was cancelled: back to FACTURE, or PREVU if never invoiced.
            jalon.setStatut(jalon.getDateFacture() != null ? JalonStatut.FACTURE : JalonStatut.PREVU);
        }
        jalonRepository.save(jalon);
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Refuses a payment plan whose percentages would add up to over 100%.
     * oldPct is the percentage being replaced (null on create); newPct is the requested value.
     */
    private void validatePourcentageSum(Long projectId, BigDecimal oldPct, BigDecimal newPct) {
        BigDecimal current = jalonRepository.sumPourcentageByProjectId(projectId);
        BigDecimal effective = current.subtract(oldPct != null ? oldPct : BigDecimal.ZERO).add(newPct);
        if (effective.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException(
                    "La somme des pourcentages dépasserait 100% (actuel : " + current.toPlainString() + "%)");
        }
    }

    /**
     * Amount of money for a percentage: effective budget x percentage / 100, rounded to the cent.
     * Returns null when the project has no budget yet (a legal state).
     */
    private BigDecimal computeMontant(Project project, BigDecimal pourcentage) {
        BigDecimal budget = project.getEffectiveBudget();
        // null (not zero) on purpose: zero would mean "worth nothing", null means "not computable
        // yet" — recalculerStatut() relies on that distinction.
        if (budget == null) return null;
        return budget.multiply(pourcentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Checks the jalon really belongs to the project named in the URL — ADR-021 only proves the
     * caller may act on {projectId}, not that this jalon id belongs to it. 404, not 403, so the
     * reply doesn't confirm the row exists elsewhere.
     */
    private void checkBelongsToProject(JalonFacturation jalon, Long projectId) {
        if (!jalon.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Jalon introuvable : " + jalon.getId());
        }
    }

    /**
     * Loads a jalon that is not soft-deleted, or throws a 404. Package-private: PaiementService
     * reuses it so both services share the same filter and error message.
     */
    JalonFacturation loadJalon(Long id) {
        return jalonRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Jalon introuvable : " + id));
    }

    /** Loads a project that is not soft-deleted, or throws a 404. */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
