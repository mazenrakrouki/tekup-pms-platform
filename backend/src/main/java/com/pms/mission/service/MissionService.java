package com.pms.mission.service;

import com.pms.mission.dto.MissionRequest;
import com.pms.mission.dto.MissionResponse;
import com.pms.mission.entity.Mission;
import com.pms.mission.mapper.MissionMapper;
import com.pms.mission.repository.MissionRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business rules for a mission: one trip made by one user for one project (purpose, place,
 * start/end date). The trip's spending is handled by the sister class {@link ComposanteService},
 * which calls {@link #loadMission(Long)} here so both services load a mission the same way.
 * By the time a method here runs, ProjectScopeInterceptor (ADR-021) has already confirmed the
 * caller may reach the URL's project, so this class only decides "may this user do this action,
 * and on which rows" — never "may this user reach this project at all".
 */
@Service
@RequiredArgsConstructor
public class MissionService {

    // Constructor injection (Lombok): fields stay final, and a unit test can build this with
    // fake repositories in one line.
    private final MissionRepository missionRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;
    private final MissionMapper     missionMapper;

    /**
     * Returns a project's non-deleted missions, oldest start date first. UC-21 decides between
     * the wide query (everyone's missions) and the narrow one (own missions only) based on a
     * capability, not a role (ADR-001); the filter runs in SQL, not on an in-memory list, so it
     * stays correct once this endpoint is paginated.
     */
    // VIEW_MISSION required; authorities are permission codes, never role names (ADR-001).
    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    @Transactional(readOnly = true)
    public List<MissionResponse> findByProject(Long projectId) {
        // Result discarded on purpose: only used to 404 if the project doesn't exist/is deleted,
        // rather than silently returning an empty mission list.
        loadProject(projectId);
        if (canSeeAllMissions()) {
            return missionMapper.toResponseList(missionRepository.findActiveByProjectId(projectId));
        }
        // Narrow view (UC-21): only the rows whose user_id is the current user.
        return missionMapper.toResponseList(
                missionRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * Whether the current user sees the whole team's missions or only their own (UC-21).
     * Tests two capabilities, not one: the director holds VIEW_ALL_PROJECTS but not
     * MANAGE_MISSION (doesn't create trips), so testing MANAGE_MISSION alone would wrongly
     * narrow them to their own (usually empty) mission list.
     */
    private boolean canSeeAllMissions() {
        return hasAuthority("MANAGE_MISSION") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    /**
     * True when the logged-in user carries the given permission code. Hand-written rather than
     * a second @PreAuthorize because the answer must steer which query runs, not block the call.
     */
    private boolean hasAuthority(String code) {
        // Authentication is null outside a request (scheduled job, unauthenticated test), hence
        // the guard below instead of risking a NullPointerException.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /**
     * Id of the current user; -1 when it can't be found, so the row-filter fails closed
     * (matches no row) rather than a null being later misread as "no filter at all".
     */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        // Principal is the e-mail (see JwtAuthenticationFilter); re-read from the DB rather than
        // trusting an id in the token, so an account deleted after the token was issued is caught.
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    /**
     * Creates one mission. Project and user are loaded from the DB (not built from bare ids) so
     * both are confirmed to exist and the mapper can read project.code / the user's full name
     * from the saved result.
     */
    // MANAGE_MISSION required: a developer (VIEW_MISSION only) cannot book a trip for themself.
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public MissionResponse create(Long projectId, MissionRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());
        validateDates(request);

        // Named builder args: two same-typed dates can't be swapped by position as they could
        // with a plain constructor. Id/audit columns are filled by the DB and BaseEntity's
        // JPA auditing listener.
        Mission mission = Mission.builder()
                .project(project)
                .user(user)
                .objet(request.objet())
                .lieu(request.lieu())
                .dateDebut(request.dateDebut())
                .dateFin(request.dateFin())
                .build();

        return missionMapper.toResponse(missionRepository.save(mission));
    }

    /**
     * Updates one mission. The loaded mission's project is compared against the URL's
     * projectId, since the ADR-021 interceptor only checks the URL's projectId, not which
     * project mission {id} actually belongs to. Mismatch answers 404, not 403, to avoid
     * confirming the mission exists elsewhere.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public MissionResponse update(Long projectId, Long id, MissionRequest request) {
        Mission mission = loadMission(id);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        // Dates checked before anything is changed, so a bad request leaves the row untouched.
        validateDates(request);

        // The mission's traveller can change (trip handed to someone else); loadUser refuses a
        // deleted/deactivated account.
        User user = loadUser(request.userId());
        mission.setUser(user);
        mission.setObjet(request.objet());
        mission.setLieu(request.lieu());
        mission.setDateDebut(request.dateDebut());
        mission.setDateFin(request.dateFin());

        return missionMapper.toResponse(missionRepository.save(mission));
    }

    /**
     * Deletes one mission. Soft delete (a flag), not a real SQL delete: cost lines still point
     * at it via fk_comp_mission, and the accounting history must survive for later audits.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Mission mission = loadMission(id);
        // Same project check as update(): prevents deleting another project's mission.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        mission.setDeleted(true);
        missionRepository.save(mission);
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Refuses a trip that ends before it starts. Checked here (not only via DB constraint
     * chk_mission_dates) so the user gets a readable 422 instead of a raw 500 at commit. Two
     * equal dates are accepted: a one-day trip starts and ends the same day.
     */
    private void validateDates(MissionRequest request) {
        if (request.dateFin().isBefore(request.dateDebut())) {
            throw new BusinessRuleException("La date de fin doit être égale ou postérieure à la date de début");
        }
    }

    /**
     * Loads one non-deleted mission, or throws NotFoundException (404). Package-private:
     * {@link ComposanteService} in the same package reuses this exact load; a self-call from
     * within this class bypasses the Spring security proxy, so @PreAuthorize here would be a
     * no-op anyway — the real guard is on each public entry point.
     */
    Mission loadMission(Long id) {
        return missionRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Mission introuvable : " + id));
    }

    /** Loads one non-deleted project, or throws NotFoundException (404). */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads the user a mission is given to, or throws NotFoundException (404). Filters out
     * deleted users, unlike plain findById, so a trip can't be booked for someone who has left.
     */
    private User loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}
