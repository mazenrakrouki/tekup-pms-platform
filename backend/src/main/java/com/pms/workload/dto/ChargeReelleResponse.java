package com.pms.workload.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/*
 * ============================ FILE HEADER ============================
 * WHAT THIS FILE IS
 *   The shape of one declared-workload line as the API sends it back to the browser: who, which
 *   project, which month, how many days really worked, and where that line stands in the
 *   submit-then-validate cycle. It is a DTO (Data Transfer Object): a small read-only object built
 *   only to travel over HTTP, so the ChargeReelle entity never leaves the backend.
 *
 * WHERE IT SITS IN THE FLOW
 *   table charges_reelles -> ChargeReelleRepository -> ChargeReelle entity
 *     -> ChargeReelleMapper.toResponse(...) / toResponseList(...) builds this record. MapStruct
 *        writes that mapper code at compile time from the @Mapping lines in the interface.
 *     -> ChargeReelleService.findByProject(...) returns it, either as a List or wrapped in a
 *        Spring Data Page. The service decides there whether the caller sees the whole team or
 *        only their own lines (BR-062..064): holders of VALIDATE_WORKLOAD or VIEW_ALL_PROJECTS
 *        see everything, everybody else sees only their own rows.
 *     -> submit(), update(), validate() also return this record, so the screen can refresh the
 *        edited row without asking for the list again.
 *     -> WorkloadController returns it as JSON; on a create it also reads id() to build the
 *        Location header of the 201 Created answer.
 *     -> Angular: interface ChargeReelle in core/models/workload.model.ts. The names below must
 *        match that interface exactly, field by field.
 *   The other direction, browser to backend, uses ChargeReelleRequest (sister file in this folder).
 *
 * WHY IT EXISTS (what would break if you deleted it)
 *   - The controller would have to return the ChargeReelle entity. That entity has three
 *     @ManyToOne(fetch = FetchType.LAZY) links: project, user and validatedBy. Serializing it
 *     outside the transaction throws LazyInitializationException; and if the links were loaded,
 *     the JSON would contain two full User rows, including passwordHash and tokenVersion. Both
 *     outcomes are unacceptable, so the API answers with this flat, hand-picked shape instead.
 *   - It is also the contract with the frontend. Renaming a column in the database does not break
 *     Angular as long as the mapper still fills these names.
 *
 * WHAT IS DELIBERATELY ABSENT
 *   No "deleted" flag, no createdAt / updatedAt. Rows are soft-deleted (deleted = true) and the
 *   repository queries already filter them out, so a deleted line never reaches this record.
 *   There is also no boolean "validated": the screen reads it from validatedAt being null or not,
 *   exactly as the entity does in its isValidated() method. Keeping one source of truth avoids a
 *   line where the flag says false and the timestamp says otherwise.
 * =====================================================================
 */

/**
 * One declared-workload line, flattened for the workload screen, with its validation state.
 *
 * <p>Why a record: it is final and has no setters, so once the mapper has built it the values
 * cannot be changed further down the call chain. Java writes the constructor, the accessors
 * ({@code id()}, {@code actualDays()}, ...), {@code equals} and {@code hashCode}, and Jackson
 * turns it into JSON by calling those accessors, so the JSON field names are exactly the names
 * written below.</p>
 *
 * <p>Why this record is longer than PlanChargeResponse: a planned line is simply a figure a project
 * manager writes. A declared line has a life cycle -- somebody submits it, then somebody else
 * approves it -- and the screen must show who did what and when, because once a line is validated
 * the service refuses to edit or delete it.</p>
 *
 * <p>Why year and month are two numbers here while the database stores one date: the entity keeps
 * a {@code LocalDate period} always set to the 1st of the month, and the mapper splits it back with
 * {@code getPeriod().getYear()} and {@code getPeriod().getMonthValue()}. This way the JSON carries
 * no fake day number that the screen would have to hide, and the shape matches
 * ChargeReelleRequest, which also speaks in year + month.</p>
 */
