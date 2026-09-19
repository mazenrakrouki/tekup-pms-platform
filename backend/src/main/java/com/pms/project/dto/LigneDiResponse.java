package com.pms.project.dto;

import com.pms.project.entity.SectionDi;

import java.math.BigDecimal;

/*
 * FILE: LigneDiResponse.java
 *
 * WHAT THIS FILE IS
 * One line of the Devis Interne (internal quote) as it is sent to the browser: the values the
 * user typed, followed by the amounts the server derived from them. It is a DTO (Data Transfer
 * Object): an object whose only job is to carry data over the network. It holds no logic.
 *
 * WHERE IT SITS IN THE FLOW
 *   DevisInterneService.compute(project, lignes) reads each LigneDi entity (table lignes_di),
 *   computes the six derived amounts, and builds one LigneDiResponse per line
 *     -> the list of these records is placed inside DevisInterneResponse.lignes()
 *     -> DevisInterneController returns that to the Angular DI screen as JSON.
 * The opposite direction uses a different record: what the user sends back is LigneDiRequest.
 * The two are deliberately not the same object; see the note below.
 *
 * WHY IT EXISTS
 * Without it there would be nowhere to put the computed amounts. The LigneDi entity stores only
 * the raw inputs (sold workload, unit price, internal quantity, unit cost, fees, rate). The
 * decision recorded in BUSINESS_ANALYSIS.md section 16 is that computed amounts are derived at
 * read time and never stored, so montantDevise, montantTnd, prixRevient, coutFinal, margeNette
 * and margePct exist only here. Deleting this record would force those numbers into columns, and
 * the day a project exchange rate is corrected every stored dinar amount would become a lie.
 * It also protects the caller: the entity has a lazy link back to Project, and serialising it
 * directly would either fail or pull the whole project graph out of the database.
 *
 * WHY IT IS NOT REUSED AS THE INPUT TYPE
 * A response carries an id and six read-only amounts. If the client sent this same shape back,
 * it could try to dictate its own montantTnd or its own margin. Keeping a separate
 * LigneDiRequest, with no id and no computed field, makes that impossible by construction: the
 * server is the only place where money is calculated.
 */

/**
 * One DI line with its amounts computed at read time (never stored).
 *
 * Why a record and not a class: a record is immutable. Once the calculation engine has filled
 * it, no later layer can quietly change an amount before it reaches the screen.
 *
 * Reading the formulas below: "Devise" is the project currency (the one in the contract), "TND"
 * is the Tunisian dinar used for internal steering, and "JH" is a "jour-homme", one person
 * working one day. Every money value is rounded to 2 decimals with HALF_UP (0.005 goes up to
 * 0.01), and every percentage is kept with 4 decimals, so 0.4412 means 44.12 %.
 *
 * WATCH THE TWO CURRENCIES - this is where a jury question is likely to land. The SOLD side is
 * typed in the project currency (prixVenteUnitaire, then montantDevise) and is converted once
 * into montantTnd. The COST side is already in dinars: coutUnitaireTcc is what the company pays
 * its own people and the company pays in dinars, so prixRevient, the three flat extra costs and
 * coutFinal are dinar figures that DevisInterneService never multiplies by the exchange rate.
 * That is what lets margeNette subtract coutFinal from montantTnd directly: by then both sides
 * are in TND. Example of why it matters: on a contract in FCFA (about 0.00585 TND for one
 * FCFA), converting the cost side a second time would divide the real cost of the line by
 * roughly 170 and show a margin close to 100 %.
 */
