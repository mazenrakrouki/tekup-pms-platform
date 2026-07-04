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

@Service
@RequiredArgsConstructor
public class ComposanteService {

    private final ComposanteMissionRepository composanteRepository;
    private final MissionService              missionService;
    private final ComposanteMapper            composanteMapper;

    @PreAuthorize("hasAuthority('VIEW_MISSION')")
    @Transactional(readOnly = true)
    public List<ComposanteResponse> findByMission(Long projectId, Long missionId) {
        Mission mission = missionService.loadMission(missionId);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }
        return composanteMapper.toResponseList(composanteRepository.findActiveByMissionId(missionId));
    }

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
                .devise(request.devise().toUpperCase())
                .description(request.description())
                .build();

        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

    @PreAuthorize("hasAuthority('MANAGE_MISSION')")
    @Transactional
    public ComposanteResponse update(Long projectId, Long missionId, Long id, ComposanteRequest request) {
        Mission mission = missionService.loadMission(missionId);
        if (!mission.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Mission introuvable : " + missionId);
        }

        ComposanteMission composante = loadComposante(id);
        if (!composante.getMission().getId().equals(missionId)) {
            throw new NotFoundException("Composante introuvable : " + id);
        }

        composante.setTypeComposante(request.typeComposante());
        composante.setMontant(request.montant());
        composante.setDevise(request.devise().toUpperCase());
        composante.setDescription(request.description());

        return composanteMapper.toResponse(composanteRepository.save(composante));
    }

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

    private ComposanteMission loadComposante(Long id) {
        return composanteRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Composante introuvable : " + id));
    }
}
