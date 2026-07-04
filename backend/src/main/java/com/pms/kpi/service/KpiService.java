package com.pms.kpi.service;

import com.pms.kpi.dto.KpiResponse;
import com.pms.kpi.entity.SnapshotKpi;
import com.pms.kpi.mapper.SnapshotKpiMapper;
import com.pms.kpi.repository.SnapshotKpiRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import com.pms.user.entity.Resource;
import com.pms.user.repository.ResourceRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class KpiService {

    private final ProjectRepository         projectRepository;
    private final PlanChargeRepository      planChargeRepository;
    private final ChargeReelleRepository    chargeReelleRepository;
    private final ResourceRepository        resourceRepository;
    private final SnapshotKpiRepository     snapshotKpiRepository;
    private final SnapshotKpiMapper         snapshotKpiMapper;

    // ── Calcul en temps réel ──────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public KpiResponse computeLive(Long projectId) {
        Project project = loadProject(projectId);
        return buildKpi(project, null);
    }

    // ── Snapshot ──────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional(readOnly = true)
    public List<KpiResponse> findSnapshots(Long projectId) {
        loadProject(projectId);
        return snapshotKpiMapper.toResponseList(snapshotKpiRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('VIEW_KPI')")
    @Transactional
    public KpiResponse createSnapshot(Long projectId) {
        Project project = loadProject(projectId);
        LocalDate today = LocalDate.now();

        if (snapshotKpiRepository.existsByProjectIdAndSnapshotDateAndDeletedFalse(projectId, today)) {
            throw new IllegalArgumentException("Un snapshot existe déjà pour ce projet à la date d'aujourd'hui");
        }

        KpiResponse kpi = buildKpi(project, today);

        SnapshotKpi snapshot = SnapshotKpi.builder()
                .project(project)
                .snapshotDate(today)
                .budgetPlanifie(kpi.budgetPlanifie())
                .budgetConsome(kpi.budgetConsome())
                .eac(kpi.eac())
                .marge(kpi.marge())
                .tauxConsommation(kpi.tauxConsommation())
                .build();

        return snapshotKpiMapper.toResponse(snapshotKpiRepository.save(snapshot));
    }

    // ── Moteur de calcul ─────────────────────────────────────────

    private KpiResponse buildKpi(Project project, LocalDate snapshotDate) {
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

        BigDecimal budgetPlanifie = computeCost(planCharges,
                pc -> pc.getUser().getId(), PlanCharge::getPlannedDays, resourceMap);
        BigDecimal budgetConsome  = computeCost(actualCharges,
                cr -> cr.getUser().getId(), ChargeReelle::getActualDays, resourceMap);

        // EAC : coût réel + coût planifié des périodes sans charge réelle validée
        Set<String> actualKeys = actualCharges.stream()
                .map(cr -> cr.getUser().getId() + ":" + cr.getPeriod())
                .collect(Collectors.toSet());

        BigDecimal coveredPlanCost = planCharges.stream()
                .filter(pc -> actualKeys.contains(pc.getUser().getId() + ":" + pc.getPeriod()))
                .map(pc -> chargeCost(pc.getPlannedDays(), resourceMap.get(pc.getUser().getId())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal eac = budgetConsome.add(budgetPlanifie.subtract(coveredPlanCost));

        // Budget converti en TND (les coûts sont calculés via des tarifs journaliers en TND) — ADR-020 :
        // toutes les grandeurs KPI sont ainsi homogènes en TND, quelle que soit la devise du projet.
        BigDecimal budgetTnd = project.getBudgetTnd();
        BigDecimal marge     = budgetTnd != null ? budgetTnd.subtract(eac) : null;
        BigDecimal tauxConsommation = null;
        if (budgetTnd != null && budgetTnd.compareTo(BigDecimal.ZERO) > 0) {
            tauxConsommation = budgetConsome.divide(budgetTnd, 4, RoundingMode.HALF_UP);
        }

        return new KpiResponse(null, project.getId(), project.getCode(),
                snapshotDate, budgetPlanifie, budgetConsome, eac, marge, tauxConsommation);
    }

    private <T> BigDecimal computeCost(List<T> charges,
                                       Function<T, Long> userIdExtractor,
                                       Function<T, BigDecimal> daysExtractor,
                                       Map<Long, Resource> resourceMap) {
        return charges.stream()
                .map(c -> chargeCost(daysExtractor.apply(c), resourceMap.get(userIdExtractor.apply(c))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal chargeCost(BigDecimal days, Resource resource) {
        if (resource == null) return BigDecimal.ZERO;
        return days
                .multiply(resource.getDailyRate())
                .multiply(resource.getTccRate().add(BigDecimal.ONE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }
}
