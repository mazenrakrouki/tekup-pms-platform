package com.pms.agile.entity;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The closed list of the three states a sprint can be in. An "enum" is a
 *   type whose value can only be one of the names written below.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular agile board (features/agile/agile.component.ts)
 *     -> SprintController   /api/projects/{projectId}/sprints
 *     -> SprintRequest      (the JSON field "status")
 *     -> SprintService      -> Sprint.status
 *     -> column "status" of the table sprints (migration V27).
 *   On the way back, SprintMapper copies it into SprintResponse and the board
 *   receives the plain text "PLANNED", "ACTIVE" or "CLOSED".
 *
 * WHY IT EXISTS
 *   If the status were a free String, a client could send "actif", "Active "
 *   or a typo, and we would save it. The board would then draw a sprint that
 *   matches no filter and the user would think the data is lost. Because the
 *   field is an enum, the JSON reader (Jackson) refuses an unknown value and
 *   the request stops with a 400 answer before anything is written.
 *   The same three names are repeated in the database as the CHECK constraint
 *   chk_sprint_status (V27), so even an SQL script typed by hand cannot put a
 *   fourth state in the table.
 *
 * WHAT THE CODE DOES *NOT* DO WITH THIS VALUE
 *   Nothing in SprintService reacts to the status: a CLOSED sprint can still
 *   be edited, and two sprints of the same project can both be ACTIVE. The
 *   status is declared by the project manager; it is information for the
 *   team, not a lock.
 * =========================================================================
 */

/**
 * Lifecycle of a sprint. Values are persisted as strings.
 *
 * "Persisted as strings" means the name itself is written in the column,
 * because Sprint marks the field with EnumType.STRING. The position of a
 * constant in the list below is therefore never stored, and reordering the
 * three names would not change the meaning of the rows already saved.
 */
public enum SprintStatus {
    /** Prepared but not started. This is the value Sprint uses when the builder sets none. */
    PLANNED,
    /** The team is working inside this time box right now. */
    ACTIVE,
    /** The time box is over. The row stays in the table, so the history of past iterations is kept. */
    CLOSED
}
