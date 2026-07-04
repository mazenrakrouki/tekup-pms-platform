package com.pms.governance.service;

import com.pms.governance.dto.PartiePrenanteRequest;
import com.pms.governance.dto.PartiePrenanteResponse;
import com.pms.governance.entity.PartiePrenante;
import com.pms.governance.mapper.PartiePrenanteMapper;
import com.pms.governance.repository.PartiePrenanteRepository;
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
public class PartiePrenanteService {

    private final PartiePrenanteRepository ppRepository;
    private final ProjectRepository        projectRepository;
    private final PartiePrenanteMapper     ppMapper;

    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<PartiePrenanteResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return ppMapper.toResponseList(ppRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public PartiePrenanteResponse create(Long projectId, PartiePrenanteRequest request) {
        Project project = loadProject(projectId);
        PartiePrenante pp = PartiePrenante.builder()
                .project(project)
                .nom(request.nom())
                .fonction(request.fonction())
                .email(request.email())
                .telephone(request.telephone())
                .influence(request.influence())
                .interet(request.interet())
                .build();
        return ppMapper.toResponse(ppRepository.save(pp));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public PartiePrenanteResponse update(Long projectId, Long id, PartiePrenanteRequest request) {
        PartiePrenante pp = loadPP(id, projectId);
        pp.setNom(request.nom());
        pp.setFonction(request.fonction());
        pp.setEmail(request.email());
        pp.setTelephone(request.telephone());
        pp.setInfluence(request.influence());
        pp.setInteret(request.interet());
        return ppMapper.toResponse(ppRepository.save(pp));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        PartiePrenante pp = loadPP(id, projectId);
        pp.setDeleted(true);
        ppRepository.save(pp);
    }

    private PartiePrenante loadPP(Long id, Long projectId) {
        PartiePrenante pp = ppRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Partie prenante introuvable : " + id));
        if (!pp.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Partie prenante introuvable : " + id);
        }
        return pp;
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
