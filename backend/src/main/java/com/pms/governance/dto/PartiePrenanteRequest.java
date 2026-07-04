package com.pms.governance.dto;

import com.pms.governance.entity.NiveauRisque;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PartiePrenanteRequest(
        @NotBlank String nom,
        String fonction,
        @Email @Size(max = 255) String email,
        String telephone,
        @NotNull NiveauRisque influence,
        @NotNull NiveauRisque interet
) {}
