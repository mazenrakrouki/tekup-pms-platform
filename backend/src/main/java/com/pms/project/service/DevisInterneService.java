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
 * WHAT THIS FILE IS
 * -----------------
 * The Devis Interne (DI, "internal quote") service: it stores the lines of the internal quote
 * of a project and computes every amount and every margin of that quote when it is read.
 *
 * A Devis Interne is how the company decided the price of a contract, line by line. Each line
 * says: this profile, this many days SOLD to the client at this unit price, against this many
 * days of INTERNAL work at this internal daily cost (the TCC). The difference between the two
 * is the margin. It is the most sensitive screen of the application, because it exposes the
 * real cost prices of the company.
 *
 * This file is the calculation engine of F-AFF-13 section 3.2.
 *
 * WHERE IT SITS IN THE FLOW
 * -------------------------
 *   Browser (Angular DI screen)
 *     -> DevisInterneController  (/api/projects/{projectId}/devis-interne...)
 *     -> ProjectScopeInterceptor (ADR-021: the URL starts with /api/projects/{id}, so the
 *        perimeter of the caller is checked before this file is even entered)
 *     -> THIS FILE               permission MANAGE_DI + the whole arithmetic
 *          -> LigneDiRepository  reads and writes the "lignes_di" table
 *          -> ProjectRepository  reads the project, for its currency and its exchange rate
 *     -> DevisInterneResponse (JSON): the lines WITH their computed amounts, plus the totals.
 *
 * One extra door exists, computeMargeVenduePct(), called by KpiService and not by the
 * controller. It is documented on the method itself.
 *
 * WHY IT EXISTS
 * -------------
 * Because of the rule of BUSINESS_ANALYSIS.md section 16 (management decision of 2026-07-05):
 * the DI STRUCTURE may live in the application, as an EMPTY template; the real figures of the
 * company are never seeded, never hardcoded, never copied into migrations, fixtures, tests or
 * documentation. The company types its own values into its own deployment.
 * The second half of that rule is what shapes this whole file: the computed amounts (amount in
 * the project currency, amount in TND, cost price, final cost, margin, margin percentage) are
 * derived every time the quote is read, and NEVER stored in a column.
 * Why that matters concretely: if the amount in TND were stored, then changing the exchange
 * rate of the project would leave every old line carrying a dinar amount computed with the
 * former rate. The quote would no longer add up, and nobody could tell which rate each line
 * had used. Deriving on read makes that impossible by construction.
 *
 * THE SHAPE OF THE ARITHMETIC (and why it needs TWO passes)
 * ---------------------------------------------------------
 *   amount in currency = days sold      x unit selling price
 *   amount in TND      = amount in currency x project exchange rate
 *   cost price         = internal days  x internal daily cost (TCC)
 *   final cost         = cost price + sundry costs + overheads + taxes
 * but the lines of the AUTRES_FRAIS section that carry a percentage (local taxes, registration
 * fees, risk provision) are a percentage OF THE TOTAL SOLD in TND. Their cost therefore cannot
 * be computed until every other line has been added up. Hence pass 1 (the totals sold) and
 * pass 2 (the cost and the margin of each line). A single pass would compute those lines
 * against a total that is still incomplete, and a 5% tax line would come out at 5% of whatever
 * part of the quote happened to be read first.
 */
@Service
// @Service: Spring builds one shared instance and injects it into DevisInterneController and
// into KpiService. It is also what wraps the class in the proxy that enforces @PreAuthorize
// and @Transactional below.
@RequiredArgsConstructor
// Lombok writes the constructor over the two final fields, and Spring fills them in.
public class DevisInterneService {

    private final LigneDiRepository ligneDiRepository;
    // The project is needed for two of its fields only: the currency and the exchange rate to
    // the dinar. Both are read on every computation, because the amounts in TND depend on them.
    private final ProjectRepository projectRepository;

