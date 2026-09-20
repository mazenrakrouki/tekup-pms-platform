package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;

import java.math.BigDecimal;

// Read side of the backlog pair (BacklogItemRequest in, this out). Avoids returning the entity
// directly: its assignee (User) carries passwordHash, and its lazy project/sprint/assignee links
// throw LazyInitializationException once Jackson touches them after the transaction closes.

/**
 * One backlog card, flattened for the screen: each foreign key (projectId, sprintId,
 * assigneeId) sits next to its readable label so the board doesn't need one extra HTTP call
 * per card just to resolve an id to a name.
 */
public record BacklogItemResponse(
        // Used by the board for every follow-up call (PUT, PATCH /move, DELETE).
        Long id,

        Long projectId,

        // Human-readable project reference (e.g. "S2I-2026-014"), from project.code.
        String projectCode,

        // Null when the card is still in the product backlog.
        Long sprintId,

        String sprintName,

        String title,

        // Null when nobody wrote one yet.
        String description,

        // Sent as the enum name (not ordinal) so meaning survives if the enum is ever reordered.
        BacklogPriority priority,

        // Man-days; BigDecimal to avoid the rounding drift a double would introduce when summed.
        BigDecimal estimateDays,

        BacklogItemStatus status,

        // Null when nobody picked the card up yet. Compared by id (not name) so "my cards"
        // filtering can't be fooled by two team members sharing a name.
        Long assigneeId,

        // From User.getFullName(); the User itself is never exposed (would leak email/password hash).
        String assigneeName
) {}
