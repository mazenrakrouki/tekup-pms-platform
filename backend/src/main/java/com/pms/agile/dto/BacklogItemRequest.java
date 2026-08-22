package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BacklogItemRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @NotNull BacklogPriority priority,
        /** Estimation en jours-homme. Facultative : un élément non estimé reste dans le backlog. */
        @DecimalMin("0.0") @Digits(integer = 4, fraction = 2) BigDecimal estimateDays,
        @NotNull BacklogItemStatus status,
        /** {@code null} = backlog produit, non engagé dans un sprint. */
        Long sprintId
) {}