    /**
     * Returns the whole internal quote of a project: every line that is not deleted, each one
     * carrying its computed amounts, plus the totals of the quote.
     *
     * Why it recomputes instead of reading stored totals: see the header. Nothing is stored,
     * so the figures can never drift from the lines they come from.
     */
    // MANAGE_DI is a capability of its own, created by the Flyway migration V23, and in the
    // default matrix only the Director holds it. Why a separate one and not VIEW_KPI: the DI
    // contains the internal cost prices of the company (section 16 of BUSINESS_ANALYSIS.md).
    // Without this check a chef de projet, who legitimately holds VIEW_PROJECT and VIEW_KPI,
    // would read the real daily cost of every colleague on the project.
    // The check sits on the service, not on the controller, so KpiService and any future
    // caller are subject to it too. And it tests a capability, never a role name (ADR-001):
    // the link between role and capability is a row in the database, editable at run time.
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    // readOnly = true: one transaction, nothing meant to be written. Hibernate skips change
    // tracking on the loaded lines. Without it a stray setter in the computation could be
    // flushed and would silently write a value into the table.
    @Transactional(readOnly = true)
    public DevisInterneResponse getDevisInterne(Long projectId) {
        Project project = loadProject(projectId);
        // findActiveByProjectId filters out the deleted lines IN THE QUERY and sorts them by
        // section, then by rank, then by id. Doing it in SQL rather than in Java means a
        // deleted line is never even loaded, so no later mistake can add it to a total.
        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Adds one line to the quote and returns the WHOLE recomputed quote, not just the new line.
     *
     * Why it returns everything: adding a line changes the total sold, and the total sold is
     * the base of the percentage lines of the AUTRES_FRAIS section. So one new line can change
     * the cost of several other lines and every total. Returning only the new line would leave
     * the screen showing amounts that are already out of date, and the user would have to
     * reload to see the truth.
     */
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    // @Transactional without readOnly: this method writes. It makes the insert and the reread
    // one single unit of work in the database.
    // Without it, the read that follows the save could run outside the transaction and return
    // the quote as it was BEFORE the new line, so the screen would not show what was just added.
    @Transactional
    public DevisInterneResponse addLigne(Long projectId, LigneDiRequest request) {
        // Loaded first: it produces a clean 404 for an unknown project, and it also gives the
        // exchange rate that compute() needs at the end.
        Project project = loadProject(projectId);

        // The builder sets only the link to the project; every other field is filled by
        // apply() below, which is the same method update uses. One place to fill a line means
        // a field added tomorrow cannot be handled on creation and forgotten on edit.
        LigneDi ligne = LigneDi.builder().project(project).build();
        apply(ligne, request);
        ligneDiRepository.save(ligne);

        // Reread from the database rather than adding the new line to a list in memory: the
        // reread gives the rows in the section/rank order the screen expects, and it proves
        // the row really was written.
        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Changes one line of the quote and returns the whole recomputed quote.
     * Throws 404 when the line does not exist, was deleted, or belongs to another project.
     */
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse updateLigne(Long projectId, Long ligneId, LigneDiRequest request) {
        Project project = loadProject(projectId);
        // loadLigne checks that the line really belongs to THIS project. See the method for
        // why that check is a security check and not a detail.
        LigneDi ligne = loadLigne(projectId, ligneId);

        apply(ligne, request);
        ligneDiRepository.save(ligne);

        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Removes one line of the quote - as a SOFT delete: the row stays in the table with its
     * "deleted" flag raised - and returns the whole recomputed quote.
     *
     * Why not a real delete: the quote is the commercial history of the contract. Keeping the
     * removed rows means the audit columns of BaseEntity still say who removed what and when,
     * and a line deleted by mistake can be brought back straight in the database. A real
     * DELETE would destroy that trace for good.
     */
    @PreAuthorize("hasAuthority('MANAGE_DI')")
    @Transactional
    public DevisInterneResponse deleteLigne(Long projectId, Long ligneId) {
        Project project = loadProject(projectId);
        LigneDi ligne = loadLigne(projectId, ligneId);

        ligne.setDeleted(true);
        ligneDiRepository.save(ligne);

        // The reread uses findActiveByProjectId, which filters on deleted = false, so the line
        // that was just flagged is already gone from the returned quote and from every total.
        return compute(project, ligneDiRepository.findActiveByProjectId(projectId));
    }

    /**
     * Returns the sold margin percentage that the internal quote produces, or an empty
     * Optional when the project has no quote yet.
     *
     * WHO CALLS THIS: KpiService, and only KpiService. It is the "sold margin" baseline the
     * KPI screen shows next to the margin the project is actually reaching. KpiService reads
     * it as devisInterneService.computeMargeVenduePct(id).orElse(project.getMargeNetteVendue()),
     * so the computed quote wins, and the percentage typed on the identification sheet is the
     * fallback for a project that has no quote.
     *
     * WHY Optional AND NOT null: Optional forces the caller to decide what an absent quote
     * means. Returning null here, on a method whose result feeds a chain of arithmetic, is how
     * a NullPointerException reaches the KPI screen of every project created before the DI
     * feature existed.
     *
     * WHY THIS IS THE ONLY METHOD OF THE CLASS WITHOUT @PreAuthorize("hasAuthority('MANAGE_DI')"):
     * this is a deliberate decision and a jury may well ask about it. The quote itself is
     * Director-only because it holds internal cost prices. What leaves this method is one
     * single aggregated percentage - a steering indicator, not a cost price. A VIEW_KPI holder
     * therefore sees the percentage and still cannot read one line of the quote. The method is
     * not reachable from any controller: no endpoint maps to it.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> computeMargeVenduePct(Long projectId) {
        List<LigneDi> lignes = ligneDiRepository.findActiveByProjectId(projectId);
        // The empty check comes FIRST, before loadProject: it answers "no quote" without the
        // second query. The answer would be the same empty Optional either way, because an
        // empty quote has a total sold of zero and compute() then returns a null margePct.
        if (lignes.isEmpty()) return Optional.empty();
        Project project = loadProject(projectId);
        // Optional.ofNullable and not Optional.of: compute() returns a null margePct when the
        // total sold is zero (a quote made only of cost lines, with nothing sold yet).
        // Optional.of(null) throws NullPointerException on the spot; ofNullable turns that null
        // into the empty Optional, and KpiService then falls back to the declared margin.
        return Optional.ofNullable(compute(project, lignes).margePct());
    }

    // ── The calculation engine (two passes) ────────────────────────

    /**
     * The heart of the file: turns the raw lines into the full response, with every amount and
     * every margin computed. It writes nothing and reads nothing from the database - it is
     * pure arithmetic over the arguments it was given, which is exactly what makes
     * DevisInterneServiceTest able to check the figures with synthetic values.
     *
     * Returns a DevisInterneResponse: the project header (id, code, currency, rate), one
     * LigneDiResponse per line, and the totals of the quote.
     *
     * WHY TWO PASSES: the lines of the AUTRES_FRAIS section that carry a percentage cost a
     * percentage OF THE TOTAL SOLD in TND. That total has to exist before their cost can be
     * computed, so pass 1 adds up what is sold and pass 2 computes the costs and the margins.
     * With a single pass, a 5% tax line placed first in the list would be computed against a
     * total of zero and would cost nothing at all.
     */
    private DevisInterneResponse compute(Project project, List<LigneDi> lignes) {
        // The project exchange rate to the dinar. BigDecimal.ONE is the safe fallback: a
        // missing rate then means "the project is already in TND", which leaves the amounts
        // unchanged. Without the fallback this multiplication would throw
        // NullPointerException, and the whole DI screen of a project whose rate was never
        // filled in would fail with an HTTP 500.
        BigDecimal rate = project.getExchangeRateToTnd() != null
                ? project.getExchangeRateToTnd() : BigDecimal.ONE;

        // Pass 1: the amounts sold (the percentage lines depend on them)
        BigDecimal totalVenduDevise = BigDecimal.ZERO;
        for (LigneDi l : lignes) {
            // BigDecimal is immutable: add() RETURNS a new value, it does not change the old
            // one. Writing totalVenduDevise.add(...) without the assignment would compile and
            // leave the total at zero for ever - the classic BigDecimal mistake.
            totalVenduDevise = totalVenduDevise.add(montantDevise(l));
        }
        // The total is converted once, then rounded to 2 decimals: converting and rounding
        // each line and then adding those rounded amounts up would drift by a few centimes on
        // a long quote. HALF_UP is the ordinary commercial rounding (0.005 goes up), the one
        // an accountant expects.
        BigDecimal totalVenduTnd = totalVenduDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        // Pass 2: costs and margins, line by line
        BigDecimal totalCout = BigDecimal.ZERO;
        BigDecimal totalChargeVendue = BigDecimal.ZERO;
        BigDecimal totalQuantiteInterne = BigDecimal.ZERO;

        // The fully qualified java.util.ArrayList is used because ArrayList is not imported in
        // this file. Sizing the list to lignes.size() reserves the array once instead of
        // letting it grow and be copied as the loop fills it.
        List<LigneDiResponse> responses = new java.util.ArrayList<>(lignes.size());
        for (LigneDi l : lignes) {
            // Recomputed rather than kept from pass 1: pass 1 only needed the sum, and keeping
            // a parallel list of per-line values would be one more thing able to fall out of
            // step with the lines themselves.
            BigDecimal mDevise = montantDevise(l);
            BigDecimal mTnd    = mDevise.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            // Cost price of the line: internal days x internal daily cost (TCC).
            // This is the number the whole DI exists to protect - what the work really costs
            // the company, as opposed to what it was sold for.
            BigDecimal prixRevient = nz(l.getQuantiteInterneJh())
                    .multiply(nz(l.getCoutUnitaireTcc())).setScale(2, RoundingMode.HALF_UP);

            BigDecimal coutFinal;
            // The two families of lines. A line of the AUTRES_FRAIS section that carries a
            // percentage (local tax, registration fee, risk provision) costs that percentage
            // of the TOTAL SOLD in dinars - which is why pass 1 had to run first. Every other
            // line costs its own cost price plus its own extra charges.
            // BOTH halves of the condition are needed: an AUTRES_FRAIS line may perfectly well
            // be a flat amount (a plane ticket), and it must then take the ordinary branch.
            // Testing the section alone would compute it as 0% of the total and make it free.
            if (l.getSection() == SectionDi.AUTRES_FRAIS && l.getTauxPourcentage() != null) {
                // The rate is stored as a fraction: 0.05 means 5% (see LigneDi.tauxPourcentage,
                // NUMERIC(7,4)). So it is a plain multiplication, with no division by 100.
                coutFinal = l.getTauxPourcentage().multiply(totalVenduTnd).setScale(2, RoundingMode.HALF_UP);
            } else {
                // Cost price plus the three optional extras of the line: sundry costs,
                // overheads, taxes. nz() turns each absent value into zero - see the method.
                coutFinal = prixRevient
                        .add(nz(l.getFraisDivers()))
                        .add(nz(l.getFraisGeneraux()))
                        .add(nz(l.getCoutImpots()));
            }

            // What the line earns: sold in dinars minus what it costs. A negative value is
            // perfectly legal and is exactly what the Director must see - it means the line is
            // sold below its cost.
            BigDecimal marge = mTnd.subtract(coutFinal);
            // The margin as a fraction of what was sold, 4 decimals (0.4412 = 44.12%), the same
            // shape as Project.margeNetteVendue so the two can be compared directly.
            // The "greater than zero" guard is not decoration: BigDecimal.divide() by zero
            // throws ArithmeticException. A cost-only line, sold for nothing, would crash the
            // whole quote. null here means "no margin percentage can be given for this line",
            // and the screen shows an empty cell rather than a made-up 0%.
            // A scale MUST be passed to divide(): without it, a division with no exact decimal
            // result (1 divided by 3) throws ArithmeticException as well.
            BigDecimal margePct = mTnd.compareTo(BigDecimal.ZERO) > 0
                    ? marge.divide(mTnd, 4, RoundingMode.HALF_UP) : null;

            totalCout = totalCout.add(coutFinal);
            totalChargeVendue = totalChargeVendue.add(nz(l.getChargeVendueJh()));
            totalQuantiteInterne = totalQuantiteInterne.add(nz(l.getQuantiteInterneJh()));

            // LigneDiResponse is a record built by position, so the order of these arguments
            // must match its declaration field for field. Two BigDecimal values swapped here
            // would compile without a word and show the cost price in the margin column - a
            // reason to read this call against the record, not to reorder it for tidiness.
            // The stored fields are sent back as they are, and the six computed ones
            // (mDevise, mTnd, prixRevient, coutFinal, marge, margePct) are added at the end.
            responses.add(new LigneDiResponse(
                    l.getId(), l.getSection(), l.getOrdre(),
                    l.getProfilContractuel(), l.getRessourceProposee(), l.getRessourceRetenue(),
                    l.getUnite(), l.getChargeVendueJh(), l.getPrixVenteUnitaire(),
                    l.getQuantiteInterneJh(), l.getCoutUnitaireTcc(),
                    l.getFraisDivers(), l.getFraisGeneraux(), l.getCoutImpots(), l.getTauxPourcentage(),
                    mDevise, mTnd, prixRevient, coutFinal, marge, margePct));
        }

        // The margin of the whole quote. Deliberately NOT the sum of the per-line margins: it
        // is recomputed from the two totals, so the figure the Director reads is consistent
        // with the two totals displayed beside it, whatever rounding happened line by line.
        BigDecimal margeNette = totalVenduTnd.subtract(totalCout);
        // Same division guard as above, for the same reason: a quote with nothing sold yet
        // would otherwise crash on a division by zero instead of showing an empty percentage.
        // This is the number computeMargeVenduePct() hands to the KPI engine.
        BigDecimal margePct = totalVenduTnd.compareTo(BigDecimal.ZERO) > 0
                ? margeNette.divide(totalVenduTnd, 4, RoundingMode.HALF_UP) : null;

        // The currency and the rate travel with the response on purpose: the screen shows
        // amounts in two currencies, and without the rate the reader cannot check one against
        // the other.
        return new DevisInterneResponse(
                project.getId(), project.getCode(), project.getCurrency(), rate,
                responses, totalVenduDevise, totalVenduTnd,
                totalChargeVendue, totalQuantiteInterne, totalCout, margeNette, margePct);
    }

    /**
     * What one line is sold for, in the currency of the project: days sold x unit selling
     * price, rounded to 2 decimals.
     *
     * Written once and called from both passes so that the two passes can never disagree. If
     * pass 1 and pass 2 each had their own copy of this formula, a correction applied to one
     * of them would make the totals stop matching the lines they are built from.
     */
    private BigDecimal montantDevise(LigneDi l) {
        return nz(l.getChargeVendueJh()).multiply(nz(l.getPrixVenteUnitaire()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * "null to zero": returns the value it is given, or zero when that value is null.
     *
     * Why the whole file needs it: almost every money column of LigneDi is nullable, because a
     * line of the FRAIS section has no selling price and a line being typed in is incomplete.
     * BigDecimal arithmetic on a null reference throws NullPointerException, so without nz()
     * one half-filled line would make the entire quote fail with an HTTP 500 instead of simply
     * counting as zero.
     *
     * static because it depends on nothing in the instance: it is a pure function.
     */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Copies the fields of the form onto a line, for both creation and edit.
     *
     * Why one shared method: written twice, a column added tomorrow would be handled in one of
     * the two paths and silently dropped in the other - the bug where a value saves when you
     * edit a line and disappears when you create one.
     *
     * Note what is NOT copied: the link to the project. A line can therefore never be moved to
     * another project through the ordinary edit form, however the request is crafted.
     */
    private void apply(LigneDi ligne, LigneDiRequest r) {
        ligne.setSection(r.section());
        // The rank inside the section. Defaulted to 0 because the column is NOT NULL and the
        // sort of findActiveByProjectId reads it: a null would make the insert fail.
        ligne.setOrdre(r.ordre() != null ? r.ordre() : 0);
        ligne.setProfilContractuel(r.profilContractuel());
        ligne.setRessourceProposee(r.ressourceProposee());
        ligne.setRessourceRetenue(r.ressourceRetenue());
        // The unit of the line. "H-Jour" (man-day) is the unit of the Excel model and of
        // almost every line. isBlank() and not just a null test: the form sends an empty
        // string when the field is cleared, and an empty unit would be displayed as an empty
        // column on the quote instead of falling back to the default.
        ligne.setUnite(r.unite() != null && !r.unite().isBlank() ? r.unite() : "H-Jour");
        // The money fields are copied as they are, nulls included. That is deliberate: a null
        // means "not filled in" and must stay distinguishable from a real zero in the table.
        // nz() turns them into zeros at read time, in compute(), and only there.
        ligne.setChargeVendueJh(r.chargeVendueJh());
        ligne.setPrixVenteUnitaire(r.prixVenteUnitaire());
        ligne.setQuantiteInterneJh(r.quantiteInterneJh());
        ligne.setCoutUnitaireTcc(r.coutUnitaireTcc());
        ligne.setFraisDivers(r.fraisDivers());
        ligne.setFraisGeneraux(r.fraisGeneraux());
        ligne.setCoutImpots(r.coutImpots());
        ligne.setTauxPourcentage(r.tauxPourcentage());
    }

    /**
     * Loads a project that is not soft-deleted, or raises NotFoundException, which the global
     * exception handler turns into an HTTP 404.
     *
     * Why every method goes through it: the "deleted = false" condition and the 404 then
     * cannot be forgotten. A call to projectRepository.findById() would happily open the quote
     * of a deleted project.
     */
    private Project loadProject(Long id) {
        return projectRepository.findActiveById(id)
                .orElseThrow(() -> new NotFoundException("Projet introuvable : " + id));
    }

    /**
     * Loads one line by its id AND checks that it really belongs to the project named in the
     * URL. Raises 404 when the line does not exist, was deleted, or belongs elsewhere.
     *
     * THIS IS A SECURITY CHECK, not a detail. The URL is
     * /api/projects/{projectId}/devis-interne/lignes/{ligneId}, and ProjectScopeInterceptor
     * only checks the FIRST id. Without the ownership test below, a Director - or anyone who
     * came to hold MANAGE_DI - could put his own project id in the path and any line id of
     * another project after it, and edit or delete a line of a quote he is not inside the
     * perimeter of. That attack has a name, IDOR (Insecure Direct Object Reference), and this
     * method is what closes it.
     *
     * Why 404 and not 403: answering "forbidden" would confirm that the line exists, which is
     * already information. A plain "not found" tells the caller nothing at all.
     */
    private LigneDi loadLigne(Long projectId, Long ligneId) {
        LigneDi ligne = ligneDiRepository.findById(ligneId)
                // A soft-deleted line is still a row, so findById returns it. Without this
                // filter a deleted line could be edited back to life through the API, and the
                // totals would change with no visible line to explain the difference.
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ligne DI introuvable : " + ligneId));
        // getId() returns a Long object, so equals() is used and not "==". With "==" this
        // would compare two references: it happens to work for small numbers that Java caches,
        // and stops working above 127 - the check would pass on a test database with a few
        // rows and let the cross-project edit through on a real one.
        if (!ligne.getProject().getId().equals(projectId)) {
            throw new NotFoundException("Ligne DI introuvable pour ce projet : " + ligneId);
        }
        return ligne;
    }
}
