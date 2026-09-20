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
 * KPI engine for one project: planned/consumed budget, EAC, margin, and the F-AFF-13 EVM
 * indicators. Live (computeLive) and snapshot (createSnapshot) modes share one formula engine,
 * buildKpi(), so a frozen review can never silently drift from what the live screen shows.
 */

/**
 * KPI calculation service. Needs a Spring proxy for @PreAuthorize/@Transactional to take effect,
 * so an internal call from one public method to another here would bypass the permission check —
 * which is why buildKpi/chargeCost/latestSnapshotEv/loadProject are private.
 */
@Service
@RequiredArgsConstructor
public class KpiService {

    // Shared constant for the × 100 / ÷ 100 percentage conversions below.
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // Ten collaborators; only snapshotKpiRepository is written to — everything else here is read
    // from another module's table (plan, validated timesheets, rates, deliverables, milestones)
    // or via DevisInterneService for the sold-margin baseline.
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
     * Computes every indicator of one project from current data; nothing is written, so
     * snapshotId/snapshotDate stay null. EV % is not recomputed — it's copied from the latest
     * snapshot, since it's a human judgement (guessing it from spend would defeat the point of EVM).
     *
     * @param projectId project to measure; must be a project the caller is allowed to see
     * @return the full indicator set, with snapshotId/snapshotDate null
     */
    // VIEW_KPI walls developers off from financial data (BR-050); project scope is checked
    // separately by ProjectScopeInterceptor (ADR-021).
    @PreAuthorize("hasAuthority('VIEW_KPI')")
    // readOnly: several SELECTs plus lazy associations need one open transaction (open-in-view is
    // false), and it doubles as a safety net against an accidental write in a read path.
    @Transactional(readOnly = true)
    public KpiResponse computeLive(Long projectId) {
        Project project = loadProject(projectId);
        // EV, end date and highlights are all carried over from the latest snapshot — CdP
        // judgement, not derivable data.
        SnapshotEv latest = latestSnapshotEv(projectId);
        // null date marks this as a live calculation, not a stored review.
        return buildKpi(project, null, latest.evPct(), latest.dateFinEstimee(), latest.faitsMarquants());
    }

    // ── Snapshots (stored history) ────────────────────────────────

