package com.pms.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// JSON body for adding or editing a team member. A DTO, not the entity, so a caller can't write
// fields like "deleted" that the API must never expose, and validation rules live in one place.

/**
 * Input for "add this person to this project team" and "edit this team line".
 *
 * <p>The project id is deliberately NOT a field here: it comes from the URL, so
 * ProjectScopeInterceptor (ADR-021) can check it before the controller runs. If it travelled in
 * the body instead, the interceptor would never see it.</p>
 */
public record TeamAssignmentRequest(
        // Required only for assign(); update() keeps the member already on the row and ignores this.
        @NotNull Long userId,
        // Display label only, not a security role — authorization is permission-based elsewhere.
        @NotBlank @Size(max = 50) String roleInTeam,
        @NotNull LocalDate startDate,
        // No annotation: null is valid and means "still on the project". The end-after-start rule
        // needs both fields, so it's enforced in TeamAssignmentService (422), not here.
        LocalDate endDate
) {}
