package com.pms.project.service;

import com.pms.project.dto.DevisInterneResponse;
import com.pms.project.dto.LigneDiRequest;
import com.pms.project.dto.LigneDiResponse;
import com.pms.project.entity.LigneDi;
import com.pms.project.entity.Project;
import com.pms.project.entity.SectionDi;
import com.pms.project.repository.LigneDiRepository;
import com.pms.project.repository.ProjectRepository;
import com.pms.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Devis Interne — moteur de calcul (F-AFF-13 §3.2).
 *
 * Tous les montants sont dérivés à la lecture : montant devise = charge vendue × prix,
 * montant TND = devise × taux projet, coût = quantité interne × TCC (+ frais + impôts).
 * Les lignes AUTRES_FRAIS à taux (taxes, provision risque) sont assises sur le total
 * vendu TND — d'où le calcul en deux passes. Rien n'est stocké en dur.
 */
@Service
@RequiredArgsConstructor
public class DevisInterneService {

    private final LigneDiRepository ligneDiRepository;
    private final ProjectRepository projectRepository;

    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional(readOnly = true)
    public DevisInterneResponse getDevisInterne(Long projectId) {
        Project project = loadProject(projectId);
        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse addLigne(Long projectId, LigneDiRequest request) {
        Project project = loadProject(projectId);

        LigneDi ligne = LigneDi.builder().project(project).build();
        apply(ligne, request);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse updateLigne(Long projectId, Long ligneId, LigneDiRequest request) {
        Project project = loadProject(projectId);
        LigneDi ligne = loadLigne(projectId, ligneId);

        apply(ligne, request);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse deleteLigne(Long projectId, Long ligneId) {
        Project project = loadProject(projectId);
        LigneDi ligne = loadLigne(projectId, ligneId);

        ligne.setDeleted(true);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Marge nette vendue issue du DI si des lignes existent (baseline calculée).
     * Utilisée par le KPI ; vide si le projet n'a pas de DI saisi.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> computeMargeVenduePct(Long projectId) {
        List<LigneDi> lignes = ligneDiRepository.findActiveByProjectId(projectId);
        if (lignes.isEmpty()) return Optional.empty();
        Project project = loadProject(projectId);
        return Optional.ofNullable(compute(project, lignes).margePct());
    }

    // ── Moteur de calcul (deux passes) ────────────────────────────

    private DevisInterneResponse compute(Project project, List<LigneDi> lignes) {
        BigDecimal rate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;

        // Passe 1 : montants vendus (les lignes à taux % en dépendent)
        BigDecimal totalVenduDevise = BigDecimal.ZERO;
        for (LigneDi l : lignes) {
            totalVenduDevise = totalVenduDevise.add(montantDevise(l));
        }
        BigDecimal totalVenduTnd = totalVenduDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        // Passe 2 : coûts et marges ligne par ligne
        BigDecimal totalCout = BigDecimal.ZERO;
        BigDecimal totalChargeVendue = BigDecimal.ZERO;
        BigDecimal totalQuantiteInterne = BigDecimal.ZERO;

        List<LigneDiResponse> responses = new java.util.ArrayList<>(lignes.size());
        for (LigneDi l : lignes) {
            BigDecimal mDevise = montantDevise(l);
            BigDecimal mTnd    = mDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            BigDecimal prixRevient = nz(l.getQuantiteInterneJh())
                    .multiply(nz(l.getCoutUnitaireTcc())).setScale(2, RoundingMode.HALF_UP);

            BigDecimal coutFinal;
            if (l.getSection() == SectionDi.AUTRES_FRAIS && l.getTauxPourcentage() != null) {
                coutFinal = l.getTauxPourcentage().multiply(totalVenduTnd).setScale(2, RoundingMode.HALF_UP);
            } else {
                coutFinal = prixRevient
                        .add(nz(l.getFraisDivers()))
                        .add(nz(l.getFraisGeneraux()))
                        .add(nz(l.getCoutImpots()));
            }

            BigDecimal marge = mTnd.subtract(coutFinal);
            BigDecimal margePct = mTnd.compareTo(BigDecimal.ZERO) > 0
                    ? marge.divide(mTnd, 4, RoundingMode.HALF_UP) : null;

            totalCout = totalCout.add(coutFinal);
            totalChargeVendue = totalChargeVendue.add(nz(l.getChargeVendueJh()));
            totalQuantiteInterne = totalQuantiteInterne.add(nz(l.getQuantiteInterneJh()));

            responses.add(new LigneDiResponse(
                    l.getId(), l.getSection(), l.getOrdre(),
                    l.getProfilContractuel(), l.getRessourceProposee(), l.getRessourceRetenue(),
                    l.getUnite(), l.getChargeVendueJh(), l.getPrixVenteUnitaire(),
                    l.getQuantiteInterneJh(), l.getCoutUnitaireTcc(),
                    l.getFraisDivers(), l.getFraisGeneraux(), l.getCoutImpots(), l.getTauxPourcentage(),
                    mDevise, mTnd, prixRevient, coutFinal, marge, margePct));
        }

        BigDecimal margeNette = totalVenduTnd.subtract(totalCout);
        BigDecimal margePct = totalVenduTnd.compareTo(BigDecimal.ZERO) > 0
                ? margeNette.divide(totalVenduTnd, 4, RoundingMode.HALF_UP) : null;

        return new DevisInterneResponse(
                project.getId(), project.getCode(), project.getCurrency(), rate,
                responses, totalVenduDevise, totalVenduTnd,
                totalChargeVendue, totalQuantiteInterne, totalCout, margeNette, margePct);
    }

    private BigDecimal montantDevise(LigneDi l) {
        return nz(l.getChargeVendueJh()).multiply(nz(l.getPrixVenteUnitaire()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private void apply(LigneDi ligne, LigneDiRequest r) {
        ligne.setSection(r.section());
        ligne.setOrdre(r.ordre() != null ? r.ordre() : 0);
        ligne.setProfilContractuel(r.profilContractuel());
        ligne.setRessourceProposee(r.ressourceProposee());
        ligne.setRessourceRetenue(r.ressourceRetenue());
        ligne.setUnite(r.unite() != null && !r.unite().isBlank() ? r.unite() : "H-Jour");
        ligne.setChargeVendueJh(r.chargeVendueJh());
        ligne.setPrixVenteUnitaire(r.prixVenteUnitaire());
        ligne.setQuantiteInterneJh(r.quantiteInterneJh());
        ligne.setCoutUnitaireTcc(r.coutUnitaireTcc());
        ligne.setFraisDivers(r.fraisDivers());
        ligne.setFraisGeneraux(r.fraisGeneraux());
        ligne.setCoutImpots(r.coutImpots());
        ligne.setTauxPourcentage(r.tauxPourcentage());
    }

    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    private LigneDi loadLigne(Long projectId, Long ligneId) {
        LigneDi ligne = ligneDiRepository.findById(ligneId)
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ligne DI introuvable : " + ligneId));
        if (!ligne.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Ligne DI introuvable pour ce projet : " + ligneId);
        }
        return ligne;
    }
}
