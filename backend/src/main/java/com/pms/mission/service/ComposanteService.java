package com.pms.mission.service;

import com.pms.mission.dto.ComposanteRequest;
import com.pms.mission.dto.ComposanteResponse;
import com.pms.mission.entity.ComposanteMission;
import com.pms.mission.entity.Mission;
import com.pms.mission.mapper.ComposanteMapper;
import com.pms.mission.repository.ComposanteMissionRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business rules for a mission's cost lines (composantes: PERDIEM, BILLET, TIMBRE, TRANSPORT,
 * SEJOUR — each one amount + one currency). The trip itself belongs to {@link MissionService}.
 * Every method re-checks the whole chain project -> mission -> composante, because the
 * ADR-021 scope interceptor only knows the projectId in the URL, not which project a given
 * mission or cost line actually belongs to; skipping that check here would let a caller reach
 * another project's costs by guessing an id. Mismatches answer 404, not 403, so as not to
 * confirm that another project's mission or line exists.
 */
@Service
@RequiredArgsConstructor
public class ComposanteService {

    // Depends on MissionService (not MissionRepository directly) so mission loading stays one
    // piece of code with one "not deleted" rule and one error message.
    private final ComposanteMissionRepository composanteRepository;
    private final MissionService              missionService;
    private final ComposanteMapper            composanteMapper;

    /** Lists a mission's non-deleted cost lines, ordered by cost type. Empty list is normal. */
    // VIEW_MISSION check sits on the service, not the controller, so any future caller is
    // covered and granting the capability to a new role is a data change, not a code change.
    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    @Transactional(readOnly = true)
    public List<ComposanteResponse> findByMission(Long projectId, Long missionId) {
        Mission mission = missionService.loadMission(missionId);
        // Confirms the mission belongs to the URL's project.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }
        return composanteMapper.toResponseList(composanteRepository.findActiveByMissionId(missionId));
    }

    /**
     * Adds one cost line to a mission. Loading the parent mission (not just building it from a
     * bare id) proves it exists, isn't deleted, and belongs to the URL's project.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public ComposanteResponse create(Long projectId, Long missionId, ComposanteRequest request) {
        Mission mission = missionService.loadMission(missionId);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = ComposanteMission.builder()
                .mission(mission)
                .typeComposante(request.typeComposante())
                .montant(request.montant())
                // Upper-cased so "tnd"/"Tnd"/"TND" don't split into separate currency groups.
                .devise(request.devise().toUpperCase())
                .description(request.description())
                .build();

        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

    /**
     * Changes one cost line, checking both the mission and the line against the URL's ids so a
     * guessed id can't be used to edit another project's line.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public ComposanteResponse update(Long projectId, Long missionId, Long id, ComposanteRequest request) {
        Mission mission = missionService.loadMission(missionId);
        // Link 1: mission belongs to the URL's project.
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = loadComposante(id);
        // Link 2: cost line belongs to that mission.
        if (!composante.getMission().getId().equals(missionId)) {
            throw new NotFoundException("Composante introuvable : " + id);
        }

        composante.setTypeComposante(request.typeComposante());
        composante.setMontant(request.montant());
        composante.setDevise(request.devise().toUpperCase());
        composante.setDescription(request.description());

        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

    /**
     * Deletes one cost line. Soft delete (a flag), not a real SQL delete, because these rows are
     * accounting data that must stay auditable.
     */
    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public void delete(Long projectId, Long missionId, Long id) {
        Mission mission = missionService.loadMission(missionId);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = loadComposante(id);
        if (!composante.getMission().getId().equals(missionId)) {
            throw new NotFoundException("Composante introuvable : " + id);
        }

        composante.setDeleted(true);
        composanteRepository.save(composante);
    }

    /** Loads one non-deleted cost line, or throws NotFoundException (404). */
    private ComposanteMission loadComposante(Long id) {
        return composanteRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Composante introuvable : " + id));
    }
}
