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
import com.pms.shared.exception.BusinessRuleException;
import com.pms.shared.exception.NotFoundException;
import com.pms.team.repository.TeamAssignmentRepository;
import com.pms.user.entity.User;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// Business layer of the product backlog: create, read, update, move and soft-delete the cards
// of one project. Every method re-checks that the row it touches really belongs to projectId,
// so guessing another project's item/sprint/user id never works (see loadItem/resolveSprint/
// resolveAssignee below). Sister file SprintService owns the sprints.

/**
 * Business rules of the product backlog of one project.
 */
@Service
@RequiredArgsConstructor
public class BacklogItemService {

    // Reads and writes the backlog_items table (the cards themselves).
    private final BacklogItemRepository      backlogItemRepository;
    // Looks up a target sprint and proves it belongs to the same project; sprints themselves
    // are owned by SprintService.
    private final SprintRepository           sprintRepository;
    // Proves the project in the URL really exists and is not deleted.
    private final ProjectRepository          projectRepository;
    // Converts a BacklogItem entity into the flat BacklogItemResponse sent as JSON.
    private final BacklogItemMapper          backlogItemMapper;
    // Proves the chosen assignee is a real, active user.
    private final UserRepository             userRepository;
    // Proves the assignee is actually a member of this project's team.
    private final TeamAssignmentRepository   teamAssignmentRepository;

    /**
     * Gives back every live backlog item of one project, mapped to the flat shape the board
     * draws as three columns.
     */
    @PreAuthorize("hasAuthority('VIEW_AGILE')")
    @Transactional(readOnly = true)
    public List<BacklogItemResponse> findByProject(Long projectId) {
        loadProject(projectId);
        return backlogItemMapper.toResponseList(backlogItemRepository.findActiveByProjectId(projectId));
    }

    /**
     * Creates one backlog item inside a project and gives it back with its new database id.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse create(Long projectId, BacklogItemRequest request) {
        Project project = loadProject(projectId);
        BacklogItem item = BacklogItem.builder()
                .project(project)
                // Project comes from the URL, never the request body, so a caller allowed on
                // project 7 cannot create an item inside project 9.
                .sprint(resolveSprint(request.sprintId(), projectId))
                .title(request.title())
                .description(request.description())
                .priority(request.priority())
                .estimateDays(request.estimateDays())
                .status(request.status())
                .assignee(resolveAssignee(request.assigneeId(), projectId))
                .build();
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Replaces every editable field of one existing item (HTTP PUT of the card form).
     */
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
        item.setAssignee(resolveAssignee(request.assigneeId(), projectId));
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Moves one card on the board: changes its column, and possibly its sprint.
     *
     * <p>Separate from update() so a drag-and-drop can never overwrite someone else's edits to
     * the title/description in a race with a full PUT.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public BacklogItemResponse move(Long projectId, Long id, BacklogItemMoveRequest request) {
        BacklogItem item = loadItem(id, projectId);
        item.setStatus(request.status());
        item.setSprint(resolveSprint(request.sprintId(), projectId));
        return backlogItemMapper.toResponse(backlogItemRepository.save(item));
    }

    /**
     * Soft delete: raises the {@code deleted} flag instead of removing the row, so history and
     * undo stay possible.
     */
    @PreAuthorize("hasAuthority('MANAGE_AGILE')")
    @Transactional
    public void delete(Long projectId, Long id) {
        BacklogItem item = loadItem(id, projectId);
        item.setDeleted(true);
        backlogItemRepository.save(item);
    }

    /**
     * Finds the target sprint and proves it belongs to the same project as the item. Returns
     * {@code null} when no sprint was asked for (the item stays in the product backlog).
     */
    private Sprint resolveSprint(Long sprintId, Long projectId) {
        if (sprintId == null) {
            return null;
        }
        Sprint sprint = sprintRepository.findActiveById(sprintId)
                .orElseThrow(() -> new NotFoundException("Sprint introuvable : " + sprintId));
        if (!sprint.getProject().getId().equals(projectId)) {
            // Same "not found" as above, not "forbidden" — a different answer would confirm
            // that sprint exists elsewhere.
            throw new NotFoundException("Sprint introuvable : " + sprintId);
        }
        return sprint;
    }

    /**
     * Finds the person the card is given to, and proves that person is a member of this
     * project's team. Returns {@code null} when nobody was named.
     */
    private User resolveAssignee(Long assigneeId, Long projectId) {
        if (assigneeId == null) {
            return null;
        }
        User user = userRepository.findActiveById(assigneeId)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + assigneeId));
        // "DeletedFalse" matters because leaving a team is itself a soft delete.
        if (!teamAssignmentRepository.existsByProjectIdAndUserIdAndDeletedFalse(projectId, assigneeId)) {
            throw new BusinessRuleException(
                    "L'utilisateur affecté doit être membre de l'équipe du projet.");
        }
        return user;
    }

    /**
     * Loads one backlog item and proves it belongs to {@code projectId}. Every method that
     * changes an existing card starts here.
     */
    private BacklogItem loadItem(Long id, Long projectId) {
        BacklogItem item = backlogItemRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Élément de backlog introuvable : " + id));
        if (!item.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Élément de backlog introuvable : " + id);
        }
        return item;
    }

    /**
     * Loads the project named in the URL, or fails with "not found".
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
