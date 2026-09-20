package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

// DTO for creating/fully updating one backlog item. Kept separate from the BacklogItem entity
// so a client can never set id/createdAt/deleted or smuggle in a different project via the body
// (project comes only from the URL, per ADR-021 / ProjectScopeInterceptor).

/**
 * One product backlog item as the client sends it, shared by create and full update so the
 * two operations can't drift apart and silently lose a validation rule.
 */
public record BacklogItemRequest(
        // Rejects null/blank/whitespace-only: backlog_items.title is NOT NULL and is the
        // only text shown on the card.
        @NotBlank String title,

        // Optional: a card is often created during planning and described later.
        String description,

        // Required: overwrites the entity's default MEDIUM even with null, and the column
        // is NOT NULL with a CHECK on the four priority values.
        @NotNull BacklogPriority priority,

        // Effort in man-days. BigDecimal (not double) to avoid rounding drift when summing
        // estimates; mirrors NUMERIC(6,2) with a "IS NULL OR >= 0" check in the DB.
        @PositiveOrZero BigDecimal estimateDays,

        // Required: the board column; backlog_items.status is NOT NULL with a CHECK.
        @NotNull BacklogItemStatus status,

        /**
         * Null keeps the item in the product backlog (normal, not an error). Never trusted
         * as-is: BacklogItemService.resolveSprint reloads it and rejects one from another project.
         */
        Long sprintId,

        /**
         * Null leaves the item unassigned; otherwise must be a project team member, enforced by
         * BacklogItemService.resolveAssignee via TeamAssignmentRepository.
         */
        Long assigneeId
) {}
