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

/*
 * The Devis Interne (DI, "internal quote") service: stores the quote's lines and computes every
 * amount and margin on read. Each line sells a profile for N days at a unit price against M
 * internal days at an internal daily cost (TCC); the difference is the margin - the most
 * sensitive screen in the app, since it exposes real cost prices. Calculation engine of
 * F-AFF-13 section 3.2.
 *
 * Per BUSINESS_ANALYSIS.md section 16 (2026-07-05): the DI structure may ship as an empty
 * template, but real company figures are never seeded/hardcoded. Consequently every computed
 * amount (currency/TND amounts, cost price, final cost, margin, margin %) is derived on every
 * read and never stored - otherwise a changed exchange rate would leave old lines carrying
 * stale TND amounts with no way to tell which rate was used.
 *
 * Needs two passes because AUTRES_FRAIS percentage lines (local taxes, fees, risk provision)
 * cost a percentage OF THE TOTAL SOLD in TND, which must exist before they can be computed:
 * pass 1 totals what's sold, pass 2 computes each line's cost and margin.
 */
@Service
@RequiredArgsConstructor
public class DevisInterneService {

    private final LigneDiRepository ligneDiRepository;
    private final ProjectRepository projectRepository;

    /** Returns the whole quote: every non-deleted line with its computed amounts, plus totals. */
    // MANAGE_DI (V23), Director-only by default - separate from VIEW_KPI because the DI exposes
    // internal cost prices, which a chef de projet with VIEW_KPI must not see.
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional(readOnly = true)
    public DevisInterneResponse getDevisInterne(Long projectId) {
        Project project = loadProject(projectId);
        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one line and returns the WHOLE recomputed quote: a new line changes the total sold,
     * which is the base for AUTRES_FRAIS percentage lines, so several totals can shift at once.
     */
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse addLigne(Long projectId, LigneDiRequest request) {
        Project project = loadProject(projectId);

        // apply() is shared with updateLigne so a field added tomorrow can't be handled on
        // create and forgotten on edit.
        LigneDi ligne = LigneDi.builder().project(project).build();
        apply(ligne, request);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /** Changes one line and returns the recomputed quote. 404 if the line is missing/deleted/elsewhere. */
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse updateLigne(Long projectId, Long ligneId, LigneDiRequest request) {
        Project project = loadProject(projectId);
        LigneDi ligne = loadLigne(projectId, ligneId);

        apply(ligne, request);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Soft-deletes one line (row stays, "deleted" flag raised - keeps the commercial history
     * and audit trail) and returns the recomputed quote.
     */
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
     * Sold margin percentage from the quote, or empty when the project has no quote yet.
     * Called only by KpiService, as {@code computeMargeVenduePct(id).orElse(project.getMargeNetteVendue())}.
     * No {@code @PreAuthorize} here deliberately: what leaves this method is one aggregated
     * percentage, a steering indicator rather than a cost price, so a VIEW_KPI holder may see
     * it without being able to read a single DI line. Not reachable from any controller.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> computeMargeVenduePct(Long projectId) {
        List<LigneDi> lignes = ligneDiRepository.findActiveByProjectId(projectId);
        // Checked before loadProject to skip a query when there's no quote.
        if (lignes.isEmpty()) return Optional.empty();
        Project project = loadProject(projectId);
        // ofNullable: compute() returns a null margePct when nothing is sold yet.
        return Optional.ofNullable(compute(project, lignes).margePct());
    }

    // ── The calculation engine (two passes) ────────────────────────

    /**
     * Turns raw lines into the full response with every amount and margin computed. Pure
     * arithmetic (no DB access), which is what lets DevisInterneServiceTest check figures with
     * synthetic values. Two passes: AUTRES_FRAIS percentage lines cost a percentage of the
     * total sold in TND, so that total must exist first, or a 5% tax line placed early in the
     * list would be computed against a still-incomplete total.
     */
    private DevisInterneResponse compute(Project project, List<LigneDi> lignes) {
        // ONE fallback: a missing rate means "already in TND", avoiding an NPE on the multiply.
        BigDecimal rate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;

        // Pass 1: amounts sold (percentage lines depend on the total).
        BigDecimal totalVenduDevise = BigDecimal.ZERO;
        for (LigneDi l : lignes) {
            // BigDecimal is immutable - add() must be reassigned, or the total stays zero.
            totalVenduDevise = totalVenduDevise.add(montantDevise(l));
        }
        // Converted and rounded once at the end, not per line, to avoid centime drift on a long quote.
        BigDecimal totalVenduTnd = totalVenduDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        // Pass 2: costs and margins, line by line
        BigDecimal totalCout = BigDecimal.ZERO;
        BigDecimal totalChargeVendue = BigDecimal.ZERO;
        BigDecimal totalQuantiteInterne = BigDecimal.ZERO;

        List<LigneDiResponse> responses = new java.util.ArrayList<>(lignes.size());
        for (LigneDi l : lignes) {
            // Recomputed rather than cached from pass 1, so nothing can fall out of sync.
            BigDecimal mDevise = montantDevise(l);
            BigDecimal mTnd    = mDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            // Cost price: internal days x internal daily cost (TCC) - what the work really
            // costs the company, the figure the whole DI exists to protect.
            BigDecimal prixRevient = nz(l.getQuantiteInterneJh())
                    .multiply(nz(l.getCoutUnitaireTcc())).setScale(2, RoundingMode.HALF_UP);

            BigDecimal coutFinal;
            // Both halves needed: an AUTRES_FRAIS line can still be a flat amount (a plane
            // ticket) and must take the ordinary branch, not be treated as 0% of the total.
            if (l.getSection() == SectionDi.AUTRES_FRAIS && l.getTauxPourcentage() != null) {
                // Stored as a fraction (0.05 = 5%), so a plain multiply with no /100.
                coutFinal = l.getTauxPourcentage().multiply(totalVenduTnd).setScale(2, RoundingMode.HALF_UP);
            } else {
                coutFinal = prixRevient
                        .add(nz(l.getFraisDivers()))
                        .add(nz(l.getFraisGeneraux()))
                        .add(nz(l.getCoutImpots()));
            }

            // Negative is legal here: it means the line is sold below cost, and the Director must see it.
            BigDecimal marge = mTnd.subtract(coutFinal);
            // Guarded: dividing by zero would crash a cost-only line sold for nothing; null
            // means "no percentage" so the screen shows an empty cell, not a fake 0%.
            BigDecimal margePct = mTnd.compareTo(BigDecimal.ZERO) > 0
                    ? marge.divide(mTnd, 4, RoundingMode.HALF_UP) : null;

            totalCout = totalCout.add(coutFinal);
            totalChargeVendue = totalChargeVendue.add(nz(l.getChargeVendueJh()));
            totalQuantiteInterne = totalQuantiteInterne.add(nz(l.getQuantiteInterneJh()));

            // Positional record: argument order must match LigneDiResponse's declaration.
            responses.add(new LigneDiResponse(
                    l.getId(), l.getSection(), l.getOrdre(),
                    l.getProfilContractuel(), l.getRessourceProposee(), l.getRessourceRetenue(),
                    l.getUnite(), l.getChargeVendueJh(), l.getPrixVenteUnitaire(),
                    l.getQuantiteInterneJh(), l.getCoutUnitaireTcc(),
                    l.getFraisDivers(), l.getFraisGeneraux(), l.getCoutImpots(), l.getTauxPourcentage(),
                    mDevise, mTnd, prixRevient, coutFinal, marge, margePct));
        }

        // Recomputed from the two totals, not summed per-line, so it stays consistent with them.
        BigDecimal margeNette = totalVenduTnd.subtract(totalCout);
        BigDecimal margePct = totalVenduTnd.compareTo(BigDecimal.ZERO) > 0
                ? margeNette.divide(totalVenduTnd, 4, RoundingMode.HALF_UP) : null;

        // Currency and rate travel with the response so the two-currency display can be checked.
        return new DevisInterneResponse(
                project.getId(), project.getCode(), project.getCurrency(), rate,
                responses, totalVenduDevise, totalVenduTnd,
                totalChargeVendue, totalQuantiteInterne, totalCout, margeNette, margePct);
    }

    /** Days sold x unit selling price, rounded to 2 decimals; shared so both passes always agree. */
    private BigDecimal montantDevise(LigneDi l) {
        return nz(l.getChargeVendueJh()).multiply(nz(l.getPrixVenteUnitaire()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** null-to-zero: most LigneDi money columns are nullable (incomplete or cost-only lines). */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Copies the form fields onto a line, shared by create and edit so a field added later
     * can't be handled on one path and dropped on the other. Does not copy the project link.
     */
    private void apply(LigneDi ligne, LigneDiRequest r) {
        ligne.setSection(r.section());
        // Column is NOT NULL; default to 0 rather than let a null insert fail.
        ligne.setOrdre(r.ordre() != null ? r.ordre() : 0);
        ligne.setProfilContractuel(r.profilContractuel());
        ligne.setRessourceProposee(r.ressourceProposee());
        ligne.setRessourceRetenue(r.ressourceRetenue());
        // isBlank(), not just null: the form sends "" when the field is cleared.
        ligne.setUnite(r.unite() != null && !r.unite().isBlank() ? r.unite() : "H-Jour");
        // Nulls are kept as-is: "not filled in" must stay distinguishable from a real zero.
        ligne.setChargeVendueJh(r.chargeVendueJh());
        ligne.setPrixVenteUnitaire(r.prixVenteUnitaire());
        ligne.setQuantiteInterneJh(r.quantiteInterneJh());
        ligne.setCoutUnitaireTcc(r.coutUnitaireTcc());
        ligne.setFraisDivers(r.fraisDivers());
        ligne.setFraisGeneraux(r.fraisGeneraux());
        ligne.setCoutImpots(r.coutImpots());
        ligne.setTauxPourcentage(r.tauxPourcentage());
    }

    /** Loads a non-deleted project or throws NotFoundException (→ 404). */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads a line and checks it belongs to the given project - a security check, not a
     * detail: ProjectScopeInterceptor only checks the project id in the URL, so without this,
     * anyone holding MANAGE_DI could edit a line of a project outside their scope by pairing
     * their own project id with another project's line id (IDOR). 404, not 403, so as not to
     * confirm the line's existence to an unauthorized caller.
     */
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
