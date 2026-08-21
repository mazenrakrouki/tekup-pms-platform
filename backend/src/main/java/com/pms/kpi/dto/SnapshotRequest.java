package com.pms.kpi.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Saisie mensuelle du CdP lors de la revue (F-AFF-13 "Situation actuelle"). */
public record SnapshotRequest(
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal evPct, // avancement EV estimé par le CdP
        LocalDate dateFinEstimee,
        @Size(max = 2000) String faitsMarquants
) {}