    /**
     * Returns the stored snapshots of one project, newest first. loadProject() is called only
     * for its 404, so an unknown project doesn't come back as an empty (misleading) list.
     */
    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public List<KpiResponse> findSnapshots(Long projectId) {
        loadProject(projectId);
        return snapshotKpiMapper.toResponseList(snapshotKpiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Freezes today's indicators into one row of snapshot_kpis and returns it — the "project
     * review" action. Gated on EDIT_PROJECT, not VIEW_KPI: creating a snapshot writes history.
     *
     * @param request may be null; the controller allows an empty body meaning "just freeze
     *                today's numbers, nothing new to declare"
     */
    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    // Writes: reads across a dozen tables then one insert must commit as a single unit, so a
    // duplicate-key failure on the insert rolls everything back instead of leaving a partial state.
    @Transactional
    public KpiResponse createSnapshot(Long projectId, SnapshotRequest request) {
        Project project = loadProject(projectId);
        LocalDate today = LocalDate.now();

        // One snapshot per project per day; the real guarantee is the partial unique index
        // uk_kpi_project_date (V8) — this check just turns a race/duplicate into a clean message
        // instead of a raw constraint-violation 500.
        if (snapshotKpiRepository.existsByProjectIdAndSnapshotDateAndDeletedFalse(projectId, today)) {
            throw new IllegalArgumentException("Un snapshot existe déjà pour ce projet à la date d'aujourd'hui");
        }

        // The three manual values; null-safe for the empty-body POST case.
        BigDecimal evPct = request != null ? request.evPct() : null;
        LocalDate dateFinEstimee = request != null ? request.dateFinEstimee() : null;
        String faitsMarquants = request != null ? request.faitsMarquants() : null;
        // Only EV falls back to the previous snapshot — it drives the money formulas below; the
        // date and notes are commentary that shouldn't be put in the manager's mouth if unset.
        if (evPct == null) {
            evPct = latestSnapshotEv(projectId).evPct();
        }

        // Same engine as the live view; a real date marks this result as a stored review.
        KpiResponse kpi = buildKpi(project, today, evPct, dateFinEstimee, faitsMarquants);

        // Copied field by field rather than storing the DTO itself, so the API and the table can
        // evolve independently. margeVenduePct and warnings are skipped: snapshot_kpis has no
        // column for either (see SnapshotKpiMapper for how margeVenduePct is re-derived on read).
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

        // save() returns the managed entity with its generated id, which the mapper needs for
        // snapshotId (KpiController builds the 201's Location header from it).
        return snapshotKpiMapper.toResponse(snapshotKpiRepository.save(snapshot));
    }

    // ── The calculation engine ───────────────────────────────────

    /**
     * Computes every indicator for one project; both public modes call this so live and stored
     * figures can never disagree on a formula. Private and takes an already-loaded Project since
     * the caller already checked permission and paid for that SELECT.
     *
     * @param snapshotDate    null for a live calculation, today's date for a snapshot
     * @param evPct           progress 0-100 declared by the manager, may be null
     * @param dateFinEstimee  estimated end date declared by the manager, may be null
     * @param faitsMarquants  free-text highlights of the review, may be null
     * @return a fully built KpiResponse whose snapshotId is always null (the mapper sets it once
     *         the row has been saved)
     */
    private KpiResponse buildKpi(Project project, LocalDate snapshotDate,
                                 BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {
        Long projectId = project.getId();

        // Only VALIDATED timesheets count as real cost — an unapproved entry is a claim, not a fact.
        List<PlanCharge>   planCharges   = planChargeRepository.findActiveByProjectId(projectId);
        List<ChargeReelle> actualCharges = chargeReelleRepository.findValidatedByProjectId(projectId);

        // Every user id across both lists, fetched once so rates can be looked up in memory
        // instead of one query per row (a normal project has ~240 rows: N+1 would mean 240 round trips).
        Set<Long> userIds = Stream.concat(
                planCharges.stream().map(pc -> pc.getUser().getId()),
                actualCharges.stream().map(cr -> cr.getUser().getId())
        ).collect(Collectors.toSet());

        // userId -> Resource (base daily rate + TCC). No merge function needed: resources.user_id
        // is UNIQUE (uk_resources_user_id, absolute since V18), so toMap can never see a duplicate
        // key — if it ever did, failing loudly beats silently pricing the project on the wrong rate.
        Map<Long, Resource> resourceMap = userIds.isEmpty()
                ? Map.of()
                : resourceRepository.findActiveByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(r -> r.getUser().getId(), r -> r));

        // Per-year TCC rates (F-AFF-13 §6.3 rule 4): the rate of the year the day was charged to
        // wins, falling back to the resource's base rate. userId -> (year -> TccAnnuel), built once
        // so the 240-odd charge rows below are O(1) lookups instead of a full scan each.
        Map<Long, Map<Integer, TccAnnuel>> tccByUserYear = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (TccAnnuel t : tccAnnuelRepository.findActiveByUserIdIn(userIds)) {
                tccByUserYear.computeIfAbsent(t.getResource().getUser().getId(), k -> new HashMap<>())
                             .put(t.getAnnee(), t);
            }
        }

        // H-3: users with no daily rate configured are costed at 0 by chargeCost() below — the
        // only sane arithmetic, but dangerous if silent, so it's surfaced as an explicit warning.
        List<String> warnings = new ArrayList<>();
        if (!userIds.isEmpty()) {
            // (a, b) -> a: the same user appears in many rows, so a merge function is mandatory
            // here to avoid Collectors.toMap throwing on the second occurrence of a key.
            Map<Long, String> userNames = Stream.concat(
                    planCharges.stream().map(pc -> pc.getUser()),
                    actualCharges.stream().map(cr -> cr.getUser())
            ).collect(Collectors.toMap(u -> u.getId(), u -> u.getFullName(), (a, b) -> a));

            userIds.stream()
                    .filter(uid -> !resourceMap.containsKey(uid))
                    .forEach(uid -> {
                        String name = userNames.getOrDefault(uid, "Utilisateur #" + uid);
                        warnings.add("Tarif journalier manquant pour « " + name + " » — coût compté à 0");
                    });
        }

        // ── 4. Costs ──
        // Planned/consumed budget = Σ (days × loaded daily rate) over the plan / the validated
        // timesheets respectively.
        BigDecimal budgetPlanifie = planCharges.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetConsome = actualCharges.stream()
                .map(cr -> chargeCost(cr.getActualDays(), cr.getUser().getId(), cr.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // EAC = cost already spent + planned cost of the (person, month) pairs that have no
        // validated timesheet yet (the ETC). actualKeys marks pairs already actualised, so they
        // aren't counted a second time from the plan — without this, EAC would roughly double.
        Set<String> actualKeys = actualCharges.stream()
                .map(cr -> cr.getUser().getId() + ":" + cr.getPeriod())
                .collect(Collectors.toSet());

        // Plan rows still ahead of us; reused below both in money (etcCost) and in man-days (rafJh)
        // so the two figures describe exactly the same remaining work.
        List<PlanCharge> remainingPlan = planCharges.stream()
                .filter(pc -> !actualKeys.contains(pc.getUser().getId() + ":" + pc.getPeriod()))
                .toList();

        BigDecimal etcCost = remainingPlan.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal eac = budgetConsome.add(etcCost);

        // Derived getter, not a column (ADR-020/DI rule): (revised or initial budget) × exchange
        // rate, recomputed on every read; null when no budget was ever entered.
        BigDecimal budgetTnd = project.getBudgetTnd();
        // Null rather than a large negative number when there's no budget yet — an honest
        // "unknown" instead of a false alarm at the review.
        BigDecimal marge     = budgetTnd != null ? budgetTnd.subtract(eac) : null;
        BigDecimal tauxConsommation = null;
        // "> 0" guard: dividing by a zero/negative budget would throw ArithmeticException and 500
        // the whole screen. 4 decimals keep the 2-decimal percentage the frontend displays exact.
        if (budgetTnd != null && budgetTnd.compareTo(BigDecimal.ZERO) > 0) {
            tauxConsommation = budgetConsome.divide(budgetTnd, 4, RoundingMode.HALF_UP);
        }

        // ── 5. EVM indicators (F-AFF-13 §5, formulas from the Glossary) ──

        // Consumed / remaining / drift, in man-days (JH). Drift is measured against the workload
        // SOLD to the client, not the internal plan — the plan can be revised at any time, which
        // would make the drift disappear exactly when it appears.
        BigDecimal consommeJh = actualCharges.stream()
                .map(ChargeReelle::getActualDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rafJh = remainingPlan.stream()
                .map(PlanCharge::getPlannedDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Negative drift = the project will need more days than were sold; null when no sold
        // workload was ever entered, for the same honesty reason as marge above.
        BigDecimal deriveJh = project.getSoldWorkloadDays() != null
                ? project.getSoldWorkloadDays().subtract(consommeJh).subtract(rafJh)
                : null;

        // ── 6. Delivery % = delivered deliverables / planned deliverables × 100 ──
        // The only progress figure the system can compute on its own; kept next to the manager's
        // EV so a strong disagreement (EV 80%, delivery 20%) raises a question at the review.
        List<Livrable> livrables = livrableRepository.findActiveByProjectId(projectId);
        BigDecimal deliveryPct = null;
        // Null (not 0) with no deliverables declared, so an empty list doesn't look like a red flag.
        if (!livrables.isEmpty()) {
            long delivered = livrables.stream()
                    // == is correct for an enum. VALIDE must count too, or accepting a deliverable
                    // would make the percentage drop.
                    .filter(l -> l.getStatut() == StatutLivrable.LIVRE || l.getStatut() == StatutLivrable.VALIDE)
                    .count();
            // × 100 before dividing, so 1/3 rounds to 33.33 and not 33.00.
            deliveryPct = BigDecimal.valueOf(delivered)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(livrables.size()), 2, RoundingMode.HALF_UP);
        }

        // ── 7. The revenue side ──
        // CA production = contract total × EV% — the earned-value figure expressed in money.
        // FAE = CA production − what's already invoiced: earned but not yet billed.
        BigDecimal caProduction = null;
        if (evPct != null && budgetTnd != null) {
            // ÷ 100 because evPct is on a 0-100 scale, not 0-1.
            caProduction = budgetTnd.multiply(evPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else if (evPct == null) {
            // Explains the three blank cells instead of leaving them looking like a bug.
            warnings.add("Avancement EV non renseigné — créer un snapshot avec l'EV % pour activer CA production, FAE et marge actuelle");
        }

        // Billing milestones are stored in the project's own currency, costs are in TND — this is
        // the single conversion point (ADR-020). Default rate of 1 means "already in TND".
        BigDecimal exchangeRate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;
        BigDecimal totalFacture = jalonFacturationRepository.findActiveByProjectId(projectId).stream()
                // Only FACTURE/PAYE milestones are real revenue; a merely planned or delivered one isn't.
                .filter(j -> j.getStatut() == JalonStatut.FACTURE || j.getStatut() == JalonStatut.PAYE)
                .map(JalonFacturation::getMontant)
                // A milestone can have no amount yet; skip rather than NPE the whole screen.
                .filter(m -> m != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                // Convert once after summing, not per line, so rounding can't drift the total.
                .multiply(exchangeRate)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal fae = caProduction != null ? caProduction.subtract(totalFacture) : null;

        // Current margin = CA production − cost incurred so far ("where do I stand today", vs.
        // marge above which answers "where will I land").
        BigDecimal margeActuelle = caProduction != null ? caProduction.subtract(budgetConsome) : null;
        BigDecimal margeActuellePct = null;
        // margeActuelle != null already implies caProduction != null; "> 0" avoids a divide-by-zero
        // when EV is still 0%.
        if (margeActuelle != null && caProduction.compareTo(BigDecimal.ZERO) > 0) {
            margeActuellePct = margeActuelle.divide(caProduction, 4, RoundingMode.HALF_UP);
        }

        // ── 8. The baseline to compare against ──
        // Sold margin: prefers the computed Devis Interne (line-by-line, more precise) and falls
        // back to the rate declared on the project sheet, so pre-DI projects still show a baseline.
        // Note: DevisInterneService.computeMargeVenduePct is the one DI method with no MANAGE_DI
        // guard — the aggregated percentage is a steering indicator, unlike the DI's line items.
        BigDecimal margeVenduePct = devisInterneService.computeMargeVenduePct(projectId)
                .orElse(project.getMargeNetteVendue());

        // snapshotId is always null here — for a live call it stays null, for a snapshot the
        // mapper fills it in once the row is actually saved. Positional record: a swapped
        // argument here would compile and silently show the wrong figure in the wrong field.
        return new KpiResponse(null, project.getId(), project.getCode(),
                snapshotDate, budgetPlanifie, budgetConsome, eac, marge, tauxConsommation,
                evPct, deliveryPct, consommeJh, rafJh, deriveJh,
                caProduction, totalFacture, fae, margeActuelle, margeActuellePct, margeVenduePct,
                dateFinEstimee, faitsMarquants, warnings);
    }

    /**
     * Cost of one charge line: days × the TCC daily rate for the year the line belongs to
     * (F-AFF-13 §6.3 rule 4), falling back to the resource's base rate. Takes the two maps as
     * parameters, not a repository, since it runs once per charge row (hundreds of times a call).
     *
     * @param period the month the days were charged to; only its YEAR is used
     */
    private BigDecimal chargeCost(BigDecimal days, Long userId, LocalDate period,
                                  Map<Long, Resource> resourceMap,
                                  Map<Long, Map<Integer, TccAnnuel>> tccByUserYear) {
        // getOrDefault(..., Map.of()) avoids a null check for a user with no yearly rate at all.
        TccAnnuel yearly = period != null
                ? tccByUserYear.getOrDefault(userId, Map.of()).get(period.getYear())
                : null;
        // Priority 1: the rate negotiated for that exact year. "+1" turns the TCC coefficient
        // (e.g. 0.35) into the loaded multiplier (1.35) — the real cost to the company.
        if (yearly != null) {
            return days.multiply(yearly.getDailyRate())
                    .multiply(yearly.getTccRate().add(BigDecimal.ONE))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        // Priority 2: the base rate on the resource sheet.
        Resource resource = resourceMap.get(userId);
        // Priority 3: no rate at all -> 0 rather than crash (the H-3 silent zero; a warning naming
        // this person was already added in buildKpi).
        if (resource == null) return BigDecimal.ZERO;
        return days.multiply(resource.getDailyRate())
                .multiply(resource.getTccRate().add(BigDecimal.ONE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Carries the three values a snapshot holds that can't be computed: EV estimate, expected end
     * date, highlights. Private — must never leak into the API, where KpiResponse is the contract.
     */
    private record SnapshotEv(BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {}

    /**
     * Finds the most recent snapshot of a project and returns its three manual values, or three
     * nulls when the project has never been reviewed — so "no snapshot yet" flows into "EV
     * unknown" without every caller needing its own null check.
     */
    private SnapshotEv latestSnapshotEv(Long projectId) {
        return snapshotKpiRepository.findActiveByProjectId(projectId).stream()
                // Picks by snapshot date explicitly rather than trusting the query's ORDER BY, so
                // a later change to that ORDER BY can't silently pick the wrong review.
                .max(Comparator.comparing(SnapshotKpi::getSnapshotDate))
                .map(s -> new SnapshotEv(s.getEvPct(), s.getDateFinEstimee(), s.getFaitsMarquants()))
                .orElse(new SnapshotEv(null, null, null));
    }

    /**
     * Loads a project by id or throws NotFoundException (404). findActiveById filters
     * soft-deleted rows, so an archived project behaves like one that never existed.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
