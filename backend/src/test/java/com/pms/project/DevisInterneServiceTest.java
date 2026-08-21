package com.pms.project;

import com.pms.project.dto.DevisInterneResponse;
import com.pms.project.dto.LigneDiResponse;
import com.pms.project.entity.LigneDi;
import com.pms.project.entity.Project;
import com.pms.project.entity.SectionDi;
import com.pms.project.repository.LigneDiRepository;
import com.pms.project.repository.ProjectRepository;
import com.pms.project.service.DevisInterneService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Moteur de calcul du Devis Interne (F-AFF-13 §3.2).
 * Valeurs de test SYNTHÉTIQUES — jamais les valeurs réelles de la société
 * (BUSINESS_ANALYSIS.md §16).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevisInterneServiceTest {

    @Mock LigneDiRepository ligneDiRepository;
    @Mock ProjectRepository projectRepository;

    @InjectMocks DevisInterneService service;

    private Project stubProject(String rate) {
        Project p = Project.builder()
                .code("TEST-DI")
                .currency("XAF")
                .exchangeRateToTnd(new BigDecimal(rate))
                .build();
        p.setId(1L);
        return p;
    }

    private LigneDi honoraires(BigDecimal chargeVendue, BigDecimal prixVente,
                               BigDecimal qteInterne, BigDecimal coutTcc) {
        return LigneDi.builder()
                .section(SectionDi.HONORAIRES)
                .chargeVendueJh(chargeVendue)
                .prixVenteUnitaire(prixVente)
                .quantiteInterneJh(qteInterne)
                .coutUnitaireTcc(coutTcc)
                .build();
    }

    @Test
    @DisplayName("Montant TND = charge vendue × prix × taux ; marge = vendu TND − coût interne")
    void computesLineAmountsAndMargin() {
        Project project = stubProject("0.01"); // 1 unité devise = 0,01 TND
        // vendu : 100 JH × 5000/j = 500 000 devise = 5 000 TND
        // coût  : 80 JH × 30 TND/j = 2 400 TND → marge 2 600 TND = 52 %
        LigneDi ligne = honoraires(new BigDecimal("100"), new BigDecimal("5000"),
                new BigDecimal("80"), new BigDecimal("30"));

        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(ligneDiRepository.findActiveByProjectId(1L)).thenReturn(List.of(ligne));

        DevisInterneResponse di = service.getDevisInterne(1L);

        LigneDiResponse r = di.lignes().get(0);
        assertThat(r.montantDevise()).isEqualByComparingTo("500000");
        assertThat(r.montantTnd()).isEqualByComparingTo("5000");
        assertThat(r.coutFinal()).isEqualByComparingTo("2400");
        assertThat(r.margeNette()).isEqualByComparingTo("2600");
        assertThat(r.margePct()).isEqualByComparingTo("0.52");
        assertThat(di.margePct()).isEqualByComparingTo("0.52");
    }

    @Test
    @DisplayName("Ligne AUTRES_FRAIS à taux % : coût assis sur le total vendu TND (deux passes)")
    void percentageLineUsesTotalSoldTnd() {
        Project project = stubProject("0.01");
        LigneDi hono = honoraires(new BigDecimal("100"), new BigDecimal("5000"),
                new BigDecimal("80"), new BigDecimal("30")); // vendu 5 000 TND
        LigneDi taxe = LigneDi.builder()
                .section(SectionDi.AUTRES_FRAIS)
                .tauxPourcentage(new BigDecimal("0.05")) // 5 % du total vendu TND
                .build();

        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(ligneDiRepository.findActiveByProjectId(1L)).thenReturn(List.of(hono, taxe));

        DevisInterneResponse di = service.getDevisInterne(1L);

        LigneDiResponse taxeLine = di.lignes().get(1);
        assertThat(taxeLine.coutFinal()).isEqualByComparingTo("250");     // 5 % × 5 000
        assertThat(taxeLine.margeNette()).isEqualByComparingTo("-250");   // coût sec, rien de vendu
        // total : vendu 5 000 − (2 400 + 250) = 2 350 → 47 %
        assertThat(di.totalCoutFinal()).isEqualByComparingTo("2650");
        assertThat(di.margeNette()).isEqualByComparingTo("2350");
        assertThat(di.margePct()).isEqualByComparingTo("0.47");
    }

    @Test
    @DisplayName("Marge vendue baseline : vide sans lignes DI (fallback fiche identification côté KPI)")
    void margeVendueEmptyWithoutLines() {
        when(ligneDiRepository.findActiveByProjectId(1L)).thenReturn(List.of());

        Optional<BigDecimal> marge = service.computeMargeVenduePct(1L);

        assertThat(marge).isEmpty();
    }

    @Test
    @DisplayName("Ligne interne sans contrepartie vendue : marge négative absorbée par le total")
    void internalOnlyLineHasNegativeMargin() {
        Project project = stubProject("0.01");
        LigneDi expert = honoraires(new BigDecimal("100"), new BigDecimal("5000"),
                new BigDecimal("40"), new BigDecimal("30"));   // vendu 5 000, coût 1 200
        LigneDi junior = honoraires(null, null,
                new BigDecimal("120"), new BigDecimal("10"));  // rien de vendu, coût 1 200

        when(projectRepository.findActiveById(1L)).thenReturn(Optional.of(project));
        when(ligneDiRepository.findActiveByProjectId(1L)).thenReturn(List.of(expert, junior));

        DevisInterneResponse di = service.getDevisInterne(1L);

        assertThat(di.lignes().get(1).margeNette()).isEqualByComparingTo("-1200");
        assertThat(di.margeNette()).isEqualByComparingTo("2600"); // 5 000 − 2 400
        assertThat(di.totalQuantiteInterneJh()).isEqualByComparingTo("160");
    }
}
