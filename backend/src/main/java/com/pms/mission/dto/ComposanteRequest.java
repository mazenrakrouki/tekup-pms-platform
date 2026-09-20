package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// Body for adding/editing one cost line (composante) of a mission: per diem, ticket, stamp,
// transport, or stay. The mission itself is taken only from the URL, never from this body, so
// a caller cannot attach a line to another project's trip and bypass the ADR-021 scope check.

/**
 * Body of "add a cost line to a mission" and "edit a cost line".
 *
 * Why a `record`: immutable, so the amount Bean Validation accepted is exactly what gets saved.
 */
public record ComposanteRequest(
        // Enum, not a String: Jackson rejects any other word (400) and the DB repeats the same
        // five values via CHECK constraint chk_comp_type (V10).
        @NotNull TypeComposante typeComposante,

        // BigDecimal, not double: exact decimals (column is NUMERIC(15,2)). @Positive mirrors
        // the DB CHECK chk_comp_montant (montant > 0), which stays as a last guard.
        @NotNull @Positive BigDecimal montant,

        // ISO-4217 three-letter currency code (e.g. "TND"). @NotBlank (not just @NotNull)
        // also refuses "" and blank strings that @Size(3,3) alone would let through.
        @NotBlank @Size(min = 3, max = 3) String devise,

        // Free text explaining the line. Optional: column accepts NULL.
        String description
) {}
