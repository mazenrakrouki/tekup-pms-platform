package com.pms.project.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * WHAT THIS FILE IS
 * One row of the table lignes_di: a single line of the Devis Interne (DI = the internal
 * quote, the sheet where the company compares what it sold on a project with what that work
 * really costs it). One project has many of these lines, grouped in three sections. This is
 * the whole of the DI model, and it is STRUCTURE ONLY: it holds what a human typed, never a
 * computed result.
 *
 * Original French note, kept and translated - decision of 2026-07-05, recorded in
 * BUSINESS_ANALYSIS.md section 16: the model is delivered empty, and the company's real
 * figures (salaries, margins) are never seeded. Amounts (in the project currency and in TND)
 * and margins are worked out when the quote is read, by DevisInterneService, and never
 * stored: "never store the project-currency amount and the TND amount independently".
 * Traceability reference for the whole feature: F-AFF-13 section 3.
 *
 * WHERE IT SITS IN THE FLOW
 * DevisInterneController, on /api/projects/{projectId}/devis-interne
 *   -> DevisInterneService.addLigne / updateLigne / deleteLigne / getDevisInterne
 *   -> LigneDiRepository.findActiveByProjectId(projectId) loads the live lines of one
 *      project (deleted = false), and save() writes this object back as one row;
 *   -> DevisInterneService.compute() reads these fields, derives every amount and margin,
 *      and packs them into LigneDiResponse objects inside one DevisInterneResponse;
 *   -> KpiService also reaches the DI, through
 *      DevisInterneService.computeMargeVenduePct(projectId), to use the quote's real margin
 *      as the sold-margin baseline of the KPI screen; it falls back to the manually typed
 *      Project.margeNetteVendue only when the project has no DI line at all.
 * This class calls nothing itself: it only describes the table to Hibernate.
 *
 * WHY IT EXISTS, AND WHY IT HOLDS NO ARITHMETIC
 * Delete it and the most sensitive screen of the application disappears: nobody could record
 * what a project is expected to cost, and the margin shown on the KPI screen would be only
 * the figure a human typed, with nothing behind it.
 * The obvious alternative was to keep a "montant_tnd" or a "marge" column next to the inputs,
 * as the Excel sheet appears to do. That is refused on purpose. The amounts depend on the
 * project exchange rate, which lives on the project row and can be corrected at any time. A
 * stored amount would keep the rate of the day it was written, so correcting one wrong rate
 * would leave every old line showing figures that no longer match the rate printed next to
 * them, with no way to tell which lines are stale. Deriving everything at read time means
 * there is exactly one version of the truth.
 *
 * WHO IS ALLOWED IN
 * Two separate gates, and both are needed. The permission MANAGE_DI is checked on the
 * SERVICE methods of DevisInterneService, not on the controller, so no route can be added
 * that reaches the data without the check. On top of that, ADR-021: because the URL matches
 * /api/projects/{id}/**, ProjectScopeInterceptor also checks that this particular user is
 * allowed on this particular project. Permission alone is not enough - without the scope
 * check, a user holding MANAGE_DI could read the cost structure of a project that is none of
 * his business. The code never tests a role name anywhere.
 *
 * NOTE ON THE COLUMN DEFINITIONS BELOW
 * The table was created by Flyway V23 and JPA never creates or changes it: application.yml
 * sets ddl-auto to "validate" (ADR-019). So every length, precision and scale written below
 * is compared with the real column when the application starts, and a mismatch stops it
 * immediately instead of silently rounding money later.
 *
 * ANNOTATIONS ON THE CLASS BELOW
 * The @Entity annotation tells Hibernate this class is a database table and not a plain Java
 * object. Why: Spring Data needs it to build LigneDiRepository. Without it the application
 * refuses to start with "Not a managed type: com.pms.project.entity.LigneDi", so no quote
 * line could ever be saved or read.
 *
 * The @Table(name = "lignes_di") annotation pins the exact table name. Why: from the class
 * name Hibernate would guess "ligne_di", but V23 created "lignes_di" (plural). Without this
 * line the application starts and then fails on the first query with: relation "ligne_di"
 * does not exist.
 *
 * The @Getter and @Setter annotations ask Lombok to write the get.../set... methods at
 * compile time. Why: Hibernate fills the row through them and DevisInterneService reads and
 * writes every field through them. Fifteen pairs written by hand would add a hundred lines
 * carrying no information.
 *
 * The @NoArgsConstructor annotation gives an empty constructor. Why: to load a row Hibernate
 * first does "new LigneDi()" and only then fills the fields. Without it, opening the DI of a
 * project fails with InstantiationException.
 *
 * The @AllArgsConstructor and @Builder annotations together give
 * LigneDi.builder().project(p).build(), which is how DevisInterneService.addLigne creates a
 * line. Why the builder rather than the long constructor: this class has fifteen fields and
 * eight of them are BigDecimal. A fifteen-argument constructor would still compile with
 * chargeVendueJh and quantiteInterneJh swapped - days sold put in the internal-days slot -
 * and the quote would show a wrong margin with no error at all. The builder names every
 * value at the point of the call.
 * Note that this is @Builder and not @SuperBuilder, so the builder only knows the fields
 * declared in this class; id, the audit columns and "deleted" come from BaseEntity and from
 * Spring auditing.
 *
 * extends BaseEntity adds the columns shared by every table in PMS: id, created_at,
 * updated_at, created_by, updated_by and the "deleted" flag. Why it matters here in
 * particular: deleting a DI line never erases it. DevisInterneService.deleteLigne sets
 * deleted = true and LigneDiRepository.findActiveByProjectId filters on deleted = false, so
 * a line removed by mistake from a quote that carries the company's cost structure can still
 * be recovered, and created_by / updated_by record who last touched these sensitive figures.
 */
