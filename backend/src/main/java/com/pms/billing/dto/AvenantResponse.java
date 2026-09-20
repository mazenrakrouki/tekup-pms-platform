package com.pms.billing.dto;

// Read-side DTO for one contract amendment. Kept separate from AvenantRequest because
// id/projectId/projectCode are server-produced and must never be accepted from a caller.

import java.math.BigDecimal;
import java.time.LocalDate;

// Immutable record, built once by AvenantMapper and only read afterwards; no validation
// annotations here since Bean Validation guards input, not output already read from the DB.
public record AvenantResponse(
        // Primary key; the Angular table uses it to build the delete URL.
        Long id,
        // Flattened from Avenant.project by AvenantMapper (project.id/project.code) while the
        // transaction is still open, so the LAZY proxy resolves without a LazyInitializationException.
        Long projectId,
        // Human-readable project code, shown next to projectId to avoid a second HTTP call.
        String projectCode,
        // Reference of the signed amendment, as sent in AvenantRequest.
        String numero,
        // Free-text purpose of the amendment. Can be null.
        String objet,
        // Amount, sign included (positive grows the contract, negative cuts it). BigDecimal to
        // avoid double's rounding drift when the amendments table sums these values.
        BigDecimal montant,
        // Impact on sold workload in man-days (JH). Null when the amendment is money-only.
        BigDecimal workloadDays,
        // Signature date; Jackson writes it as ISO "2026-03-14", matching the Angular model.
        LocalDate dateAvenant
) {}
