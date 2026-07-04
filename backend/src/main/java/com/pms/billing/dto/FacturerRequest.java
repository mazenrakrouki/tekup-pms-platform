package com.pms.billing.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record FacturerRequest(@NotNull LocalDate dateFacture) {}
