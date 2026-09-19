/*
 * FILE: kpi.model.ts
 *
 * WHAT THIS FILE IS
 * The two shapes of the steering indicators of a project: KpiResponse, the block of
 * figures the server computes and sends, and SnapshotRequest, the small form the project
 * manager fills in during the monthly review.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot KpiService.buildKpi() computes everything from the plan, the validated
 *   timesheets, the deliverables, the billing milestones and the Devis Interne
 *     -> KpiController answers with the Java record KpiResponse
 *     -> core/services/project.service.ts declares getLiveKpi(), getSnapshots() and
 *        createSnapshot() with the shapes below
 *     -> features/kpi/kpi.component.ts (the steering dashboard) and
 *        features/projects/project-detail/project-detail.component.ts display them.
 *
 * LIVE FIGURES vs SNAPSHOT
 * The same shape serves both. getLiveKpi() gives today's figures, recomputed on the spot,
 * and then snapshotId and snapshotDate are absent. createSnapshot() freezes them into a
 * row, and the frozen copy comes back with those two fields filled. That is what makes the
 * history readable: a month later the live figures have moved on, but the snapshot still
 * says what was reported at the review.
 *
 * WHY THE BROWSER COMPUTES NOTHING
 * Every figure below is derived on the server. The screen only displays and colours them.
 * With a second copy of the formulas in TypeScript, the dashboard and the exported report
 * would disagree the day one of the two was corrected - on the numbers a director takes
 * decisions with.
 *
 * READ THIS BEFORE READING THE FIELDS: THE TWO SCALES
 * Some fields are on a 0-100 scale and some are a fraction between 0 and 1. It is not an
 * oversight, it follows the meaning of each figure, and each field below says which one it
 * uses. Mixing them up is the single easiest mistake to make here: showing
 * tauxConsommation without multiplying by 100 would display a project that has burnt 85%
 * of its budget as "0.85%".
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading the KPI
 * needs VIEW_KPI and creating a snapshot needs EDIT_PROJECT, checked with @PreAuthorize on
 * the SERVICE methods; and because the URLs look like /api/projects/{id}/kpi...,
 * ProjectScopeInterceptor also checks that this user may touch THAT project (ADR-021).
 */

/**
 * The whole indicator block of one project (mirror of the Java record KpiResponse).
 *
 * Many fields are optional because the server answers null rather than inventing a figure
 * it cannot compute - for example when the sold workload was never entered, or when the
 * monthly review has not been done yet. null means "no answer possible", which the screen
 * shows as a dash. Writing 0 instead would be a lie: a project with no deliverables listed
 * would be displayed as "0% delivered", in red, as if it were failing.
 */
