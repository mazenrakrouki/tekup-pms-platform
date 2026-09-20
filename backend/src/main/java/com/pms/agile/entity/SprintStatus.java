package com.pms.agile.entity;

// The three states a sprint can be in. Kept as an enum (not a free String) so a typo like
// "actif" is rejected with 400 instead of saved as an unmatched value; the DB repeats the same
// three names via CHECK chk_sprint_status (V27). Purely informational: nothing in SprintService
// reacts to the status — a CLOSED sprint can still be edited, and two sprints can both be ACTIVE.

/**
 * Lifecycle of a sprint. Stored as text (EnumType.STRING on Sprint), so reordering these
 * constants never changes the meaning of saved rows.
 */
public enum SprintStatus {
    /** Prepared but not started. Used by Sprint's builder default. */
    PLANNED,
    /** The team is working inside this time box right now. */
    ACTIVE,
    /** Over. The row stays so the history of past iterations is kept. */
    CLOSED
}
