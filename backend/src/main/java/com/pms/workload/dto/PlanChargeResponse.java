package com.pms.workload.dto;

import java.math.BigDecimal;

/*
 * API response shape for one planned-workload line: who, which project, which month, planned days.
 * Flattened and hand-picked (not the entity itself) because the entity carries lazy associations
 * and a full User row with a password hash.
 */

/**
 * One planned-workload line, flattened for the workload screen. Flat rather than nested
 * project/user objects, since the table only needs an id and a label per row.
 */
public record PlanChargeResponse(
        // Needed by PUT/DELETE .../plan-charges/{id}, and to build the 201 Location header on create.
        Long id,

        // From the lazy project link; lets the screen confirm the row matches the project it opened.
        Long projectId,

        // Sent alongside the id so the table can show a title without an extra call per row.
        String projectCode,

        // Echoed back inside PlanChargeRequest on edit, where the service checks it matches the stored row.
        Long userId,

        // "FirstName LastName" via User.getFullName(); only the name travels, never email/passwordHash/role.
        String userFullName,

        // Year of the planned month, split from the stored period date.
        Integer year,

        // 1-12, split from period.getMonthValue() (already 1-based, unlike Calendar/JS Date).
        Integer month,

        // Planned days; BigDecimal keeps the NUMERIC(5,2) precision exactly.
        BigDecimal plannedDays
) {}
