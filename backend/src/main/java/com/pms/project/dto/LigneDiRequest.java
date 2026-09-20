package com.pms.project.dto;

import com.pms.project.entity.SectionDi;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

// JSON body sent when adding or changing one Devis Interne line. Carries Bean Validation rules
// so a bad value is refused with 400 before the service runs, and deliberately has no id/project
// field — both come from the URL, which ProjectScopeInterceptor already checks (ADR-021) — and
// no computed amount, since the server derives those at read time. Same shape for create and
// update; the two differ only by URL.

/**
 * One editable DI line coming from the client. A record, so a value that passed validation
 * can't be rewritten before the service copies it onto the entity.
 * Bean Validation ignores null, so most rules below mean "if present, must look like this" —
 * a DI line is filled in progressively, so only @NotNull actually demands a value.
 */
public record LigneDiRequest(
        // Required: the section drives the cost rule (AUTRES_FRAIS with a rate is costed as a
        // share of the total sold) and the column is NOT NULL.
        @NotNull SectionDi section,
        // Display rank inside the section; set by the screen, not typed, so null falls back to 0.
        Integer ordre,
        // Matches the profil_contractuel column (VARCHAR(120)); caught here as 400 instead of a
        // raw 500 from Postgres.
        @Size(max = 120) String profilContractuel,
        // Same 120-char cap, mirroring ressource_proposee / ressource_retenue.
        @Size(max = 120) String ressourceProposee,
        @Size(max = 120) String ressourceRetenue,
        // Unit of the line ("H-Jour" etc.); the service falls back to "H-Jour" when blank.
        @Size(max = 20) String unite,
        // Zero allowed (a pure cost line); negative refused, since it would subtract from
        // revenue and inflate the margin the whole DI exists to report honestly.
        @PositiveOrZero BigDecimal chargeVendueJh,
        // Unit selling price, in the project currency; negative would also fake negative revenue.
        @PositiveOrZero BigDecimal prixVenteUnitaire,
        // Internal workload planned, in JH; negative would understate the computed cost.
        @PositiveOrZero BigDecimal quantiteInterneJh,
        // Fully loaded daily cost (TCC, "Taux de Cout Charge"), in DINARS (not the project
        // currency, unlike prixVenteUnitaire) since this is what the company pays its own
        // people. Negative would turn a cost into a gain.
        @PositiveOrZero BigDecimal coutUnitaireTcc,
        // Three flat extra costs in dinars (misc fees, overhead, taxes); a negative value here
        // would quietly cancel part of the line's real cost.
        @PositiveOrZero BigDecimal fraisDivers,
        @PositiveOrZero BigDecimal fraisGeneraux,
        @PositiveOrZero BigDecimal coutImpots,
        // Fraction between 0 and 1 (0.05 = 5%) used by AUTRES_FRAIS lines costed as a share of
        // total sold. The max stops a "5" meant as "5%" from multiplying the cost by 5x revenue.
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal tauxPourcentage
) {}
