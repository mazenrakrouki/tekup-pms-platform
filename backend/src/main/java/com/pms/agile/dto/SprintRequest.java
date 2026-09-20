package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// DTO for creating/fully updating one sprint. Kept separate from the Sprint entity so a client
// can never set id/deleted/project via the body (project comes only from the URL, ADR-021).

/**
 * One sprint as the client sends it, shared by create and full update. Field-level rules
 * (blank/null) live here as annotations; the cross-field rule "end not before start" needs
 * SprintService.validateDates instead, and is repeated once more by the DB check chk_sprint_dates.
 */
public record SprintRequest(
        // Rejects null/blank/whitespace-only: identifies the iteration on every screen.
        @NotBlank String name,

        // Optional: often written after the sprint itself during planning.
        String goal,

        // LocalDate, not a timestamp: a sprint starts on a day, and a timestamp would drag in
        // a time zone that could shift the date by one day depending on where it's read.
        @NotNull LocalDate startDate,

        // "After startDate" isn't checked here (see class doc) — only null/missing is.
        @NotNull LocalDate endDate,

        // Required: overwrites the entity's default PLANNED even with null; column is NOT NULL.
        @NotNull SprintStatus status
) {}
