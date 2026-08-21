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

@Service
@RequiredArgsConstructor
public class LivrableService {

    private final LivrableRepository livrableRepository;
    private final ProjectRepository  projectRepository;
    private final LivrableMapper     livrableMapper;

    @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')")
    @Transactional(readOnly = true)
    public List<LivrableResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return livrableMapper.toResponseList(livrableRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse create(Long projectId, LivrableRequest request) {
        Project project = loadProject(projectId);
        Livrable livrable = Livrable.builder()
                .project(project)
                .titre(request.titre())
                .description(request.description())
                .dateEcheance(request.dateEcheance())
                .build();
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse update(Long projectId, Long id, LivrableRequest request) {
        Livrable livrable = loadLivrable(id, projectId);
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de modifier un livrable validé");
        }
        livrable.setTitre(request.titre());
        livrable.setDescription(request.description());
        livrable.setDateEcheance(request.dateEcheance());
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse demarrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        if (livrable.getStatut() != StatutLivrable.EN_ATTENTE) {
            throw new BusinessRuleException("Seul un livrable en attente peut être démarré");
        }
        livrable.setStatut(StatutLivrable.EN_COURS);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse livrer(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Un livrable déjà validé ne peut pas être modifié");
        }
        livrable.setStatut(StatutLivrable.LIVRE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public LivrableResponse valider(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        if (livrable.getStatut() != StatutLivrable.LIVRE) {
            throw new BusinessRuleException("Seul un livrable livré peut être validé");
        }
        livrable.setStatut(StatutLivrable.VALIDE);
        return livrableMapper.toResponse(livrableRepository.save(livrable));
    }

    @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Livrable livrable = loadLivrable(id, projectId);
        if (livrable.getStatut() == StatutLivrable.VALIDE) {
            throw new BusinessRuleException("Impossible de supprimer un livrable validé");
        }
        livrable.setDeleted(true);
        livrableRepository.save(livrable);
    }

    private Livrable loadLivrable(Long id, Long projectId) {
        Livrable livrable = livrableRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Livrable introuvable : " + id));
        if (!livrable.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Livrable introuvable : " + id);
        }
        return livrable;
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
