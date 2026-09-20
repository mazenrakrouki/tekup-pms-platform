package com.pms.mission.dto;

import java.time.LocalDate;

// Read side of MissionRequest: the JSON sent back for one mission. Kept flat (ids + one label)
// instead of nested Project/User objects, which would risk LazyInitializationException and leak
// fields like the password hash, and would let the response follow Mission -> Project -> ...

/**
 * One mission as the browser sees it.
 *
 * Why a `record`: read-only, so nothing between the mapper and the JSON writer can change a
 * value. Cost lines are deliberately not included here; they are a separate call to
 * GET .../missions/{missionId}/composantes, answered with ComposanteResponse.
 */
public record MissionResponse(
        // Primary key of the row in `missions`.
        Long id,

        // Id of the owning project, from project.id, so the nested Project object never leaks.
        Long projectId,

        // Short business code of the project (e.g. "PRJ-2025-001"), for display without a
        // second HTTP call.
        String projectCode,

        // Id of the travelling employee, from user.id (not the User object).
        Long userId,

        // Traveller's display name, built by MissionMapper's fullName() helper, which returns
        // null instead of throwing when the user row is missing.
        String userFullName,

        // Purpose of the trip, copied from MissionRequest.
        String objet,

        // Where the trip takes place. May be null.
        String lieu,

        // First day of the trip. LocalDate: Jackson writes plain "2026-05-10", no time/timezone.
        LocalDate dateDebut,

        // Last day of the trip. dateFin >= dateDebut is already enforced on the way in.
        LocalDate dateFin
) {}
