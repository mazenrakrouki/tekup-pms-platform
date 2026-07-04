package com.pms.governance.service;

import com.pms.governance.dto.RiskRequest;
import com.pms.governance.dto.RiskResponse;
import com.pms.governance.entity.Risk;
import com.pms.governance.mapper.RiskMapper;
import com.pms.governance.repository.RiskRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskRepository    riskRepository;
    private final ProjectRepository projectRepository;
    private final RiskMapper        riskMapper;

    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<RiskResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return riskMapper.toResponseList(riskRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public RiskResponse create(Long projectId, RiskRequest request) {
        Project project = loadProject(projectId);
        Risk risk = Risk.builder()
                .project(project)
                .description(request.description())
                .probabilite(request.probabilite())
                .impact(request.impact())
                .planMitigation(request.planMitigation())
                .statut(request.statut())
                .build();
        return riskMapper.toResponse(riskRepository.save(risk));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public RiskResponse update(Long projectId, Long id, RiskRequest request) {
        Risk risk = loadRisk(id, projectId);
        risk.setDescription(request.description());
        risk.setProbabilite(request.probabilite());
        risk.setImpact(request.impact());
        risk.setPlanMitigation(request.planMitigation());
        risk.setStatut(request.statut());
        return riskMapper.toResponse(riskRepository.save(risk));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Risk risk = loadRisk(id, projectId);
        risk.setDeleted(true);
        riskRepository.save(risk);
    }

    private Risk loadRisk(Long id, Long projectId) {
        Risk risk = riskRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Risque introuvable : " + id));
        if (!risk.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Risque introuvable : " + id);
        }
        return risk;
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
