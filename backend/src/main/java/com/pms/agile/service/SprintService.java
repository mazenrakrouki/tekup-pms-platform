package com.pms.agile.service;

import com.pms.agile.dto.SprintRequest;
import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.Sprint;
import com.pms.agile.mapper.SprintMapper;
import com.pms.agile.repository.BacklogItemRepository;
import com.pms.agile.repository.SprintRepository;
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
public class SprintService {

    private final SprintRepository      sprintRepository;
    private final BacklogItemRepository backlogItemRepository;
    private final ProjectRepository     projectRepository;
    private final SprintMapper          sprintMapper;

    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<SprintResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return sprintMapper.toResponseList(sprintRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse create(Long projectId, SprintRequest request) {
        Project project = loadProject(projectId);
        validateDates(request);
        Sprint sprint = Sprint.builder()
                .project(project)
                .name(request.name())
                .goal(request.goal())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status(request.status())
                .build();
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse update(Long projectId, Long id, SprintRequest request) {
        Sprint sprint = loadSprint(id, projectId);
        validateDates(request);
        sprint.setName(request.name());
        sprint.setGoal(request.goal());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        sprint.setStatus(request.status());
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    /**
     * Suppression logique du sprint. Les éléments qui lui étaient rattachés retournent au
     * backlog produit au lieu de disparaître avec l'itération : du travail encore à faire ne
     * doit pas être perdu parce qu'un sprint a été supprimé.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Sprint sprint = loadSprint(id, projectId);
        backlogItemRepository.detachFromSprint(id);
        sprint.setDeleted(true);
        sprintRepository.save(sprint);
    }

    private void validateDates(SprintRequest request) {
        if (request.startDate() != null && request.endDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin du sprint précède sa date de début.");
        }
    }

    /**
     * Charge un sprint en vérifiant qu'il appartient bien au projet de l'URL. Sans ce
     * contrôle, connaître un identifiant suffirait à lire ou modifier le sprint d'un autre
     * projet. On renvoie 404 et non 403 : l'existence même de la ressource ne doit pas fuir.
     */
    private Sprint loadSprint(Long id, Long projectId) {
        Sprint sprint = sprintRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + id));
        if (!sprint.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Sprint introuvable : " + id);
        }
        return sprint;
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
