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

@Service
@RequiredArgsConstructor
public class KpiService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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

    // ── Calcul en temps réel ──────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public KpiResponse computeLive(Long projectId) {
        Project project = loadProject(projectId);
        // EV live = dernier snapshot (l'EV est une estimation mensuelle du CdP)
        SnapshotEv latest = latestSnapshotEv(projectId);
        return buildKpi(project, null, latest.evPct(), latest.dateFinEstimee(), latest.faitsMarquants());
    }

    // ── Snapshot ──────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public List<KpiResponse> findSnapshots(Long projectId) {
        loadProject(projectId);
        return snapshotKpiMapper.toResponseList(snapshotKpiRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('EDIT_PROJECT')")
    @Transactional
    public KpiResponse createSnapshot(Long projectId, SnapshotRequest request) {
        Project project = loadProject(projectId);
        LocalDate today = LocalDate.now();

        if (snapshotKpiRepository.existsByProjectIdAndSnapshotDateAndDeletedFalse(projectId, today)) {
            throw new IllegalArgumentException("Un snapshot existe déjà pour ce projet à la date d'aujourd'hui");
        }

        // EV : saisi par le CdP à la revue ; à défaut, on reprend le dernier snapshot
        BigDecimal evPct = request != null ? request.evPct() : null;
        LocalDate dateFinEstimee = request != null ? request.dateFinEstimee() : null;
        String faitsMarquants = request != null ? request.faitsMarquants() : null;
        if (evPct == null) {
            evPct = latestSnapshotEv(projectId).evPct();
        }

        KpiResponse kpi = buildKpi(project, today, evPct, dateFinEstimee, faitsMarquants);

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

        return snapshotKpiMapper.toResponse(snapshotKpiRepository.save(snapshot));
    }

    // ── Moteur de calcul ─────────────────────────────────────────

    private KpiResponse buildKpi(Project project, LocalDate snapshotDate,
                                 BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {
        Long projectId = project.getId();

        List<PlanCharge>   planCharges   = planChargeRepository.findActiveByProjectId(projectId);
        List<ChargeReelle> actualCharges = chargeReelleRepository.findValidatedByProjectId(projectId);

        // Charger les ressources de tous les membres concernés (une seule requête)
        Set<Long> userIds = Stream.concat(
                planCharges.stream().map(pc -> pc.getUser().getId()),
                actualCharges.stream().map(cr -> cr.getUser().getId())
        ).collect(Collectors.toSet());

        Map<Long, Resource> resourceMap = userIds.isEmpty()
                ? Map.of()
                : resourceRepository.findActiveByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(r -> r.getUser().getId(), r -> r));

        // TCC par année (F-AFF-13 §6.3 règle 4) : tarif de l'année d'imputation, sinon tarif de base
        Map<Long, Map<Integer, TccAnnuel>> tccByUserYear = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (TccAnnuel t : tccAnnuelRepository.findActiveByUserIdIn(userIds)) {
                tccByUserYear.computeIfAbsent(t.getResource().getUser().getId(), k -> new HashMap<>())
                             .put(t.getAnnee(), t);
            }
        }

        // H-3 : collecter les utilisateurs sans tarif journalier configuré (leur coût est compté à 0 silencieusement)
        List<String> warnings = new ArrayList<>();
        if (!userIds.isEmpty()) {
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

        BigDecimal budgetPlanifie = planCharges.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetConsome = actualCharges.stream()
                .map(cr -> chargeCost(cr.getActualDays(), cr.getUser().getId(), cr.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // EAC : coût réel + coût planifié des périodes sans charge réelle validée
        Set<String> actualKeys = actualCharges.stream()
                .map(cr -> cr.getUser().getId() + ":" + cr.getPeriod())
                .collect(Collectors.toSet());

        List<PlanCharge> remainingPlan = planCharges.stream()
                .filter(pc -> !actualKeys.contains(pc.getUser().getId() + ":" + pc.getPeriod()))
                .toList();

        BigDecimal etcCost = remainingPlan.stream()
                .map(pc -> chargeCost(pc.getPlannedDays(), pc.getUser().getId(), pc.getPeriod(), resourceMap, tccByUserYear))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal eac = budgetConsome.add(etcCost);

        // Budget converti en TND (les coûts sont calculés via des tarifs journaliers en TND) — ADR-020 :
        // toutes les grandeurs KPI sont ainsi homogènes en TND, quelle que soit la devise du projet.
        BigDecimal budgetTnd = project.getBudgetTnd();
        BigDecimal marge     = budgetTnd != null ? budgetTnd.subtract(eac) : null;
        BigDecimal tauxConsommation = null;
        if (budgetTnd != null && budgetTnd.compareTo(BigDecimal.ZERO) > 0) {
            tauxConsommation = budgetConsome.divide(budgetTnd, 4, RoundingMode.HALF_UP);
        }

        // ── Indicateurs EVM (F-AFF-13 §5, formules du Glossaire) ──

        // Charge totale consommée / RAF / Dérive (en JH, contre le workload VENDU — pas le plan de charge)
        BigDecimal consommeJh = actualCharges.stream()
                .map(ChargeReelle::getActualDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rafJh = remainingPlan.stream()
                .map(PlanCharge::getPlannedDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deriveJh = project.getSoldWorkloadDays() != null
                ? project.getSoldWorkloadDays().subtract(consommeJh).subtract(rafJh)
                : null;

        // Delivery % = livrables réalisés / livrables planifiés × 100
        List<Livrable> livrables = livrableRepository.findActiveByProjectId(projectId);
        BigDecimal deliveryPct = null;
        if (!livrables.isEmpty()) {
            long delivered = livrables.stream()
                    .filter(l -> l.getStatut() == StatutLivrable.LIVRE || l.getStatut() == StatutLivrable.VALIDE)
                    .count();
            deliveryPct = BigDecimal.valueOf(delivered)
                    .multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(livrables.size()), 2, RoundingMode.HALF_UP);
        }

        // CA Production = total contrat (TND) × EV % ; FAE = CA production − total facturé
        BigDecimal caProduction = null;
        if (evPct != null && budgetTnd != null) {
            caProduction = budgetTnd.multiply(evPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else if (evPct == null) {
            warnings.add("Avancement EV non renseigné — créer un snapshot avec l'EV % pour activer CA production, FAE et marge actuelle");
        }

        BigDecimal exchangeRate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;
        BigDecimal totalFacture = jalonFacturationRepository.findActiveByProjectId(projectId).stream()
                .filter(j -> j.getStatut() == JalonStatut.FACTURE || j.getStatut() == JalonStatut.PAYE)
                .map(JalonFacturation::getMontant)
                .filter(m -> m != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(exchangeRate)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal fae = caProduction != null ? caProduction.subtract(totalFacture) : null;

        // Marge actuelle = CA production − coût actuel
        BigDecimal margeActuelle = caProduction != null ? caProduction.subtract(budgetConsome) : null;
        BigDecimal margeActuellePct = null;
        if (margeActuelle != null && caProduction.compareTo(BigDecimal.ZERO) > 0) {
            margeActuellePct = margeActuelle.divide(caProduction, 4, RoundingMode.HALF_UP);
        }

        // Marge vendue (baseline) : DI calculé s'il existe, sinon la fiche identification
        BigDecimal margeVenduePct = devisInterneService.computeMargeVenduePct(projectId)
                .orElse(project.getMargeNetteVendue());

        return new KpiResponse(null, project.getId(), project.getCode(),
                snapshotDate, budgetPlanifie, budgetConsome, eac, marge, tauxConsommation,
                evPct, deliveryPct, consommeJh, rafJh, deriveJh,
                caProduction, totalFacture, fae, margeActuelle, margeActuellePct, margeVenduePct,
                dateFinEstimee, faitsMarquants, warnings);
    }

    /** Coût d'une charge : JH × tarif TCC de l'année de la période (F-AFF-13 §6.3 règle 4), sinon tarif de base. */
    private BigDecimal chargeCost(BigDecimal days, Long userId, LocalDate period,
                                  Map<Long, Resource> resourceMap,
                                  Map<Long, Map<Integer, TccAnnuel>> tccByUserYear) {
        TccAnnuel yearly = period != null
                ? tccByUserYear.getOrDefault(userId, Map.of()).get(period.getYear())
                : null;
        if (yearly != null) {
            return days.multiply(yearly.getDailyRate())
                    .multiply(yearly.getTccRate().add(BigDecimal.ONE))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        Resource resource = resourceMap.get(userId);
        if (resource == null) return BigDecimal.ZERO;
        return days.multiply(resource.getDailyRate())
                .multiply(resource.getTccRate().add(BigDecimal.ONE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private record SnapshotEv(BigDecimal evPct, LocalDate dateFinEstimee, String faitsMarquants) {}

    private SnapshotEv latestSnapshotEv(Long projectId) {
        return snapshotKpiRepository.findActiveByProjectId(projectId).stream()
                .max(Comparator.comparing(SnapshotKpi::getSnapshotDate))
                .map(s -> new SnapshotEv(s.getEvPct(), s.getDateFinEstimee(), s.getFaitsMarquants()))
                .orElse(new SnapshotEv(null, null, null));
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
