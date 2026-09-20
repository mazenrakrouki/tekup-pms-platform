package com.pms.agile.entity;

// The three columns of the agile board. Kept as an enum (not a free String) so an unknown value
// is rejected with a 400 at the JSON boundary instead of saving a card that belongs to no column;
// the DB repeats the same three names via CHECK chk_backlog_status (V27).

/**
 * Column of the board an item currently sits in. Stored as text (EnumType.STRING on
 * BacklogItem), so reordering these constants never changes the meaning of saved rows.
 */
public enum BacklogItemStatus {
    /** Not started yet. Used by BacklogItem's builder default. */
    TODO,
    /** Somebody is working on it now. */
    IN_PROGRESS,
    /** Finished. The row stays so a closed sprint keeps what it actually delivered. */
    DONE
}
