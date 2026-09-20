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

/*
 * Business service for plan_charges (the manager's forecast). Twin of ChargeReelleService, kept
 * separate since every write here needs VALIDATE_WORKLOAD (a manager plans for others, so there's
 * no BR-033 ownership guard) and there's no approval step, so no "frozen once validated" rule.
 */

// Registers as a Spring bean so the proxy Spring wraps it in can apply @PreAuthorize/@Transactional.
@Service
// Constructor injection for the collaborators below, keeping them final and mockable in tests.
@RequiredArgsConstructor
public class PlanChargeService {

    // planChargeRepository: soft-delete-aware reads/writes. projectRepository/userRepository:
    // existence checks for the target project/user and the caller. planChargeMapper: MapStruct
    // entity->DTO translator. teamAssignmentRepository: the H-8 "is this person on the team?" check.
    private final PlanChargeRepository planChargeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PlanChargeMapper planChargeMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    /**
     * Lists planned rows of one project, unpaged: full team if the caller has the broad view,
     * otherwise only their own lines (BR-062..064). Kept for callers needing the whole set at once.
     */
    // VIEW_WORKLOAD, the same code that guards the timesheet read — for the user it's one screen.
    // Checked by permission code, never a role name (ADR-001), so an admin can reassign it at runtime.
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    // Read-only transaction keeps entities attached so the mapper can walk lazy links (open-in-view is false).
    @Transactional(readOnly = true)
    public List<PlanChargeResponse> findByProject(Long projectId) {
        // 404 for an unknown project, rather than a silently empty list.
        loadProject(projectId);
        // BR-062...064: whole team's plan, or own lines only.
        if (canSeeAllWorkload()) {
            return planChargeMapper.toResponseList(planChargeRepository.findActiveByProjectId(projectId));
        }
        // currentUserId() returns -1 when unresolved, matching no row: fails closed, never open.
        return planChargeMapper.toResponseList(
                planChargeRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * Paged version of the method above — this is the one the workload screen actually calls.
     * Each repository method carries its own countQuery with the matching WHERE, so an own-only
     * caller never gets a total that counts the whole team's rows.
     */
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public Page<PlanChargeResponse> findByProject(Long projectId, Pageable pageable) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            // Page.map keeps paging metadata (page number, size, total) while converting only this slice.
            return planChargeRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(planChargeMapper::toResponse);
        }
        return planChargeRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(planChargeMapper::toResponse);
    }

    /**
     * Plans the days one person is expected to spend on one project in one month. No ownership
     * guard here (unlike ChargeReelleService.submit()): planning for other people is the point of
     * this method, gated by VALIDATE_WORKLOAD and ADR-021 scope; H-8 below is the only "who" check.
     */
    // VALIDATE_WORKLOAD guards all three writes of this file — planning and validating are the
    // same manager's job, so no separate MANAGE_PLAN permission was introduced.
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    // One transaction: the duplicate check and the insert must not be split.
    @Transactional
    public PlanChargeResponse create(Long projectId, PlanChargeRequest request) {
        // Project is used below: it carries the chef de projet for assertTeamMembership and becomes the FK.
        Project project = loadProject(projectId);
        // Loaded before the membership check (unlike the twin file), so an unknown id answers 404
        // while a known non-member answers 422 — both correct, only the order differs.
        User user = loadUser(request.userId());

        // H-8: target must be an active team member (or the chef de projet). Checked once here,
        // since update() freezes the row's user afterward.
        assertTeamMembership(project, request.userId());

        // Normalized to the 1st of the month (V7 convention) so two forecasts for the same month
        // are always comparable as the same LocalDate.
        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        // Friendly pre-check for a duplicate month; the real guarantee is the partial unique index
        // uk_pc_active (WHERE deleted = FALSE), which also lets a deleted month be re-planned.
        if (planChargeRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            // IllegalArgumentException (not BusinessRuleException, marker M-3) so GlobalExceptionHandler
            // answers 409 Conflict, distinct from a refused rule's 422.
            throw new IllegalArgumentException(
                    "Une charge planifiée existe déjà pour cet utilisateur sur cette période");
        }

        // Unlike a timesheet row, a plan has no submittedAt/validatedAt/validatedBy — it's a
        // forecast, complete as soon as it's written.
        PlanCharge pc = PlanCharge.builder()
                .project(project)
                .user(user)
                .period(period)
                .plannedDays(request.plannedDays())
                .build();

        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    /**
     * Changes the planned days of an existing row. period and user are frozen (compared against
     * the request, refused on mismatch) rather than updated, so an edit can't sidestep the H-8
     * membership check by silently moving the row to another month or person. No "frozen once
     * validated" rule here, unlike the timesheet file: a forecast has no approval step.
     */
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public PlanChargeResponse update(Long projectId, Long id, PlanChargeRequest request) {
        PlanCharge pc = loadPlanCharge(id);

        // 404, not 403: confirms nothing about a row from another project to a caller out of scope.
        if (!pc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge planifiée introuvable : " + id);
        }

        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!pc.getPeriod().equals(expectedPeriod)) {
            throw new BusinessRuleException("La période d'une charge planifiée ne peut pas être modifiée");
        }
        // Freezing the user is also what makes it safe to skip re-checking H-8 here.
        if (!pc.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge planifiée ne peut pas être modifié");
        }

        pc.setPlannedDays(request.plannedDays());
        return planChargeMapper.toResponse(planChargeRepository.save(pc));
    }

    /**
     * Soft-deletes a planned row. No isValidated() guard, unlike ChargeReelleService.delete():
     * nothing in this table is ever validated, so there's nothing to freeze.
     */
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
     * BR-062..064: the whole-team view is reserved to VALIDATE_WORKLOAD (does the planning) or
     * VIEW_ALL_PROJECTS (director's portfolio scope — without it the Director, who holds
     * VIEW_WORKLOAD but not VALIDATE_WORKLOAD, would see an empty workload screen everywhere).
     * Byte-for-byte twin of ChargeReelleService's version, kept separate so a future change to
     * one table's scope rule can't silently leak into the other's.
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /** Whether the logged-in caller carries this permission code (never a role name, ADR-001). */
    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /** Current user's id, or -1 when unresolved — an id no row can have, so the caller sees nothing rather than everything. */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    /**
     * H-8: the target user must be an active team member, or the project's chef de projet (who
     * often has no team_assignments row of their own). Membership check is delegated to
     * TeamAssignmentRepository so "who is on this team" has one definition app-wide.
     */
    private void assertTeamMembership(Project project, Long targetUserId) {
        if (project.getChefProjet() != null && project.getChefProjet().getId().equals(targetUserId)) {
            return; // the chef de projet is implicitly a member of their own project
        }
        // DeletedFalse matters: leaving a team is a soft delete, so a removed member counts as absent.
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(project.getId(), targetUserId)) {
            throw new BusinessRuleException(
                    "L'utilisateur n'est pas membre actif de l'équipe de ce projet");
        }
    }

    /** Loads one live row by id, project/user preloaded; 404 (not NPE) when missing. */
    private PlanCharge loadPlanCharge(Long id) {
        return planChargeRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge planifiée introuvable : " + id));
    }

    /** Loads one live project by id; centralizes the 404 so it can't be forgotten in a new method. */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /** Loads one user by id, refusing soft-deleted accounts so a departed employee can't be planned for. */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}
