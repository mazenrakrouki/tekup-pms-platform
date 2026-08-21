package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.PlanChargeRequest;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import com.pms.workload.mapper.PlanChargeMapper;
import com.pms.workload.repository.PlanChargeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanChargeService {

    private final PlanChargeRepository planChargeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PlanChargeMapper planChargeMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public List<PlanChargeResponse> findByProject(Long projectId) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            return planChargeMapper.toResponseList(planChargeRepository.findActiveByProjectId(projectId));
        }
        return planChargeMapper.toResponseList(
                planChargeRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public Page<PlanChargeResponse> findByProject(Long projectId, Pageable pageable) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            return planChargeRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(planChargeMapper::toResponse);
        }
        return planChargeRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(planChargeMapper::toResponse);
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public PlanChargeResponse create(Long projectId, PlanChargeRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());

        // H-8 : vérifier que l'utilisateur est membre actif de l'équipe du projet
        assertTeamMembership(project, request.userId());

        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        if (planChargeRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            throw new IllegalArgumentException(
                    "Une charge planifiée existe déjà pour cet utilisateur sur cette période");
        }

        PlanCharge pc = PlanCharge.builder()
                .project(project)
                .user(user)
                .period(period)
                .plannedDays(request.plannedDays())
                .build();

        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public PlanChargeResponse update(Long projectId, Long id, PlanChargeRequest request) {
        PlanCharge pc = loadPlanCharge(id);

        if (!pc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge planifiée introuvable : " + id);
        }

        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!pc.getPeriod().equals(expectedPeriod)) {
            throw new BusinessRuleException("La période d'une charge planifiée ne peut pas être modifiée");
        }
        if (!pc.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge planifiée ne peut pas être modifié");
        }

        pc.setPlannedDays(request.plannedDays());
        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public void delete(Long projectId, Long id) {
        PlanCharge pc = loadPlanCharge(id);

        if (!pc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge planifiée introuvable : " + id);
        }

        pc.setDeleted(true);
        planChargeRepository.save(pc);
    }

    /**
     * BR-062…064 : la vue élargie (plan de toute l'équipe) est réservée aux porteurs de
     * VALIDATE_WORKLOAD (chef de projet, qui planifie) ou VIEW_ALL_PROJECTS (directeur).
     * Sinon → son propre plan uniquement. Check par capacité, jamais par rôle (ADR-001).
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /** Id de l'utilisateur courant ; -1 si introuvable (aucune ligne ne remonte alors). */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    /** H-8 : l'utilisateur ciblé doit être membre actif de l'équipe (ou le chef de projet). */
    private void assertTeamMembership(Project project, Long targetUserId) {
        if (project.getChefProjet() != null && project.getChefProjet().getId().equals(targetUserId)) {
            return; // le chef de projet est implicitement membre
        }
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(project.getId(), targetUserId)) {
            throw new BusinessRuleException(
                    "L'utilisateur n'est pas membre actif de l'équipe de ce projet");
        }
    }

    private PlanCharge loadPlanCharge(Long id) {
        return planChargeRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge planifiée introuvable : " + id));
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}
