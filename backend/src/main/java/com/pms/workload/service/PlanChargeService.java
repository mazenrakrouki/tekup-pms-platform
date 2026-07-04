package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.PlanChargeRequest;
import com.pms.workload.dto.PlanChargeResponse;
import com.pms.workload.entity.PlanCharge;
import com.pms.workload.mapper.PlanChargeMapper;
import com.pms.workload.repository.PlanChargeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public List<PlanChargeResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return planChargeMapper.toResponseList(planChargeRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public PlanChargeResponse create(Long projectId, PlanChargeRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());
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
            throw new IllegalArgumentException("La période d'une charge planifiée ne peut pas être modifiée");
        }
        if (!pc.getUser().getId().equals(request.userId())) {
            throw new IllegalArgumentException("L'utilisateur d'une charge planifiée ne peut pas être modifié");
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
