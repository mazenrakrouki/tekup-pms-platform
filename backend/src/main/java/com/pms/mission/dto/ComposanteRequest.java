package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ComposanteRequest(
        @NotNull TypeComposante typeComposante,
        @NotNull @Positive BigDecimal montant,
        @NotBlank @Size(min = 3, max = 3) String devise,
        String description
) {}
