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

// Business layer of the sprints (iterations) of one project: create, list, update and
// soft-delete. Sister file BacklogItemService owns the cards; this class only detaches them
// (delete() below) when their sprint disappears.

/**
 * Business rules of the sprints of one project.
 *
 * <p>Like BacklogItemService, every public method takes {@code projectId} first and re-proves
 * the sprint it touches really belongs to it ({@code loadSprint}), so a guessed id cannot
 * rename or delete another project's sprint.
 */
@Service
@RequiredArgsConstructor
public class SprintService {

    // Reads and writes the sprints table.
    private final SprintRepository       sprintRepository;
    // Needed only by delete(): a sprint's cards must be sent back to the product backlog
    // before the sprint disappears.
    private final BacklogItemRepository  backlogItemRepository;
    // Proves the project named in the URL exists and is not soft-deleted.
    private final ProjectRepository      projectRepository;
    // Converts a Sprint entity into the flat SprintResponse sent as JSON.
    private final SprintMapper           sprintMapper;

    /**
     * Gives back every live sprint of one project, oldest start date first.
     */
    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<SprintResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return sprintMapper.toResponseList(sprintRepository.findActiveByProjectId(projectId));
    }

    /**
     * Creates one sprint inside a project and gives it back with its generated id.
     *
     * <p>validateDates runs before the builder so nothing is built for a request already known
     * to be refused.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse create(Long projectId, SprintRequest request) {
        Project project = loadProject(projectId);
        validateDates(request);
        Sprint sprint = Sprint.builder()
                // Project comes from the URL, never the request body, so a caller allowed on
                // project 7 cannot create a sprint inside project 9.
                .project(project)
                .name(request.name())
                .goal(request.goal())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status(request.status())
                .build();
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    /**
     * Replaces every editable field of one existing sprint (HTTP PUT of the sprint form). The
     * project itself is never changed here, so a sprint can't be moved between projects.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public SprintResponse update(Long projectId, Long id, SprintRequest request) {
        Sprint sprint = loadSprint(id, projectId);
        // Checked again on every update, not only on create.
        validateDates(request);
        sprint.setName(request.name());
        sprint.setGoal(request.goal());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        sprint.setStatus(request.status());
        return sprintMapper.toResponse(sprintRepository.save(sprint));
    }

    /**
     * Soft-deletes one sprint, after sending the cards committed to it back to the product
     * backlog.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        Sprint sprint = loadSprint(id, projectId);

        // Detach committed cards first: they return to the product backlog instead of keeping
        // a foreign key pointing at a sprint that is about to become invisible. Wrapped in the
        // same transaction as the sprint's own delete below, so a crash mid-way can't leave one
        // write without the other.
        List<BacklogItem> committed = backlogItemRepository.findActiveBySprintId(id);
        committed.forEach(item -> item.setSprint(null));
        backlogItemRepository.saveAll(committed);

        sprint.setDeleted(true);
        sprintRepository.save(sprint);
    }

    /**
     * Refuses a sprint whose end date is before its start date.
     *
     * <p>Duplicates the database constraint chk_sprint_dates (V27) on purpose: this gives a
     * readable message instead of a raw SQL error. Equal start/end dates pass (a one-day sprint
     * is legitimate).
     */
    private void validateDates(SprintRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("La date de fin doit etre posterieure ou egale a la date de debut.");
        }
    }

    /**
     * Loads one sprint and proves it belongs to {@code projectId}. Every method that changes an
     * existing sprint starts here.
     */
    private Sprint loadSprint(Long id, Long projectId) {
        Sprint sprint = sprintRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + id));
        if (!sprint.getProject().getId().equals(projectId)) {
            // Same "not found" message on purpose, not "forbidden" — a different answer would
            // confirm the sprint exists elsewhere.
            throw new NotFoundException("Sprint introuvable : " + id);
        }
        return sprint;
    }

    /**
     * Loads the project named in the URL, or fails with "not found".
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