public record LigneDiResponse(
        // Database id of the line. The client sends it back in the URL when it updates or
        // deletes this line (PUT/DELETE .../lignes/{ligneId}), which is why the response must
        // carry it; without it the screen could display lines but never edit one.
        Long id,
        // Which block of the quote this line belongs to: HONORAIRES (fees for a profile or a
        // resource), FRAIS (per-diem, travel) or AUTRES_FRAIS (local taxes, registration, risk
        // provision). It is an enum, so only those three values can ever arrive; a free String
        // would let a typo such as "FRAI" through and the cost rule below would silently take
        // the wrong branch.
        SectionDi section,
        // Display rank inside the section, so the screen can show the lines in the order the
        // user arranged them instead of in database order.
        Integer ordre,
        // The contractual profile sold to the client, for example "Senior developer".
        String profilContractuel,
        // The person proposed in the offer, and the person finally staffed. The two can differ,
        // which is exactly why both are kept: the quote was built on one cost and delivered with
        // another.
        String ressourceProposee,
        String ressourceRetenue,
        // Unit the line is counted in, "H-Jour" by default (a working day).
        String unite,
        // Workload sold to the client on this line, in JH. This is what the client pays for.
        BigDecimal chargeVendueJh,
        // Price of one unit sold, in the project currency.
        BigDecimal prixVenteUnitaire,
        // Workload the company really plans to spend on this line, in JH. It can be lower than
        // the sold workload (that is where the margin comes from) or higher (a loss).
        BigDecimal quantiteInterneJh,
        // Cost of one internal day, in DINARS - not in the project currency, unlike the
        // selling price two lines above. TCC stands for "Taux de Cout Charge": the fully
        // loaded daily cost of a resource, that is the daily rate with the company overhead
        // already added on top.
        // Why in TND: this is what the company pays its own people, and it pays in dinars.
        // Why the figure is copied onto the line instead of being read from the TccAnnuel
        // table (the per-resource, per-year rates maintained by holders of MANAGE_RESOURCES):
        // a quote is a forecast, often written for a contractual profile or for a
        // subcontractor who has no resource row at all, and an old quote must not change by
        // itself the day a rate is renegotiated.
        BigDecimal coutUnitaireTcc,
        // Three extra costs attached to this line that are not day-based, each a flat amount
        // in dinars like the TCC above: miscellaneous fees (FD on the Excel sheet), general
        // overhead (FG-P&ST) and direct taxes. DevisInterneService adds them to the cost of
        // the line exactly as they are.
        // Why three columns and not one total: the reviewer has to be able to see WHY a line
        // costs more than days times daily rate. Merged into a single "extras" figure, a line
        // carrying a large structural overhead would look identical to one carrying a piece of
        // equipment bought for the task, and the review question could not even be asked.
        BigDecimal fraisDivers,
        BigDecimal fraisGeneraux,
        BigDecimal coutImpots,
        // Percentage used by the AUTRES_FRAIS lines that are a share of the total sold, such as
        // a 5 % risk provision. It is stored as a fraction: 0.05 means 5 %.
        BigDecimal tauxPourcentage,
        // -- computed at read time, never stored --
        // Amount sold on this line, in the project currency:
        //   montantDevise = chargeVendueJh x prixVenteUnitaire   (missing values count as 0)
        BigDecimal montantDevise,
        // The same amount in dinars: montantTnd = montantDevise x project exchange rate.
        // Why it is recomputed rather than saved in a column: the day the project rate is
        // corrected, a stored dinar amount would keep the old conversion and the quote would no
        // longer add up. Derived at read time, every line follows the correction at once.
        BigDecimal montantTnd,
        // Internal cost of the days themselves, in DINARS (coutUnitaireTcc is already a dinar
        // figure, so nothing is converted here):
        //   prixRevient = quantiteInterneJh x coutUnitaireTcc
        // "Prix de revient" is the French accounting term for cost price: what the work costs
        // the company, before any selling price is looked at.
        BigDecimal prixRevient,
        // Final cost of the line, in dinars, and the only field with two possible formulas:
        //   normal line      -> prixRevient + fraisDivers + fraisGeneraux + coutImpots
        //   AUTRES_FRAIS line with a tauxPourcentage -> tauxPourcentage x total sold in TND
        // Why the second rule exists: a tax or a risk provision is not bought by the day, it is
        // a share of what the whole project sells. That is also why the engine computes the
        // quote in two passes: the total sold must be known before these lines can be costed.
        // Read the condition carefully - BOTH halves are required. An AUTRES_FRAIS line whose
        // tauxPourcentage is null takes the ordinary branch, which is what makes a registration
        // fee known as a fixed sum still work. Testing the section alone would cost that line
        // at 0 % of the total, that is at nothing, and the quote would look more profitable
        // than it is.
        BigDecimal coutFinal,
        // What the line leaves once it is paid for: margeNette = montantTnd - coutFinal.
        // It can be negative, and that is wanted: a line sold below its cost must be visible,
        // not clipped to zero.
        BigDecimal margeNette,
        // The same margin as a fraction of the line amount: margeNette / montantTnd, 4 decimals.
        // It is null, not zero, when montantTnd is zero or less, because dividing by zero has no
        // meaning. Example of why that matters: a pure cost line (a tax line sells nothing)
        // would otherwise display "0 %", which reads as "this line breaks even" when in truth it
        // has no revenue to compare against.
        BigDecimal margePct
) {}
