package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import jakarta.validation.constraints.NotNull;

// DTO for dragging a card on the agile board: sent to PATCH .../backlog/{id}/move. Kept
// separate from BacklogItemRequest so a drag can't accidentally overwrite a concurrent
// edit to title/description with a stale copy.

/**
 * Board move: reassign an item to a sprint and/or change its column. A null sprintId
 * returns the item to the product backlog.
 *
 * <p>No id/projectId fields: both travel in the URL (ADR-021 / ProjectScopeInterceptor
 * trusts only the URL's project id, never one from the body).
 */
public record BacklogItemMoveRequest(
        // Null means "send back to the product backlog". Never trusted as-is:
        // BacklogItemService.resolveSprint reloads it and rejects a sprint from another project.
        Long sprintId,

        // Required: this is the whole point of a move, and backlog_items.status is NOT NULL.
        @NotNull BacklogItemStatus status
) {}