@Entity
@Table(name = "lignes_di")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneDi extends BaseEntity {

    /**
     * The project this quote line belongs to. DevisInterneService.loadLigne() uses it to
     * check that the line asked for really belongs to the project in the URL, and refuses it
     * with "not found" otherwise - so someone cannot edit a line of project B through the
     * address of project A.
     *
     * The @ManyToOne annotation says many lines point at one project. Why a real relation and
     * not a plain projectId number: compute() needs the project's currency and exchange rate
     * on every read, and a bare number would force a second lookup each time.
     *
     * fetch = FetchType.LAZY means the project row is NOT read until someone actually calls
     * getProject(). Why: a quote can hold dozens of lines, all pointing at the same project.
     * With the default EAGER, loading the quote of one project would fire one query for the
     * lines plus one more per line for that same project - the classic "N+1 queries"
     * slowdown, where a 40-line quote costs 41 round trips to the database. Note that
     * application.yml sets open-in-view to false, so a lazy field touched after the
     * transaction has ended throws LazyInitializationException; that is safe here because
     * DevisInterneService touches getProject() inside its own transaction.
     *
     * The @JoinColumn annotation names the real column, project_id, and nullable = false says
     * a line can never float free of a project. Why: without it Hibernate would allow saving
     * a line with no project, and compute() would have no exchange rate to convert it with.
     * The database says the same thing twice, through NOT NULL and the foreign key
     * fk_ligne_di_project added by V23, which also blocks a line pointing at a project id
     * that does not exist.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * Which of the three blocks of the quote this line sits in: HONORAIRES, FRAIS or
     * AUTRES_FRAIS. This is not only a label - compute() gives AUTRES_FRAIS lines a
     * completely different cost formula (see SectionDi).
     *
     * The @Enumerated(EnumType.STRING) annotation stores the NAME of the value as text,
     * "AUTRES_FRAIS", instead of its position in the enum. Why this matters: the default,
     * ORDINAL, would store 0, 1 or 2. Someone inserting a new section in the middle of
     * SectionDi one day would then silently turn every stored 1 into a different meaning, and
     * every travel expense already recorded would become a tax line with a percentage
     * formula. Storing the name also lets the V23 constraint chk_ligne_di_section check the
     * value, and lets a director reading the table directly understand it.
     *
     * nullable = false: every line must say where it belongs. Without it, a line with no
     * section would be grouped nowhere on the DI screen, so it would be invisible to the
     * reader while still counting in the totals.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SectionDi section;

    /**
     * Display rank of the line inside its section. LigneDiRepository.findActiveByProjectId
     * sorts on section, then ordre, then id, so the quote always comes back in the same
     * sequence.
     *
     * WHY A COLUMN AND NOT SIMPLY THE ORDER OF CREATION: a quote is read line by line against
     * the paper Excel sheet during a review. Rows added later, or a row corrected, must still
     * be able to sit where the reader expects them. Without this field the only stable order
     * left would be the id, so a line added afterwards could never be put back in its place.
     *
     * nullable = false plus @Builder.Default: Lombok normally drops the "= 0" written here
     * when a builder is used, which would leave ordre at null and make the insert fail
     * against the NOT NULL column. @Builder.Default keeps the 0 for lines built with
     * LigneDi.builder(). DevisInterneService.apply() also replaces a missing value from the
     * request with 0, so the two paths agree.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 0;

    /**
     * The profile that was sold in the offer, for example "PC-1 Chef de mission". It is the
     * wording of the contract, so it is free text and not a link to a Resource row: the
     * client bought a profile, not a named person, and that wording must stay readable years
     * later even if the internal job titles are reorganised.
     *
     * The name = "profil_contractuel" part maps the Java camelCase name onto the snake_case
     * column created by V23. Without it Hibernate would look for a column named
     * "profilcontractuel" and the application would fail to start, because ddl-auto is set to
     * "validate".
     * length = 120 mirrors VARCHAR(120) in V23, so an over-long value is refused at startup
     * validation time rather than truncated by the database later.
     */
    @Column(name = "profil_contractuel", length = 120)
    private String profilContractuel;

    /**
     * The person whose name was put in the offer sent to the client.
     * Kept apart from ressourceRetenue below because offers are often written with a senior
     * name that the client knows, while someone else is finally staffed. Both have to be
     * visible side by side during the review, which is exactly what the Excel sheet shows.
     */
    @Column(name = "ressource_proposee", length = 120)
    private String ressourceProposee;

    /**
     * The person really staffed on the line. Compared with ressourceProposee above, this is
     * what tells the reader whether the promise made to the client was kept.
     * Free text on purpose, like the two fields above: it also has to be able to name a
     * subcontractor, who has no user account in PMS.
     */
    @Column(name = "ressource_retenue", length = 120)
    private String ressourceRetenue;

    /**
     * The unit the quantities on this line are counted in. "H-Jour" means homme-jour, a
     * man-day: one person working one day.
     *
     * WHY THE FIELD EXISTS AT ALL, SINCE IT IS ALMOST ALWAYS THE SAME: expense lines are not
     * counted in days (a flight, a monthly allowance), and the unit is printed next to the
     * quantity on the sheet. Without it the DI screen would have to print "days" on a line
     * that counts plane tickets.
     * Note that nothing in compute() reads this field: it is a label carried through to the
     * screen, and the arithmetic is the same whatever it says.
     *
     * nullable = false plus @Builder.Default: the column is NOT NULL with a default of
     * 'H-Jour' in V23. Lombok normally drops the "= H-Jour" initial value when the builder is
     * used, which would send null and break the insert, so @Builder.Default keeps it.
     * DevisInterneService.apply() repeats the same fallback for a value that is missing or
     * made only of spaces, so a user who clears the box does not blank the column.
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String unite = "H-Jour";

    /**
     * The workload SOLD on this line, in man-days (JH = homme-jour). This is the promise made
     * to the client, not what the company plans to spend.
     *
     * WHY BigDecimal AND NOT double: BigDecimal keeps decimal numbers exactly, digit by
     * digit, while double stores them in binary and cannot hold 0.1 exactly. With double,
     * 0.1 + 0.2 gives 0.30000000000000004, and a quote of a few hundred lines would end with
     * a total that is a few millimes off the Excel sheet it is checked against - which in a
     * financial review destroys trust in the whole screen. Every amount and every quantity in
     * PMS is BigDecimal for this reason.
     *
     * precision = 10, scale = 2 mirrors NUMERIC(10,2) in V23: ten digits in all, two of them
     * after the decimal point, so up to 99,999,999.99 and half-days such as 12.50 are exact.
     * Why it is written here as well as in the migration: ddl-auto is "validate", so if
     * someone changed the column to NUMERIC(8,2) without touching this line, the application
     * would refuse to start instead of silently rounding days later.
     *
     * The field is allowed to be null (a section heading line, an expense line with no days),
     * which is why DevisInterneService protects every read with its nz() helper, turning null
     * into zero. Without that, the first line left empty would end the request with a
     * NullPointerException and the whole quote would fail to open.
     *
     * Used by compute() as: amount in project currency = chargeVendueJh x prixVenteUnitaire.
     */
    @Column(name = "charge_vendue_jh", precision = 10, scale = 2)
    private BigDecimal chargeVendueJh;

    /**
     * Selling price of one unit, expressed in the PROJECT currency, not in TND. For a fee
     * line, the price of one man-day as written in the contract.
     *
     * Why the project currency and not TND: the contract is signed in the client's currency
     * and is checked against the client's own paperwork. Storing a converted value would mean
     * that fixing the project exchange rate afterwards would change a price the client
     * actually signed. The conversion happens once, in compute(), using the rate held on the
     * project row.
     *
     * precision = 15, scale = 2 mirrors NUMERIC(15,2) - wider than the quantity columns
     * because a currency such as FCFA counts in hundreds of thousands per day.
     */
    @Column(name = "prix_vente_unitaire", precision = 15, scale = 2)
    private BigDecimal prixVenteUnitaire;

    /**
     * The workload the company really expects to spend on this line, in man-days. The
     * difference with chargeVendueJh above is the whole point of the internal quote: days
     * sold are revenue, these days are cost, and the gap between the two is where the margin
     * comes from.
     *
     * WHY TWO SEPARATE COLUMNS AND NOT ONE: they are genuinely different figures. A line can
     * be sold at 10 days and staffed with a junior for 12, or sold at 10 and delivered in 8.
     * Keeping one column would make it impossible to see which lines are being sold at a
     * loss, which is the first question asked at a project review.
     *
     * Used by compute() as: prix de revient (cost price) = quantiteInterneJh x coutUnitaireTcc.
     */
    @Column(name = "quantite_interne_jh", precision = 10, scale = 2)
    private BigDecimal quantiteInterneJh;

    /**
     * What one internal man-day really costs the company on this line, in TND. TCC stands for
     * "taux de coût chargé", the loaded cost rate: the daily rate with the company overhead
     * already added on top, so this single figure is the full cost of a day.
     *
     * WHY THE VALUE IS COPIED ONTO THE LINE INSTEAD OF BEING READ FROM THE RESOURCE TABLE:
     * the DI is a forecast written before the project starts, often for a profile rather than
     * for a named person, and sometimes for a subcontractor who has no Resource row at all.
     * Tying it to the resource table would make the quote impossible to write, and would also
     * make an old quote change by itself the day a rate is renegotiated. Note the contrast
     * with KpiService, which computes the REAL cost of days already worked and does read the
     * per-year rates (F-AFF-13 section 6.3 rule 4). Forecast and reality are two different
     * numbers on purpose.
     *
     * Why in TND while the selling price above is in the project currency: this is what the
     * company pays its own people, and the company pays in TND. compute() converts the sold
     * side to TND before subtracting, so the two sides meet in the same currency.
     *
     * This is the most sensitive figure in the whole application - it is the company's real
     * cost structure. It is one of the values the 2026-07-05 decision says must never be
     * seeded, and reaching it needs both MANAGE_DI and the project scope check of ADR-021.
     */
    @Column(name = "cout_unitaire_tcc", precision = 10, scale = 2)
    private BigDecimal coutUnitaireTcc;

    /**
     * Miscellaneous costs added to this line as a flat amount in TND, noted FD on the Excel
     * sheet: anything that is not a man-day and not general overhead, for example a piece of
     * equipment bought for this task.
     * Kept as a separate column rather than folded into the daily cost because the reviewer
     * has to be able to see why a line costs more than days times rate.
     */
    @Column(name = "frais_divers", precision = 15, scale = 2)
    private BigDecimal fraisDivers;

    /**
     * General overhead charged to this line as a flat amount in TND, noted FG-P&ST on the
     * Excel sheet. Separate from fraisDivers above because the two are read by different
     * people: one is a project decision, the other is the company's structural charge.
     */
    @Column(name = "frais_generaux", precision = 15, scale = 2)
    private BigDecimal fraisGeneraux;

    /**
     * Direct taxes charged to this line as a flat amount in TND. V23 lists the ones meant
     * here: RS/IS (withholding and company tax), IRPP/CNSS (income tax and social security),
     * ENR (registration), REDEV (royalties).
     *
     * WHY A FLAT AMOUNT HERE WHILE tauxPourcentage BELOW IS A RATE: some taxes are known as a
     * fixed sum for this line, while others can only be worked out once the total sold is
     * known. Both cases exist on the same quote, so the model carries both. compute() adds
     * this amount into the cost of a normal line, and the three flat-amount columns above are
     * simply summed on top of the cost price.
     */
    @Column(name = "cout_impots", precision = 15, scale = 2)
    private BigDecimal coutImpots;

    /**
     * Rate used by tax and provision lines: their cost is this rate multiplied by the TOTAL
     * sold of the whole quote, converted to TND. Translated from the original French note:
     * tax/provision lines, cost = rate x total sold in TND, for example 0.05 for 5 %.
     *
     * The rate is stored as a coefficient (0.05), not as the number 5. Why: compute() writes
     * tauxPourcentage.multiply(totalVenduTnd) with nothing in between. Storing 5 would make
     * every provision a hundred times too big - a 5 % risk provision on a 1,000,000 TND quote
     * would come out at 50,000,000 TND and turn the margin deeply negative. Project.
     * margeNetteVendue follows the same convention, so the whole application reads rates the
     * same way.
     *
     * precision = 7, scale = 4 mirrors NUMERIC(7,4): four digits after the point, so 0.0525
     * (5.25 %) is kept exactly.
     *
     * THIS FIELD IS WHY THE CALCULATION NEEDS TWO PASSES. A line priced on the total of the
     * quote cannot be computed until every other line has been added up, so
     * DevisInterneService first walks all the lines to get the sold total, then walks them
     * again to price each one. Note the exact condition it uses: section is AUTRES_FRAIS AND
     * this field is not null. An AUTRES_FRAIS line with no rate goes down the ordinary path
     * and is costed from the flat amounts above, so a registration fee known as a fixed sum
     * still works.
     */
    @Column(name = "taux_pourcentage", precision = 7, scale = 4)
    private BigDecimal tauxPourcentage;
}
