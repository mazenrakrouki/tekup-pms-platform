package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/* ============================================================================
 * FILE: BacklogItemRequest
 *
 * WHAT THIS FILE IS
 *   The JSON body a client sends to create or to fully update one product
 *   backlog item (a "card" on the agile board). It is a DTO ("Data Transfer
 *   Object"): a plain object that only carries data in from the browser. It is
 *   never saved as such; the service copies its values onto the BacklogItem
 *   entity.
 *
 * WHERE IT SITS IN THE FLOW
 *   Browser form (new card / edit card)
 *     -> POST  /api/projects/{projectId}/backlog       (create)
 *        PUT   /api/projects/{projectId}/backlog/{id}  (update)
 *     -> BacklogItemController binds the JSON into this record and, because the
 *        parameter is marked @Valid, runs every rule written below. A broken
 *        body stops here with HTTP 400.
 *     -> BacklogItemService.create / .update read the accessors, resolve
 *        sprintId and assigneeId into real entities, and save.
 *     -> BacklogItemMapper returns a BacklogItemResponse to the browser.
 *
 * WHY IT EXISTS
 *   Two reasons, and both break if you delete it.
 *   1. Safety of what comes in. The BacklogItem entity has fields the client
 *      must never set: id, createdAt, createdBy, and the soft-delete flag
 *      "deleted" it inherits from BaseEntity. If the controller bound JSON
 *      straight onto the entity, a crafted body {"deleted":true} or
 *      {"id":999} would let a user erase or overwrite another row. This record
 *      only contains fields a user is allowed to send, so those attacks have
 *      nowhere to land.
 *   2. A stable contract. The entity follows the database; this record follows
 *      the screen. Renaming a column later does not break the browser.
 *
 * RELATION TO ITS NEIGHBOURS IN THIS FOLDER
 *   - BacklogItemMoveRequest is the tiny version of this record, used for drag
 *     and drop (column + sprint only).
 *   - BacklogItemResponse is what comes back out, and it is NOT the same shape:
 *     it adds the readable projectCode, sprintName and assigneeName.
 * ============================================================================
 */

/**
 * One product backlog item as the client sends it, for create and for full
 * update. Both endpoints use the same record on purpose: a create and a full
 * update accept exactly the same fields, so two near-identical records would
 * drift apart over time and one of them would end up missing a rule.
 *
 * <p>WHY A RECORD: a Java record is a short, immutable data holder. The
 * compiler writes the constructor, the accessors ({@code title()}, not
 * {@code getTitle()}) and equals/hashCode/toString. Immutable means no setter
 * exists, so the values the validator approved are the exact values the service
 * later reads.
 *
 * <p>WHY THERE IS NO projectId FIELD: the project comes from the URL
 * (/api/projects/{projectId}/backlog). This matters for ADR-021:
 * ProjectScopeInterceptor checks the project id found in the URL, and only
 * that one. If the body carried its own projectId, the interceptor could
 * approve access to project 7 while the row was written into project 12.
 */
public record BacklogItemRequest(
        // WHAT: @NotBlank refuses null, the empty string "" and a string made
        // only of spaces such as "   ".
        // WHY: the title is the only text shown on the card, and
        // backlog_items.title is NOT NULL in migration V27.
        // WITHOUT it: @NotNull alone would still accept "   ", and the board
        // would display an empty card that nobody can identify or search for.
        @NotBlank String title,

        // Free text, optional on purpose: a card can be created quickly during
        // a planning meeting and described later. No annotation here means null
        // and "" are both accepted, which matches the nullable column
        // backlog_items.description.
        String description,

        // WHAT: @NotNull refuses a missing or null "priority".
        // WHY: BacklogItemService.create copies this value straight into the
        // builder, so the default MEDIUM declared on the entity is overwritten
        // even when the value is null; and the column is NOT NULL with a CHECK
        // limited to ('LOW','MEDIUM','HIGH','CRITICAL').
        // WITHOUT it: a body with no priority would reach the database as NULL
        // and PostgreSQL would refuse it, so the user would get a 500 server
        // error instead of a clear 400 message.
        @NotNull BacklogPriority priority,

        // WHAT: the effort of the card in man-days. @PositiveOrZero rejects a
        // negative number, accepts 0, and accepts null (Bean Validation always
        // treats null as valid; only @NotNull rejects null).
        // WHY it mirrors the database exactly: migration V27 declares
        // estimate_days NUMERIC(6,2) plus the constraint chk_backlog_estimate
        // (estimate_days IS NULL OR estimate_days >= 0). The annotation catches
        // the same mistake earlier and with a readable message.
        // WITHOUT it: an estimate of -5 days would be caught only by PostgreSQL
        // as a 500 error, and if that CHECK were ever dropped, a negative
        // estimate would quietly shrink the total effort of the sprint.
        // WHY BigDecimal AND NOT double: double stores numbers in binary, so
        // 0.1 cannot be held exactly. Adding ten cards of 0.1 day with double
        // gives 0.9999999999999999, and the sprint total would read 0.99 day
        // instead of 1. BigDecimal keeps decimal digits exactly, which is also
        // what NUMERIC(6,2) does on the database side.
        @PositiveOrZero BigDecimal estimateDays,

        // WHAT: @NotNull refuses a missing or null "status" (the board column).
        // WHY: same reasoning as priority. The service writes the value as it
        // arrives, and backlog_items.status is NOT NULL with a CHECK limited to
        // ('TODO','IN_PROGRESS','DONE').
        // WITHOUT it: a card created with no status would be refused by the
        // database, and if it ever got through it would belong to no column of
        // the board and would simply never be drawn.
        @NotNull BacklogItemStatus status,

        /**
         * Null keeps the item in the product backlog. That is a normal state,
         * not an error: work is written down before it is committed to a
         * sprint.
         *
         * <p>The number is never trusted as it arrives.
         * BacklogItemService.resolveSprint reloads the sprint and throws
         * NotFoundException when it belongs to another project. Without that
         * check, guessing a number here would attach one project's card to
         * another project's sprint, and that second board would start showing
         * work that is not its own.
         */
        Long sprintId,

        /**
         * Null leaves the item unassigned; otherwise it must be a member of the
         * project team. Unassigned is a normal state too: a card can be planned
         * into a sprint before anyone picks it up.
         *
         * <p>BacklogItemService.resolveAssignee enforces the team rule through
         * TeamAssignmentRepository and throws BusinessRuleException otherwise.
         * Without that check, a project manager could hand work to somebody who
         * never joined the project, and that person would carry a card on a
         * board they are not even allowed to open.
         */
        Long assigneeId
) {}
