package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record BacklogItemRequest(
        @NotBlank String title,
        String description,
        @NotNull BacklogPriority priority,
        @PositiveOrZero BigDecimal estimateDays,
        @NotNull BacklogItemStatus status,
        /** Null keeps the item in the product backlog. */
        Long sprintId
) {}
