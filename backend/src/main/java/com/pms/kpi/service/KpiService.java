package com.pms.kpi.service;

import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.repository.JalonFacturationRepository;
import com.pms.governance.entity.Livrable;
import com.pms.governance.entity.StatutLivrable;
import com.pms.governance.repository.LivrableRepository;
import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.dto.SnapshotRequest;
import com.pms.kpi.entity.SnapshotKpi;
import com.pms.kpi.mapper.SnapshotKpiMapper;
import com.pms.kpi.repository.SnapshotKpiRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.project.service.DevisInterneService;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.Resource;
import com.pms.user.entity.TccAnnuel;
import com.pms.user.repository.ResourceRepository;
import com.pms.user.repository.TccAnnuelRepository;
import com.pms.workload.entity.ChargeReelle;
import com.pms.workload.entity.PlanCharge;
import com.pms.workload.repository.ChargeReelleRepository;
import com.pms.workload.repository.PlanChargeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE IS
 * ═══════════════════════════════════════════════════════════════════════════════
 * This is the KPI engine of the application. Give it one project id and it gives back
 * the full set of indicators of that project: planned budget, consumed budget, EAC,
 * margin, consumption rate, plus the EVM indicators of spec F-AFF-13 (EV %, delivery %,
 * consumed man-days, remaining man-days, drift, production revenue, total invoiced,
 * FAE, current margin, sold margin).
 *
 * Two words the rest of the file uses all the time:
 *   - JH ("jour-homme") = man-day. One person working one day.
 *   - EVM = Earned Value Management, a standard method that compares three things:
 *     what the work was supposed to cost, what it really cost, and how much of it is
 *     really finished. Without the third number, a project that has spent half its
 *     budget looks healthy even when nothing has been delivered.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHERE IT SITS IN THE FLOW
 * ═══════════════════════════════════════════════════════════════════════════════
 *   Browser
 *     -> KpiController                      GET  /api/projects/{projectId}/kpi
 *                                           GET  /api/projects/{projectId}/kpi/snapshots
 *                                           POST /api/projects/{projectId}/kpi/snapshots
 *     -> KpiService (THIS FILE)             permission check, transaction, arithmetic
 *     -> eight repositories                 ProjectRepository, PlanChargeRepository,
 *                                           ChargeReelleRepository, ResourceRepository,
 *                                           TccAnnuelRepository, LivrableRepository,
 *                                           JalonFacturationRepository,
 *                                           SnapshotKpiRepository
 *     -> DevisInterneService                only for the sold-margin baseline
 *     -> SnapshotKpiMapper                  SnapshotKpi row -> KpiResponse record
 *     -> KpiResponse (JSON)                 back to the browser
 *
 * This service is a READER of almost everything. It owns only one table, snapshot_kpis.
 * Every other number it needs (plan rows, validated timesheets, daily rates, yearly TCC
 * rates, deliverables, billing milestones, the internal quote) belongs to another
 * module and is read through that module's repository. That is on purpose: the KPI
 * module holds the formulas, the other modules hold the truth.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * THE TWO MODES, AND WHY BOTH EXIST
 * ═══════════════════════════════════════════════════════════════════════════════
 *   1. LIVE  (computeLive)   -> recomputed from the current database rows on every
 *                               call, stored nowhere. It always tells the truth of
 *                               "right now".
 *   2. SNAPSHOT (createSnapshot) -> the same numbers, frozen into one row of
 *                               snapshot_kpis, one row per project per day.
 *
 * Why keep both instead of only the live one? Because a live value cannot be compared
 * with itself later. Example: the project review of March says the margin was 12 %. In
 * June somebody corrects a timesheet of March; the live calculation silently rewrites
 * history and the March review minutes no longer match anything. The snapshot row is
 * the evidence that the March decision was taken on the March numbers.
 *
 * Why not keep only snapshots? Because between two reviews the project manager still
 * needs to see where the project stands today.
 *
 * Both modes go through the SAME private method, buildKpi(). One engine, two callers.
 * If the formulas lived in two places they would drift apart, and the live screen would
 * one day disagree with the stored history for no visible reason.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * SECURITY - three layers, only one of which is visible in this file
 * ═══════════════════════════════════════════════════════════════════════════════
 * 1. PERMISSION (visible here). @PreAuthorize("hasAuthority('VIEW_KPI')") and
 *    @PreAuthorize("hasAuthority('EDIT_PROJECT')") sit on the SERVICE methods below,
 *    never on KpiController. Two reasons: the controller is not the only possible
 *    caller (another service could call this one), and putting the guard here means
 *    every entry point is covered by construction. Note the code tests a PERMISSION
 *    CODE, never a role name. An administrator can move VIEW_KPI from one role to
 *    another in the database and the check honours it immediately, with zero code
 *    change. If this tested "hasRole('DIRECTOR')" instead, every such business change
 *    would mean a new release.
 * 2. PROJECT SCOPE (ADR-021, NOT visible here). Holding VIEW_KPI is not enough.
 *    ProjectScopeInterceptor matches the URL /api/projects/{id}/** before the
 *    controller runs and asks ProjectScopeService whether the current user may touch
 *    THAT project. Without it, a project manager with VIEW_KPI could read the margin
 *    and the cost price of a competitor colleague's project just by changing the id in
 *    the address bar (BR-063). Permission says "may do the action", scope says "on
 *    which rows".
 * 3. FIELD-LEVEL MASKING (elsewhere). A user without VIEW_KPI never reaches this
 *    service at all, and ProjectService additionally blanks the financial fields of
 *    the project itself, so a developer sees no money anywhere (BR-050).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * MONEY - everything produced here is in TND
 * ═══════════════════════════════════════════════════════════════════════════════
 * A project can be sold in another currency (EUR, FCFA...). Costs, however, are always
 * computed from daily rates that are expressed in TND. So invoiced amounts, which are
 * stored in the project currency, are multiplied by project.exchangeRateToTnd before
 * being mixed with costs (ADR-020). Without that single conversion point, a euro
 * milestone would simply be added to a dinar cost: the error is as large as the
 * exchange rate itself, and nothing on screen would say anything is wrong.
 *
 * Every money value uses BigDecimal, never double. With double, 0.1 + 0.2 is
 * 0.30000000000000004; on a million-dinar budget that kind of drift produces a margin
 * that does not tie back to the accounting, and a jury question nobody can answer.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHY IT EXISTS - what would be missing if you deleted this file
 * ═══════════════════════════════════════════════════════════════════════════════
 * KpiController would not compile, and with it the whole financial steering of the
 * product would disappear: no EAC, no margin, no drift, no project review sheet. The
 * raw data would still be in the database (timesheets, plans, milestones) but nothing
 * would turn it into a decision. This file IS the added value of the application over
 * a spreadsheet; the rest is data entry around it.
 */

