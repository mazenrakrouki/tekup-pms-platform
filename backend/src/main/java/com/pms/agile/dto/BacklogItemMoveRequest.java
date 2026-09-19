package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import jakarta.validation.constraints.NotNull;

/* ============================================================================
 * FILE: BacklogItemMoveRequest
 *
 * WHAT THIS FILE IS
 *   The small JSON body sent when someone drags a card on the agile board.
 *   It is a DTO ("Data Transfer Object"): a plain object whose only job is to
 *   carry data between the browser and the server. It is never stored; it is
 *   not a database row.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser (drag and drop a card)
 *     -> PATCH /api/projects/{projectId}/backlog/{id}/move
 *     -> BacklogItemController.move(...) turns the JSON into this record
 *        (@Valid checks it first)
 *     -> BacklogItemService.move(...) reads status() and sprintId()
 *     -> the BacklogItem entity is updated and saved
 *     -> BacklogItemMapper builds a BacklogItemResponse, which is the JSON
 *        the browser gets back.
 *
 * WHY IT EXISTS
 *   Delete it, and the board would have to reuse BacklogItemRequest, which
 *   carries the whole item (title, description, priority, estimate, assignee).
 *   But a drag only knows two things: the new column, and the new sprint.
 *   Sending the whole item would force the browser to send back a copy of
 *   fields it never touched, and that copy can be out of date. Example: a
 *   colleague fixes a typo in the title while my board tab stays open; if my
 *   drag sent the full item, my old title would silently overwrite his fix.
 *   With this record that cannot happen, because the title is simply not part
 *   of the message.
 * ============================================================================
 */

/**
 * Board move: reassign an item to a sprint and/or change its column.
 * A null sprintId returns the item to the product backlog.
 *
 * <p>WHY A RECORD AND NOT A NORMAL CLASS: a Java record is a short way to
 * declare an immutable data holder. The compiler writes the constructor, the
 * accessors (you call {@code status()}, not {@code getStatus()}) and
 * equals/hashCode/toString for you. "Immutable" means there is no setter, so
 * nothing can quietly change the request after Spring has validated it. Example
 * of what that prevents: some filter running between the validation step and
 * the service could not swap sprintId for a sprint of another project.
 *
 * <p>WHY THERE IS NO id AND NO projectId FIELD: both already travel in the URL
 * (/api/projects/{projectId}/backlog/{id}/move) and the controller reads them
 * with @PathVariable. Keeping them out of the body is a security choice tied to
 * ADR-021: ProjectScopeInterceptor checks the project id it finds in the URL.
 * If the body could carry a different projectId, the scope check would approve
 * project 7 while the write actually landed in project 12.
 */
public record BacklogItemMoveRequest(
        // WHAT: the sprint the card is dropped into, or null.
        // WHY nullable: here null is a real, wanted value. It means "send this
        // item back to the product backlog", which is the normal way to pull
        // work out of a sprint that is running late.
        // WITHOUT a nullable field: the board would need a second, separate
        // endpoint whose only job is to detach an item, for no gain.
        // NOTE: this value is never trusted as it arrives. BacklogItemService
        // .resolveSprint reloads the sprint and refuses it if it belongs to a
        // different project, so guessing an id cannot move work across projects.
        Long sprintId,

        // WHAT: @NotNull tells Spring to reject the request when "status" is
        // missing or null in the JSON. The check runs because the controller
        // parameter is marked @Valid, so it happens before any line of the
        // service runs.
        // WHY: the target column is the whole point of a move, and the database
        // column backlog_items.status is NOT NULL and carries a CHECK limiting
        // it to ('TODO','IN_PROGRESS','DONE') (migration V27).
        // WITHOUT it: a body such as {"sprintId":3} would reach
        // BacklogItemService.move, which calls item.setStatus(null), and the
        // save would fail deep inside PostgreSQL. The user would see a 500
        // server error instead of a clean 400 saying "status is required".
        @NotNull BacklogItemStatus status
) {}
