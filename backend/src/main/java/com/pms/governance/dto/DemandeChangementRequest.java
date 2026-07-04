package com.pms.governance.dto;

import com.pms.governance.entity.PrioriteChangement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DemandeChangementRequest(
        @NotNull Long demandeurId,
        @NotBlank String titre,
        String description,
        @NotNull PrioriteChangement priorite,
        @NotNull LocalDate dateDemande
) {}
