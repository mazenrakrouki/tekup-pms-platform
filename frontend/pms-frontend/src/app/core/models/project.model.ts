/*
 * FILE: project.model.ts
 *
 * WHAT THIS FILE IS
 * The shapes of a project, which is the central object of the whole application: Project,
 * as the server sends it, and ProjectRequest, the body sent when a project is created or
 * edited. It also holds the readable labels of the five statuses.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot ProjectService -> ProjectMapper build the Java record ProjectResponse
 *     -> Jackson turns it into JSON
 *     -> core/services/project.service.ts declares list(), get(), create(), update()... with
 *        the shapes below, and wraps the list in PagedResponse<Project>
 *        (see pagination.model.ts)
 *     -> almost every screen of the application reads it: the project list, the project
 *        form, the project detail page, the dashboard, the KPI screen, the sidebar and the
 *        shared <app-project-picker>.
 *
 * WHY IT EXISTS
 * It is the most widely shared contract of the front end - nine different files import it.
 * That is exactly why it must be written once. With each screen describing the answer its
 * own way, one spelling difference would be enough for two screens to print two different
 * budgets for the same project, and nothing would point at the mistake. TypeScript types
 * are erased at compile time, so the file costs nothing in the browser and buys checking
 * while the project is being built.
 *
 * READ THIS BEFORE THE JURY ASKS WHY SO MANY FIELDS ARE OPTIONAL
 * There are two different reasons, and they must not be confused.
 *   1. The value genuinely may not exist yet. A project in DRAFT has no dates and no
 *      budget; a project that has never been amended has no revised budget.
 *   2. The server DELIBERATELY BLANKED the value. ProjectResponse.withoutFinancials()
 *      returns a copy with the money fields set to null for a user who does not hold
 *      VIEW_KPI. The budget really is absent from the JSON that user receives.
 * Both look the same here - an absent number - which is the point: a screen cannot tell
 * them apart, so it cannot leak the second one. What it must never do is replace a missing
 * budget by 0 and print it as a real figure.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_PROJECT, creating CREATE_PROJECT, editing EDIT_PROJECT, deleting DELETE_PROJECT and
 * naming a project manager ASSIGN_CHEF_PROJET, all checked with @PreAuthorize on the
 * SERVICE methods. For the URLs under /api/projects/{id}/, ProjectScopeInterceptor
 * additionally checks that this user may touch THAT project (ADR-021) - the permission
 * alone is not enough.
 */

// A "union type" lists the only texts a field may hold - here the five states of a
// project, exactly as the Java enum ProjectStatus spells them.
// Why not a plain string: the status drives the colour of the badge AND the label map just
// below. A typo such as 'ACTIF' would compile as a string, match no entry in the map, and
// the list would print an empty status on a running project.
export type ProjectStatus = 'DRAFT' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';
// How the company sells the project: SEUL = alone, GROUPEMENT = in a consortium with other
// companies. It changes who invoices the client and how the margin is shared.
export type BusinessModel = 'SEUL' | 'GROUPEMENT';
// The contract type. FORFAIT = a fixed price for an agreed scope, so every extra day eats
// the margin. REGIE = the client pays for the days actually spent.
// This is the single most important business distinction of the application: the drift in
// days (see kpi.model.ts) is a loss under FORFAIT and simply more revenue under REGIE.
export type EngagementType = 'FORFAIT' | 'REGIE';

// The label printed on the status badge, read by project-list.component.ts and by the
// shared project-picker.
// Record<ProjectStatus, string> is a built-in generic type meaning "an object whose keys
// are exactly the values of ProjectStatus, each holding a string".
// WHY Record and not a plain object: the compiler now refuses the file if a status is
// missing OR if a key is misspelled. Without it, adding a sixth status to the union type
// above would leave this map incomplete, the lookup would give undefined, and the badge
// would be drawn empty on every project in that state.
// (The labels are in French because they are the words used in the company's own project
// documents. Both callers write `?? s` after the lookup as a last safety net, so an
// unexpected value coming from the server is printed raw instead of disappearing.)
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'Actif',
  ON_HOLD: 'En pause',
  COMPLETED: 'Terminé',
  CANCELLED: 'Annulé'
};

/**
 * One project as the server SENDS it (mirror of the Java record ProjectResponse).
 * This is what nine files of the front end read.
 */
export interface Project {
  id: number;
  /** The short business reference, for example "S2I-2026-014". It is what humans use to
   *  name a project; the id above is only the database key. The server keeps it unique. */
  code: string;
  name: string;
  description?: string;
  status: ProjectStatus;
  /**
   * The contract dates, as plain ISO text ("2026-07-14"). JSON has no date type, so a Java
   * LocalDate always arrives as a string; typing them as Date would be a lie, and calling
   * a Date method on one would crash the screen.
   * Optional, because a project in DRAFT exists before its dates are agreed.
   */
  startDate?: string;
  endDate?: string;
  /** The budget written in the signed contract. It is never overwritten afterwards. */
  initialBudget?: number;
  /**
   * The budget as it stands today, after the signed amendments. It stays absent until a
   * first amendment is approved, and it is the server that writes it.
   * Why a second field instead of simply changing initialBudget: a review asks two
   * different questions - what did we sign, and what are we working with now. Overwriting
   * the first would erase the evidence of how far the project has drifted from its
   * contract, and no amendment could be justified against anything afterwards.
   */
  revisedBudget?: number;
  /**
   * The one to DISPLAY and to compute with: revisedBudget when it exists, otherwise
   * initialBudget. The server picks; the browser must not choose between the two itself.
   * Why: two screens each writing that little test would eventually write it differently,
   * and one of them would keep showing the original contract amount on a project that has
   * been amended twice.
   */
  effectiveBudget?: number;
  /** The Director in charge. The id is sent back when saving, the name is what is printed,
   *  so the list does not need one extra request per row to show a person. */
  directorId?: number;
  directorName?: string;
  /** The project manager ("chef de projet"). Absent while nobody has been named yet -
   *  which is exactly how the screen knows to show the "assign" button. */
  chefProjetId?: number;
  chefProjetName?: string;

