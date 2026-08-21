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

@Service
@RequiredArgsConstructor
public class AvenantService {

    private final AvenantRepository  avenantRepository;
    private final ProjectRepository  projectRepository;
    private final AvenantMapper      avenantMapper;
    private final JalonService       jalonService;

    @PreAuthorize("hasAuthority('VIEW_BILLING')")
    @Transactional(readOnly = true)
    public List<AvenantResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return avenantMapper.toResponseList(avenantRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public AvenantResponse create(Long projectId, AvenantRequest request) {
        Project project = loadProject(projectId);

        // Mise à jour du budget révisé
        BigDecimal currentBudget = project.getEffectiveBudget();
        BigDecimal newRevisedBudget = (currentBudget != null ? currentBudget : BigDecimal.ZERO)
                .add(request.montant());
        project.setRevisedBudget(newRevisedBudget);
        projectRepository.save(project);
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

    @PreAuthorize("hasAuthority('MANAGE_BILLING')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Avenant avenant = avenantRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Avenant introuvable : " + id));
        if (!avenant.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Avenant introuvable : " + id);
        }

        // Inversion de l'impact sur le budget révisé
        // getEffectiveBudget() est utilisé comme fallback sûr (jamais négatif si on ne revient pas en dessous du budget initial)
        Project project = avenant.getProject();
        BigDecimal current = project.getEffectiveBudget() != null ? project.getEffectiveBudget() : BigDecimal.ZERO;
        project.setRevisedBudget(current.subtract(avenant.getMontant()));
        projectRepository.save(project);
        jalonService.recomputePrevuMontants(project); // H-4

        avenant.setDeleted(true);
        avenantRepository.save(avenant);
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
