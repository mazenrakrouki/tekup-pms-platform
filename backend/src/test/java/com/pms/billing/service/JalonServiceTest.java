package com.pms.billing.service;

import com.pms.billing.dto.FacturerRequest;
import com.pms.billing.dto.JalonRequest;
import com.pms.billing.entity.JalonFacturation;
import com.pms.billing.entity.JalonStatut;
import com.pms.billing.dto.JalonResponse;
import com.pms.billing.mapper.JalonMapper;
import com.pms.billing.repository.JalonFacturationRepository;
import com.pms.billing.repository.PaiementRepository;
import com.pms.project.entity.Project;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JalonServiceTest {

    @Mock JalonFacturationRepository jalonRepository;
    @Mock PaiementRepository         paiementRepository;
    @Mock ProjectRepository          projectRepository;
    @Mock JalonMapper                jalonMapper;

    @InjectMocks JalonService service;

    // ── Percentage sum validation ────────────────────────────────

    @Test
    @DisplayName("create — somme des pourcentages > 100 % lève BusinessRuleException")
    void create_pourcentageSumExceeds100_throws() {
        Project project = stubProject(1L, BigDecimal.valueOf(100_000));
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(jalonRepository.sumPourcentageByProjectId(1L)).thenReturn(BigDecimal.valueOf(80));

        // New jalon at 30% would push sum to 110%
        JalonRequest req = new JalonRequest("Jalon 3", BigDecimal.valueOf(30), LocalDate.now());
        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("100%");
    }

    @Test
    @DisplayName("create — somme exactement 100 % est acceptée")
    void create_pourcentageSumExactly100_success() {
        Project project = stubProject(1L, BigDecimal.valueOf(100_000));
        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(jalonRepository.sumPourcentageByProjectId(1L)).thenReturn(BigDecimal.valueOf(70));
        when(jalonRepository.save(any())).thenReturn(mock(JalonFacturation.class));
        when(jalonMapper.toResponse(any())).thenReturn(mock(JalonResponse.class));

        // 70% + 30% = 100%, should not throw
        service.create(1L, new JalonRequest("Final", BigDecimal.valueOf(30), LocalDate.now()));
    }

    // ── Status machine ────────────────────────────────────────────

    @Test
    @DisplayName("facturer — jalon FACTURE ne peut pas être re-facturé")
    void facturer_alreadyFacture_throwsBusinessRule() {
        JalonFacturation jalon = stubJalon(1L, 1L, JalonStatut.FACTURE, BigDecimal.valueOf(10));
        when(jalonRepository.findActiveById(42L)).thenReturn(Optional.of(jalon));

        assertThatThrownBy(() -> service.facturer(1L, 42L, new FacturerRequest(LocalDate.now())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PREVU");
    }

    @Test
    @DisplayName("facturer — jalon PAYE ne peut pas être facturé")
    void facturer_alreadyPaye_throwsBusinessRule() {
        JalonFacturation jalon = stubJalon(1L, 1L, JalonStatut.PAYE, BigDecimal.valueOf(10));
        when(jalonRepository.findActiveById(42L)).thenReturn(Optional.of(jalon));

        assertThatThrownBy(() -> service.facturer(1L, 42L, new FacturerRequest(LocalDate.now())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("delete — jalon FACTURE ne peut pas être supprimé")
    void delete_facturedJalon_throwsBusinessRule() {
        JalonFacturation jalon = stubJalon(1L, 1L, JalonStatut.FACTURE, BigDecimal.valueOf(10));
        when(jalonRepository.findActiveById(42L)).thenReturn(Optional.of(jalon));

        assertThatThrownBy(() -> service.delete(1L, 42L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("facturé ou payé");
    }

    // ── H-4: recomputePrevuMontants ───────────────────────────────

    @Test
    @DisplayName("recomputePrevuMontants — recalcule le montant de chaque jalon PREVU")
    void recomputePrevuMontants_updatesAllPrevuJalons() {
        Project project = stubProject(1L, BigDecimal.valueOf(200_000));

        JalonFacturation j1 = stubJalon(1L, 1L, JalonStatut.PREVU, BigDecimal.valueOf(50_000)); // 25%
        when(j1.getPourcentage()).thenReturn(BigDecimal.valueOf(25));
        JalonFacturation j2 = stubJalon(2L, 1L, JalonStatut.PREVU, BigDecimal.valueOf(50_000)); // 25%
        when(j2.getPourcentage()).thenReturn(BigDecimal.valueOf(25));

        when(jalonRepository.findByProjectIdAndStatutAndDeletedFalse(1L, JalonStatut.PREVU))
                .thenReturn(List.of(j1, j2));

        service.recomputePrevuMontants(project);

        // Each jalon should be updated to 25% of 200 000 = 50 000
        verify(j1).setMontant(BigDecimal.valueOf(50_000).setScale(2));
        verify(j2).setMontant(BigDecimal.valueOf(50_000).setScale(2));
        verify(jalonRepository).saveAll(List.of(j1, j2));
    }

    @Test
    @DisplayName("recomputePrevuMontants — ne touche pas aux jalons FACTURE et PAYE")
    void recomputePrevuMontants_skipsFactureAndPaye() {
        Project project = stubProject(1L, BigDecimal.valueOf(200_000));
        when(jalonRepository.findByProjectIdAndStatutAndDeletedFalse(1L, JalonStatut.PREVU))
                .thenReturn(List.of()); // no PREVU jalons

        service.recomputePrevuMontants(project);

        verify(jalonRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("recomputePrevuMontants — sans budget effectif, ne fait rien")
    void recomputePrevuMontants_noBudget_skips() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(project.getEffectiveBudget()).thenReturn(null);
        JalonFacturation jalon = mock(JalonFacturation.class);
        when(jalonRepository.findByProjectIdAndStatutAndDeletedFalse(1L, JalonStatut.PREVU))
                .thenReturn(List.of(jalon));

        service.recomputePrevuMontants(project);

        verify(jalonRepository, never()).saveAll(any());
    }

    // ── recalculerStatut ──────────────────────────────────────────

    @Test
    @DisplayName("recalculerStatut — total paiements >= montant → PAYE")
    void recalculerStatut_fullPayment_setsPaye() {
        JalonFacturation jalon = mock(JalonFacturation.class);
        when(jalon.getMontant()).thenReturn(BigDecimal.valueOf(10_000));
        when(jalon.getId()).thenReturn(1L);
        when(paiementRepository.sumMontantByJalonId(1L)).thenReturn(BigDecimal.valueOf(10_000));

        service.recalculerStatut(jalon);

        verify(jalon).setStatut(JalonStatut.PAYE);
        verify(jalonRepository).save(jalon);
    }

    @Test
    @DisplayName("recalculerStatut — paiement partiel ne change pas le statut FACTURE")
    void recalculerStatut_partialPayment_keepsFacture() {
        JalonFacturation jalon = mock(JalonFacturation.class);
        when(jalon.getMontant()).thenReturn(BigDecimal.valueOf(10_000));
        when(jalon.getId()).thenReturn(1L);
        when(jalon.getStatut()).thenReturn(JalonStatut.FACTURE);
        when(paiementRepository.sumMontantByJalonId(1L)).thenReturn(BigDecimal.valueOf(5_000));

        service.recalculerStatut(jalon);

        verify(jalon, never()).setStatut(JalonStatut.PAYE);
        verify(jalonRepository).save(jalon);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private Project stubProject(Long id, BigDecimal budget) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(id);
        when(p.getEffectiveBudget()).thenReturn(budget);
        return p;
    }

    private JalonFacturation stubJalon(Long id, Long projectId, JalonStatut statut, BigDecimal montant) {
        JalonFacturation j = mock(JalonFacturation.class);
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(j.getId()).thenReturn(id);
        when(j.getProject()).thenReturn(project);
        when(j.getStatut()).thenReturn(statut);
        when(j.getMontant()).thenReturn(montant);
        return j;
    }
}
