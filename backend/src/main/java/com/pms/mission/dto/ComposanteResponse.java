package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;

import java.math.BigDecimal;

// Read side of ComposanteRequest: the JSON sent back for one mission cost line. Never returns
// the ComposanteMission entity directly, which would risk a LazyInitializationException on its
// lazy `mission` link and would leak internal columns (deleted, createdBy/updatedBy).

/**
 * One cost line of a mission as the browser sees it.
 *
 * Why a `record`: read-only, so nothing can change an amount between the database read and the
 * JSON write.
 */
public record ComposanteResponse(
        // Primary key of the row in `composantes_mission`.
        Long id,

        // Id of the parent mission. Filled by MapStruct via mission.id so only the number
        // crosses the boundary, never the Mission object.
        Long missionId,

        // Kind of cost (PERDIEM, BILLET, TIMBRE, TRANSPORT, SEJOUR). Kept as the enum so
        // Angular can switch on the exact name for icon/translation.
        TypeComposante typeComposante,

        // Amount, exactly as stored in NUMERIC(15,2). BigDecimal keeps decimals exact where a
        // double would drift; Angular converts it with Number(c.montant) before summing.
        BigDecimal montant,

        // Three-letter ISO-4217 currency, always upper-cased by ComposanteService before save.
        String devise,

        // Free text explaining the line. May be null.
        String description
) {}
