/*
 * FILE: di.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes of the Devis Interne (DI = internal quote), seen from the browser: one line
 * as it is SENT (LigneDiRequest), the same line as it comes BACK with its computed amounts
 * (LigneDiResponse), and the whole quote with its totals (DevisInterneResponse). It also
 * holds the three readable labels of the sections.
 *
 * WHAT A DEVIS INTERNE IS
 * The internal quote is the sheet where, line by line, the company writes what it SOLD
 * (days sold x unit selling price) against what the work really COSTS it (internal days x
 * internal daily cost, plus overheads and taxes). The difference is the margin. It is the
 * most sensitive screen of the whole application: it shows what the company earns on each
 * profile, which is exactly what must never reach a client or a developer.
 *
 * WHERE IT SITS IN THE FLOW
 *   features/di/devis-interne.component.ts (the DI screen) fills a LigneDiRequest
 *     -> core/services/di.service.ts POSTs / PUTs it to
 *        /api/projects/{id}/devis-interne/lignes
 *     -> Spring Boot DevisInterneController -> DevisInterneService saves the line, then
 *        recomputes the WHOLE quote and answers with DevisInterneResponse
 *     -> the screen redraws every line and every total from that single answer.
 *   The same DevisInterneResponse shape also comes back from a plain GET of the quote.
 *
 * WHY EVERY WRITE GIVES BACK THE WHOLE QUOTE, NOT JUST THE SAVED LINE
 * Some lines cost a PERCENTAGE of the total sold (a local tax, a risk provision), so
 * changing one line changes the cost of others. If the server answered with the saved line
 * alone, the screen would have to recompute the rest by itself, and the two would drift
 * apart. Here the browser computes nothing at all: it displays.
 *
 * THE RULE THE JURY WILL ASK ABOUT
 * The computed amounts are DERIVED WHEN READ, never stored in a column. That is why they
 * exist on LigneDiResponse and are absent from LigneDiRequest. Stored, they would become
 * stale the moment an exchange rate or a daily cost changed, and the quote would show a
 * margin that matches nothing.
 *
 * SECURITY REMINDER
 * Nothing in this file protects anything; it only describes data. On the server every
 * public method of DevisInterneService carries
 * @PreAuthorize("hasAuthority('MANAGE_DI')"), and because the URLs look like
 * /api/projects/{id}/devis-interne/..., ProjectScopeInterceptor also checks that this user
 * may touch THAT project (ADR-021). Permission alone is not enough.
 */

// The three families of lines of the quote, exactly as the Java enum SectionDi spells
// them. HONORAIRES = the people sold; FRAIS = the direct expenses; AUTRES_FRAIS = taxes
// and provisions, which are the only lines allowed to be a percentage of the total.
// Why a union type and not a plain string: the section decides how the server computes the
// cost of the line. A typo such as 'AUTRE_FRAIS' would compile as a string, take the
// ordinary branch on the server, and a tax line would silently cost nothing at all.
export type SectionDi = 'HONORAIRES' | 'FRAIS' | 'AUTRES_FRAIS';

// The label printed as the title of each group of lines on the DI screen. Read by
// devis-interne.component.ts through sectionLabel().
// Record<SectionDi, string> is a built-in generic type meaning "an object whose keys are
// exactly the values of SectionDi, each holding a string".
// WHY Record and not a plain object: the compiler now refuses the file if a section is
// forgotten OR if a key is misspelled. Without it, adding a fourth section to the union
// type above would leave this map incomplete, sectionLabel() would return undefined, and
// the screen would print an empty group title over a block of money figures.
// (The labels are in French because they are the words used in the company's own Excel
// sheet, which is the reference document for this screen.)
export const SECTION_DI_LABELS: Record<SectionDi, string> = {
  HONORAIRES: 'Honoraires',
  FRAIS: 'Frais',
  AUTRES_FRAIS: 'Autres frais (taxes, provisions)'
};

/**
 * One line of the quote as the browser SENDS it (mirror of the Java record LigneDiRequest).
 *
 * WHAT IT DELIBERATELY DOES NOT CONTAIN: no id, no projectId, and none of the computed
 * amounts. The line id and the project id come from the URL, which is where
 * ProjectScopeInterceptor reads them (ADR-021). If they sat in the body, a caller could
 * keep the URL of his own project and move a line into somebody else's quote.
 *
 * Nearly every field is optional. That is on purpose: a line of the FRAIS section has no
 * selling price, and a line being typed in is incomplete. On the server each missing value
 * is read as zero, so a half-filled line counts as nothing instead of making the whole
 * quote fail.
 */
