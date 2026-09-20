package com.pms.billing.service;

import com.pms.billing.dto.AvenantRequest;
import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import com.pms.billing.mapper.AvenantMapper;
import com.pms.billing.repository.AvenantRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// Business service for "avenants": signed contract changes that add or remove budget after a
// project has started. This is the single writer of projects.revised_budget (C-1) — before that
// fix the project edit form also wrote that column, and saving the project name could silently
// erase the avenant history from the budget.
@Service
@RequiredArgsConstructor
public class AvenantService {

    private final AvenantRepository  avenantRepository;
    private final ProjectRepository  projectRepository;
    private final AvenantMapper      avenantMapper;
    private final JalonService       jalonService;

    /** Every avenant of one project, oldest signature date first. */
    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<AvenantResponse> findByProject(Long projectId) {
        loadProject(projectId); // fails fast with 404 if the project id is unknown/deleted
        return avenantMapper.toResponseList(avenantRepository.findActiveByProjectId(projectId));
    }

    /**
     * Records a new avenant and moves the project budget by the same amount.
     * The revised budget is an accumulator (each avenant adds its montant on top of the current
     * effective budget), never a value typed directly by a user — see C-1.
     */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public AvenantResponse create(Long projectId, AvenantRequest request) {
        Project project = loadProject(projectId);

        // getEffectiveBudget(): revisedBudget when it exists, otherwise initialBudget — starting
        // from it is what makes avenants stack. montant may be negative (an avenant can reduce budget).
        BigDecimal currentBudget = project.getEffectiveBudget();
        BigDecimal newRevisedBudget = (currentBudget != null ? currentBudget : BigDecimal.ZERO)
                .add(request.montant());
        project.setRevisedBudget(newRevisedBudget);
        projectRepository.save(project);
        // H-4: milestone amounts are stored, not derived, so they must be rebuilt now that the
        // budget moved. Only PREVU milestones are touched; invoiced/paid ones stay frozen.
        jalonService.recomputePrevuMontants(project); // H-4

        Avenant avenant = Avenant.builder()
                .project(project)
                .numero(request.numero())
                .objet(request.objet())
                .montant(request.montant())
                .workloadDays(request.workloadDays())
                .dateAvenant(request.dateAvenant())
                .build();

        return avenantMapper.toResponse(avenantRepository.save(avenant));
    }

    /** Cancels an avenant: reverses its amount out of the budget and soft-deletes the row. */
    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Avenant avenant = avenantRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Avenant introuvable : " + id));
        // Cross-check against id mixing: ADR-021 only proves the caller may act on {projectId}, not
        // that this avenant id belongs to it. 404 (not 403) so we don't leak that it exists elsewhere.
        if (!avenant.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Avenant introuvable : " + id);
        }

        Project project = avenant.getProject();
        BigDecimal current = project.getEffectiveBudget() != null ? project.getEffectiveBudget() : BigDecimal.ZERO;
        project.setRevisedBudget(current.subtract(avenant.getMontant()));
        projectRepository.save(project);
        jalonService.recomputePrevuMontants(project); // H-4

        avenant.setDeleted(true);
        avenantRepository.save(avenant);
    }

    /** Loads a project that is not soft-deleted, or throws a 404. */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
