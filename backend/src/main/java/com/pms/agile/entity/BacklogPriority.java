package com.pms.agile.entity;

// The four priority levels a backlog item can carry. Kept as an enum (not a free String) so
// "High"/"HIGH"/"high" can't split a filter or colour rule in two; the DB repeats the same four
// names via CHECK chk_backlog_priority (V27).

/**
 * Relative ordering of a backlog item. Stored as text (EnumType.STRING on BacklogItem). Purely a
 * signal for the team — no service treats CRITICAL differently from LOW, and cards are not
 * ordered by priority, only by creation order.
 */
public enum BacklogPriority {
    /** Can wait; slipping it to a later sprint hurts nobody. */
    LOW,
    /** Ordinary work. Used by BacklogItem's builder default. */
    MEDIUM,
    /** Should be taken before the MEDIUM ones. */
    HIGH,
    /** The most urgent level the team can put on a card. */
    CRITICAL
}
