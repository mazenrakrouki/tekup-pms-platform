package com.pms.project.dto;

import com.pms.project.entity.SectionDi;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * FILE: LigneDiRequest.java
 *
 * WHAT THIS FILE IS
 * The shape of the JSON body the browser sends when it adds or changes one line of the Devis
 * Interne (internal quote). It is a DTO (Data Transfer Object): an object used only to carry
 * data over the network. It also carries the input rules, written as Bean Validation
 * annotations, so a bad value is refused before any business code runs.
 *
 * WHERE IT SITS IN THE FLOW
 *   Angular DI screen (JSON body)
 *     -> DevisInterneController.addLigne / updateLigne, where the parameter is marked @Valid
 *     -> if a rule below fails, Spring answers 400 Bad Request and the service is never entered
 *     -> otherwise DevisInterneService.apply(ligne, request) copies these values onto the
 *        LigneDi entity, saves it, and returns the whole recomputed DevisInterneResponse.
 *
 * WHY IT EXISTS
 * Two reasons, and both matter for the demonstration.
 * 1. Safety. If the controller took the LigneDi entity directly, the JSON could also set fields
 *    the client must never choose: the id, the deleted flag, or the project link. A caller could
 *    then move a line from his own project into someone else's quote just by editing the body.
 *    This record has no id and no project: the line id and the project id come from the URL,
 *    which ProjectScopeInterceptor already checks (ADR-021).
 * 2. Clean errors. The rules below turn a bad entry into a 400 with the field name, instead of
 *    letting it reach PostgreSQL and come back as a 500 that tells the user nothing.
 *
 * WHY ONE RECORD FOR BOTH CREATE AND UPDATE
 * Both operations send exactly the same fields, so a second near-identical record would only add
 * a place to forget a rule. The difference between the two is the URL, not the payload.
 *
 * WHAT IT NEVER CONTAINS
 * No computed amount. montantDevise, montantTnd, coutFinal and the margins are absent on
 * purpose: the server derives them at read time. If the client could send a margin, it could
 * dictate the figure the company steers on.
 */

/**
 * One editable DI line coming from the client.
 *
 * Why a record: it is immutable, so the values that passed validation are the exact values the
 * service later copies onto the entity. Nothing can rewrite them in between.
 *
 * Note on the annotations below: Bean Validation ignores null. Every rule here therefore means
 * "if a value is present, it must look like this". Only @NotNull actually demands a value, and
 * that is deliberate: a DI line is filled in progressively, so most fields must be allowed to
 * stay empty while the quote is being built.
 */
public record LigneDiRequest(
        // @NotNull refuses a missing or null section.
        // Why: the section drives the cost rule (an AUTRES_FRAIS line with a rate is costed as a
        // share of the total sold, any other line is costed from its days and fees), and the
        // database column is NOT NULL.
        // Without it: a line saved with no section would be rejected by PostgreSQL as a
        // constraint violation, so the user would get a 500 instead of a clear "section required".
        @NotNull SectionDi section,
        // Display rank inside the section. No rule on purpose: it is not typed by the user but
        // set by the screen, and the service replaces a null with 0.
        Integer ordre,
        // @Size(max = 120) caps the text at 120 characters.
        // Why 120: it is exactly the length of the matching column (profil_contractuel
        // VARCHAR(120)) on the lignes_di table.
        // Without it: a pasted 300-character title would travel all the way to the database and
        // fail there with "value too long for type character varying(120)", a 500 error with no
        // field name in it. With it, the caller gets a 400 naming profilContractuel.
        @Size(max = 120) String profilContractuel,
        // Same 120-character cap, for the same reason: these two mirror the ressource_proposee
        // and ressource_retenue columns.
        @Size(max = 120) String ressourceProposee,
        @Size(max = 120) String ressourceRetenue,
        // Unit of the line ("H-Jour" and so on), capped at the 20 characters of the unite column.
        // The service falls back to "H-Jour" when this arrives empty or blank.
        @Size(max = 20) String unite,
        // @PositiveOrZero accepts 0 and any positive number, and refuses a negative one.
        // Why zero is allowed: a line can legitimately sell nothing yet, or be a pure cost line.
        // Why negatives are refused: chargeVendueJh is multiplied by prixVenteUnitaire to build
        // the amount sold. A charge of -10 days would subtract from the project revenue and make
        // the total margin look better than reality, which is the one number the whole Devis
        // Interne exists to tell the truth about.
        @PositiveOrZero BigDecimal chargeVendueJh,
        // Unit selling price, in the project currency. Same rule, same reason: a negative price
        // would produce negative revenue on a line that is really being sold.
        @PositiveOrZero BigDecimal prixVenteUnitaire,
        // Workload the company plans to spend internally, in JH (one person, one day).
        // Negative days are meaningless and would lower the computed cost of the project.
        @PositiveOrZero BigDecimal quantiteInterneJh,
        // Fully loaded daily cost of the resource (TCC, "Taux de Cout Charge"): the daily rate
        // with the company overhead already added on top. It is typed in DINARS, not in the
        // project currency like prixVenteUnitaire above, because this is what the company pays
        // its own people and it pays in dinars. The calculation engine relies on that: it
        // converts only the sold side, then subtracts the cost side as it is.
        // A negative cost would turn a cost into a gain and inflate the margin.
        @PositiveOrZero BigDecimal coutUnitaireTcc,
        // The three flat extra costs added to a normal line, each a plain amount in dinars
        // like the TCC above: miscellaneous fees, general overhead, taxes. Each is added to
        // the line cost as it is, so a negative value here would quietly cancel part of the
        // real cost of the line - for example a fraisGeneraux of -500 on a line costing 500
        // would make that line appear to cost nothing at all.
        @PositiveOrZero BigDecimal fraisDivers,
        @PositiveOrZero BigDecimal fraisGeneraux,
        @PositiveOrZero BigDecimal coutImpots,
        // @DecimalMin("0.0") and @DecimalMax("1.0") force this to be a fraction between 0 and 1:
        // 0.05 means 5 %.
        // Why: for an AUTRES_FRAIS line the cost is tauxPourcentage x total sold in TND (a local
        // tax, a risk provision). The whole quote depends on this number staying a fraction.
        // Without the maximum, a user typing 5 because he means "5 %" would create a cost equal
        // to five times the total sold, and the project margin would drop by 500 % of revenue.
        // Without the minimum, a negative rate would invent money out of a tax line.
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal tauxPourcentage
) {}
