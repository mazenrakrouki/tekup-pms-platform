package com.pms.kpi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE IS
 * ═══════════════════════════════════════════════════════════════════════════════
 * The single answer object that every KPI screen of one project receives.
 * "DTO" (Data Transfer Object) means: a plain box of values whose only job is to
 * travel from the backend to the browser as JSON. It carries no rule, no
 * behaviour, and no link to the database.
 *
 * One KpiResponse = one complete financial picture of ONE project at one moment.
 * The very same shape serves the two modes of the KPI engine:
 *   - LIVE     : recomputed from today's rows, stored nowhere.
 *                snapshotId and snapshotDate stay null.
 *   - SNAPSHOT : one frozen row of the table snapshot_kpis, read back.
 *                snapshotId and snapshotDate are filled.
 * The front end uses "is snapshotId null?" to tell the two apart.
 *
 * Words used below, explained once:
 *   - JH ("jour-homme") = man-day: one person working one day.
 *   - EVM = Earned Value Management: the method that compares planned cost, real
 *     cost AND how much work is really finished. Without that third number, a
 *     project that burnt half its budget looks healthy even if nothing was
 *     delivered.
 *   - CdP ("chef de projet") = the project manager who runs the monthly review.
 *   - TCC ("Taux de Coût Chargé") = loaded cost rate: the overhead added on top of
 *     a daily rate, so a cost is days × dailyRate × (1 + tccRate).
 *   - DI ("Devis Interne") = the internal quote of the project.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHERE IT SITS IN THE FLOW
 * ═══════════════════════════════════════════════════════════════════════════════
 *   Browser (Angular)
 *     -> KpiController   GET  /api/projects/{projectId}/kpi            -> one live object
 *                        GET  /api/projects/{projectId}/kpi/snapshots  -> a list, newest first
 *                        POST /api/projects/{projectId}/kpi/snapshots  -> the object just saved
 *     -> KpiService      builds this record component by component inside buildKpi()
 *                        (live mode), or hands a stored row to the mapper (history mode)
 *     -> SnapshotKpiMapper (MapStruct)  turns a SnapshotKpi entity into THIS record
 *     -> Jackson         turns this record into JSON
 *     -> Angular         frontend/pms-frontend/src/app/core/models/kpi.model.ts declares the
 *                        same shape; features/kpi/kpi.component.ts displays it.
 *
 * So this file is the written contract between the Java side and the TypeScript
 * side. Rename one component here and the matching field in kpi.model.ts becomes
 * undefined at runtime, with no compiler error on either side to warn anybody.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHY IT EXISTS (what would break if it were deleted)
 * ═══════════════════════════════════════════════════════════════════════════════
 * Most numbers below exist in NO table. They are derived at read time from the plan
 * de charge, the validated timesheets, the daily rates and TCC rates, the
 * deliverables and the billing milestones. No entity is able to carry them.
 * Delete this record and KpiService would have to return the JPA entity SnapshotKpi
 * instead. Three things would break at once: the live values (which are not
 * columns) would have nowhere to go; the JSON sent to Angular would become a mirror
 * of the database schema, so every future column rename would break the front end;
 * and Jackson would try to serialise the lazy Project proxy hanging off the entity.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * THREE RULES THAT APPLY TO EVERY COMPONENT BELOW
 * ═══════════════════════════════════════════════════════════════════════════════
 * 1. WHY A RECORD AND NOT A CLASS. A Java record is immutable: once built, no
 *    component can be changed. A response must not be modified after the service
 *    computed it, and the record also gives, for free, the accessors that Jackson
 *    and MapStruct need (snapshotId(), evPct()...). Written as a normal class with
 *    fields, constructor, getters and equals, this file would be several times
 *    longer, and one public setter left behind would let any later code silently
 *    rewrite a margin after the calculation.
 * 2. WHY BigDecimal AND NEVER double. double is stored in binary and cannot hold
 *    0.1 exactly. A budget of 12 000.00 TND could come back as 11999.999999999998
 *    on screen, and adding many such amounts makes the error grow. BigDecimal keeps
 *    the exact decimal value and the number of decimals chosen by the engine.
 * 3. WHY null IS MEANINGFUL. A component set to null means "cannot be computed
 *    yet"; it never means zero. Example: a project with no budget recorded has
 *    marge = null, so the screen prints a dash. If the engine put 0 there, the page
 *    would claim the project has exactly zero margin, which is a different and
 *    false statement. The Angular interface marks these same fields optional ("?").
 *
 * All money amounts here are in TND (Tunisian dinar) — ADR-020. Costs come from
 * daily rates already expressed in TND, and amounts billed in the project currency
 * are converted with the project exchange rate before they reach this object, so no
 * two components of the same response are in different currencies.
 */
