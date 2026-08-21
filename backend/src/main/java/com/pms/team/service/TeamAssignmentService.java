package com.pms.team.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.dto.TeamAssignmentRequest;
import com.pms.team.dto.TeamAssignmentResponse;
import com.pms.team.entity.TeamAssignment;
import com.pms.team.mapper.TeamAssignmentMapper;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamAssignmentService {

    private final TeamAssignmentRepository teamAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamAssignmentMapper teamAssignmentMapper;

    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByUser(Long userId) {
        loadUser(userId);
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByUserId(userId));
    }

    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse assign(Long projectId, TeamAssignmentRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());

        if (teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(projectId, request.userId())) {
            throw new IllegalArgumentException("L'utilisateur est déjà membre de ce projet");
        }

        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        TeamAssignment ta = TeamAssignment.builder()
                .project(project)
                .user(user)
                .roleInTeam(request.roleInTeam())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse update(Long projectId, Long assignmentId, TeamAssignmentRequest request) {
        TeamAssignment ta = loadAssignment(assignmentId);

        if (!ta.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        ta.setRoleInTeam(request.roleInTeam());
        ta.setStartDate(request.startDate());
        ta.setEndDate(request.endDate());

        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public void remove(Long projectId, Long assignmentId) {
        TeamAssignment ta = loadAssignment(assignmentId);

        if (!ta.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        ta.setDeleted(true);
        teamAssignmentRepository.save(ta);
    }

    private TeamAssignment loadAssignment(Long id) {
        return teamAssignmentRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Affectation introuvable : " + id));
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
