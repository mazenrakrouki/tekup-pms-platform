package com.pms.project.dto;

import com.pms.project.entity.SectionDi;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record LigneDiRequest(
        @NotNull SectionDi section,
        Integer ordre,
        @Size(max = 120) String profilContractuel,
        @Size(max = 120) String ressourceProposee,
        @Size(max = 120) String ressourceRetenue,
        @Size(max = 20) String unite,
        @PositiveOrZero BigDecimal chargeVendueJh,
        @PositiveOrZero BigDecimal prixVenteUnitaire,
        @PositiveOrZero BigDecimal quantiteInterneJh,
        @PositiveOrZero BigDecimal coutUnitaireTcc,
        @PositiveOrZero BigDecimal fraisDivers,
        @PositiveOrZero BigDecimal fraisGeneraux,
        @PositiveOrZero BigDecimal coutImpots,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal tauxPourcentage
) {}