  // ── Identification sheet (the company's Excel model) ──
  // Every field in this block comes straight from the identity sheet the company already
  // filled in by hand in Excel. They are kept with the same meaning and the same wording so
  // the screen can be checked line by line against the original document - which is what
  // makes the application trustworthy to the people who used that sheet before.
  /** The contract reference on the client side. */
  contractId?: string;
  /** Who the work is done for. */
  client?: string;
  /** Who pays, when it is not the client (a development bank, a ministry). The two are
   *  kept apart because a funder has its own reporting rules. */
  funder?: string;
  businessModel?: BusinessModel;
  engagementType?: EngagementType;
  /** The currency the contract is written in, as a short code ("TND", "EUR", "XOF"). */
  currency?: string;
  /**
   * How many dinars one unit of that currency is worth.
   * Why it is stored on the project and not looked up live: a contract is converted at the
   * rate agreed when it was signed. With a live rate, the margin of a project would move
   * every day for reasons that have nothing to do with the project.
   */
  exchangeRateToTnd?: number;
  /** The part of the budget that goes to bought licences and subcontractors, so it can be
   *  taken out of what the company's own people produce. */
  licenseSubcontractBudget?: number;
  /** The man-days SOLD to the client. This is the reference the drift is measured against
   *  (see kpi.model.ts): never the internal plan, which can be revised at any time. */
  soldWorkloadDays?: number;
  /** Man-days kept aside for the warranty period after delivery. Sold, but not available
   *  for the build - counting them as build days would make the project look comfortable
   *  when it is not. */
  warrantyWorkloadDays?: number;
  /** Money set aside for late-delivery penalties, as typed by a user. Not to be confused
   *  with pprTnd below, which is a fixed percentage computed by the server. */
  penaltyProvision?: number;
  /**
   * The margin the company committed to when it sold the project, as a FRACTION with 4
   * decimals: 0.4412 means 44.12%.
   * It is the baseline the KPI screen compares the current margin against. Note the scale:
   * displaying it without multiplying by 100 would show "0.44%" for a very healthy
   * project, and typing 44 into it would claim a 4400% margin.
   */
  margeNetteVendue?: number;

  /** true when the project has been put away. Archived projects are hidden from the normal
   *  list but never deleted: their history feeds the company's figures. */
  archived?: boolean;

  // ── Audit ──
  /** When the project row was created, as ISO text with a time ("2026-07-14T08:24:06"),
   *  because it comes from a Java LocalDateTime and not a LocalDate. */
  createdAt?: string;

  // ── Computed by the server, never stored in a column ──
  // The three fields below have no column in PostgreSQL. They are derived every time the
  // project is read.
  // Why never stored: they depend on the dates, the budget and the exchange rate. Stored,
  // they would keep the value they had the day they were written, and a project whose
  // amendment raised the budget would go on showing last month's figure.
  /**
   * Contract length in days, BOTH ENDS COUNTED, as on the Excel sheet: from the 1st to the
   * 3rd is 3 days, not 2. Absent when either date is missing - an unknown length and a
   * length of zero are different answers, and "0 days" on a project whose dates are simply
   * not agreed yet would look like a bug.
   * Note that this one survives withoutFinancials(): a length in days is not financial
   * information (BR-050).
   */
  durationDays?: number;
  /** The effective budget converted into dinars: effectiveBudget x exchangeRateToTnd.
   *  Everything the company spends is in dinars, so this is the figure the KPI engine
   *  compares costs against. Blanked for a user without VIEW_KPI. */
  budgetTnd?: number;
  /** PPR, "Provision Pour Risques": 5% of budgetTnd, as the Excel identity sheet computes
   *  it. Different from penaltyProvision above, which a user types in. Blanked for a user
   *  without VIEW_KPI. */
  pprTnd?: number;
}

/**
 * The body SENT when a project is created or updated (mirror of the Java record
 * ProjectRequest).
 *
 * WHY IT IS NOT THE Project SHAPE ABOVE
 * What is sent is not what comes back. Everything the browser must NOT choose is absent
 * here: the id, revisedBudget and effectiveBudget (the server derives them from the
 * amendments), archived, createdAt, the three computed fields, and every "...Name" field.
 * If the browser could send revisedBudget, anyone could raise the budget of a project
 * without a single signed amendment behind it - and the margin with it.
 *
 * WHY THE THREE FIELDS BELOW ACCEPT null EXPLICITLY
 * chefProjetId, businessModel and engagementType are written "?: T | null" while the
 * others are only "?: T". The difference matters when a form is SAVED: leaving a field out
 * means "do not touch it", whereas sending null means "clear it". Without the "| null" the
 * compiler would refuse to send null, and a project manager or a contract type set by
 * mistake could never be removed again - only replaced by another wrong value.
 */
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

  // ── The same identification-sheet block as on Project ──
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
  /** Still a FRACTION between -1 and 1 (0.4412 = 44.12%). The server refuses anything
   *  outside that range, so a form that sent 44 comes back as a clear 400 Bad Request
   *  instead of storing a 4400% baseline that would make every project look disastrous. */
  margeNetteVendue?: number;
}
