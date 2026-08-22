package com.pms.agile.service;

import com.pms.agile.dto.BacklogItemMoveRequest;
import com.pms.agile.dto.BacklogItemRequest;
import com.pms.agile.dto.BacklogItemResponse;
import com.pms.agile.entity.BacklogItem;
import com.pms.agile.entity.Sprint;
import com.pms.agile.mapper.BacklogItemMapper;
import com.pms.agile.repository.BacklogItemRepository;
import com.pms.agile.repository.SprintRepository;
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
public class BacklogItemService {

    private final BacklogItemRepository backlogItemRepository;
    private final SprintRepository      sprintRepository;
    private final ProjectRepository     projectRepository;
    private final BacklogItemMapper     backlogItemMapper;

    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<BacklogItemResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return backlogItemMapper.toResponseList(backlogItemRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse create(Long projectId, BacklogItemRequest request) {
        Project project = loadProject(projectId);
        BacklogItem item = BacklogItem.builder()
                .project(project)
                .sprint(resolveSprint(request.sprintId(), projectId))
                .title(request.title())
                .description(request.description())
                .priority(request.priority())
                .estimateDays(request.estimateDays())
                .status(request.status())
                .build();
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse update(Long projectId, Long id, BacklogItemRequest request) {
        BacklogItem item = loadItem(id, projectId);
        item.setTitle(request.title());
        item.setDescription(request.description());
        item.setPriority(request.priority());
        item.setEstimateDays(request.estimateDays());
        item.setStatus(request.status());
        item.setSprint(resolveSprint(request.sprintId(), projectId));
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /** Déplacement d'une carte sur le tableau : colonne, et éventuellement itération. */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse move(Long projectId, Long id, BacklogItemMoveRequest request) {
        BacklogItem item = loadItem(id, projectId);
        item.setStatus(request.status());
        item.setSprint(resolveSprint(request.sprintId(), projectId));
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        BacklogItem item = loadItem(id, projectId);
        item.setDeleted(true);
        backlogItemRepository.save(item);
    }

    /**
     * Résout le sprint cible en garantissant qu'il appartient au même projet que l'élément.
     * C'est le contrôle qui empêche de rattacher un élément à l'itération d'un autre projet
     * en devinant un identifiant — la relation traverserait alors la frontière du projet.
     * {@code null} est une valeur légitime : l'élément retourne au backlog produit.
     */
    private Sprint resolveSprint(Long sprintId, Long projectId) {
        if (sprintId == null) {
            return null;
        }
        Sprint sprint = sprintRepository.findActiveById(sprintId)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + sprintId));
        if (!sprint.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Sprint introuvable : " + sprintId);
        }
        return sprint;
    }

    private BacklogItem loadItem(Long id, Long projectId) {
        BacklogItem item = backlogItemRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Élément de backlog introuvable : " + id));
        if (!item.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Élément de backlog introuvable : " + id);
        }
        return item;
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
