package com.pms.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Full KPI answer for one project at one moment — the same shape serves a live calculation
 * (snapshotId/snapshotDate null) and a stored snapshot. All amounts are in TND (ADR-020); a null
 * component means "not computable yet", never zero.
 */
public record KpiResponse(
        // Null for a live calculation; the id also feeds the POST's Location header once saved.
        Long snapshotId,
        // Repeated here (not just implied by the URL) so a cached response is self-contained.
        Long projectId,
        // Copied in to avoid an extra HTTP call; for snapshots MapStruct reads it via the
        // (JOIN FETCH-ed) project link to avoid N+1 queries.
        String projectCode,
        // Null for a live calculation; the x-axis of the review timeline.
        LocalDate snapshotDate,
        // Planned cost of the whole plan de charge, in TND (days × daily rate × (1+TCC), summed).
        BigDecimal budgetPlanifie,
        // Cost from VALIDATED timesheets only — an unapproved entry is a claim, not a fact.
        BigDecimal budgetConsome,
        // Estimate At Completion: cost consumed + planned cost of periods with no validated actual yet.
        BigDecimal eac,
        // Budget − EAC, in TND; null (not a large negative number) when no budget is recorded.
        BigDecimal marge,
        // Ratio, e.g. 0.75 = 75% (not a percentage); null when the budget is missing or ≤ 0.
        BigDecimal tauxConsommation,
        // ── EVM indicators (F-AFF-13 §5) — French names match the client's reference Excel ──
        // Physical progress 0–100, typed by the CdP at review (copied from the latest snapshot on
        // live). Never derived from spend: money burnt is not proof of work done.
        BigDecimal evPct,
        // LIVRE+VALIDE deliverables ÷ all active ones × 100 — a percentage, unlike tauxConsommation's
        // ratio. Null with zero deliverables. Cross-checks evPct against a hard count.
        BigDecimal deliveryPct,
        // Total man-days (JH) from validated timesheets — separates "too many days" from "too costly".
        BigDecimal consommeJh,
        // RAF (reste à faire): planned JH of periods with no validated actual yet.
        BigDecimal rafJh,
        // Sold workload − consumed − RAF; negative means more days are needed than were sold —
        // the early warning of the method. Null when no sold workload was recorded.
        BigDecimal deriveJh,
        // = budget × evPct/100; revenue earned by work done, not by invoices sent.
        BigDecimal caProduction,
        // Sum of FACTURE/PAYE billing milestones, converted to TND; 0 (never null) when nothing
        // invoiced yet.
        BigDecimal totalFacture,
        // Facture à établir = caProduction − totalFacture; negative means the client was invoiced
        // ahead of the work done.
        BigDecimal fae,
        // caProduction − cost consumed so far: the margin realised today, vs. marge's end-of-project
        // forecast.
        BigDecimal margeActuelle,
        // margeActuelle as a ratio of caProduction; null when caProduction is missing or ≤ 0.
        BigDecimal margeActuellePct,
        // Sold-margin baseline to compare margeActuellePct against: from the DI when one exists,
        // else the project's declared rate. Stored snapshots read project.margeNetteVendue, since
        // snapshot_kpis has no column for it.
        BigDecimal margeVenduePct,
        // CdP's expected end date as of the last review; the gap with the contractual date is the delay.
        LocalDate dateFinEstimee,
        // Free text from the review explaining the figures (max 2000 chars, matches the column size).
        String faitsMarquants,
        // H-3: e.g. one entry per user with no daily rate (counted at cost 0), so it's never silent.
        // Empty list, never null, on stored snapshots — a warning describes a live calculation.
        List<String> warnings
) {}
