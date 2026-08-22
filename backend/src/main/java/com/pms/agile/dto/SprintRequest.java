package com.pms.agile.dto;

import com.pms.agile.entity.SprintStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SprintRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String goal,
        LocalDate startDate,
        LocalDate endDate,
        @NotNull SprintStatus status
) {}
