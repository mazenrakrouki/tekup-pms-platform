package com.pms.mission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record MissionRequest(
        @NotNull Long userId,
        @NotBlank String objet,
        String lieu,
        @NotNull LocalDate dateDebut,
        @NotNull LocalDate dateFin
) {}
