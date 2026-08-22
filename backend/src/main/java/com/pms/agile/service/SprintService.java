package com.pms.agile.service;

import com.pms.agile.dto.SprintRequest;
import com.pms.agile.dto.SprintResponse;
import com.pms.agile.entity.BacklogItem;
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

    private final SprintRepository       sprintRepository;
    private final BacklogItemRepository  backlogItemRepository;
    private final ProjectRepository      projectRepository;
    private final SprintMapper           sprintMapper;

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

    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Sprint sprint = loadSprint(id, projectId);

        // Supprimer un sprint rend ses éléments au backlog produit plutôt que de
        // les supprimer avec lui : le travail engagé mais non terminé n'est pas
        // perdu, il redevient simplement non planifié. C'est aussi ce qui évite
        // de laisser des éléments pointer, par clé étrangère, vers un sprint
        // logiquement supprimé.
        List<BacklogItem> committed = backlogItemRepository.findActiveBySprintId(id);
        committed.forEach(item -> item.setSprint(null));
        backlogItemRepository.saveAll(committed);

        sprint.setDeleted(true);
        sprintRepository.save(sprint);
    }

    private void validateDates(SprintRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin doit etre posterieure ou egale a la date de debut.");
        }
    }

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
