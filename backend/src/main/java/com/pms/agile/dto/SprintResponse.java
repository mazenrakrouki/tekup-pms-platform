package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;

import java.time.LocalDate;

// Read side of the sprint pair (SprintRequest in, this out). Avoids returning the Sprint entity
// directly, which would throw LazyInitializationException on its lazy Project link once the
// transaction is closed, and would over-expose the whole Project object.

/**
 * One sprint, flattened for the screen. Unlike SprintRequest it carries id and project
 * (projectId/projectCode) since a client holding a list of sprints needs to tell them apart.
 * Deliberately omits the BaseEntity audit fields and the soft-delete flag — unused by any screen.
 */
public record SprintResponse(
        Long id,

        // Sent even though the browser already has it in the URL, so a sprint held in memory
        // can be checked/matched against a card's sprintId without re-deriving it.
        Long projectId,

        // Human-readable project reference (e.g. "S2I-2026-014"), from project.code.
        String projectCode,

        String name,

        // Null when nobody wrote one yet — normal, not an error.
        String goal,

        // Day only, not a timestamp — see SprintRequest for why.
        LocalDate startDate,

        // Guaranteed not before startDate by DB constraint chk_sprint_dates.
        LocalDate endDate,

        // Sent as the enum name (not ordinal) so meaning survives if the enum is ever reordered.
        SprintStatus status
) {}
