package com.pms.agile.dto;

import com.pms.agile.entity.BacklogItemStatus;
import com.pms.agile.entity.BacklogPriority;

import java.math.BigDecimal;

/* ============================================================================
 * FILE: BacklogItemResponse
 *
 * WHAT THIS FILE IS
 *   The JSON one backlog card looks like when it leaves the server. It is the
 *   "read" side of this folder: BacklogItemRequest comes in, this goes out.
 *
 * WHERE IT SITS IN THE FLOW
 *   BacklogItemRepository loads BacklogItem entities
 *     -> BacklogItemService (findByProject, create, update, move) hands them to
 *     -> BacklogItemMapper.toResponse / .toResponseList, which fills this record
 *        (MapStruct writes that copy code at compile time)
 *     -> BacklogItemController returns it
 *     -> Jackson turns it into the JSON the board draws.
 *
 * WHY IT EXISTS - THE IMPORTANT POINT
 *   Delete it and the natural move is to return the BacklogItem entity itself.
 *   That breaks two things at once.
 *   1. A data leak. BacklogItem points at a User (the assignee), and User holds
 *      passwordHash. Jackson serialises every getter it finds, so the password
 *      hash of the assignee would be sent to every person who opens the board.
 *      This record has no such field, so nothing can leak through it.
 *   2. A crash. project, sprint and assignee are declared
 *      @ManyToOne(fetch = FetchType.LAZY) on the entity, which means Hibernate
 *      hands back a stand-in object and loads the real row only when it is
 *      touched. Jackson touches it after the transaction is closed, which
 *      throws LazyInitializationException and gives the user a 500.
 *   So this record is not "extra plumbing": it is what makes the read path both
 *   safe and stable.
 * ============================================================================
 */

/**
 * One backlog card, flattened for the screen.
 *
 * <p>WHY IT IS FLAT AND NOT NESTED: the record repeats the readable label next
 * to each foreign key (projectCode beside projectId, sprintName beside
 * sprintId, assigneeName beside assigneeId). A nested object would carry whole
 * sub-documents the board does not need, and sending ids alone would force the
 * browser to call the server again for every card just to turn "3" into
 * "Sprint 3". On a board of 60 cards that is 180 extra HTTP calls for text that
 * the server already had in memory.
 *
 * <p>WHY IT IS A RECORD: it is read-only data on its way out. A record gives
 * immutable fields and short accessors ({@code id()}, not {@code getId()}),
 * which is why BacklogItemController writes {@code created.id()} when it builds
 * the Location header of a 201 response.
 *
 * <p>WHAT IS DELIBERATELY ABSENT: the audit columns of BaseEntity (createdAt,
 * createdBy, updatedAt, updatedBy) and the soft-delete flag "deleted". The
 * board never shows them, and the repository queries already filter on
 * {@code deleted = false}, so exposing the flag would only invite a client to
 * build logic on a column that is a server-side concern.
 */
public record BacklogItemResponse(
        // Database key of the card. The board uses it for every follow-up call
        // (PUT, PATCH /move, DELETE), and BacklogItemController reads it back
        // from here to build the Location header after a create.
        Long id,

        // Owning project. Filled by the mapper from project.id.
        // WHY it is still sent although the browser already put it in the URL:
        // the board keeps cards in memory, and a card that carries its own
        // project id can be checked, grouped or logged without guessing which
        // request it came from.
        Long projectId,

        // Human-readable project reference (for example "S2I-2026-014"), taken
        // from project.code by the mapper.
        // WHY: a screen or an export must show something a person recognises.
        // WITHOUT it the board would print "project 42", which means nothing to
        // a project manager reading a printed sprint report.
        String projectCode,

        // Sprint the card is committed to, or null when it sits in the product
        // backlog.
        // WHY null is expected here: sprint_id is nullable in migration V27 and
        // the mapper navigates sprint.id safely, so an uncommitted card maps to
        // null instead of crashing.
        // WITHOUT accepting null: every card not yet planned would throw during
        // mapping, and the product backlog column of the board could never be
        // displayed at all.
        Long sprintId,

        // Name of that sprint, or null for the same reason as above.
        // WHY: so the card can print "Sprint 4 - Reporting" without a second
        // request per card.
        String sprintName,

        // Short text shown on the card.
        String title,

        // Long text shown when the card is opened. Null when nobody wrote one.
        String description,

        // LOW / MEDIUM / HIGH / CRITICAL. Sent as the enum name, so the JSON
        // reads "HIGH" and not the position 2.
        // WHY that matters: the position would change silently if a value were
        // ever inserted in the middle of the enum, and every saved dashboard or
        // front-end filter built on numbers would start pointing at the wrong
        // priority. The database stores the name too (VARCHAR with a CHECK), so
        // JSON, Java and SQL all use the same word.
        BacklogPriority priority,

        // Effort in man-days, or null when nobody estimated the card yet.
        // WHY BigDecimal: it keeps decimal digits exactly. Summing ten cards of
        // 0.1 day with a double would give 0.9999999999999999 and the sprint
        // total would be shown as 0.99 day instead of 1.
        BigDecimal estimateDays,

        // TODO / IN_PROGRESS / DONE: which column of the board draws this card.
        BacklogItemStatus status,

        // The team member doing the work, or null when nobody picked the card
        // up. Filled by the mapper from assignee.id.
        // WHY the id is sent next to the name: the board filters "my cards" by
        // comparing this id with the connected user. Comparing names would
        // break the moment two people share a name.
        Long assigneeId,

        // Display name of that person, or null when unassigned.
        // NOTE: there is no full_name column in the database. User.getFullName()
        // joins firstName and lastName, and the mapper reaches that method with
        // @Mapping(target = "assigneeName", source = "assignee.fullName").
        // WHY it is exposed instead of the whole User: sending the User object
        // would also send its email and its password hash to everyone who can
        // open the board. One string is all the screen needs.
        String assigneeName
) {}
