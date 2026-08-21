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

@Service
@RequiredArgsConstructor
public class MissionService {

    private final MissionRepository missionRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository    userRepository;
    private final MissionMapper     missionMapper;

    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    @Transactional(readOnly = true)
    public List<MissionResponse> findByProject(Long projectId) {
        loadProject(projectId);
        if (canSeeAllMissions()) {
            return missionMapper.toResponseList(missionRepository.findActiveByProjectId(projectId));
        }
        return missionMapper.toResponseList(
                missionRepository.findActiveByProjectIdAndUserId(projectId, currentUserId()));
    }

    /**
     * UC-21 : la vue élargie (missions de toute l'équipe) est réservée aux porteurs de
     * MANAGE_MISSION (chef de projet) ou VIEW_ALL_PROJECTS (directeur).
     * Sinon → ses propres missions uniquement. Check par capacité, jamais par rôle (ADR-001).
     */
    private boolean canSeeAllMissions() {
        return hasAuthority("MANAGE_MISSION") || hasAuthority("VIEW_ALL_PROJECTS");
    }

    private boolean hasAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    /** Id de l'utilisateur courant ; -1 si introuvable (aucune mission ne remonte alors). */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return -1L;
        return userRepository.findActiveByEmailWithRole(auth.getName())
                .map(User::getId)
                .orElse(-1L);
    }

    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public MissionResponse create(Long projectId, MissionRequest request) {
        Project project = loadProject(projectId);
        User user = loadUser(request.userId());
        validateDates(request);

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

    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public MissionResponse update(Long projectId, Long id, MissionRequest request) {
        Mission mission = loadMission(id);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        validateDates(request);

        User user = loadUser(request.userId());
        mission.setUser(user);
        mission.setObjet(request.objet());
        mission.setLieu(request.lieu());
        mission.setDateDebut(request.dateDebut());
        mission.setDateFin(request.dateFin());

        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Mission mission = loadMission(id);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + id);
        }
        mission.setDeleted(true);
        missionRepository.save(mission);
    }

    // ── Utilitaires ───────────────────────────────────────────────

    private void validateDates(MissionRequest request) {
        if (request.dateFin().isBefore(request.dateDebut())) {
            throw new BusinessRuleException("La date de fin doit être égale ou postérieure à la date de début");
        }
    }

    Mission loadMission(Long id) {
        return missionRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Mission introuvable : " + id));
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