/**
 * KPI calculation service: live indicators and monthly snapshots for one project.
 *
 * Why a Spring @Service and not a plain class built with "new": this object needs ten
 * collaborators (repositories, a mapper, another service). Spring builds it once at
 * startup, injects them, and - more importantly - wraps it in a proxy so that the
 * security and transaction annotations actually do something. A hand-built instance
 * would silently ignore both: the permission check would never run and every read would
 * happen outside a transaction. That is a security hole that compiles perfectly.
 *
 * Why the fields are "private final" and there is no constructor written by hand: the
 * Lombok annotation on the class generates the constructor that takes exactly those
 * final fields. "final" also means nothing can swap a repository at runtime, and it
 * makes the object safe to share between the many HTTP threads that call it at the same
 * time - the service itself keeps no mutable state between calls.
 *
 * IMPORTANT LIMIT to state honestly: because the permission check is enforced by the
 * Spring proxy, a call from INSIDE this class to another public method of this class
 * (this.x()) would bypass it. That is why the internal helpers below (buildKpi,
 * chargeCost, latestSnapshotEv, loadProject) are private and carry no security
 * annotation: the guard is always crossed at the public entry point, exactly once.
 */
@Service
@RequiredArgsConstructor
public class KpiService {

    // Reusable constant for the "× 100" of delivery % and the "÷ 100" of production
    // revenue. Why a constant rather than writing BigDecimal.valueOf(100) in both places:
    // it is one object created once instead of one per call, and the two percentage
    // formulas below then read like the spec. Concrete risk avoided: someone typing 1000
    // by mistake in one of the two places, which would make delivery % or production
    // revenue wrong on one screen only, and be very hard to spot.
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // The ten collaborators, injected by Lombok's generated constructor.
    // They are listed in the order the calculation uses them, which is also the order of
    // the sections of buildKpi() below:
    //   projectRepository          -> the project itself (budget, sold workload, rate)
    //   planChargeRepository       -> what was PLANNED, per person per month
    //   chargeReelleRepository     -> what was REALLY done and VALIDATED, per person per month
    //   resourceRepository         -> the base daily rate and TCC rate of each person
    //   tccAnnuelRepository        -> the per-year daily rate, which overrides the base one
    //   livrableRepository         -> the deliverables, for the delivery percentage
    //   jalonFacturationRepository -> the billing milestones, for "total invoiced"
    //   snapshotKpiRepository      -> the only table this service writes to
    //   snapshotKpiMapper          -> entity -> KpiResponse (MapStruct, generated at build)
    //   devisInterneService        -> the sold-margin baseline coming from the internal quote
    private final ProjectRepository          projectRepository;
    private final PlanChargeRepository       planChargeRepository;
    private final ChargeReelleRepository     chargeReelleRepository;
    private final ResourceRepository         resourceRepository;
    private final TccAnnuelRepository        tccAnnuelRepository;
    private final LivrableRepository         livrableRepository;
    private final JalonFacturationRepository jalonFacturationRepository;
    private final SnapshotKpiRepository      snapshotKpiRepository;
    private final SnapshotKpiMapper          snapshotKpiMapper;
    private final DevisInterneService        devisInterneService;

    // ── Live calculation (nothing is stored) ──────────────────────

