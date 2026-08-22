package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;

import java.math.BigDecimal;

public record BacklogItemResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long sprintId,
        String sprintName,
        String title,
        String description,
        BacklogPriority priority,
        BigDecimal estimateDays,
        BacklogItemStatus status
) {}
