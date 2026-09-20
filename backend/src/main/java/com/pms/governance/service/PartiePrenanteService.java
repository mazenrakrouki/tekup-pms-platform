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

/*
 * Business service for a project's stakeholder register (parties prenantes): people concerned by
 * the project, each with an influence and an interest level (FAIBLE/MOYEN/ELEVE), feeding the
 * classic influence/interest grid. Simplest of the four governance services: no state machine,
 * so it never throws BusinessRuleException. Contact details are personal data, so the read
 * permission matters more here than on its siblings (RiskService is the closest twin).
 */

/**
 * Stakeholder-register operations for one project: list, create, update, delete. Every public
 * method takes the projectId from the URL and returns a PartiePrenanteResponse DTO, never the
 * entity itself.
 */
@Service
@RequiredArgsConstructor
public class PartiePrenanteService {

    private final PartiePrenanteRepository ppRepository;
    private final ProjectRepository        projectRepository;
    private final PartiePrenanteMapper     ppMapper;

    /**
     * Returns every stakeholder of one project that is not soft-deleted, ordered by name.
     */
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<PartiePrenanteResponse> findByProject(Long projectId) {
        // Side effect only: throws 404 when the project doesn't exist (an empty list would say
        // "no stakeholder" instead of "no such project").
        loadProject(projectId);
        return ppMapper.toResponseList(ppRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one stakeholder to the register of a project and returns it with its new id.
     *
     * No duplicate check on purpose: two different people can share a name, and the register is
     * a working document the project manager cleans up himself.
     */
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

    /**
     * Overwrites the six editable fields of one stakeholder and returns the updated DTO.
     *
     * No status check, unlike a deliverable or a change request: a contact can always be
     * corrected, since people change job and phone number while the project runs.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public PartiePrenanteResponse update(Long projectId, Long id, PartiePrenanteRequest request) {
        PartiePrenante pp = loadPP(id, projectId);
        // A PUT replaces every field, including the optional ones: an absent one empties that column.
        pp.setNom(request.nom());
        pp.setFonction(request.fonction());
        pp.setEmail(request.email());
        pp.setTelephone(request.telephone());
        pp.setInfluence(request.influence());
        pp.setInteret(request.interet());
        return ppMapper.toResponse(ppRepository.save(pp));
    }

    /**
     * Soft-deletes one stakeholder: the row stays with deleted = true. Returns nothing, and the
     * controller answers 204 No Content. Unlike Livrable/DemandeChangement, there is no status
     * to check first — a stakeholder has no lifecycle.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        PartiePrenante pp = loadPP(id, projectId);
        // The personal data (name, e-mail, phone) stays in the row; only the flag changes. A real
        // erasure request has to be handled outside this path.
        pp.setDeleted(true);
        ppRepository.save(pp);
    }

    /**
     * Loads one non-deleted stakeholder AND proves it belongs to the project of the URL. Throws
     * NotFoundException (404) otherwise — used by update() and delete().
     *
     * ProjectScopeInterceptor only checks that the caller may act on the project of the URL, not
     * that this specific stakeholder belongs to it; the comparison below closes that gap. It
     * answers 404 rather than 403 on a mismatch so the response never confirms the id exists
     * elsewhere.
     */
    private PartiePrenante loadPP(Long id, Long projectId) {
        PartiePrenante pp = ppRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Partie prenante introuvable : " + id));
        // equals(), not ==: both are Long objects, and == would only coincidentally work under 128.
        if (!pp.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Partie prenante introuvable : " + id);
        }
        return pp;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (404).
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