public record ChargeReelleResponse(
        // WHAT: primary key of the row in charges_reelles.
        // WHY it is exposed: every later call needs it -- PUT, DELETE and above all
        //   PATCH /charges-reelles/{id}/validate end with this id, and
        //   WorkloadController.submitCharge calls created.id() to build the Location header of
        //   the 201 answer.
        // WITHOUT IT: a project manager could never approve a line, because the approve button
        //   would have no way to name the row it is approving.
        Long id,

        // WHAT: id of the project this declared line belongs to.
        //   Filled by the mapper from project.id, which reads the lazy @ManyToOne link.
        // WHY: the screen uses it to check that the row it received really belongs to the project
        //   currently open, and to rebuild the scoped URL /api/projects/{projectId}/charges-reelles.
        Long projectId,

        // WHAT: the short human code of the project (for example "PRJ-2026-014").
        // WHY it travels next to the id: the id alone means nothing to a reader. Sending the code
        //   here saves one HTTP call per row.
        // WITHOUT IT: a workload list of 20 rows would need 20 extra calls to /api/projects/{id}
        //   just to print a title, or the table would show bare numbers.
        String projectCode,

        // WHAT: id of the person who worked those days.
        // WHY: the frontend compares it with the logged-in user to decide whether to show the edit
        //   button, which matches BR-033 on the server side (a developer may only touch their own
        //   lines). The check on the screen is only comfort; the real refusal happens in
        //   ChargeReelleService.assertOwnership.
        Long userId,

        // WHAT: "FirstName LastName" of that person, produced by User.getFullName() through the
        //   mapper helper annotated @Named("fullName").
        // WHY that helper exists rather than a plain source mapping: it returns null when the user
        //   object is null instead of calling getFullName() on nothing.
        // WHY only the name is sent: the User entity also carries email, passwordHash, tokenVersion
        //   and the role. None of that belongs in a workload table, and the password hash must
        //   never leave the server at all.
        String userFullName,

        // WHAT: the year of the declared month, computed by the mapper from period.getYear().
        Integer year,

        // WHAT: the declared month, 1 for January up to 12 for December, computed from
        //   period.getMonthValue().
        // NOTE for the reader: getMonthValue() returns 1..12, unlike java.util.Calendar and unlike
        //   the JavaScript Date object, which both count months from 0. Getting this wrong would
        //   shift every line by one month on the screen.
        Integer month,

        // WHAT: the number of working days really spent, for example 7.50. Zero is a legal value.
        // WHY BigDecimal and not double: the column is NUMERIC(5,2) and BigDecimal keeps the exact
        //   decimal value and its scale, so 7.50 stays 7.50. With double the same value could be
        //   serialized as 7.499999999999999 and the table would show a figure the user never typed.
        BigDecimal actualDays,

        // WHAT: the moment the line was last submitted or corrected. The service sets it with
        //   LocalDateTime.now() in submit() AND again in update().
        // WHY it is refreshed on every correction: it answers "how fresh is this declaration?".
        //   A line corrected today should not still show last month's date, or a validator would
        //   approve a figure believing it is the one they read before.
        // NOTE: the column is nullable in the database, so this may be null in theory; in practice
        //   every line written by the service carries a value.
        LocalDateTime submittedAt,

        // WHAT: the moment a validator approved the line, or null while nobody has approved it.
        // WHY null carries the meaning instead of a separate boolean: the entity method
        //   isValidated() is literally "validatedAt != null", and the service refuses to update or
        //   delete a line once it is set. One field, one truth.
        // WITHOUT IT: the screen could not grey out an approved line, and a developer would try to
        //   correct a month that the server will refuse with BusinessRuleException, which looks
        //   like a bug to the user.
        LocalDateTime validatedAt,

        // WHAT: id of the user who approved the line, or null while it is not approved.
        // WHY the mapper uses the helper annotated @Named("userId") here: the validatedBy link is
        //   nullable, so a plain "source = validatedBy.id" mapping would call getId() on null.
        //   The helper returns null instead.
        // WITHOUT THAT NULL GUARD: listing a project would throw NullPointerException as soon as
        //   one single line is still waiting for approval -- that is, almost always.
        Long validatedById,

        // WHAT: "FirstName LastName" of the validator, or null while it is not approved.
        // WHY it is sent next to the id: the workload table must show "approved by Sarah Ben Ali"
        //   without a second HTTP call. It is the audit trail the jury asks about: who signed off
        //   on the days that will be billed.
        String validatedByName
) {}
