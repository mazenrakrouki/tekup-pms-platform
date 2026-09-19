package com.pms.agile.entity;

/*
 * =========================================================================
 * WHAT THIS FILE IS
 *   The closed list of the three columns of the agile board. One backlog item
 *   (one card) is always in exactly one of them.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular agile board (features/agile/agile.component.ts)
 *     -> BacklogItemController  PATCH /api/projects/{projectId}/backlog/{id}/move
 *        (or POST / PUT for a full create or update)
 *     -> BacklogItemMoveRequest / BacklogItemRequest  (the JSON field "status")
 *     -> BacklogItemService.move()  -> BacklogItem.status
 *     -> column "status" of the table backlog_items (migration V27).
 *   On the way back, BacklogItemMapper copies it into BacklogItemResponse and
 *   the board receives the plain text "TODO", "IN_PROGRESS" or "DONE".
 *
 * WHY IT EXISTS
 *   If the status were a free String, a client could send "in progres" or
 *   "Done " with a space and we would save it. The board would then draw a
 *   card that belongs to no column: the user would see the card disappear
 *   while the row is still in the database. Because the field is an enum, the
 *   JSON reader (Jackson) refuses an unknown value and the request stops with
 *   a 400 answer before anything is written.
 *   The same three names are repeated in the database as the CHECK constraint
 *   chk_backlog_status (V27), so even an SQL script typed by hand cannot put a
 *   fourth column in the table.
 *
 * WHY THREE AND ONLY THREE
 *   The three names are the board itself: the front end draws one column per
 *   value. Adding a value here is therefore also a UI change plus a new
 *   migration to widen chk_backlog_status - the two must be done together, or
 *   the database would refuse a state the screen offers.
 * =========================================================================
 */

/**
 * Column of the board an item currently sits in.
 *
 * The value is stored as text, because BacklogItem marks the field with
 * EnumType.STRING. The position of a constant in the list below is never
 * written to the database, so reordering the three names would not change the
 * meaning of the rows already saved.
 */
public enum BacklogItemStatus {
    /** Not started yet. This is the value BacklogItem uses when the builder sets none. */
    TODO,
    /** Somebody is working on it now. */
    IN_PROGRESS,
    /**
     * The work is finished. The row stays where it is: no code archives or
     * removes a finished card, so a closed sprint keeps the list of what it
     * really delivered.
     */
    DONE
}