export interface LigneDiRequest {
  /** Which family the line belongs to. The only field the server refuses to receive empty. */
  section: SectionDi;
  /** Where the line sits inside its section, so the user can reorder the sheet. */
  ordre?: number;
  /** The profile sold to the client, for example "Ingenieur confirme". */
  profilContractuel?: string;
  /** The person put forward in the offer. */
  ressourceProposee?: string;
  /** The person really staffed, which is often not the one put forward. Kept apart so the
   *  quote can show the difference between what was promised and what was delivered. */
  ressourceRetenue?: string;
  /** The unit the line is counted in ("JH" for man-day, "forfait", "mois"...). */
  unite?: string;
  /** Days SOLD to the client for this line (JH = jours-homme, man-days). */
  chargeVendueJh?: number;
  /** Selling price of one unit, in the currency of the project. */
  prixVenteUnitaire?: number;
  /** Days the company really plans to spend. It is normal for it to differ from the days
   *  sold - that gap is one of the things the quote exists to make visible. */
  quantiteInterneJh?: number;
  /** TCC = internal cost of one day of that person for the company (salary and charges).
   *  Multiplied by quantiteInterneJh, it gives the cost price of the line. */
  coutUnitaireTcc?: number;
  /** Extra direct costs of the line (travel, small purchases). */
  fraisDivers?: number;
  /** Share of the company overheads charged to the line. */
  fraisGeneraux?: number;
  /** Taxes charged to the line. */
  coutImpots?: number;
  /**
   * A rate stored as a FRACTION: 0.05 means 5%, not 5.
   * It only has an effect on an AUTRES_FRAIS line, where the server computes the cost as
   * "this fraction x total sold in dinars" instead of a cost price.
   * Why a fraction and not a percentage: the server multiplies directly, with no division
   * by 100. Sending 5 here would charge 500% of the total sold to that line.
   * The server also refuses anything outside 0 to 1.
   */
  tauxPourcentage?: number;
}

/**
 * One line as the server SENDS IT BACK (mirror of the Java record LigneDiResponse).
 *
 * "extends LigneDiRequest" means: everything that can be sent, plus what follows.
 * WHY extends and not a second full list of fields: the two shapes must stay in step. With
 * two independent lists, a column added to the form tomorrow would be added to one of them
 * and forgotten in the other, and the screen would lose that value the moment it reloaded.
 */
export interface LigneDiResponse extends LigneDiRequest {
  id: number;
  // The six fields below are COMPUTED BY THE SERVER EVERY TIME THE QUOTE IS READ. There is
  // no column for them in PostgreSQL.
  // Why never stored: they depend on the exchange rate of the project and, for the
  // percentage lines, on the total of all the other lines. Stored, they would keep the
  // value they had on the day they were saved: change the exchange rate and the sheet would
  // still display last month's dinars while claiming to be today's quote.
  // They are optional here because the server sends null when it cannot give a figure - see
  // margePct below.
  /** Sold in the project currency: chargeVendueJh x prixVenteUnitaire, 2 decimals. */
  montantDevise?: number;
  /** The same amount converted into dinars: montantDevise x exchangeRateToTnd. */
  montantTnd?: number;
  /** Cost price: quantiteInterneJh x coutUnitaireTcc. What the work really costs. */
  prixRevient?: number;
  /**
   * The full cost of the line. Two cases:
   *  - an AUTRES_FRAIS line carrying a tauxPourcentage: tauxPourcentage x total sold in TND;
   *  - every other line: prixRevient + fraisDivers + fraisGeneraux + coutImpots.
   */
  coutFinal?: number;
  /** What the line earns: montantTnd - coutFinal. A negative value is legal and is exactly
   *  what the Director must see - it means the line is sold below its cost. */
  margeNette?: number;
  /**
   * The margin as a FRACTION of what was sold, 4 decimals (0.4412 = 44.12%).
   * It is absent when nothing was sold on the line: the server refuses to divide by zero
   * and sends null instead, so the screen shows an empty cell rather than a made-up 0%.
   * Same shape as Project.margeNetteVendue, so the two can be compared without converting.
   */
  margePct?: number;
}

/**
 * The WHOLE quote of one project (mirror of the Java record DevisInterneResponse). This is
 * what every call of di.service.ts gives back - read, create, update and delete alike.
 *
 * Why the totals are sent instead of being added up in the browser: the server already
 * needs them to price the percentage lines, and two places computing money is two places
 * that can disagree. Here the screen displays what it is given and computes nothing.
 */
export interface DevisInterneResponse {
  projectId: number;
  projectCode: string;
  /** The currency the project sells in, for example "EUR". */
  currency: string;
  /**
   * How many dinars one unit of that currency is worth. It travels with the quote on
   * purpose: the screen shows amounts in two currencies, and without the rate the reader
   * cannot check one against the other.
   */
  exchangeRateToTnd: number;
  /** Every line of the quote, already recomputed. */
  lignes: LigneDiResponse[];
  /** Sum of montantDevise over all the lines, in the project currency. */
  totalVenduDevise: number;
  /**
   * The same total in dinars. It is converted ONCE, from the total, and not line by line:
   * rounding each line to 2 decimals and then adding them up drifts by a few centimes on a
   * long quote, and the sheet would stop matching the contract.
   */
  totalVenduTnd: number;
  /** Total days sold, all lines together. */
  totalChargeVendueJh: number;
  /** Total days really planned, all lines together. The gap with the line above is the one
   *  the project manager is asked to explain. */
  totalQuantiteInterneJh: number;
  /** Sum of coutFinal over all the lines, in dinars. */
  totalCoutFinal: number;
  /**
   * Margin of the whole quote: totalVenduTnd - totalCoutFinal.
   * It is recomputed from the two totals and is deliberately NOT the sum of the per-line
   * margins, so the figure the Director reads always agrees with the two totals printed
   * beside it, whatever rounding happened line by line.
   */
  margeNette: number;
  /** margeNette / totalVenduTnd, as a fraction with 4 decimals. Absent when nothing has
   *  been sold yet, for the same "no division by zero" reason as on a line. */
  margePct?: number;
}
