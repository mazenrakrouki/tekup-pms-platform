package com.pms.mission.dto;

import java.time.LocalDate;

public record MissionResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long userId,
        String userFullName,
        String objet,
        String lieu,
        LocalDate dateDebut,
        LocalDate dateFin
) {}
