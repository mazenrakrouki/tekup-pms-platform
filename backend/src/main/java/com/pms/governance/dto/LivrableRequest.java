package com.pms.governance.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record LivrableRequest(
        @NotBlank String titre,
        String description,
        LocalDate dateEcheance
) {}
