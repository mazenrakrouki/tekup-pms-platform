package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;

import java.math.BigDecimal;

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * The shape of the JSON the server sends back for one cost line of a mission
 * (per diem, ticket, fiscal stamp, transport, stay). It is the read side of the
 * pair: ComposanteRequest comes in, ComposanteResponse goes out. It is a DTO
 * (Data Transfer Object): its only job is to carry data to the browser.
 *
 * WHERE IT SITS IN THE FLOW
 *   ComposanteMissionRepository.findActiveByMissionId / findActiveById
 *     -> gives ComposanteMission entities (soft-deleted rows already filtered
 *        out by "deleted = false" inside the queries)
 *   -> ComposanteMapper.toResponse / toResponseList builds these records.
 *      ComposanteMapper is a MapStruct interface, so the mapping code is
 *      generated at compile time; ADR-018 makes MapStruct mandatory here.
 *   -> ComposanteService.findByMission / create / update returns them
 *   -> MissionController.listComposantes / createComposante / updateComposante
 *   -> Angular: mission.service.ts listComposantes(), displayed by
 *      missions.component.ts, which adds the amounts up on screen.
 *
 * WHY IT EXISTS
 * Returning the ComposanteMission entity directly would break two things:
 *   1. The entity holds `mission` as @ManyToOne(fetch = LAZY). Writing it as
 *      JSON would touch that lazy proxy and could throw
 *      LazyInitializationException, ending the request in a 500 error; and if
 *      it did load, the JSON would then contain the whole mission, its project
 *      and its user for every single cost line.
 *   2. It would also expose `deleted`, `createdBy` and `updatedBy`, which are
 *      internal bookkeeping, not business data for this screen.
 *
 * WHY NO TOTAL FIELD
 * There is no "total cost" here and none is stored in the database. Each line
 * is returned on its own and the sum is computed when it is needed. This
 * follows the rule used for the sensitive DI amounts: a computed amount is
 * derived when read, never written into a column, so it can never drift away
 * from the lines it comes from. If a total were stored, deleting one cost line
 * without recomputing would leave a mission showing a cost that no longer
 * matches its own lines.
 * ============================================================================
 */

/**
 * One cost line of a mission as the browser sees it.
 *
 * Gives back: the identity of the line, the mission it belongs to, its kind,
 * its amount, its currency and its free-text explanation.
 *
 * Why a `record`: read-only data with no setters, so nothing can change an
 * amount between the moment it is read from the database and the moment it is
 * written into the JSON answer. On money that guarantee is worth more than the
 * flexibility of a normal class.
 */
public record ComposanteResponse(
        // Primary key of the row in `composantes_mission`. The browser sends it
        // back in the URL of PUT and DELETE on this cost line.
        Long id,

        // WHAT: id of the mission this cost line belongs to.
        // WHY: MapStruct fills it with
        //      @Mapping(target = "missionId", source = "mission.id"), so only
        //      the number crosses the boundary, never the Mission object.
        // WITHOUT IT: the browser could not tell two lists apart if it ever
        //      held the cost lines of several missions at once, and it would
        //      not know which URL to call to edit or delete the line.
        Long missionId,

        // WHAT: the kind of cost, one of PERDIEM (daily allowance), BILLET
        //       (ticket), TIMBRE (fiscal stamp), TRANSPORT, SEJOUR (stay).
        // WHY THE ENUM AND NOT A String: Jackson writes it as its exact name,
        //       for example "PERDIEM", so the Angular side can safely switch on
        //       that value to choose the icon and the translation key.
        // WITHOUT IT: a free text could arrive with a different spelling and
        //       the line would fall through every case of that switch and be
        //       displayed with no label.
        TypeComposante typeComposante,

        // WHAT: the amount of this line, exactly as stored in NUMERIC(15,2).
        // WHY BigDecimal: it keeps the decimals exact. A double would turn an
        //       amount such as 1234.10 into 1234.0999999999999, and the total
        //       shown to the user would end in stray cents.
        // NOTE: Jackson writes BigDecimal as a JSON number, and JavaScript
        //       reads every number as a double, which is why the Angular code
        //       converts it with Number(c.montant) before adding.
        BigDecimal montant,

        // WHAT: the currency of `montant`, a three-letter ISO-4217 code such as
        //       "TND". Always stored in capitals: ComposanteService uppercases
        //       the value before saving.
        // WHY IT TRAVELS WITH THE AMOUNT: an amount alone means nothing. The
        //       screen prints "1 200 TND", and a reader can tell at once that a
        //       line in EUR is not comparable to a line in TND.
        // WITHOUT IT: two lines of 500 in different currencies would look
        //       identical on screen.
        String devise,

        // Free text explaining the line, for example "Tunis-Paris return".
        // May be null: the column accepts NULL, so the Angular template has to
        // survive an empty cell here.
        String description
) {}