export interface KpiResponse {
  /** Set only on a frozen snapshot row; absent on the live figures. */
  snapshotId?: number;
  projectId: number;
  /** The readable project code, so the dashboard can name the project without a second call. */
  projectCode: string;
  /** The day the snapshot was taken, as ISO text ("2026-07-14"). Absent on live figures. */
  snapshotDate?: string;
  /** Planned cost, in dinars: the workload plan priced with the daily rates. */
  budgetPlanifie: number;
  /** Cost really spent so far, in dinars: the VALIDATED timesheets priced the same way.
   *  Only validated ones count - a day someone typed in but nobody approved is not a cost. */
  budgetConsome: number;
  /** EAC, "estimate at completion": what the project will have cost in the end, in dinars,
   *  computed as what has been spent plus what the remaining plan will cost. */
  eac: number;
  /** Forecast margin, in dinars: contract budget in TND minus the EAC. Negative means the
   *  project is heading for a loss. It answers "where will I land". */
  marge: number;
  /** How much of the budget has been burnt, as a FRACTION between 0 and 1 (0.85 = 85%).
   *  The screen multiplies it by 100 to display it. */
  tauxConsommation: number;
  // ── EVM indicators (F-AFF-13 section 5) ──
  // EVM = Earned Value Management, the standard method for measuring whether a project is
  // getting done as fast as it is spending money.
  /** Progress declared BY THE PROJECT MANAGER at the snapshot, on a 0-100 scale (40 = 40%
   *  done). It is the only figure in this block that a human types in: no system can know
   *  how finished a piece of work really is. */
  evPct?: number;
  /** Deliverables handed over or accepted, divided by all the deliverables, ON A 0-100
   *  SCALE. It is the only progress figure the system can compute alone, and it sits next
   *  to evPct on purpose: when the two disagree badly (EV 80, delivery 20) the review has
   *  a question to ask. Absent when the project has no deliverable listed yet. */
  deliveryPct?: number;
  /** Man-days really spent: the sum of the VALIDATED timesheets (JH = jours-homme). */
  consommeJh?: number;
  /** RAF, "reste a faire": the man-days still planned ahead. */
  rafJh?: number;
  /** Drift in man-days: days SOLD minus consumed minus remaining. Negative is the alarm -
   *  the project needs more days than were sold, so the margin is being eaten.
   *  Measured against what was sold, never against the internal plan: the plan can be
   *  revised at any time, so measuring against it would make the drift disappear exactly
   *  at the moment it appears. Absent when the sold workload was never entered. */
  deriveJh?: number;
  /** Production revenue, in dinars: contract budget in TND x evPct / 100. In plain words,
   *  if the manager says the project is 40% done then 40% of what the client will pay has
   *  been EARNED, even if no invoice has been sent. Absent until an EV has been declared. */
  caProduction?: number;
  /** Sum of the milestones already invoiced or paid, converted into dinars. */
  totalFacture?: number;
  /** FAE, "facture a etablir": caProduction minus totalFacture. The revenue that is earned
   *  but not yet billed - a real accounting figure, not a display trick. */
  fae?: number;
  /** Current margin, in dinars: caProduction minus budgetConsome (what has really been
   *  spent). Note the difference with "marge" above, which uses the EAC forecast: this one
   *  answers "where do I stand today", the other "where will I land". */
  margeActuelle?: number;
  /** margeActuelle / caProduction, as a FRACTION between 0 and 1 with 4 decimals.
   *  Absent when nothing has been earned yet: the server refuses to divide by zero and
   *  sends null instead of a made-up 0. */
  margeActuellePct?: number;
  /** The baseline to compare margeActuellePct against: the margin the company committed to
   *  when it sold the project, also a FRACTION (0.4412 = 44.12%).
   *  Two sources, in order of trust: the computed Devis Interne when its lines exist, and
   *  otherwise the margin typed on the project identification sheet. */
  margeVenduePct?: number;
  /** The end date the manager expects, as ISO text. Typed in at the review, not computed. */
  dateFinEstimee?: string;
  /** The manager's free-text notes for the month ("client delayed the acceptance"). */
  faitsMarquants?: string;
  /**
   * Messages the server attaches to explain missing or doubtful figures, for example
   * "no EV declared, so production revenue, FAE and current margin are empty", or
   * "daily rate missing for X, cost counted as 0".
   * Why they travel with the data instead of being guessed by the screen: only the server
   * knows WHY a figure is missing. Without them the user sees three dashes in a row and
   * concludes the feature is broken, when the monthly review has simply not been done.
   */
  warnings?: string[];
}

/**
 * What the project manager types in at the monthly project review (mirror of the Java
 * record SnapshotRequest). POSTed to /api/projects/{id}/kpi/snapshots, which freezes
 * today's computed figures together with these three human inputs.
 *
 * Why only three fields: everything else in KpiResponse is derived from data the system
 * already holds. Letting the manager type a margin or a consumed amount would break the
 * whole point of the snapshot, which is to be a faithful photograph of the system on that
 * day, next to the one judgement the system cannot make - how finished the work is.
 *
 * All three are optional so a review can record a comment without an EV, or an EV without
 * a comment.
 */
export interface SnapshotRequest {
  /** Progress declared by the manager, on a 0-100 scale. The server refuses anything
   *  outside that range, so a mistyped 400 comes back as a clear 400 Bad Request instead
   *  of quadrupling the production revenue of the project. */
  evPct?: number;
  /** The end date now expected, as ISO text ("2026-12-31"). */
  dateFinEstimee?: string;
  /** Free-text notes for the month. The server limits it to 2000 characters. */
  faitsMarquants?: string;
}
