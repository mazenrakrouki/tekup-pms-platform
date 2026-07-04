package com.pms.project.service;

import com.pms.project.dto.ProjectRequest;
import com.pms.project.dto.ProjectResponse;
import com.pms.project.entity.Project;
import com.pms.project.entity.ProjectStatus;
import com.pms.project.mapper.ProjectMapper;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMapper projectMapper;
    private final ProjectScopeService scopeService;

    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        List<Project> projects = projectRepository.findAllActive();
        if (!scopeService.hasAllAccess()) {
            // Scope ADR-021 : ne renvoyer que les projets gérés/assignés
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return projectMapper.toResponseList(projects);
    }

    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public List<ProjectResponse> findArchived() {
        List<Project> projects = projectRepository.findAllArchived();
        if (!scopeService.hasAllAccess()) {
            Set<Long> accessible = scopeService.accessibleProjectIds(currentEmail());
            projects = projects.stream().filter(p -> accessible.contains(p.getId())).toList();
        }
        return projectMapper.toResponseList(projects);
    }

    @PreAuthorize("hasAuthority('VIEW_PROJECT')")
    @Transactional(readOnly = true)
    public ProjectResponse findById(Long id) {
        Project project = loadProject(id);
        scopeService.assertCanAccess(id, currentEmail()); // scope ADR-021
        return projectMapper.toResponse(project);
    }

    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse archive(Long id) {
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new IllegalArgumentException("Seul un projet terminé peut être archivé");
        }
        project.setArchived(true);
        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse unarchive(Long id) {
        scopeService.assertCanAccess(id, currentEmail());
        Project project = loadProject(id);
        project.setArchived(false);
        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasAuthority('CREATE_PROJECT')")
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        String normalizedCode = request.code().toUpperCase();
        if (projectRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        Project project = Project.builder()
                .code(normalizedCode)
                .name(request.name())
                .description(request.description())
                .status(request.status() != null ? request.status() : ProjectStatus.DRAFT)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .initialBudget(request.initialBudget())
                .director(resolveUser(request.directorId()))
                .build();

        // L'affectation du chef requiert la capacité ASSIGN_CHEF_PROJET (pas seulement CREATE_PROJECT)
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);
        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        Project project = loadProject(id);
        String normalizedCode = request.code().toUpperCase();

        if (!project.getCode().equals(normalizedCode) && projectRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException("Code projet déjà utilisé : " + normalizedCode);
        }

        project.setCode(normalizedCode);
        project.setName(request.name());
        project.setDescription(request.description());
        if (request.status() != null) project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setEndDate(request.endDate());
        project.setInitialBudget(request.initialBudget());
        // Réaffectation du directeur réservée à la capacité portefeuille (CREATE_PROJECT) —
        // un chef de projet (EDIT_PROJECT seul) ne peut pas changer le directeur de son projet
        if (request.directorId() != null && hasAuthority("CREATE_PROJECT")) {
            project.setDirector(resolveUser(request.directorId()));
        }
        // Réaffectation du chef réservée à la capacité ASSIGN_CHEF_PROJET (empêche un PM EDIT_PROJECT de la contourner)
        if (request.chefProjetId() != null && hasAuthority("ASSIGN_CHEF_PROJET")) {
            project.setChefProjet(resolveUser(request.chefProjetId()));
        }
        applyFicheIdentification(project, request);

        return projectMapper.toResponse(projectRepository.save(project));
    }

    /** Renseigne les champs de la Fiche d'identification (modèle Excel) sur le projet. */
    private void applyFicheIdentification(Project project, ProjectRequest request) {
        project.setContractId(request.contractId());
        project.setClient(request.client());
        project.setFunder(request.funder());
        project.setBusinessModel(request.businessModel());
        project.setEngagementType(request.engagementType());
        if (request.currency() != null && !request.currency().isBlank()) {
            project.setCurrency(request.currency().toUpperCase());
        }
        if (request.exchangeRateToTnd() != null) {
            project.setExchangeRateToTnd(request.exchangeRateToTnd());
        }
        project.setLicenseSubcontractBudget(request.licenseSubcontractBudget());
        project.setSoldWorkloadDays(request.soldWorkloadDays());
        project.setWarrantyWorkloadDays(request.warrantyWorkloadDays());
        project.setPenaltyProvision(request.penaltyProvision());
    }

    @PreAuthorize("hasAuthority('ASSIGN_CHEF_PROJET')")
    @Transactional
    public ProjectResponse assignChefProjet(Long projectId, Long userId) {
        Project project = loadProject(projectId);
        User chef = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));

        project.setChefProjet(chef);
        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public ProjectResponse changeStatus(Long id, ProjectStatus newStatus) {
        Project project = loadProject(id);
        project.setStatus(newStatus);
        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasAuthority('DELETE_PROJECT')")
    @Transactional
    public void delete(Long id) {
        Project project = loadProject(id);
        project.setDeleted(true);
        projectRepository.save(project);
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    /** Vrai si l'utilisateur courant détient la capacité donnée (ADR-001 : check par capacité, pas par rôle). */
    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    private User resolveUser(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + userId));
    }
}
