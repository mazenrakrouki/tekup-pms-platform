package com.pms.governance.service;

import com.pms.governance.dto.LivrableRequest;
import com.pms.governance.dto.LivrableResponse;
import com.pms.governance.entity.Livrable;
import com.pms.governance.entity.StatutLivrable;
import com.pms.governance.mapper.LivrableMapper;
import com.pms.governance.repository.LivrableRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * Business service for a project's deliverables (livrables): something the company owes the
 * client, walking EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE. VALIDE is final: update() and
 * delete() both refuse it. Sibling of RiskService, PartiePrenanteService and
 * DemandeChangementService; shares their permission checks and project-ownership guard.
 */

/**
 * Deliverable operations for one project: list, create, edit, delete, and the three status
 * moves demarrer / livrer / valider. Each move is its own method with its own accepted source
 * state, so the status can never be jumped by sending it in the PUT body (there is no such field).
 */
@Service
@RequiredArgsConstructor
public class LivrableService {

    private final LivrableRepository livrableRepository;
    private final ProjectRepository  projectRepository;
    private final LivrableMapper     livrableMapper;

    /**
     * Returns every deliverable of one project that is not soft-deleted, ordered by due date
     * with undated ones last.
     */
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<LivrableResponse> findByProject(Long projectId) {
        // Side effect only: throws 404 when the project doesn't exist (an empty list would say
        // "no deliverable" instead of "no such project").
        loadProject(projectId);
        return livrableMapper.toResponseList(livrableRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one deliverable to a project and returns it with its new id.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse create(Long projectId, LivrableRequest request) {
        Project project = loadProject(projectId);
        // statut is deliberately not set: the entity defaults it to EN_ATTENTE, so a deliverable
        // can never be created already VALIDE and skip client acceptance.
        Livrable livrable = Livrable.builder()
                .project(project)
                .titre(request.titre())
                .description(request.description())
                .dateEcheance(request.dateEcheance())
                .build();
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Edits the title, the description and the due date of a deliverable that has not been
     * accepted yet, and returns the updated DTO.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse update(Long projectId, Long id, LivrableRequest request) {
        Livrable livrable = loadLivrable(id, projectId);
        // Only VALIDE is frozen: once accepted, the record must match what was really accepted.
        // A LIVRE deliverable can still be corrected, since the client has not signed it off yet.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de modifier un livrable validé");
        }
        // A PUT replaces the three fields; an absent one empties that column.
        livrable.setTitre(request.titre());
        livrable.setDescription(request.description());
        livrable.setDateEcheance(request.dateEcheance());
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Moves one deliverable from EN_ATTENTE to EN_COURS ("work has started") and returns the
     * updated DTO. Reached by PATCH /api/projects/{projectId}/livrables/{id}/demarrer.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse demarrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // Requires exactly EN_ATTENTE: starting an already-accepted deliverable must be refused,
        // not silently reopen it.
        if (livrable.getStatut() != StatutLivrable.EN_ATTENTE) {
            throw new BusinessRuleException("Seul un livrable en attente peut être démarré");
        }
        livrable.setStatut(StatutLivrable.EN_COURS);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Marks one deliverable as LIVRE ("sent to the client") and returns the updated DTO.
     * Reached by PATCH /api/projects/{projectId}/livrables/{id}/livrer.
     *
     * Written as a forbidden state (VALIDE) rather than a required one: a deliverable can go
     * straight from EN_ATTENTE to LIVRE, and calling it again on an already-LIVRE row is a no-op.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse livrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // Once accepted (VALIDE), re-declaring a delivery would contradict the acceptance.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Un livrable déjà validé ne peut pas être modifié");
        }
        livrable.setStatut(StatutLivrable.LIVRE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Marks one deliverable as VALIDE ("accepted by the client") and returns the updated DTO.
     * Reached by PATCH /api/projects/{projectId}/livrables/{id}/valider. This freezes the row
     * for good: no method here ever moves it out of VALIDE again.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse valider(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // Requires exactly LIVRE: you cannot accept something that was never sent.
        if (livrable.getStatut() != StatutLivrable.LIVRE) {
            throw new BusinessRuleException("Seul un livrable livré peut être validé");
        }
        livrable.setStatut(StatutLivrable.VALIDE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    /**
     * Soft-deletes one deliverable that has not been accepted: the row stays with
     * deleted = true. Returns nothing, and the controller answers 204.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        // Same frozen state as update(): what the client accepted cannot be made to disappear.
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de supprimer un livrable validé");
        }
        livrable.setDeleted(true);
        livrableRepository.save(livrable);
    }

    /**
     * Loads one non-deleted deliverable AND proves it belongs to the project of the URL. Throws
     * NotFoundException (404) otherwise — used by update(), demarrer(), livrer(), valider(), delete().
     *
     * ProjectScopeInterceptor only checks that the caller may act on the project of the URL, not
     * that this specific deliverable belongs to it; the comparison below closes that gap. It
     * answers 404 rather than 403 on a mismatch so the response never confirms the id exists
     * elsewhere.
     */
    private Livrable loadLivrable(Long id, Long projectId) {
        Livrable livrable = livrableRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Livrable introuvable : " + id));
        // equals(), not ==: both are Long objects, and == would only coincidentally work under 128.
        if (!livrable.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Livrable introuvable : " + id);
        }
        return livrable;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (404).
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
