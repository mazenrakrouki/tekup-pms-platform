package com.pms.workload.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/*
 * API response shape for one declared-workload line: who, which project, which month, days worked,
 * and where it stands in the submit-then-validate cycle. Flattened and hand-picked (not the entity
 * itself) because the entity carries lazy associations and full User rows with password hashes.
 */

/**
 * One declared-workload line, flattened for the workload screen, with its validation state.
 * validatedAt (not a separate boolean) is the single source of truth for "is this approved".
 */
public record ChargeReelleResponse(
        // Needed by PUT/DELETE and PATCH .../validate, and to build the 201 Location header on create.
        Long id,

        // From the lazy project link; lets the screen confirm the row matches the project it opened.
        Long projectId,

        // Sent alongside the id so the table can show a title without an extra call per row.
        String projectCode,

        // Compared client-side with the logged-in user to show/hide edit; the real check is server-side (BR-033).
        Long userId,

        // "FirstName LastName" via User.getFullName(); only the name travels, never email/passwordHash/role.
        String userFullName,

        // Year of the declared month, split from the stored period date.
        Integer year,

        // 1-12, split from period.getMonthValue() (already 1-based, unlike Calendar/JS Date).
        Integer month,

        // Days really worked; BigDecimal keeps the NUMERIC(5,2) precision exactly (0 is a legal value).
        BigDecimal actualDays,

        // Refreshed on every submit/correction, so a validator can tell how fresh the figure is.
        LocalDateTime submittedAt,

        // Null while unapproved; entity's isValidated() is literally validatedAt != null.
        LocalDateTime validatedAt,

        // Null while unapproved; mapper uses a null-safe helper since the validatedBy link is nullable.
        Long validatedById,

        // Validator's full name, sent next to the id as the audit trail for who approved the days.
        String validatedByName
) {}
