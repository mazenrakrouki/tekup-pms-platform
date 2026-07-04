package com.pms.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record TeamAssignmentRequest(
        @NotNull Long userId,
        @NotBlank @Size(max = 50) String roleInTeam,
        @NotNull LocalDate startDate,
        LocalDate endDate
) {}
