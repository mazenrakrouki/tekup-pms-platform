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

/*
 * Business service for a project's risk register: each risk carries a description, a
 * probabilite, an impact, a planMitigation and a statut (OUVERT/MITIGE/FERME). Sibling of
 * LivrableService, PartiePrenanteService and DemandeChangementService, sharing their permission
 * and project-ownership checks. Like a stakeholder and unlike a deliverable or change request, a
 * risk has no frozen state — even a closed one can still be corrected.
 */

/**
 * Risk-register operations for one project: list, create, update, delete. Every public method
 * takes the projectId from the URL and returns a RiskResponse DTO, never the Risk entity itself.
 */
@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskRepository    riskRepository;
    private final ProjectRepository projectRepository;
    private final RiskMapper        riskMapper;

    /**
     * Returns every risk of one project that is not soft-deleted, newest first.
     */
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<RiskResponse> findByProject(Long projectId) {
        // Side effect only: throws 404 when the project doesn't exist (an empty list would say
        // "no risk" instead of "no such project").
        loadProject(projectId);
        return riskMapper.toResponseList(riskRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one risk to the register of a project and returns it with its new id.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public RiskResponse create(Long projectId, RiskRequest request) {
        Project project = loadProject(projectId);
        // statut is taken from the request here, unlike a deliverable or change request: the
        // register is written by hand and a risk can legitimately start already MITIGE.
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

    /**
     * Overwrites the five editable fields of one risk and returns the updated DTO.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public RiskResponse update(Long projectId, Long id, RiskRequest request) {
        Risk risk = loadRisk(id, projectId);
        // A PUT replaces every field, including planMitigation: an absent one empties that column.
        risk.setDescription(request.description());
        risk.setProbabilite(request.probabilite());
        risk.setImpact(request.impact());
        risk.setPlanMitigation(request.planMitigation());
        risk.setStatut(request.statut());
        return riskMapper.toResponse(riskRepository.save(risk));
    }

    /**
     * Soft-deletes one risk: the row stays with deleted = true. Returns nothing, and the
     * controller answers 204 No Content.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Risk risk = loadRisk(id, projectId);
        risk.setDeleted(true);
        riskRepository.save(risk);
    }

    /**
     * Loads one non-deleted risk AND proves it belongs to the project of the URL. Throws
     * NotFoundException (404) otherwise — used by update() and delete().
     *
     * ProjectScopeInterceptor only checks that the caller may act on the project of the URL, not
     * that this specific risk belongs to it; the comparison below closes that gap. It answers 404
     * rather than 403 on a mismatch so the response never confirms the id exists elsewhere.
     */
    private Risk loadRisk(Long id, Long projectId) {
        Risk risk = riskRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Risque introuvable : " + id));
        // equals(), not ==: both are Long objects, and == would only coincidentally work under 128.
        if (!risk.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Risque introuvable : " + id);
        }
        return risk;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (404).
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
