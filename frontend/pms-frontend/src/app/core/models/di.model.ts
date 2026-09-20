// Browser-side shapes for the Devis Interne (internal quote): a line as SENT
// (LigneDiRequest), the same line BACK with computed amounts (LigneDiResponse), and the
// whole quote with totals (DevisInterneResponse). The DI is the most sensitive screen in
// the app - per-line it shows what was sold vs. what the work really costs, so the margin
// is visible; that must never reach a client. Every write returns the WHOLE recomputed
// quote (not just the saved line) because percentage-based lines depend on the total, so
// the browser never computes money itself, only displays. Computed amounts are DERIVED ON
// READ, never stored in a column - see LigneDiResponse. Access is enforced server-side via
// @PreAuthorize("hasAuthority('MANAGE_DI')") plus ProjectScopeInterceptor (ADR-021).

// Mirrors the Java enum SectionDi. HONORAIRES = people sold; FRAIS = direct expenses;
// AUTRES_FRAIS = taxes/provisions, the only lines allowed to cost a percentage of the total.
export type SectionDi = 'HONORAIRES' | 'FRAIS' | 'AUTRES_FRAIS';

// Section group titles shown by devis-interne.component.ts via sectionLabel(). Record<> so
// the compiler refuses the file if a section is missing or misspelled. French because these
// are the words used in the company's own Excel reference sheet.
export const SECTION_DI_LABELS: Record<SectionDi, string> = {
  HONORAIRES: 'Honoraires',
  FRAIS: 'Frais',
  AUTRES_FRAIS: 'Autres frais (taxes, provisions)'
};

/**
 * One quote line as the browser SENDS it (mirror of LigneDiRequest). No id/projectId - both
 * come from the URL (ADR-021) - and no computed amounts. Almost every field is optional:
 * FRAIS lines have no selling price, and a line mid-edit is incomplete; missing values are
 * read as zero server-side.
 */
export interface LigneDiRequest {
  /** The only field the server refuses empty. */
  section: SectionDi;
  /** Position within its section, for reordering. */
  ordre?: number;
  /** Profile sold to the client, e.g. "Ingenieur confirme". */
  profilContractuel?: string;
  /** Person put forward in the offer. */
  ressourceProposee?: string;
  /** Person actually staffed, often different - kept separate to show promised vs. delivered. */
  ressourceRetenue?: string;
  /** Unit the line is counted in ("JH", "forfait", "mois"...). */
  unite?: string;
  /** Days SOLD to the client (JH = jours-homme, man-days). */
  chargeVendueJh?: number;
  /** Selling price per unit, in the project's currency. */
  prixVenteUnitaire?: number;
  /** Days the company really plans to spend; normally differs from days sold. */
  quantiteInterneJh?: number;
  /** TCC = internal daily cost of that person; x quantiteInterneJh gives the line's cost price. */
  coutUnitaireTcc?: number;
  /** Extra direct costs (travel, small purchases). */
  fraisDivers?: number;
  /** Share of company overheads charged to the line. */
  fraisGeneraux?: number;
  /** Taxes charged to the line. */
  coutImpots?: number;
  /** Fraction, not percentage (0.05 = 5%): only applies to an AUTRES_FRAIS line, where the
   *  server multiplies directly against the total sold. Server also rejects values outside 0-1. */
  tauxPourcentage?: number;
}

/**
 * One line as the server SENDS IT BACK (mirror of LigneDiResponse). Extends LigneDiRequest
 * so the two shapes can't drift apart as fields are added.
 */
export interface LigneDiResponse extends LigneDiRequest {
  id: number;
  // The fields below are COMPUTED SERVER-SIDE ON EVERY READ, never stored: they depend on
  // the project's exchange rate and, for percentage lines, the other lines' totals.
  /** Sold in project currency: chargeVendueJh x prixVenteUnitaire, 2 decimals. */
  montantDevise?: number;
  /** montantDevise converted to dinars. */
  montantTnd?: number;
  /** Cost price: quantiteInterneJh x coutUnitaireTcc. */
  prixRevient?: number;
  /** Full line cost: tauxPourcentage x total sold (AUTRES_FRAIS lines), else
   *  prixRevient + fraisDivers + fraisGeneraux + coutImpots. */
  coutFinal?: number;
  /** montantTnd - coutFinal; a negative value is legal and means the line is sold below cost. */
  margeNette?: number;
  /** Margin as a fraction of sold amount, 4 decimals. Absent (not 0) when nothing was sold,
   *  to avoid a divide-by-zero. Same shape as Project.margeNetteVendue for direct comparison. */
  margePct?: number;
}

/**
 * The WHOLE quote of one project (mirror of DevisInterneResponse); returned by every
 * di.service.ts call. Totals are server-computed, never added up in the browser.
 */
export interface DevisInterneResponse {
  projectId: number;
  projectCode: string;
  /** Currency the project sells in, e.g. "EUR". */
  currency: string;
  /** Dinar value of one currency unit; travels with the quote so amounts can be cross-checked. */
  exchangeRateToTnd: number;
  lignes: LigneDiResponse[];
  /** Sum of montantDevise across lines, in project currency. */
  totalVenduDevise: number;
  /** Same total in dinars, converted once from the total (not summed line by line, to avoid
   *  rounding drift). */
  totalVenduTnd: number;
  /** Total days sold across lines. */
  totalChargeVendueJh: number;
  /** Total days really planned; the gap with the line above is what a PM must explain. */
  totalQuantiteInterneJh: number;
  /** Sum of coutFinal across lines, in dinars. */
  totalCoutFinal: number;
  /** totalVenduTnd - totalCoutFinal; deliberately not the sum of per-line margins, so it
   *  always agrees with the two totals shown beside it. */
  margeNette: number;
  /** margeNette / totalVenduTnd, 4-decimal fraction. Absent when nothing sold yet. */
  margePct?: number;
}
