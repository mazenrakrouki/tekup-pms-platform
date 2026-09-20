package com.pms.kpi.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input of the monthly project review: the three values only a human (the CdP, chef de projet)
 * can give. Every other KPI figure is derived server-side — this record is deliberately not a
 * KpiResponse, so a caller can never post his own margin or EAC into the stored history.
 *
 * All three components are nullable: the controller accepts an empty body, meaning "freeze
 * today's figures, nothing new to declare", and KpiService then reuses the previous snapshot's EV.
 */
public record SnapshotRequest(
        // Earned-value progress, 0–100. Bounds are written as text so the annotation parses them
        // as exact BigDecimal. This multiplies the whole contract amount inside the engine, so a
        // typo like 1000 would produce a 10x-inflated production revenue if unchecked.
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal evPct,
        // The end date the CdP now expects. LocalDate (no time/zone): a review states a day, and a
        // timestamp would drag a time zone along and could shift the displayed date by one day.
        // No bound: a late project's forecast can legitimately be in the past.
        LocalDate dateFinEstimee,
        // "Faits marquants": free text of the review, capped to match the storage column
        // (SnapshotKpi.faitsMarquants, length = 2000) so an overlong value 400s cleanly instead of
        // failing as a raw database error.
        @Size(max = 2000) String faitsMarquants
) {}
