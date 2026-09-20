// Project (server shape) and ProjectRequest (create/update body), the most widely-shared
// contract in the front end. Many fields are optional either because the value genuinely
// doesn't exist yet (DRAFT project) or because the server blanked it for a user without
// VIEW_KPI (withoutFinancials()) — never replace a missing one with 0.

// The five project states, mirroring the Java enum ProjectStatus; drives PROJECT_STATUS_LABELS.
export type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';
// SEUL = sold alone, GROUPEMENT = in a consortium; changes invoicing and margin sharing.
export type BusinessModel = 'SEUL' | 'GROUPEMENT';
// FORFAIT = fixed price (extra days eat the margin); REGIE = client pays for days spent.
// The drift in days (see kpi.model.ts) is a loss under FORFAIT, extra revenue under REGIE.
export type EngagementType = 'FORFAIT' | 'REGIE';

// Status badge labels (French, matching company documents). Record<ProjectStatus, string>
// forces every status to have a label at compile time. Callers still add `?? s` as a fallback.
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'Actif',
  ON_HOLD: 'En pause',
  COMPLETED: 'Terminé',
  CANCELLED: 'Annulé'
};

/** One project as the server sends it (mirror of the Java record ProjectResponse). */
export interface Project {
  id: number;
  /** Short business reference (e.g. "S2I-2026-014"), unique, used to name the project. */
  code: string;
  name: string;
  description?: string;
  status: ProjectStatus;
  /** ISO date text ("2026-07-14"), not Date. Optional: a DRAFT project has no dates yet. */
  startDate?: string;
  endDate?: string;
  /** Budget written in the signed contract; never overwritten afterwards. */
  initialBudget?: number;
  /** Budget after signed amendments, absent until the first one is approved. Kept separate
   *  from initialBudget so a review can see both what was signed and what changed since. */
  revisedBudget?: number;
  /** The one to DISPLAY/compute with: revisedBudget if present, else initialBudget. Picked
   *  server-side so screens don't each re-implement that fallback differently. */
  effectiveBudget?: number;
  /** The Director in charge; name is sent alongside id so the list avoids a request per row. */
  directorId?: number;
  directorName?: string;
  /** The project manager. Absent means nobody's assigned — that's how the screen shows the
   *  "assign" button. */
  chefProjetId?: number;
  chefProjetName?: string;

  // Identification sheet: mirrors the company's Excel model field-for-field so it can be
  // checked line by line against the original document.
  /** The contract reference on the client side. */
  contractId?: string;
  /** Who the work is done for. */
  client?: string;
  /** Who pays, when it isn't the client (a development bank, a ministry) — kept separate
   *  since a funder has its own reporting rules. */
  funder?: string;
  businessModel?: BusinessModel;
  engagementType?: EngagementType;
  /** Contract currency, as a short code ("TND", "EUR", "XOF"). */
  currency?: string;
  /** Dinar value of one currency unit, fixed at contract signing (not looked up live, so the
   *  margin doesn't drift with the market). */
  exchangeRateToTnd?: number;
  /** Part of the budget going to bought licences/subcontractors, kept out of own-team output. */
  licenseSubcontractBudget?: number;
  /** Man-days SOLD to the client — the reference drift is measured against (see kpi.model.ts),
   *  never the internal plan, which can be revised anytime. */
  soldWorkloadDays?: number;
  /** Man-days reserved for the post-delivery warranty period; not available for the build. */
  warrantyWorkloadDays?: number;
  /** Late-delivery penalty provision, user-entered (not pprTnd below, which is server-computed). */
  penaltyProvision?: number;
  /** Committed margin at sale, FRACTION with 4 decimals (0.4412 = 44.12%); the KPI screen's
   *  comparison baseline. */
  margeNetteVendue?: number;

  /** true once archived: hidden from the normal list but never deleted (history feeds figures). */
  archived?: boolean;

  /** Row creation timestamp, ISO with time ("2026-07-14T08:24:06") — a LocalDateTime, not a date. */
  createdAt?: string;

  // Computed by the server on every read, never stored in a column (so they never go stale
  // after an amendment changes the budget or dates).
  /** Contract length in days, both ends counted (1st to 3rd = 3 days). Absent, not 0, when a
   *  date is missing. Survives withoutFinancials(): not financial info (BR-050). */
  durationDays?: number;
  /** effectiveBudget x exchangeRateToTnd — what the KPI engine compares costs against.
   *  Blanked for a user without VIEW_KPI. */
  budgetTnd?: number;
  /** PPR ("Provision Pour Risques"): 5% of budgetTnd, per the Excel sheet. Blanked for a
   *  user without VIEW_KPI. */
  pprTnd?: number;
}

/** Body sent when a project is created or updated (mirror of ProjectRequest). Deliberately
 *  smaller than Project: id, revisedBudget/effectiveBudget, archived, createdAt, the computed
 *  fields and every "...Name" are all server-derived, not browser-settable — otherwise anyone
 *  could raise a budget without a signed amendment behind it.
 *  chefProjetId, businessModel and engagementType accept "| null" explicitly because on save,
 *  omitting a field means "don't touch it" while null means "clear it" — without "| null" the
 *  compiler would block sending null, and a wrong value could never be cleared, only replaced. */
export interface ProjectRequest {
  /** Required. The server refuses a duplicate code and refuses an empty one. */
  code: string;
  name: string;
  description?: string;
  /** Required: a project always has a state, and a new one starts at DRAFT. */
  status: ProjectStatus;
  startDate?: string;
  endDate?: string;
  /** Only the INITIAL budget can be sent. The revised one is the server's business. */
  initialBudget?: number;
  directorId?: number;
  /** null clears the project manager - see the note on the interface above. */
  chefProjetId?: number | null;

  // Same identification-sheet block as on Project.
  contractId?: string;
  client?: string;
  funder?: string;
  /** null clears the value. */
  businessModel?: BusinessModel | null;
  /** null clears the value. */
  engagementType?: EngagementType | null;
  currency?: string;
  exchangeRateToTnd?: number;
  licenseSubcontractBudget?: number;
  soldWorkloadDays?: number;
  warrantyWorkloadDays?: number;
  penaltyProvision?: number;
  /** Still a FRACTION between -1 and 1 (0.4412 = 44.12%); the server rejects out-of-range
   *  values with a 400 instead of storing a bogus 4400% baseline. */
  margeNetteVendue?: number;
}