    /**
     * Computes every indicator of one project from the data as it stands right now, and
     * returns them as a KpiResponse. Nothing is written to the database: snapshotId and
     * snapshotDate of the returned record stay null, which is exactly how the frontend
     * tells a live value from a stored one.
     *
     * Why the EV percentage is NOT recomputed here but copied from the last snapshot:
     * EV ("Earned Value", how much of the work is really finished, from 0 to 100) cannot
     * be derived from the database. It is a judgement made by the project manager at the
     * monthly review. So the live view reuses the most recent value he entered. The
     * obvious alternative - guessing EV from spent budget - is exactly the mistake EVM
     * exists to prevent: it would make EV follow the cost, so a project that burns money
     * without delivering would report 100 % progress.
     *
     * @param projectId project to measure; must be a project the caller is allowed to see
     * @return the full indicator set, with snapshotId/snapshotDate null
     */
    // Permission check, run by the Spring proxy BEFORE the method body.
    // Why: VIEW_KPI is the capability that walls developers off from all financial data
    // (BR-050). Without this line, any authenticated user who guessed the URL would read
    // the margin and the cost price of a project.
    // Note again: this is a permission code, not a role name, and it is NOT enough on its
    // own - ProjectScopeInterceptor (ADR-021) separately checks that THIS project is in
    // the caller's perimeter.
    @PreAuthorize("hasAuthority('VIEW_KPI')")
    // readOnly = true opens one read transaction for the whole method.
    // Why one transaction: the method fires around eight SELECT queries and also walks a
    // few associations that are not join-fetched (for example t.getResource().getUser()
    // when the yearly TCC rates are indexed below). Outside a transaction the persistence
    // context is closed between queries and such a lazy access throws
    // LazyInitializationException, so the KPI screen would answer 500 instead of numbers.
    // Why readOnly: it tells Hibernate not to keep a snapshot of every loaded entity for
    // dirty-checking, and lets the driver/database skip write bookkeeping. It is also a
    // safety net - an accidental save() inside this path fails instead of silently
    // rewriting project data during a simple read.
    @Transactional(readOnly = true)
    public KpiResponse computeLive(Long projectId) {
        Project project = loadProject(projectId);
        // Live EV = the EV of the most recent snapshot, because EV is a monthly estimate
        // made by the project manager (CdP) and cannot be derived from the data.
        // The estimated end date and the highlights ("faits marquants") are carried over
        // the same way, so the live screen still shows the last commentary of the manager
        // instead of an empty box between two reviews.
        SnapshotEv latest = latestSnapshotEv(projectId);
        // The null second argument is the snapshot date: null means "this is a live
        // calculation, it belongs to no stored review".
        return buildKpi(project, null, latest.evPct(), latest.dateFinEstimee(), latest.faitsMarquants());
    }

    // ── Snapshots (stored history) ────────────────────────────────

