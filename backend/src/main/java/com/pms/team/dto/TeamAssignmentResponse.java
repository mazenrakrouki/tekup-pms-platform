package com.pms.team.dto;

import java.time.LocalDate;

public record TeamAssignmentResponse(
        Long id,
        Long projectId,
        String projectCode,
        String projectName,
        Long userId,
        String userFullName,
        String roleInTeam,
        LocalDate startDate,
        LocalDate endDate
) {}
