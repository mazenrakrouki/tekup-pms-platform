package com.pms.governance.service;

import com.pms.governance.dto.DemandeChangementRequest;
import com.pms.governance.dto.DemandeChangementResponse;
import com.pms.governance.entity.DemandeChangement;
import com.pms.governance.entity.StatutChangement;
import com.pms.governance.mapper.DemandeChangementMapper;
import com.pms.governance.repository.DemandeChangementRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/*
 * Business service for a project's change requests (demandes de changement): somebody files one,
 * a manager approves or rejects it (EN_ATTENTE -> APPROUVE/REJETE, both final). Enforces the
 * permission and project-scope checks, and refuses to edit or delete a request once decided.
 * Sibling of RiskService, LivrableService and PartiePrenanteService; this is the only one of the
 * four that also resolves an author (UserRepository), since a change request always names one.
 */

/**
 * Change-request operations for one project: list, create, edit, delete, approuver, rejeter.
 * The two decisions are separate methods (not a status field in the PUT) so the requester can
 * never approve his own request — the server alone writes statut and dateDecision.
 */
@Service
@RequiredArgsConstructor
public class DemandeChangementService {

    // dcRepository/projectRepository/dcMapper mirror the other three governance services;
    // userRepository is the one extra collaborator, needed because a request always points at a person.
    private final DemandeChangementRepository dcRepository;
    private final ProjectRepository           projectRepository;
    private final UserRepository              userRepository;
    private final DemandeChangementMapper     dcMapper;

    /**
     * Returns every change request of one project that is not soft-deleted, most recently filed
     * first.
     */
    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<DemandeChangementResponse> findByProject(Long projectId) {
        // Side effect only: throws 404 when the project doesn't exist (an empty list would say
        // "no request" instead of "no such project").
        loadProject(projectId);
        return dcMapper.toResponseList(dcRepository.findActiveByProjectId(projectId));
    }

    /**
     * Files one change request on a project and returns it with its new id.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse create(Long projectId, DemandeChangementRequest request) {
        // Loaded before anything is built, so a refused request leaves no half-created row.
        Project project = loadProject(projectId);
        User demandeur = loadUser(request.demandeurId());
        // statut/dateDecision are deliberately not set: the entity defaults statut to EN_ATTENTE,
        // so a request can never be filed already decided.
        DemandeChangement dc = DemandeChangement.builder()
                .project(project)
                .demandeur(demandeur)
                .titre(request.titre())
                .description(request.description())
                .priorite(request.priorite())
                .dateDemande(request.dateDemande())
                .build();
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Edits a change request that has not been decided yet, and returns the updated DTO.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse update(Long projectId, Long id, DemandeChangementRequest request) {
        DemandeChangement dc = loadDC(id, projectId);
        // Frozen once decided: an approved/rejected request must keep the text it was decided on.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de modifier une demande déjà traitée");
        }
        User demandeur = loadUser(request.demandeurId());
        // A PUT replaces all five fields; an absent one empties that column.
        dc.setDemandeur(demandeur);
        dc.setTitre(request.titre());
        dc.setDescription(request.description());
        dc.setPriorite(request.priorite());
        dc.setDateDemande(request.dateDemande());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Approves one pending change request and returns the updated DTO. Reached by
     * PATCH /api/projects/{projectId}/demandes-changement/{id}/approuver.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse approuver(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // Decided exactly once — also guards against a double click re-deciding it.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        // Status and date written by the server only, never from the request body.
        dc.setStatut(StatutChangement.APPROUVE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Rejects one pending change request and returns the updated DTO. Reached by
     * PATCH /api/projects/{projectId}/demandes-changement/{id}/rejeter.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse rejeter(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // Same rule as approuver(): a decided request cannot be decided again.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        // REJETE is final: no method here reopens it — a refused change means filing a new request.
        dc.setStatut(StatutChangement.REJETE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    /**
     * Soft-deletes one change request that has not been decided: the row stays with
     * deleted = true. Returns nothing, and the controller answers 204.
     */
    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        // Only a still-waiting request can be withdrawn; a REJETE one stays in the history too.
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de supprimer une demande déjà traitée");
        }
        dc.setDeleted(true);
        dcRepository.save(dc);
    }

    /**
     * Loads one non-deleted change request AND proves it belongs to the project of the URL.
     * Throws NotFoundException (404) otherwise — used by update(), approuver(), rejeter(), delete().
     *
     * ProjectScopeInterceptor only checks that the caller may act on the project of the URL, not
     * that this specific request belongs to it; the comparison below closes that gap. It answers
     * 404 rather than 403 on a mismatch so the response never confirms the id exists elsewhere.
     */
    private DemandeChangement loadDC(Long id, Long projectId) {
        DemandeChangement dc = dcRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Demande de changement introuvable : " + id));
        // equals(), not ==: both are Long objects, and == would only coincidentally work under 128.
        if (!dc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Demande de changement introuvable : " + id);
        }
        return dc;
    }

    /**
     * Loads the project named in the URL, or throws NotFoundException (404).
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Turns the demandeurId sent in the body into a real, active User, or throws
     * NotFoundException (404).
     */
    private User loadUser(Long id) {
        // filter drops a soft-deleted user, turning the Optional empty so it 404s like an unknown id.
        return userRepository.findById(id)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + id));
    }
}