    /**
     * Returns the stored snapshots of one project, newest first (the ORDER BY lives in
     * SnapshotKpiRepository.findActiveByProjectId). This is the history the project
     * review relies on, and what the frontend draws the trend curves from.
     *
     * Why loadProject(projectId) is called and its result thrown away: it turns an
     * unknown or soft-deleted project id into a clean 404 "Projet introuvable". Without
     * it the method would happily answer 200 with an empty list, and the user could not
     * tell "this project has no snapshot yet" from "this project does not exist".
     *
     * Why the mapper is used here but not in computeLive: these rows come FROM the
     * database, so they must be converted from the SnapshotKpi entity to the response
     * record. A live calculation builds the record directly, there is no entity to
     * convert. Note the consequence: fields the snapshot table does not store (the
     * warnings list and margeVenduePct) come back empty for a stored snapshot.
     */
    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public List<KpiResponse> findSnapshots(Long projectId) {
        loadProject(projectId);
        return snapshotKpiMapper.toResponseList(snapshotKpiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Freezes today's indicators of one project into one row of snapshot_kpis and returns
     * the saved row. This is the "project review" action: the manager enters his progress
     * estimate and the system stores the financial picture that goes with it.
     *
     * Why the permission is EDIT_PROJECT and not VIEW_KPI: creating a snapshot is not
     * reading, it adds a row that becomes part of the official history of the project.
     * Reading indicators (VIEW_KPI) and committing a monthly review (EDIT_PROJECT) are
     * two different responsibilities, so they are two different capabilities.
     *
     * @param request may be null - the controller declares @RequestBody(required = false),
     *                so the client can POST with no body at all to say "just freeze the
     *                current numbers, I have nothing new to declare"
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    // No readOnly here: this method WRITES. @Transactional makes the whole method one
    // single unit of work in the database.
    // Why it matters: the method reads a dozen tables and then inserts one row. If the
    // insert fails (for instance the unique index uk_kpi_project_date rejects a duplicate
    // created a millisecond earlier by a second click), the transaction rolls back and
    // nothing half-written stays behind. It also means the duplicate check below and the
    // insert see one consistent view of the data.
    @Transactional
    public KpiResponse createSnapshot(Long projectId, SnapshotRequest request) {
        Project project = loadProject(projectId);
        LocalDate today = LocalDate.now();

        // One snapshot per project per day. This check exists to produce a readable error
        // message; the real guarantee is the partial unique index in migration V8:
        //   CREATE UNIQUE INDEX uk_kpi_project_date ON snapshot_kpis(project_id, snapshot_date)
        //   WHERE deleted = FALSE
        // Why both: the check alone loses a race - two clicks on "save review" at the same
        // instant both read "no row yet" and both insert. The database index refuses the
        // second one. Why not the index alone: the user would get a raw constraint-violation
        // 500 instead of a sentence he can act on. Note the index ignores soft-deleted rows,
        // so a snapshot deleted by mistake can be redone the same day.
        if (snapshotKpiRepository.existsByProjectIdAndSnapshotDateAndDeletedFalse(projectId, today)) {
            throw new IllegalArgumentException("Un snapshot existe déjà pour ce projet à la date d'aujourd'hui");
        }

        // The three values the manager types at the review. The "request != null" guard on
        // each line handles the empty-body POST described above; without it a body-less
        // call would throw NullPointerException instead of producing a snapshot.
        // EV: entered by the project manager (CdP) at the review; if he entered nothing,
        // the previous snapshot's EV is carried forward.
        BigDecimal evPct = request != null ? request.evPct() : null;
        LocalDate dateFinEstimee = request != null ? request.dateFinEstimee() : null;
        String faitsMarquants = request != null ? request.faitsMarquants() : null;
        // Only EV falls back to the previous snapshot, and on purpose: EV drives the money
        // formulas (production revenue, FAE, current margin), so leaving it empty would
        // blank three financial indicators of the month. The end date and the highlights,
        // on the other hand, are commentary - repeating last month's text as if it were
        // written today would be putting words in the manager's mouth.
        if (evPct == null) {
            evPct = latestSnapshotEv(projectId).evPct();
        }

        // Same engine as the live view; the only difference is that a real date is passed
        // instead of null, which marks the result as belonging to a stored review.
        KpiResponse kpi = buildKpi(project, today, evPct, dateFinEstimee, faitsMarquants);

        // The computed record is copied field by field into the entity.
        // Why copy instead of storing the KpiResponse: the response is the API contract and
        // the entity is the table. Keeping them apart means a new field can be added to the
        // screen without a database migration, and a column can be renamed without breaking
        // every client. Two fields of the response are deliberately NOT copied, because
        // snapshot_kpis has no column for them: margeVenduePct (a baseline that can be
        // recomputed from the project at any time) and warnings (advice about today's data,
        // which would be misleading if replayed months later).
        SnapshotKpi snapshot = SnapshotKpi.builder()
                .project(project)
                .snapshotDate(today)
                .budgetPlanifie(kpi.budgetPlanifie())
                .budgetConsome(kpi.budgetConsome())
                .eac(kpi.eac())
                .marge(kpi.marge())
                .tauxConsommation(kpi.tauxConsommation())
                .evPct(kpi.evPct())
                .deliveryPct(kpi.deliveryPct())
                .consommeJh(kpi.consommeJh())
                .rafJh(kpi.rafJh())
                .deriveJh(kpi.deriveJh())
                .caProduction(kpi.caProduction())
                .totalFacture(kpi.totalFacture())
                .fae(kpi.fae())
                .margeActuelle(kpi.margeActuelle())
                .margeActuellePct(kpi.margeActuellePct())
                .dateFinEstimee(kpi.dateFinEstimee())
                .faitsMarquants(kpi.faitsMarquants())
                .build();

        // save() inserts the row and gives back the managed entity, now carrying its
        // generated id. That returned instance is what is mapped, so the response contains
        // snapshotId - which KpiController needs to build the Location header of the 201.
        // Mapping the pre-save "snapshot" variable instead would send back snapshotId null.
        return snapshotKpiMapper.toResponse(snapshotKpiRepository.save(snapshot));
    }

    // ── The calculation engine ───────────────────────────────────

    /**
     * The single place where every indicator is computed. Both public modes call it, so
     * the live screen and the stored history can never disagree on a formula.
     *
     * It is private and takes an already-loaded Project rather than an id, for two
     * reasons: the caller has already checked the permission and already paid for the
     * SELECT, and being private guarantees it can never become an unguarded entry point
     * into the financial data (see the note about the Spring proxy on the class above).
     *
     * Order of the sections below, which is also the order of the spec:
     *   1. load the raw rows (plan, validated timesheets)
     *   2. load the rates of everyone involved, in two queries, not one per person
     *   3. collect warnings (H-3)
     *   4. costs: planned budget, consumed budget, EAC, margin, consumption rate
     *   5. EVM in man-days: consumed, remaining, drift
     *   6. delivery %
     *   7. revenue side: production revenue, total invoiced, FAE, current margin
     *   8. the sold-margin baseline to compare against
     *
     * @param snapshotDate    null for a live calculation, today's date for a snapshot
     * @param evPct           progress 0-100 declared by the manager, may be null
     * @param dateFinEstimee  estimated end date declared by the manager, may be null
     * @param faitsMarquants  free-text highlights of the review, may be null
     * @return a fully built KpiResponse whose snapshotId is always null (the caller sets
     *         it, or rather the mapper does, once the row has been saved)
     */
    private KpiResponse buildKpi(Project project, LocalDate snapshotDate,
                                 BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {
        Long projectId = project.getId();

        // The two raw inputs of the whole cost calculation.
        // findActiveByProjectId  -> every non-deleted PLAN row (who was supposed to work
        //                           how many days, in which month).
        // findValidatedByProjectId -> only timesheet rows whose validatedAt is not null.
        // Why "validated" and not simply "active": a submitted but unapproved timesheet is
        // a claim, not a fact. Counting it would let a developer change the consumed budget
        // and the margin of a project on his own, just by typing a number - the validation
        // by the project manager is what makes the day a cost.
        List<PlanCharge>   planCharges   = planChargeRepository.findActiveByProjectId(projectId);
        List<ChargeReelle> actualCharges = chargeReelleRepository.findValidatedByProjectId(projectId);

        // Build the set of every user id that appears in either list, so their rates can be
        // fetched in ONE query instead of one query per row.
        // Stream.concat glues the two streams end to end; Collectors.toSet() removes the
        // duplicates (the same person appears in twelve monthly rows, and in both lists).
        // Why it matters: a project with 10 people over 12 months has around 240 rows. One
        // query per row is the classic "N+1 queries" problem - 240 round trips to
        // PostgreSQL for a screen that must open instantly. Here it is exactly 2 queries,
        // whatever the size of the project.
        Set<Long> userIds = Stream.concat(
                planCharges.stream().map(pc -> pc.getUser().getId()),
                actualCharges.stream().map(cr -> cr.getUser().getId())
        ).collect(Collectors.toSet());

        // userId -> Resource (the base daily rate and TCC rate of that person).
        // The empty-set branch is not a micro-optimisation: with an empty collection the
        // JPQL "WHERE r.user.id IN :userIds" would have to be sent with an empty list,
        // which is a pointless round trip and is rejected outright by some databases. A
        // brand-new project with no plan and no timesheet hits this branch every time.
        // Map.of() returns an immutable empty map, so nothing downstream can add to it by
        // accident and hide a missing rate.
        // Collectors.toMap is called here with only TWO arguments, with no third
        // "merge" function to decide what to do when two rows give the same key.
        // WHY that is correct: the key is the user id, and resources.user_id carries a
        // UNIQUE constraint in the database - uk_resources_user_id, added by migration V4
        // and kept absolute by V18, meaning it applies to soft-deleted rows too. The entity
        // says the same thing: Resource.user is @OneToOne with @JoinColumn(unique = true).
        // One user therefore has at most one Resource, so no key can appear twice here.
        // WHAT IF IT EVER DID: toMap would throw IllegalStateException and the KPI screen
        // would fail loudly. That is the behaviour we want and it is why no merge function
        // is added. A merge function would silently keep one of the two rows, and since a
        // Resource carries the daily rate used to price the whole project, the screen would
        // then show cost figures computed from a rate nobody chose - a wrong number that
        // looks right. A page that breaks gets fixed; a wrong amount gets invoiced.
        Map<Long, Resource> resourceMap = userIds.isEmpty()
                ? Map.of()
                : resourceRepository.findActiveByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(r -> r.getUser().getId(), r -> r));

        // Per-year TCC rates (F-AFF-13 §6.3 rule 4): the rate of the year the day was
        // charged to wins; if there is no row for that year, the base rate of the resource
        // applies. TCC is the company's loading coefficient on top of the daily rate
        // (overheads), and it is renegotiated every year: TCC 2024 is not TCC 2025.
        //
        // Shape of the structure: userId -> (year -> TccAnnuel). A nested map, because the
        // lookup done for every single charge row is exactly "this person, that year", and
        // a HashMap answers it in constant time. Scanning a flat list for each of the 240
        // rows would be 240 × (number of yearly rates) comparisons.
        //
        // What goes wrong without this block: a day worked in 2024 would be costed at the
        // 2025 rate. On a multi-year project that silently moves the margin by several
        // points, and the figure can never be reconciled with the accounting.
        Map<Long, Map<Integer, TccAnnuel>> tccByUserYear = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (TccAnnuel t : tccAnnuelRepository.findActiveByUserIdIn(userIds)) {
                // computeIfAbsent creates the inner map the first time this user is seen and
                // reuses it afterwards. Written as get()/put() it would need a null test on
                // every iteration, and forgetting it is a NullPointerException.
                tccByUserYear.computeIfAbsent(t.getResource().getUser().getId(), k -> new HashMap<>())
                             .put(t.getAnnee(), t);
            }
        }

        // H-3 : collect the users who have no daily rate configured. chargeCost() below
        // counts their days as ZERO cost, which is the only sane arithmetic choice but is
        // dangerous if nobody is told: the project would look cheaper than it is and the
        // margin would look better than it is. H-3 is the audit finding that asked for this
        // silence to be broken, so the numbers come back with an explicit warning attached
        // instead of a wrong figure presented as certain.
        List<String> warnings = new ArrayList<>();
        if (!userIds.isEmpty()) {
            // userId -> display name, so the warning can name the person instead of an id.
            // The third argument (a, b) -> a is the MERGE FUNCTION and it is mandatory here:
            // the same user appears in many rows, and Collectors.toMap throws
            // IllegalStateException("Duplicate key") the second time it meets a key unless
            // it is told what to do. "Keep the first one" is correct because both values are
            // the same person's name. Without this argument the whole KPI screen would
            // crash with a 500 as soon as somebody has two months of plan.
            Map<Long, String> userNames = Stream.concat(
                    planCharges.stream().map(pc -> pc.getUser()),
                    actualCharges.stream().map(cr -> cr.getUser())
            ).collect(Collectors.toMap(u -> u.getId(), u -> u.getFullName(), (a, b) -> a));

            userIds.stream()
                    // keep only the users for whom no active Resource row was found
                    .filter(uid -> !resourceMap.containsKey(uid))
                    .forEach(uid -> {
                        // getOrDefault gives a fallback label if the name could not be
                        // resolved, so the warning is still shown instead of printing "null".
                        String name = userNames.getOrDefault(uid, "Utilisateur #" + uid);
                        warnings.add("Tarif journalier manquant pour « " + name + " » — coût compté à 0");
                    });
        }

        // ── 4. Costs ──
        // Planned budget = Σ over every plan row of (planned days × loaded daily rate).
        // Consumed budget = the same over every VALIDATED timesheet row.
        // reduce(BigDecimal.ZERO, BigDecimal::add) sums the stream starting from zero; the
        // starting value is what makes an empty project return 0 instead of an empty
        // Optional the caller would have to unwrap. BigDecimal has no "+" operator in Java,
        // which is why the sum is written this way rather than with a loop and "+=".
        BigDecimal budgetPlanifie = planCharges.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetConsome = actualCharges.stream()
                .map(cr -> chargeCost(cr.getActualDays(), cr.getUser().getId(), cr.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // EAC ("Estimate At Completion") = what the project will have cost in the end
        //     = money already spent + money still to spend on the plan that has not been
        //       consumed yet. The second part is the ETC ("Estimate To Complete").
        //
        // "Not consumed yet" is decided per (person, month) pair. actualKeys is the set of
        // pairs that already have a validated timesheet; any plan row whose pair is in that
        // set is considered done and is not counted again.
        //
        // Why a Set of strings "userId:period" and not two nested loops: Set.contains() is
        // constant time, so the filter below costs one lookup per plan row. Comparing every
        // plan row against every timesheet row would be 240 × 240 comparisons on a normal
        // project, for a screen that must feel instant. The ":" separator is safe because
        // both parts are machine values (a number and a date), not user text.
        //
        // Without this de-duplication, EAC would add the plan of a month on top of the real
        // cost of that same month, and every finished project would report a cost close to
        // double its real one.
        Set<String> actualKeys = actualCharges.stream()
                .map(cr -> cr.getUser().getId() + ":" + cr.getPeriod())
                .collect(Collectors.toSet());

        // The plan rows that are still "in front of us": one line per person and month for
        // which no validated timesheet exists yet. This same list is reused twice below, in
        // money (etcCost) and in man-days (rafJh) - the two figures are then guaranteed to
        // describe the same remaining work.
        // toList() returns an unmodifiable list, so no later step can quietly add a row to
        // the remaining work after the cost has been computed from it.
        List<PlanCharge> remainingPlan = planCharges.stream()
                .filter(pc -> !actualKeys.contains(pc.getUser().getId() + ":" + pc.getPeriod()))
                .toList();

        BigDecimal etcCost = remainingPlan.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal eac = budgetConsome.add(etcCost);

        // Budget of the project already converted into TND by the Project entity (costs are
        // computed from daily rates expressed in TND) - ADR-020: every KPI figure is then
        // homogeneous in TND, whatever currency the project was sold in.
        // getBudgetTnd() is a DERIVED getter, not a column: it is (revised budget if an
        // amendment exists, otherwise initial budget) × exchange rate, recomputed on every
        // read. That is the same rule as the DI: a computed amount is never stored, so an
        // amendment signed today cannot leave an old total frozen somewhere. It returns
        // null when no budget has been entered at all, which is why every line below tests
        // budgetTnd for null.
        BigDecimal budgetTnd = project.getBudgetTnd();
        // Margin = budget − EAC: what is expected to be left at the end of the project.
        // It stays null when the project has no budget yet, instead of being reported as a
        // large negative number. Showing "−184 000 TND" for a project whose budget has
        // simply not been entered would trigger a false alarm at the review; an empty cell
        // says honestly "not known yet".
        BigDecimal marge     = budgetTnd != null ? budgetTnd.subtract(eac) : null;
        BigDecimal tauxConsommation = null;
        // The "> 0" test is not decoration: BigDecimal.divide by zero throws
        // ArithmeticException and would turn the whole KPI screen into a 500 error for any
        // project whose budget is still 0. It also protects against a negative budget, for
        // which a consumption rate would mean nothing.
        // 4 decimal places: the value is a ratio, not a percentage - 0.7512 means 75,12 %,
        // and the frontend does the × 100. Keeping 4 digits means the percentage displayed
        // with 2 decimals is exact. HALF_UP is the rounding everyone expects (0.5 goes up):
        // 0.12345 becomes 0.1235. A BigDecimal division with a scale must always be given a
        // rounding mode, so the choice is explicit here rather than left to a default.
        if (budgetTnd != null && budgetTnd.compareTo(BigDecimal.ZERO) > 0) {
            tauxConsommation = budgetConsome.divide(budgetTnd, 4, RoundingMode.HALF_UP);
        }

        // ── 5. EVM indicators (F-AFF-13 §5, formulas from the Glossary) ──

        // Consumed / remaining / drift, in man-days (JH). Note the reference: drift is
        // measured against the workload that was SOLD to the client, not against the
        // internal plan. The plan can be revised at any time by the project manager, so
        // measuring against it would make the drift disappear exactly when it appears.
        BigDecimal consommeJh = actualCharges.stream()
                .map(ChargeReelle::getActualDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        // RAF = "reste à faire", the work still to do, taken from the same remainingPlan
        // list used for etcCost above.
        BigDecimal rafJh = remainingPlan.stream()
                .map(PlanCharge::getPlannedDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Drift = sold − consumed − remaining. A negative value is the alarm: the project
        // will need more days than were sold, so the margin is being eaten.
        // Null when the sold workload was never entered, for the same honesty reason as the
        // margin above: without the sold figure, "drift" has no meaning at all.
        BigDecimal deriveJh = project.getSoldWorkloadDays() != null
                ? project.getSoldWorkloadDays().subtract(consommeJh).subtract(rafJh)
                : null;

        // ── 6. Delivery % = delivered deliverables / planned deliverables × 100 ──
        // This is the only progress figure the system can compute by itself, and it is
        // deliberately kept next to the manager's EV: when the two disagree strongly
        // (EV 80 %, delivery 20 %) the review has a question to ask.
        List<Livrable> livrables = livrableRepository.findActiveByProjectId(projectId);
        BigDecimal deliveryPct = null;
        // The isEmpty() guard avoids a division by zero for a project that has no
        // deliverables declared yet. Returning null rather than 0 matters: 0 % would be
        // displayed as a red "nothing delivered" on a project that simply has not listed
        // its deliverables.
        if (!livrables.isEmpty()) {
            long delivered = livrables.stream()
                    // Two states count as delivered: LIVRE (handed over) and VALIDE
                    // (accepted by the client). == is correct here and not equals(),
                    // because StatutLivrable is an enum and every constant is a single
                    // shared instance. Forgetting VALIDE would make the percentage FALL
                    // when the client accepts a deliverable, which is the opposite of the
                    // intended meaning.
                    .filter(l -> l.getStatut() == StatutLivrable.LIVRE || l.getStatut() == StatutLivrable.VALIDE)
                    .count();
            // × 100 BEFORE the division, never after. The division keeps only 2 decimals,
            // so dividing first would give 0.33 for 1/3 and then 33.00 instead of 33.33.
            // Multiplying first keeps the two decimals meaningful.
            deliveryPct = BigDecimal.valueOf(delivered)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(livrables.size()), 2, RoundingMode.HALF_UP);
        }

        // ── 7. The revenue side ──
        // Production revenue ("CA production") = contract total in TND × EV %.
        // In plain words: if the manager says the project is 40 % done, then 40 % of what
        // the client will pay has been EARNED, even if no invoice has been sent yet. This
        // is the "earned value" of EVM, expressed in money.
        // FAE ("facture à établir") = production revenue − what has already been invoiced;
        // it is the revenue that is earned but not yet billed, a real accounting figure.
        BigDecimal caProduction = null;
        if (evPct != null && budgetTnd != null) {
            // ÷ 100 because evPct is stored on a 0-100 scale, not 0-1. Dropping it would
            // multiply the production revenue by one hundred, and the current margin with it.
            caProduction = budgetTnd.multiply(evPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else if (evPct == null) {
            // Explain the empty cells instead of leaving three indicators blank with no
            // reason. Without this message the user sees "-" for production revenue, FAE and
            // current margin and concludes the feature is broken, when in fact the monthly
            // review has simply not been done yet.
            warnings.add("Avancement EV non renseigné — créer un snapshot avec l'EV % pour activer CA production, FAE et marge actuelle");
        }

        // Billing milestones are stored in the CURRENCY OF THE PROJECT; costs above are in
        // TND. This is the single conversion point (ADR-020). Defaulting to ONE when the
        // rate is missing means "the project is already in TND", which is the normal case.
        // Without the null check, a project whose rate was never filled in would throw a
        // NullPointerException and the whole KPI screen would fail.
        BigDecimal exchangeRate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;
        BigDecimal totalFacture = jalonFacturationRepository.findActiveByProjectId(projectId).stream()
                // Only milestones actually invoiced (FACTURE) or already paid (PAYE) count.
                // A milestone that is merely planned or reached is not revenue yet; counting
                // it would let the FAE go negative and would show money the company has no
                // right to claim.
                .filter(j -> j.getStatut() == JalonStatut.FACTURE || j.getStatut() == JalonStatut.PAYE)
                .map(JalonFacturation::getMontant)
                // A milestone can exist with no amount entered yet. Without this filter the
                // sum would hit a null and throw NullPointerException, so one incomplete
                // milestone row would take down the whole KPI screen of that project.
                .filter(m -> m != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                // Convert AFTER summing, not per milestone: one multiplication and one
                // rounding instead of N, so the total cannot drift by a few millimes
                // because each line was rounded separately.
                .multiply(exchangeRate)
                // Money is always kept at 2 decimals; multiplying by a rate such as 3.2891
                // would otherwise leave six decimals in a displayed amount.
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal fae = caProduction != null ? caProduction.subtract(totalFacture) : null;

        // Current margin = production revenue − cost incurred so far.
        // Note it uses budgetConsome (real spending), not eac (forecast): this answers
        // "where do I stand today", while marge above answers "where will I land".
        BigDecimal margeActuelle = caProduction != null ? caProduction.subtract(budgetConsome) : null;
        BigDecimal margeActuellePct = null;
        // Two guards in one line. "margeActuelle != null" also proves caProduction is not
        // null, since the previous line only produces a value when caProduction exists - so
        // the caProduction.compareTo() that follows is safe. "> 0" prevents the division by
        // zero of a project whose EV is still 0 %, which would otherwise throw
        // ArithmeticException and return a 500 instead of the indicators.
        if (margeActuelle != null && caProduction.compareTo(BigDecimal.ZERO) > 0) {
            // Again a 0-1 ratio with 4 decimals, matching tauxConsommation, so the frontend
            // formats both the same way.
            margeActuellePct = margeActuelle.divide(caProduction, 4, RoundingMode.HALF_UP);
        }

        // ── 8. The baseline to compare against ──
        // Sold margin: the margin the company committed to when it sold the project. The
        // whole point of the current margin above is to be read next to this number.
        // Two sources, in order of trust:
        //   1. the computed Devis Interne (DI, the internal quote) when its lines exist -
        //      a figure derived from real cost prices, line by line;
        //   2. otherwise the margin typed on the project identification sheet.
        // Optional.orElse() expresses exactly that fallback: use the computed one, and only
        // if it is absent fall back to the declared one. Without the fallback, every project
        // created before the DI feature existed would show an empty baseline and the current
        // margin would have nothing to be compared with.
        //
        // Worth knowing for the defence: DevisInterneService.computeMargeVenduePct is the
        // ONLY method of that service with no @PreAuthorize("hasAuthority('MANAGE_DI')").
        // That is intentional. The DI holds internal cost prices and is Director-only, but
        // the single aggregated percentage is a steering indicator. So a VIEW_KPI holder
        // gets the percentage and still cannot see one line of the quote.
        BigDecimal margeVenduePct = devisInterneService.computeMargeVenduePct(projectId)
                .orElse(project.getMargeNetteVendue());

        // The first argument is snapshotId, always null here: buildKpi never knows a
        // database id. For a live call it stays null, and for a snapshot the id is set later
        // by SnapshotKpiMapper once the row has actually been inserted. The record is
        // positional, so the order of these arguments must match KpiResponse field for
        // field - swapping two BigDecimal arguments would compile and silently show the
        // consumed budget in the margin column.
        return new KpiResponse(null, project.getId(), project.getCode(),
                snapshotDate, budgetPlanifie, budgetConsome, eac, marge, tauxConsommation,
                evPct, deliveryPct, consommeJh, rafJh, deriveJh,
                caProduction, totalFacture, fae, margeActuelle, margeActuellePct, margeVenduePct,
                dateFinEstimee, faitsMarquants, warnings);
    }

    /**
     * Cost of one charge line: man-days × the TCC daily rate of the year the line belongs
     * to (F-AFF-13 §6.3 rule 4), and the base rate of the resource if that year has no
     * specific rate. Returns an amount in TND rounded to 2 decimals.
     *
     * The formula is: days × dailyRate × (1 + tccRate).
     * tccRate is stored as a coefficient, for instance 0.35 for 35 % of overheads, so
     * "+ 1" turns it into the multiplier 1.35. The result is the LOADED cost - what the
     * person really costs the company, not what appears on the payslip. Forgetting the
     * "+ 1" would cost a day at 35 % of the rate instead of 135 %, and every margin in the
     * application would look excellent.
     *
     * Why it takes the two maps as parameters instead of querying the database itself:
     * this method is called once per charge row, hundreds of times per screen. Looking up
     * a rate in memory costs nothing; a query here would bring back the N+1 problem that
     * buildKpi took two queries to avoid.
     *
     * @param period the month the days were charged to; only its YEAR is used
     */
    private BigDecimal chargeCost(BigDecimal days, Long userId, LocalDate period,
                                  Map<Long, Resource> resourceMap,
                                  Map<Long, Map<Integer, TccAnnuel>> tccByUserYear) {
        // getOrDefault(userId, Map.of()) avoids a null check: a user with no yearly rate at
        // all gets an empty map, and .get(year) on it simply returns null. Written as
        // tccByUserYear.get(userId).get(...) it would throw NullPointerException for the
        // first person who has no yearly rate configured.
        // The period null test is defensive: period is NOT NULL in both tables, but this
        // method is also the one place where a null would be silently catastrophic
        // (LocalDate.getYear() on null throws), so it degrades to the base rate instead.
        TccAnnuel yearly = period != null
                ? tccByUserYear.getOrDefault(userId, Map.of()).get(period.getYear())
                : null;
        // Priority 1: the rate negotiated for that exact year.
        if (yearly != null) {
            return days.multiply(yearly.getDailyRate())
                    .multiply(yearly.getTccRate().add(BigDecimal.ONE))
                    // Round once, at the end of the line. Every cost line in the
                    // application is rounded the same way, so the total of the screen always
                    // equals the sum of the lines the user can see.
                    .setScale(2, RoundingMode.HALF_UP);
        }
        // Priority 2: the base rate on the resource sheet.
        Resource resource = resourceMap.get(userId);
        // Priority 3: no rate at all -> count the days as free rather than crash. This is
        // the silent zero that finding H-3 is about; buildKpi has already added a warning
        // naming this person, so the number is never presented as if it were complete.
        if (resource == null) return BigDecimal.ZERO;
        return days.multiply(resource.getDailyRate())
                .multiply(resource.getTccRate().add(BigDecimal.ONE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Small carrier for the three values a snapshot holds that cannot be computed: the
     * manager's progress estimate, his estimated end date and his highlights.
     *
     * Why a private record inside the class rather than three separate lookups or an
     * Object[]: latestSnapshotEv() has to return three values at once and a Java method
     * returns one thing. A record gives them names, makes them immutable and costs one
     * line. It is private because it is an internal detail - it must never leak into the
     * API, where KpiResponse is the contract.
     */
    private record SnapshotEv(BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {}

    /**
     * Finds the most recent snapshot of a project and returns its three manual values, or
     * a record of three nulls when the project has never been reviewed.
     *
     * Why it returns an empty record instead of null: every caller would otherwise need a
     * null test before reading evPct, and the one that forgot it would crash the KPI
     * screen. Here the "no snapshot yet" case flows naturally into "EV is unknown", which
     * buildKpi already handles by adding a warning.
     */
    private SnapshotEv latestSnapshotEv(Long projectId) {
        return snapshotKpiRepository.findActiveByProjectId(projectId).stream()
                // max() by snapshot date rather than trusting the ORDER BY of the query.
                // The query does sort newest first today, but the picking rule is stated
                // here, so a change to that ORDER BY for the sake of a screen cannot
                // silently make the live view show the EV of an old review.
                .max(Comparator.comparing(SnapshotKpi::getSnapshotDate))
                .map(s -> new SnapshotEv(s.getEvPct(), s.getDateFinEstimee(), s.getFaitsMarquants()))
                .orElse(new SnapshotEv(null, null, null));
    }

    /**
     * Loads a project by id or fails with a 404.
     *
     * findActiveById filters out soft-deleted rows (deleted = true), so an archived
     * project behaves exactly like one that never existed. Without that filter, deleting a
     * project would hide it from every list while its financial indicators stayed
     * reachable by URL.
     *
     * orElseThrow turns the empty Optional into NotFoundException, which the global
     * exception handler maps to HTTP 404. Returning null instead would push a
     * NullPointerException a few lines later and answer 500 - the client could not tell a
     * wrong id from a broken server.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
