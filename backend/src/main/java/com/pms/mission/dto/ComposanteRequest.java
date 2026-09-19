package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * The shape of the JSON body sent when someone adds or edits one cost line of a
 * mission. A "composante" is one cost component of a work trip: the per diem
 * (daily allowance), the plane or train ticket, the fiscal stamp, local
 * transport, or the stay. It is a DTO (Data Transfer Object): an object whose
 * only job is to carry data from the browser to the server.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular missions screen (missions.component.ts, composanteForm)
 *     -> POST /api/projects/{projectId}/missions/{missionId}/composantes
 *        or  PUT  .../composantes/{id}
 *     -> MissionController.createComposante / updateComposante, which marks the
 *        body @Valid, so the rules below run before our own code.
 *     -> ComposanteService.create / update. That service first loads the
 *        mission and checks it really belongs to {projectId}, then builds a
 *        ComposanteMission entity from these four values.
 *     -> ComposanteMissionRepository.save -> row in table `composantes_mission`
 *        (migration V10).
 *   The answer sent back is a different object: ComposanteResponse.
 *
 * WHY IT EXISTS
 * If the endpoint accepted the ComposanteMission entity, the caller could set
 * `mission` himself and attach a cost line to a trip of another project, which
 * would go around the scope check of ADR-021. Here the mission is taken only
 * from the URL. The caller can also not touch `id`, `deleted` or the audit
 * columns. Delete this file and both protections disappear.
 *
 * WHY THERE IS NO missionId FIELD
 * Same reason as MissionRequest: projectId and missionId travel in the URL.
 * ProjectScopeInterceptor (ADR-021) matches /api/projects/{id}/** and refuses
 * the request with 403 when the project is outside the user's scope, before the
 * controller runs. A body field would be invisible to that interceptor.
 * ============================================================================
 */

/**
 * Body of "add a cost line to a mission" and "edit a cost line".
 *
 * Gives back: nothing by itself, it is pure data read by ComposanteService.
 *
 * Why a `record`: it is immutable, so the amount that Bean Validation accepted
 * is exactly the amount written to the database. On money this matters: with a
 * mutable class, any code running between the check and the save could change
 * the value and the @Positive check would no longer mean anything.
 *
 * Note: there is no @Size on `description` even though the column is
 * VARCHAR(500), so a text that is too long is only stopped by PostgreSQL, as a
 * 500 error instead of a clear 400.
 */
public record ComposanteRequest(
        // WHAT: which kind of cost this line is. TypeComposante is an enum with
        //       exactly five values: PERDIEM (daily allowance), BILLET
        //       (ticket), TIMBRE (fiscal stamp), TRANSPORT, SEJOUR (stay).
        // WHY AN ENUM AND NOT A String: Jackson refuses any other word and
        //       answers 400 before our code runs, and the database repeats the
        //       same list in CHECK constraint chk_comp_type (V10).
        // WITHOUT IT: someone could save the type "HOTEL"; it would not match
        //       any of the five rows the UI knows how to display or group, and
        //       that cost would silently disappear from the mission summary.
        // WHY @NotNull ON TOP: the enum still has to be present. A body without
        //       "typeComposante" would otherwise reach the builder with null
        //       and be refused only by the NOT NULL column, as a 500 error.
        @NotNull TypeComposante typeComposante,

        // WHAT: the amount of this cost line.
        // WHY BigDecimal AND NOT double: BigDecimal keeps exact decimals.
        //       A double cannot hold 0.10 exactly, so adding many trip costs
        //       drifts by cents; the column is NUMERIC(15,2) and the figures
        //       must match the accounting sheet to the cent.
        // WHY @Positive: it refuses zero and every negative number, and it
        //       mirrors the database CHECK constraint chk_comp_montant
        //       (montant > 0). The database keeps its own copy of the rule as a
        //       last guard, for rows inserted by a script or a seeder.
        // WITHOUT @Positive: a value of -500 would be accepted here and then
        //       rejected by PostgreSQL as a 500 error; worse, a 0 line would be
        //       stored and would sit in the list as a cost line that costs
        //       nothing.
        // WHY @NotNull: without an amount there is no cost line at all.
        @NotNull @Positive BigDecimal montant,

        // WHAT: the currency of `montant`, as an ISO-4217 three-letter code,
        //       for example "TND" (Tunisian dinar) or "EUR".
        // WHY @Size(min = 3, max = 3): min and max are both 3, which means
        //       "exactly three characters", matching the column
        //       `devise VARCHAR(3) NOT NULL DEFAULT 'TND'` of V10.
        // WHY @NotBlank AND NOT ONLY @NotNull: @NotBlank also refuses "" and a
        //       text of spaces. Note that "   " has length 3, so @Size alone
        //       would let three spaces through.
        // WITHOUT THESE: a code like "DINAR" would reach PostgreSQL, the insert
        //       would fail and the user would see a 500 error instead of a
        //       readable message; and a blank currency would make an amount
        //       impossible to read (500 of what?).
        // LIMIT TO KNOW: the value is not compared against a real currency
        //       list, so "ZZZ" passes. ComposanteService puts it in capitals
        //       before saving, so "tnd" is stored as "TND".
        @NotBlank @Size(min = 3, max = 3) String devise,

        // WHAT: free text explaining the line, e.g. "Tunis-Paris return".
        // WHY NO ANNOTATION: it is optional on purpose, because the column
        //       `description VARCHAR(500)` of V10 accepts NULL. A per diem line
        //       usually needs no explanation, a transport line often does.
        String description
) {}
