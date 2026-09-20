package com.pms.mission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Body sent when creating or editing a mission (a work trip). Exposes only the fields a user
// may set, unlike the entity, which also carries id, deleted and the audit columns.

/**
 * Body of "create a mission" and "update a mission".
 *
 * Why a `record`: immutable, so nothing can quietly change a field between validation and save.
 * There is no projectId field here: it travels in the URL so ProjectScopeInterceptor (ADR-021)
 * can enforce it before this reaches the service.
 */
public record MissionRequest(
        // Id of the travelling employee. @NotNull avoids a null reaching
        // userRepository.findById(null), which would otherwise throw deep inside Spring Data.
        @NotNull Long userId,

        // Purpose of the trip; @NotBlank refuses null, "" and whitespace-only text.
        @NotBlank String objet,

        // Where the trip takes place. Optional: column accepts NULL (place may be unknown yet).
        String lieu,

        // First day of the trip. LocalDate, not LocalDateTime: a trip is counted in whole days,
        // so no time/timezone should be able to shift it by a day.
        @NotNull LocalDate dateDebut,

        // Last day of the trip. The "end not before start" rule can't be a field annotation
        // (needs both fields), so it lives in MissionService.validateDates() and is mirrored by
        // DB constraint chk_mission_dates (V10).
        @NotNull LocalDate dateFin
) {}
