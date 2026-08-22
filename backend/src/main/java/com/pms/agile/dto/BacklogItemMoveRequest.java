package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Board move: reassign an item to a sprint and/or change its column.
 * A null sprintId returns the item to the product backlog.
 */
public record BacklogItemMoveRequest(
        Long sprintId,
        @NotNull BacklogItemStatus status
) {}
