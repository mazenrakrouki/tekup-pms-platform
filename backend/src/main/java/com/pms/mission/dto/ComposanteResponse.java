package com.pms.mission.dto;

import com.pms.mission.entity.TypeComposante;

import java.math.BigDecimal;

public record ComposanteResponse(
        Long id,
        Long missionId,
        TypeComposante typeComposante,
        BigDecimal montant,
        String devise,
        String description
) {}
