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

@Service
@RequiredArgsConstructor
public class DemandeChangementService {

    private final DemandeChangementRepository dcRepository;
    private final ProjectRepository           projectRepository;
    private final UserRepository              userRepository;
    private final DemandeChangementMapper     dcMapper;

    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<DemandeChangementResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return dcMapper.toResponseList(dcRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse create(Long projectId, DemandeChangementRequest request) {
        Project project = loadProject(projectId);
        User demandeur = loadUser(request.demandeurId());
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

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse update(Long projectId, Long id, DemandeChangementRequest request) {
        DemandeChangement dc = loadDC(id, projectId);
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de modifier une demande déjà traitée");
        }
        User demandeur = loadUser(request.demandeurId());
        dc.setDemandeur(demandeur);
        dc.setTitre(request.titre());
        dc.setDescription(request.description());
        dc.setPriorite(request.priorite());
        dc.setDateDemande(request.dateDemande());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse approuver(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        dc.setStatut(StatutChangement.APPROUVE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public DemandeChangementResponse rejeter(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("La demande a déjà été traitée");
        }
        dc.setStatut(StatutChangement.REJETE);
        dc.setDateDecision(LocalDate.now());
        return dcMapper.toResponse(dcRepository.save(dc));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        DemandeChangement dc = loadDC(id, projectId);
        if (dc.getStatut() != StatutChangement.EN_ATTENTE) {
            throw new BusinessRuleException("Impossible de supprimer une demande déjà traitée");
        }
        dc.setDeleted(true);
        dcRepository.save(dc);
    }

    private DemandeChangement loadDC(Long id, Long projectId) {
        DemandeChangement dc = dcRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Demande de changement introuvable : " + id));
        if (!dc.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Demande de changement introuvable : " + id);
        }
        return dc;
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
