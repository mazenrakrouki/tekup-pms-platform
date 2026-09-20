// PlanCharge (days PLANNED for one person/project/month) and ChargeReelle (days actually
// declared). Kept as two shapes because they have different lives: the plan is written ahead
// and revisable, the actual is declared afterward and needs separate approval. Downstream,
// these feed the KPI engine — plan gives planned cost, VALIDATED actuals give consumed cost.

/** The days PLANNED for one person, on one project, in one month (mirror of PlanChargeResponse).
 *  Carries both id and name for project/user so the table avoids a request per row. */
export interface PlanCharge {
  id: number;
  projectId: number;
  projectCode: string;
  userId: number;
  userFullName: string;
  /** year + month (not a date): a workload line covers a whole month, and month is 1-12
   *  (a Java Integer) — NOT 0-11, so don't feed it straight into `new Date(year, month, 1)`. */
  year: number;
  month: number;
  /** Days planned that month (JH = jours-homme). Decimal: 12.5 is a valid partial staffing. */
  plannedDays: number;
}

/** The days one person says he REALLY worked, on one project, in one month (mirror of
 *  ChargeReelleResponse). Life of a row: declared -> submittedAt filled; approved ->
 *  validatedAt/validatedById/validatedByName filled. Only a validated row counts as real
 *  cost in the KPI engine — otherwise anyone could inflate consumed cost unchecked. */
export interface ChargeReelle {
  id: number;
  projectId: number;
  projectCode: string;
  /** The person who did the work, NOT the person who approved it - see validatedById. */
  userId: number;
  userFullName: string;
  /** Same year + month pair as PlanCharge above, with month from 1 to 12. */
  year: number;
  month: number;
  /** Days really worked that month, as a decimal. Compared against plannedDays of the same
   *  person and month, this is what shows whether the project is drifting. */
  actualDays: number;
  /** ISO datetime ("2026-07-14T08:24:06"); filled once the row has been submitted. */
  submittedAt?: string;
  /** Missing means not approved yet — the field the whole cost calculation and the screen's
   *  pending/approve state depend on. Kept as a date (not a bool) so an audit can say when. */
  validatedAt?: string;
  /** Who approved — deliberately a separate field from userId, so an audit can tell declarer
   *  and approver apart. Set by the server from the token, never chosen by the browser.
   *  Self-approval is prevented by permissions (SUBMIT_WORKLOAD vs VALIDATE_WORKLOAD), not
   *  by comparing these ids. Missing while the row is still pending. */
  validatedById?: number;
  validatedByName?: string;
}
