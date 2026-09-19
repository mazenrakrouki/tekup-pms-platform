package com.pms.kpi.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE IS
 * ═══════════════════════════════════════════════════════════════════════════════
 * The input object of the monthly project review: the three values the CdP ("chef
 * de projet", the project manager) types in the form before the application takes
 * a KPI snapshot. "DTO" (Data Transfer Object) means a plain box of values whose
 * only job is to carry data across the network; it holds no rule and no behaviour.
 *
 * It is the mirror image of KpiResponse, which lives next to it in this package:
 * KpiResponse is what the server SENDS, SnapshotRequest is what the browser MAY
 * SEND.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHERE IT SITS IN THE FLOW
 * ═══════════════════════════════════════════════════════════════════════════════
 *   Angular form (features/kpi/kpi.component.ts, shape declared in
 *   core/models/kpi.model.ts as the SnapshotRequest interface)
 *     -> POST /api/projects/{projectId}/kpi/snapshots, JSON body
 *     -> KpiController.createSnapshot(), whose parameter is annotated
 *        @Valid @RequestBody(required = false) — @Valid runs the three checks
 *        written below BEFORE any business code starts
 *     -> KpiService.createSnapshot(), which reads these three values, computes
 *        every other indicator itself, and saves one SnapshotKpi row
 *     -> the saved row comes back to the browser as a KpiResponse.
 *
 * Two guards protect that endpoint, and neither of them is written in this file:
 *   - the permission: @PreAuthorize("hasAuthority('EDIT_PROJECT')") sits on the
 *     SERVICE method KpiService.createSnapshot(), not on the controller;
 *   - the project scope (ADR-021): ProjectScopeInterceptor matches the URL
 *     /api/projects/{id}/** and checks that this user may act on THIS project.
 *     Holding the permission is not enough on its own.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHY IT EXISTS (what would break if it were deleted)
 * ═══════════════════════════════════════════════════════════════════════════════
 * The obvious alternative would be to accept a KpiResponse as the request body and
 * store it. That would be a serious hole: the caller could then post his own
 * margin, his own consumed budget, his own EAC, and the "frozen evidence" row of
 * the monthly review would contain numbers invented by the browser. This small
 * record is the list of the ONLY three things a human is allowed to state; every
 * other figure of the snapshot is recomputed on the server from the database.
 *
 * The three components are all optional (nullable), and that is intentional: the
 * controller declares the body itself as required = false, so a plain POST with no
 * body at all is valid. In that case KpiService reuses the EV of the previous
 * snapshot. Reason: freezing today's figures must stay possible even when the CdP
 * has nothing new to declare. This is also why no component carries @NotNull —
 * and note that @DecimalMin, @DecimalMax and @Size all accept null by design, so
 * an absent value passes the checks instead of being rejected.
 */
/**
 * Monthly input of the CdP during the project review
 * (F-AFF-13, sheet "Situation actuelle").
 *
 * <p>Written as a Java record, so the object is immutable: once Jackson has built it
 * from the JSON body, nothing in the application can change what the user sent.
 * An input object that could be modified on the way would make the stored snapshot
 * impossible to defend, because the value checked by the validator would no longer
 * be the value that got saved.</p>
 */
public record SnapshotRequest(
        // WHAT: the earned-value progress of the project, a percentage from 0 to 100 (for example
        //      35.50 for 35.5 %). @DecimalMin and @DecimalMax refuse anything outside that range,
        //      and Spring answers HTTP 400 before KpiService is even called.
        //      The bounds are written as text ("0.0", "100.0") rather than as numbers on purpose:
        //      the annotation parses them into an exact BigDecimal, while a double literal could
        //      not hold the value exactly.
        // WHY the range check matters: this number multiplies the whole contract amount inside the
        //      engine (CA production = budget × EV ÷ 100).
        // WITHOUT IT: a CdP typing 1000 instead of 100 would produce a production revenue ten
        //      times the contract, a fake margin, a fake FAE, and the mistake would be frozen into
        //      a snapshot row that the direction reads as evidence.
        // WHY it is typed by a human at all: hours burnt are not progress, so no formula in the
        //      database can produce this figure.
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal evPct,
        // WHAT: the end date the CdP now expects for the project. Sent as an ISO date string
        //      ("2026-11-30") and bound to a LocalDate, which is a date without time and without
        //      time zone.
        // WHY LocalDate and not a full timestamp: a review states a day, not an instant. A
        //      timestamp would drag a time zone along, and the same review date could be displayed
        //      as the day before for a reader in another country.
        // WHY no validation here: a forecast can legitimately be in the past (a late project) or
        //      far in the future, so any bound would reject a truthful answer.
        LocalDate dateFinEstimee,
        // WHAT: "faits marquants" = the free text of the review (what happened, what explains the
        //      figures). @Size(max = 2000) caps it at 2000 characters, the exact length of the
        //      column that stores it (SnapshotKpi.faitsMarquants, length = 2000).
        // WHY the two limits must match: the check has to fail on the side that can explain itself.
        // WITHOUT IT: a longer text would travel all the way to PostgreSQL, the insert would be
        //      rejected there, and the user would get an opaque HTTP 500 instead of a clean
        //      400 telling him which field is too long.
        @Size(max = 2000) String faitsMarquants
) {}
