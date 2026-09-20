// Steering-indicator shapes mirroring the server: KpiResponse (computed figures) and
// SnapshotRequest (the monthly-review form). getLiveKpi() leaves snapshotId/snapshotDate
// absent; createSnapshot() freezes the figures with those two set. Fields mix a 0-100 scale
// and a 0-1 fraction (each field says which) — check before displaying one raw.

/** Mirror of the Java record KpiResponse. Optional fields are null (not 0) when the server
 *  can't compute them, so the UI can show a dash instead of a misleading zero. */
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
  /** Cost really spent so far, in dinars, from VALIDATED timesheets only. */
  budgetConsome: number;
  /** EAC ("estimate at completion"): spent so far plus what the remaining plan will cost. */
  eac: number;
  /** Forecast margin in dinars (contract budget minus EAC); negative means a projected loss. */
  marge: number;
  /** Budget burnt, as a FRACTION between 0 and 1 (0.85 = 85%); the screen multiplies by 100. */
  tauxConsommation: number;
  // EVM (Earned Value Management) indicators — F-AFF-13 section 5.
  /** Progress declared BY THE PROJECT MANAGER, 0-100 scale; the only figure a human types in. */
  evPct?: number;
  /** Deliverables done / total, 0-100 scale. Compared against evPct to flag a mismatch
   *  (e.g. EV 80, delivery 20). Absent when the project has no deliverable listed yet. */
  deliveryPct?: number;
  /** Man-days really spent: the sum of the VALIDATED timesheets (JH = jours-homme). */
  consommeJh?: number;
  /** RAF, "reste a faire": the man-days still planned ahead. */
  rafJh?: number;
  /** Drift in man-days (sold minus consumed minus remaining); negative means the project
   *  needs more days than sold. Measured against sold days, not the revisable internal plan. */
  deriveJh?: number;
  /** Production revenue in dinars (contract budget x evPct / 100): earned, even if unbilled.
   *  Absent until an EV has been declared. */
  caProduction?: number;
  /** Sum of the milestones already invoiced or paid, converted into dinars. */
  totalFacture?: number;
  /** FAE ("facture a etablir"): caProduction minus totalFacture — earned but not yet billed. */
  fae?: number;
  /** Current margin in dinars: caProduction minus budgetConsome (vs "marge" above, which
   *  uses the EAC forecast — this is "where I stand today", not "where I'll land"). */
  margeActuelle?: number;
  /** margeActuelle / caProduction, FRACTION 0-1 with 4 decimals; absent rather than a
   *  made-up 0 when nothing has been earned yet. */
  margeActuellePct?: number;
  /** Committed margin baseline to compare margeActuellePct against, as a FRACTION
   *  (0.4412 = 44.12%): from the computed Devis Interne if it has lines, else the
   *  project identification sheet. */
  margeVenduePct?: number;
  /** The end date the manager expects, as ISO text. Typed in at the review, not computed. */
  dateFinEstimee?: string;
  /** The manager's free-text notes for the month ("client delayed the acceptance"). */
  faitsMarquants?: string;
  /** Server-supplied reasons for missing/doubtful figures (e.g. "no EV declared"), so the
   *  UI doesn't read blank fields as a bug. */
  warnings?: string[];
}

/** What the PM types in at the monthly review (mirror of the Java record SnapshotRequest),
 *  POSTed to freeze today's figures. All three fields optional so a review can record a
 *  comment without an EV, or vice versa. */
export interface SnapshotRequest {
  /** Progress, 0-100 scale; the server rejects out-of-range values with a 400. */
  evPct?: number;
  /** The end date now expected, as ISO text ("2026-12-31"). */
  dateFinEstimee?: string;
  /** Free-text notes for the month. The server limits it to 2000 characters. */
  faitsMarquants?: string;
}
