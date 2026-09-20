package com.pms.team.dto;

import java.time.LocalDate;

// Flat, read-only view of one team_assignments row (project + user labels already joined in),
// returned by TeamController's GET/POST/PUT endpoints.

/**
 * One active team assignment, flattened for display. A record, not the entity, so the answer
 * can't accidentally leak User.passwordHash, trigger a LazyInitializationException on the
 * project's lazy fields after the transaction closes, or drag along the eagerly-loaded Role.
 * No "deleted" flag: every row returned here is by construction active.
 */
public record TeamAssignmentResponse(
        // Id of the assignment row itself, used to build PUT/DELETE URLs and the 201 Location header.
        Long id,
        Long projectId,
        String projectCode,
        String projectName,
        Long userId,
        // Built from User.getFullName() (firstName + lastName); there is no full_name column.
        String userFullName,
        // Display label only, echoed back as sent — never used to decide permissions.
        String roleInTeam,
        LocalDate startDate,
        // Null means still on the project with no planned end date.
        LocalDate endDate
) {}