public record KpiResponse(
        // WHAT: primary key of the saved snapshot row; null when the object is a live calculation.
        // WHY: it is the flag that tells the front end which of the two modes produced this
        //      object, and it is also the id the POST endpoint puts in its Location header after
        //      creating a snapshot.
        // WITHOUT IT: the history screen could not tell the "today, live" line from the saved
        //      monthly lines, and a newly created snapshot would have no address to point to.
        Long snapshotId,
        // WHAT: the project these numbers belong to.
        // WHY: the browser can hold several projects in memory at the same time (list, detail,
        //      cache), so each answer must say by itself which project it describes.
        // WITHOUT IT: two answers arriving out of order could be displayed under the wrong project.
        Long projectId,
        // WHAT: the readable project code, copied into the response on purpose (for example
        //      "PRJ-2026-014").
        // WHY: the KPI screens print the code next to the figures, and repeating it here saves one
        //      extra HTTP call. In the snapshot case MapStruct reads it through the LAZY link
        //      SnapshotKpi.project, which is exactly why SnapshotKpiRepository loads its rows with
        //      JOIN FETCH k.project.
        // WITHOUT IT: a history table of 24 monthly snapshots would fire 24 extra project lookups
        //      just to print a label (the classic "N+1 queries" problem).
        String projectCode,
        // WHAT: the day the snapshot was taken; null when the object is a live calculation.
        // WHY: snapshots form the timeline of the project reviews, so the chart needs this value
        //      as its x axis, and the service refuses a second snapshot on the same date.
        // WITHOUT IT: two snapshots could not be ordered, and the March review figures could not
        //      be told apart from the June ones.
        LocalDate snapshotDate,
        // WHAT: planned cost of the whole plan de charge, in TND: for every planned line,
        //      days × daily rate × (1 + TCC rate), all lines added together.
        // WHY: this is the cost the project was expected to have; the real cost below is read
        //      against it.
        // WITHOUT IT: there would be nothing to compare the real cost with, so an overrun could
        //      only be felt, never measured.
        BigDecimal budgetPlanifie,
        // WHAT: cost already consumed, in TND: the same formula, but over the VALIDATED timesheets
        //      only. Saved in the column snapshot_kpis.budget_consome when a snapshot is taken.
        // WHY: only validated effort counts. A timesheet still waiting for the CdP's approval is a
        //      claim, not a fact.
        // WITHOUT IT (if draft entries counted too): a developer could move the project cost, and
        //      therefore the margin shown to the direction, just by typing days nobody approved.
        BigDecimal budgetConsome,
        // WHAT: EAC = "Estimate At Completion", the forecast of what the project will finally cost:
        //      cost already consumed + planned cost of the periods that have no validated actual yet.
        // WHY: the months already worked must count at their REAL cost, and only the future is
        //      still taken from the plan.
        // WITHOUT IT (using the plan total instead): a month planned at 20 JH but really burnt at
        //      30 JH would still count as 20, and the forecast would hide the overrun exactly when
        //      it matters.
        BigDecimal eac,
        // WHAT: forecast margin in TND = project budget (TND) − EAC. null when the project has no
        //      budget recorded.
        // WHY: it answers the question the direction really asks: at this pace, will this project
        //      still earn money at the end?
        // WITHOUT the null case: a project created without a budget would show a margin equal to
        //      minus its whole cost, and look catastrophic although nothing is known yet.
        BigDecimal marge,
        // WHAT: share of the budget already spent, as a RATIO and not a percentage: 0.75 means
        //      75 %. Four decimals. null when the budget is missing or not strictly positive.
        // WHY: a ratio is what a progress bar and the Angular percent pipe expect, and four
        //      decimals keep 0.01 % readable on a large budget.
        // WITHOUT the null case: dividing by a budget of zero would throw ArithmeticException, and
        //      the whole KPI page would answer HTTP 500 instead of showing the other indicators.
        BigDecimal tauxConsommation,
        // ── EVM indicators (F-AFF-13 §5) ──────────────────────────────────────────
        // The block below reproduces the client's own Excel method. The names stay French because
        // they are the names used in that document and by the people who read these screens;
        // translating them would break the link with the reference file.
        // WHAT: physical progress of the project, from 0 to 100 (NOT a 0..1 ratio). Typed by the
        //      CdP during the monthly review; on the live endpoint it is copied from the most
        //      recent snapshot.
        // WHY it is typed and not computed: money burnt is not progress. A team can spend 60 % of
        //      the budget while only 20 % of the scope is really finished, and only a human can
        //      state the real figure.
        // WITHOUT IT: caProduction, fae, margeActuelle and margeActuellePct are all null, because
        //      every one of them is derived from this single number.
        BigDecimal evPct,
        // WHAT: delivery rate = deliverables in state LIVRE or VALIDE ÷ all active deliverables
        //      × 100, two decimals. It is already a percentage (100 = everything delivered),
        //      unlike tauxConsommation which is a ratio — a real trap when the two are read side
        //      by side. null when the project has no deliverable at all.
        // WHY: it is the cross-check of evPct. When the CdP claims 80 % earned value while the
        //      delivery rate is 20 %, the review has a question to ask.
        // WITHOUT the null case: a project with zero deliverables would divide by zero and make
        //      the whole KPI call fail.
        BigDecimal deliveryPct,
        // WHAT: total man-days (JH) really consumed = sum of the days of the validated timesheets.
        // WHY: the same effort must be visible in days and not only in money, because the CdP
        //      steers the team in days while the direction reads dinars.
        // WITHOUT IT: an overrun caused by expensive people could not be told apart from an
        //      overrun caused by too many days.
        BigDecimal consommeJh,
        // WHAT: RAF ("reste à faire") = the man-days still planned on the periods that have no
        //      validated actual yet.
        // WHY: consumed + RAF is the honest total effort of the project, and it is exactly the
        //      effort side of the EAC computed above (same periods, same rule).
        // WITHOUT IT: the drift below could not be computed at all.
        BigDecimal rafJh,
        // WHAT: drift in man-days = workload sold to the client − consumed − RAF. null when no
        //      sold workload was recorded on the project.
        // WHY: it is the early warning of the whole method. A negative value means the project
        //      will need more days than were sold, so part of the sold margin is already lost.
        // WITHOUT IT: that loss would only appear at the very end, inside the final margin, when
        //      nothing can be renegotiated any more.
        BigDecimal deriveJh,
        // WHAT: "CA production" = revenue earned so far = project budget in TND × evPct ÷ 100,
        //      two decimals. null when evPct or the budget is missing (a warning is added instead).
        // WHY: revenue must follow the work really done, not the invoices sent. This is the
        //      accounting view of progress.
        // WITHOUT IT: the company could only measure what it invoiced, so a project invoiced in
        //      advance would look profitable while the work is still to be done.
        BigDecimal caProduction,
        // WHAT: total really invoiced, in TND: the billing milestones in state FACTURE or PAYE,
        //      added up and converted with the project exchange rate. It is zero, never null,
        //      when nothing has been invoiced yet.
        // WHY only those two states: a milestone that is merely planned, or merely delivered, is
        //      not money asked to the client.
        // WITHOUT the currency conversion: a project sold in euros would add euro milestones to
        //      dinar costs, and the margin would be wrong by the whole exchange rate.
        BigDecimal totalFacture,
        // WHAT: FAE ("facture à établir") = invoice still to be issued = caProduction − totalFacture.
        //      null when caProduction is null. A negative value means the client was invoiced
        //      ahead of the work done.
        // WHY: it is the gap between what the company earned and what it already asked for;
        //      accounting needs it to close the month.
        // WITHOUT IT: work already done but not yet invoiced would simply be forgotten, and the
        //      company would under-declare its revenue.
        BigDecimal fae,
        // WHAT: current margin in TND = caProduction − cost already consumed. null when
        //      caProduction is null.
        // WHY: unlike "marge" above, which is a forecast for the end of the project, this one is
        //      the margin really realised at this very day.
        // WITHOUT IT: only the forecast would exist, and a forecast built on an optimistic plan
        //      can stay green while the money already spent tells another story.
        BigDecimal margeActuelle,
        // WHAT: the same current margin as a RATIO of the earned revenue (0.18 = 18 %), four
        //      decimals. null when caProduction is null or not strictly positive.
        // WHY: a percentage is the only figure comparable between a small and a large project,
        //      and it is what the baseline below is compared against.
        // WITHOUT the "strictly positive" guard: a project with zero earned revenue would divide
        //      by zero and crash the KPI page.
        BigDecimal margeActuellePct,
        // WHAT: the baseline — the margin rate promised when the project was sold, shown next to
        //      margeActuellePct. It has two sources, on purpose:
        //        - live call: the margin recomputed from the DI (internal quote) lines when a DI
        //          exists, otherwise the rate typed on the project identification sheet;
        //        - stored snapshot: SnapshotKpiMapper reads the project's own margeNetteVendue,
        //          because the table snapshot_kpis has no column for this value.
        // WHY the DI wins when it exists: the DI is the detailed, line-by-line quote, so it is
        //      closer to reality than a single rate typed by hand on the identification sheet.
        // WITHOUT IT: the reader would see "current margin 12 %" with nothing to compare it to,
        //      and could not say whether the project is above or below what was sold.
        BigDecimal margeVenduePct,
        // WHAT: the end date the CdP expects, as stated at the last review. On the live endpoint
        //      it is copied from the most recent snapshot.
        // WHY: the contractual end date lives in the project sheet; this one is the honest
        //      forecast, and the distance between the two is the delay.
        // WITHOUT IT: a slipping project would look on time until the contractual date is passed.
        LocalDate dateFinEstimee,
        // WHAT: "faits marquants" = free text written by the CdP at the review (at most 2000
        //      characters, the size of the column that stores it).
        // WHY: figures do not explain themselves. "Client froze the specification for three
        //      weeks" is what makes a drift understandable six months later.
        // WITHOUT IT: a stored snapshot would be a row of numbers whose cause nobody remembers.
        String faitsMarquants,
        // WHAT: H-3 — plain sentences meant to be shown in a banner above the figures. Two kinds
        //      are produced today: one per user who has no daily rate configured (their effort is
        //      counted at zero cost), and one when the EV has never been entered (so caProduction,
        //      fae and the current margin stay null). On snapshots read back from the database the
        //      mapper puts an empty list, never null, because a warning describes the state of a
        //      calculation and not the content of a stored row.
        // WHY the warnings travel inside the response instead of going to a log file: audit item
        //      H-3 was exactly that the engine priced unknown resources at zero SILENTLY, and a
        //      line in a server log is invisible to the CdP reading the screen.
        // WITHOUT IT: a project whose team has no rate would proudly display a huge margin, and
        //      the direction would take a decision based on a cost of zero dinar.
        List<String> warnings
) {}
