package com.pms.workload.service;

import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import com.pms.workload.dto.ChargeReelleRequest;
import com.pms.workload.dto.ChargeReelleResponse;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.mapper.ChargeReelleMapper;
import com.pms.workload.repository.ChargeReelleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * Business service for charges_reelles (actual workload / timesheet): submit -> validate. Twin of
 * PlanChargeService, kept separate since submit needs SUBMIT_WORKLOAD (self) while validate needs
 * VALIDATE_WORKLOAD (manager); only a validated row counts as cost for KpiService.
 */

// Registers as a Spring bean so the proxy Spring wraps it in can apply @PreAuthorize/@Transactional.
@Service
// Constructor injection for the collaborators below, keeping them final and mockable in tests.
@RequiredArgsConstructor
public class ChargeReelleService {

    // chargeReelleRepository: soft-delete-aware reads/writes. projectRepository/userRepository:
    // existence checks for the target project/user and the caller. chargeReelleMapper: MapStruct
    // entity->DTO translator. teamAssignmentRepository: the H-8 "is this person on the team?" check.
    private final ChargeReelleRepository chargeReelleRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ChargeReelleMapper chargeReelleMapper;
    private final TeamAssignmentRepository teamAssignmentRepository;

    /**
     * Lists timesheet rows of one project, unpaged: full team if the caller has the broad view,
     * otherwise only their own rows (BR-062..064). Kept for callers needing the whole set at once.
     */
    // VIEW_WORKLOAD by permission code, never a role name (ADR-001), so an admin can reassign it at runtime.
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    // Read-only transaction keeps entities attached so the mapper can walk lazy links (open-in-view is false).
    @Transactional(readOnly = true)
    public List<ChargeReelleResponse> findByProject(Long projectId) {
        // 404 for an unknown project, rather than a silently empty list.
        loadProject(projectId);
        // BR-062...064: whole team, or own rows only.
        if (canSeeAllWorkload()) {
            return chargeReelleMapper.toResponseList(chargeReelleRepository.findActiveByProjectId(projectId));
        }
        // currentUserId() returns -1 when unresolved, matching no row: fails closed, never open.
        return chargeReelleMapper.toResponseList(
                chargeReelleRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * Paged version of the method above — this is the one the workload screen actually calls.
     * Each repository method carries its own countQuery with the matching WHERE, so an own-only
     * caller never gets a total that counts the whole team's rows.
     */
    @PreAuthorize("hasAuthority('VIEW_WORKLOAD')")
    @Transactional(readOnly = true)
    public Page<ChargeReelleResponse> findByProject(Long projectId, Pageable pageable) {
        loadProject(projectId);
        if (canSeeAllWorkload()) {
            // Page.map keeps paging metadata (page number, size, total) while converting only this slice.
            return chargeReelleRepository.findActiveByProjectIdPaged(projectId, pageable)
                    .map(chargeReelleMapper::toResponse);
        }
        return chargeReelleRepository.findActiveByProjectIdAndUserIdPaged(projectId, currentUserId(), pageable)
                .map(chargeReelleMapper::toResponse);
    }

    /**
     * Records the days one person really worked on one project in one month. Guard order matters:
     * ownership (403) and team membership (422) are checked before the user row is even loaded, so
     * an unauthorized caller can't learn whether a given user id exists.
     */
    // SUBMIT_WORKLOAD is the developer's own capability; assertOwnership() below still decides
    // whose days may be declared (a validator may submit for a teammate).
    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    // One transaction: the duplicate check and the insert must not be split.
    @Transactional
    public ChargeReelleResponse submit(Long projectId, ChargeReelleRequest request) {
        // Project is used below: it carries the chef de projet for assertTeamMembership and becomes the FK.
        Project project = loadProject(projectId);

        // BR-033: only a VALIDATE_WORKLOAD holder may submit for someone else.
        assertOwnership(request.userId());

        // H-8: target must be an active team member (or the chef de projet). Checked once here,
        // since update() freezes the row's user afterward.
        assertTeamMembership(project, request.userId());

        User user = loadUser(request.userId());
        // Normalized to the 1st of the month (V7 convention) so two declarations for the same
        // month are always comparable as the same LocalDate.
        LocalDate period = LocalDate.of(request.year(), request.month(), 1);

        // Friendly pre-check for a duplicate month; the real guarantee is the partial unique index
        // uk_cr_active (WHERE deleted = FALSE), which also lets a deleted month be re-declared.
        if (chargeReelleRepository.existsByProjectIdAndUserIdAndPeriodAndDeletedFalse(projectId, request.userId(), period)) {
            // IllegalArgumentException (not BusinessRuleException, marker M-3) so GlobalExceptionHandler
            // answers 409 Conflict, distinct from a refused rule's 422.
            throw new IllegalArgumentException(
                    "Une charge réelle existe déjà pour cet utilisateur sur cette période");
        }

        // validatedAt/validatedBy stay unset here — isValidated() reads that as "not yet approved".
        ChargeReelle cr = ChargeReelle.builder()
                .project(project)
                .user(user)
                .period(period)
                .actualDays(request.actualDays())
                .submittedAt(LocalDateTime.now())
                .build();

        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Corrects the days of a row not yet approved. period and user are frozen (compared against
     * the request, refused on mismatch) rather than updated, so an edit can't sidestep BR-033 or
     * the H-8 membership check by silently moving the row to another month or person.
     */
    @PreAuthorize("hasAuthority('SUBMIT_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse update(Long projectId, Long id, ChargeReelleRequest request) {
        ChargeReelle cr = loadChargeReelle(id);

        // 404, not 403: confirms nothing about a row from another project to a caller out of scope.
        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // Once approved, the row is part of the consumed budget KpiService reads — read-only from here on.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de modifier une charge déjà validée");
        }

        // BR-033, same rule and same method as submit(). Checked against the row's existing owner,
        // never the request body, which is attacker-controlled.
        assertOwnership(cr.getUser().getId());

        LocalDate expectedPeriod = LocalDate.of(request.year(), request.month(), 1);
        if (!cr.getPeriod().equals(expectedPeriod)) {
            throw new BusinessRuleException("La période d'une charge réelle ne peut pas être modifiée");
        }
        // Freezing the user is also what makes it safe to skip re-checking H-8 here.
        if (!cr.getUser().getId().equals(request.userId())) {
            throw new BusinessRuleException("L'utilisateur d'une charge réelle ne peut pas être modifié");
        }

        cr.setActualDays(request.actualDays());
        cr.setSubmittedAt(LocalDateTime.now());
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Approves a submitted row: from this moment its days count as a real project cost.
     *
     * @param validatorEmail the authenticated caller's email (never client-supplied), so a
     *                       validation can't be signed under someone else's name
     */
    // VALIDATE_WORKLOAD is the manager's capability; the Director deliberately doesn't hold it
    // (reads via VIEW_ALL_PROJECTS but doesn't approve).
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public ChargeReelleResponse validate(Long projectId, Long id, String validatorEmail) {
        ChargeReelle cr = loadChargeReelle(id);

        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // Refused, not silently ignored: a second approval would overwrite who actually signed off.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Cette charge est déjà validée");
        }

        // validated_by is a real FK to users(id), so it survives an email/name change; also a
        // safety net for a token that outlived its account.
        User validator = userRepository.findActiveByEmailWithRole(validatorEmail)
                .orElseThrow(() -> new NotFoundException("Validateur introuvable"));

        // This is what turns the row into a cost: KpiService selects on validatedAt IS NOT NULL.
        cr.setValidatedAt(LocalDateTime.now());
        cr.setValidatedBy(validator);
        return chargeReelleMapper.toResponse(chargeReelleRepository.save(cr));
    }

    /**
     * Soft-deletes a mistakenly submitted row. Needs VALIDATE_WORKLOAD, not SUBMIT_WORKLOAD:
     * a developer corrects their own mistake via update(); only a manager can remove a row outright.
     */
    @PreAuthorize("hasAuthority('VALIDATE_WORKLOAD')")
    @Transactional
    public void delete(Long projectId, Long id) {
        ChargeReelle cr = loadChargeReelle(id);

        if (!cr.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Charge réelle introuvable : " + id);
        }

        // An approved row is already counted in the consumed budget; it can't be made to disappear.
        if (cr.isValidated()) {
            throw new BusinessRuleException("Impossible de supprimer une charge déjà validée");
        }

        cr.setDeleted(true);
        chargeReelleRepository.save(cr);
    }

    /** Loads one live row by id, project/user/validator preloaded; 404 (not NPE) when missing. */
    private ChargeReelle loadChargeReelle(Long id) {
        return chargeReelleRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Charge réelle introuvable : " + id));
    }

    /** Loads one live project by id; centralizes the 404 so it can't be forgotten in a new method. */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /** Loads one user by id, refusing soft-deleted accounts so a departed employee can't be booked. */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
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

    /**
     * BR-062..064: the whole-team view is reserved to VALIDATE_WORKLOAD (approves it) or
     * VIEW_ALL_PROJECTS (director's portfolio scope — without it the Director, who holds
     * VIEW_WORKLOAD but not VALIDATE_WORKLOAD, would see an empty workload screen everywhere).
     */
    private boolean canSeeAllWorkload() {
        return hasAuthority("VALIDATE_WORKLOAD") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /** Current user's id, or -1 when unresolved — an id no row can have, so the caller sees nothing rather than everything. */
    private Long currentUserId() {
        User current = currentUser();
        return current != null ? current.getId() : -1L;
    }

    /**
     * BR-033: a caller without VALIDATE_WORKLOAD may only act on their own charges. The escape
     * hatch is that capability, not a role name — someone trusted to approve days is trusted to
     * enter them for a teammate (e.g. covering for someone on leave).
     */
    private void assertOwnership(Long targetUserId) {
        if (!hasAuthority("VALIDATE_WORKLOAD")) {
            User current = currentUser();
            if (current == null || !current.getId().equals(targetUserId)) {
                throw new AccessDeniedException("Un développeur ne peut agir que sur ses propres charges");
            }
        }
    }

    /** Whether the logged-in caller carries this permission code (never a role name, ADR-001). */
    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /**
     * Loads the caller's full User row, or null if unresolved. Returns null rather than throwing
     * since callers react differently: currentUserId() turns it into "-1, see nothing",
     * assertOwnership() into "403, write nothing".
     */
    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return userRepository.findActiveByEmailWithRole(auth.getName()).orElse(null);
    }
}
