package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;

import java.time.LocalDate;

public record SprintResponse(
        Long id,
        Long projectId,
        String projectCode,
        String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate,
        SprintStatus status
) {}
