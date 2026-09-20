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

// Business layer for team assignments (who works on which project, with which role and dates).
// Keeps the permission checks, duplicate-membership rule, date rule and soft delete in one
// place instead of copied across every future caller of the repositories.

/**
 * Business rules for adding, editing, listing and removing team members of a project.
 *
 * <p>Must be a Spring bean, not static helpers: {@code @PreAuthorize} and {@code @Transactional}
 * are applied by the proxy Spring wraps around this bean, and static methods can't be proxied.
 *
 * <p>Security model (ADR-021): permission (@PreAuthorize below) and project scope
 * (ProjectScopeInterceptor, before the controller) are two independent checks — a project
 * manager's ASSIGN_DEVELOPER only works within the projects he leads.
 */
@Service
@RequiredArgsConstructor
public class TeamAssignmentService {

    private final TeamAssignmentRepository teamAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamAssignmentMapper teamAssignmentMapper;

    /**
     * Lists the current members of one project. loadProject() is an existence check whose
     * result is unused — without it an unknown project id would return "200 OK []" instead
     * of 404.
     */
    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByProjectId(projectId));
    }

    /**
     * Every current assignment of one person, across all projects (GET /api/users/{userId}/assignments).
     * That URL doesn't match /api/projects/**, so ProjectScopeInterceptor (ADR-021) does not run
     * here — VIEW_TEAM is the only check on this path.
     */
    @PreAuthorize("hasAuthority('VIEW_TEAM')")
    @Transactional(readOnly = true)
    public List<TeamAssignmentResponse> findByUser(Long userId) {
        loadUser(userId);
        return teamAssignmentMapper.toResponseList(teamAssignmentRepository.findActiveByUserId(userId));
    }

    /**
     * Puts one person on one project. Loads project and user first so an unknown id fails with
     * a readable 404 instead of a raw FK error; then refuses a duplicate active membership and
     * an inverted date range before writing.
     */
    // ASSIGN_DEVELOPER, not VIEW_TEAM: seeing a team and staffing it are different capabilities.
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse assign(Long projectId, TeamAssignmentRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());

        // Repeats in Java what uk_ta_project_user_active (V6, WHERE deleted = FALSE) guarantees
        // at the DB level, for a readable message instead of a raw constraint error.
        if (teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(projectId, request.userId())) {
            // IllegalArgumentException -> 409 Conflict (this project's convention for a duplicate).
            throw new IllegalArgumentException("L'utilisateur est déjà membre de ce projet");
        }

        // Null check must come first: endDate is optional, and isBefore(null) would throw NPE.
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        // Builder, not a positional constructor: startDate/endDate share a type, so a swapped
        // pair would otherwise compile silently.
        TeamAssignment ta = TeamAssignment.builder()
                .project(project)
                .user(user)
                .roleInTeam(request.roleInTeam())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    /**
     * Updates an assignment's role and dates. The person is never changed — request.userId() is
     * ignored; to move the work to someone else, remove and re-assign.
     */
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public TeamAssignmentResponse update(Long projectId, Long assignmentId, TeamAssignmentRequest request) {
        TeamAssignment ta = loadAssignment(assignmentId);

        // Ties assignmentId to projectId (ADR-021's second half): ProjectScopeInterceptor only
        // validated projectId, so without this an assignment from another project could be edited
        // through a URL whose projectId happens to be in the caller's perimeter. 404, not 403, to
        // avoid confirming the other assignment exists.
        if (!ta.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        // Repeated from assign(): an edit can break a period that was valid when first created.
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin ne peut pas être antérieure à la date de début");
        }

        // endDate is set even when null, which is how an assignment becomes open-ended again.
        ta.setRoleInTeam(request.roleInTeam());
        ta.setStartDate(request.startDate());
        ta.setEndDate(request.endDate());

        return teamAssignmentMapper.toResponse(teamAssignmentRepository.save(ta));
    }

    /**
     * Soft-removes a member (sets deleted = true, never a real DELETE) so past work stays
     * traceable and the person can be re-assigned to the project later.
     */
    // ASSIGN_DEVELOPER covers removal too: whoever builds a team also unbuilds it.
    @PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
    @Transactional
    public void remove(Long projectId, Long assignmentId) {
        TeamAssignment ta = loadAssignment(assignmentId);

        // Same ADR-021 tie as update(): assignmentId isn't validated by ProjectScopeInterceptor.
        if (!ta.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Affectation introuvable : " + assignmentId);
        }

        ta.setDeleted(true);
        teamAssignmentRepository.save(ta);
    }

    // Private loaders shared by the public methods above; each returns a real entity, never null.

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
                // A deactivated user gets the same 404 as one that never existed, deliberately.
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}
