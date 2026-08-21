package com.pms.billing;

import com.pms.billing.dto.AvenantRequest;
import com.pms.billing.dto.AvenantResponse;
import com.pms.billing.entity.Avenant;
import com.pms.billing.mapper.AvenantMapper;
import com.pms.billing.repository.AvenantRepository;
import com.pms.billing.service.AvenantService;
import com.pms.billing.service.JalonService;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvenantServiceTest {

    @Mock AvenantRepository  avenantRepository;
    @Mock ProjectRepository  projectRepository;
    @Mock AvenantMapper      avenantMapper;
    @Mock JalonService       jalonService;

    @InjectMocks AvenantService service;

    // ── Budget accumulation ───────────────────────────────────────

    @Test
    @DisplayName("create — le budget révisé augmente du montant de l'avenant")
    void create_addsToRevisedBudget() {
        // Initial budget 100 000, no prior revised budget
        Project project = stubProject(1L, BigDecimal.valueOf(100_000), null);
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(avenantRepository.save(any())).thenReturn(mock(Avenant.class));
        when(avenantMapper.toResponse(any())).thenReturn(mock(AvenantResponse.class));

        service.create(1L, new AvenantRequest("AV-001", "Scope extension", BigDecimal.valueOf(20_000), null, LocalDate.now()));

        // Expected revised = 100 000 (effective) + 20 000 = 120 000
        verify(project).setRevisedBudget(BigDecimal.valueOf(120_000));
        verify(projectRepository).save(project);
        verify(jalonService).recomputePrevuMontants(project);
    }

    @Test
    @DisplayName("create — s'accumule correctement sur un budget déjà révisé")
    void create_accumulatesOnExistingRevisedBudget() {
        // Revised budget already 150 000 (after a first avenant of 50 000 on 100k initial)
        Project project = stubProject(1L, BigDecimal.valueOf(100_000), BigDecimal.valueOf(150_000));
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(avenantRepository.save(any())).thenReturn(mock(Avenant.class));
        when(avenantMapper.toResponse(any())).thenReturn(mock(AvenantResponse.class));

        service.create(1L, new AvenantRequest("AV-002", "Additional scope", BigDecimal.valueOf(30_000), null, LocalDate.now()));

        // Expected: 150 000 (effective) + 30 000 = 180 000
        verify(project).setRevisedBudget(BigDecimal.valueOf(180_000));
    }

    // ── Budget inversion on delete ─────────────────────────────────

    @Test
    @DisplayName("delete — le budget révisé diminue du montant de l'avenant supprimé")
    void delete_subtractsAvenantAmount() {
        Project project = stubProject(1L, BigDecimal.valueOf(100_000), BigDecimal.valueOf(150_000));
        Avenant avenant = stubAvenant(42L, project, BigDecimal.valueOf(50_000));

        when(avenantRepository.findActiveById(42L)).thenReturn(Optional.of(avenant));

        service.delete(1L, 42L);

        // 150 000 (effective) - 50 000 = 100 000
        verify(project).setRevisedBudget(BigDecimal.valueOf(100_000));
        verify(avenant).setDeleted(true);
        verify(projectRepository).save(project);
        verify(jalonService).recomputePrevuMontants(project);
    }

    @Test
    @DisplayName("delete — deux avenants consécutifs : le second est correctement annulé")
    void delete_secondAvenant_revertsToFirstAvenantState() {
        // After two avenants: effective = 200 000
        Project project = stubProject(1L, BigDecimal.valueOf(100_000), BigDecimal.valueOf(200_000));
        // Deleting the second avenant of 50 000
        Avenant avenant = stubAvenant(99L, project, BigDecimal.valueOf(50_000));

        when(avenantRepository.findActiveById(99L)).thenReturn(Optional.of(avenant));

        service.delete(1L, 99L);

        // 200 000 - 50 000 = 150 000
        verify(project).setRevisedBudget(BigDecimal.valueOf(150_000));
    }

    @Test
    @DisplayName("delete — jalon recomputé après annulation de l'avenant")
    void delete_triggersJalonRecompute() {
        Project project = stubProject(1L, BigDecimal.valueOf(100_000), BigDecimal.valueOf(120_000));
        Avenant avenant = stubAvenant(5L, project, BigDecimal.valueOf(20_000));

        when(avenantRepository.findActiveById(5L)).thenReturn(Optional.of(avenant));

        service.delete(1L, 5L);

        verify(jalonService).recomputePrevuMontants(project);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private Project stubProject(Long id, BigDecimal initial, BigDecimal revised) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(id);
        // getEffectiveBudget() returns revised if set, otherwise initial (mirrors entity logic)
        when(p.getEffectiveBudget()).thenReturn(revised != null ? revised : initial);
        return p;
    }

    private Avenant stubAvenant(Long id, Project project, BigDecimal montant) {
        Avenant a = mock(Avenant.class);
        when(a.getId()).thenReturn(id);
        when(a.getProject()).thenReturn(project);
        when(a.getMontant()).thenReturn(montant);
        return a;
    }
}
