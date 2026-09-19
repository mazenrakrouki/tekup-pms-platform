/*
 * FILE: workload.model.ts
 *
 * WHAT THIS FILE IS
 * The two shapes of the workload module on the browser side: PlanCharge, the days PLANNED
 * for one person on one project in one month, and ChargeReelle, the days that person says
 * he really worked in that month.
 *
 * WHERE IT SITS IN THE FLOW
 *   Spring Boot PlanChargeService / ChargeReelleService answer with the Java records
 *   PlanChargeResponse and ChargeReelleResponse, inside a Spring Data page
 *     -> Jackson turns them into JSON
 *     -> core/services/workload.service.ts wraps them in PagedResponse<T>
 *        (see pagination.model.ts) for listPlanCharges() and listChargesReelles()
 *     -> features/workload/workload.component.ts (the workload screen) and
 *        features/projects/project-detail/project-detail.component.ts display them.
 *   Further downstream, these same rows feed the KPI engine: the plan gives the planned
 *   cost, and the VALIDATED actuals give the consumed cost (see kpi.model.ts).
 *
 * WHY IT EXISTS
 * It is the written contract between the Angular screens and the Java API. Without it the
 * screens would type these rows as 'any', and 'any' switches the compiler off: reading
 * 'c.days' instead of 'c.actualDays' would compile, come back undefined, and a month would
 * be shown as empty although the person did work it - on the figures the project cost is
 * computed from.
 *
 * WHY PLANNED AND ACTUAL ARE TWO SEPARATE SHAPES AND NOT ONE ROW WITH TWO COLUMNS
 * They do not have the same life. The plan is written ahead by the project manager and can
 * be revised at any time; the actual is declared afterwards by the person who did the work
 * and then has to be approved by somebody else. They are also written by different people,
 * with different permissions. Put in one row, one save would overwrite the other.
 *
 * SECURITY REMINDER
 * Nothing here protects anything; it only describes data. On the server, reading needs
 * VIEW_WORKLOAD, declaring your own days needs SUBMIT_WORKLOAD, and approving somebody
 * else's needs VALIDATE_WORKLOAD - three different permissions, checked with @PreAuthorize
 * on the SERVICE methods. Because the URLs look like /api/projects/{id}/plan-charges and
 * /charges-reelles, ProjectScopeInterceptor also checks that this user may touch THAT
 * project (ADR-021).
 */

/**
 * The days PLANNED for one person, on one project, in one month (mirror of the Java record
 * PlanChargeResponse).
 *
 * Note the pairs projectId / projectCode and userId / userFullName. The id is what
 * identifies the row when the screen saves; the name is what it prints. With the ids
 * alone, the table would need one extra request per line just to show a name.
 */
export interface PlanCharge {
  id: number;
  projectId: number;
  projectCode: string;
  userId: number;
  userFullName: string;
  /**
   * The month is stored as two plain numbers, year + month (2026 and 7 for July 2026),
   * and NOT as a date.
   * Why: a workload line is about a whole month, not about a day. With a date, every
   * screen would have to agree to put the same day in it - some the 1st, some the last -
   * and two lines meant for the same month would look different and be counted twice.
   * month goes from 1 to 12, NOT from 0 to 11: it comes from a Java Integer, not from the
   * JavaScript Date object where January is 0. Feeding this value straight into
   * 'new Date(year, month, 1)' would shift every line by one month.
   */
  year: number;
  month: number;
  /**
   * Days planned that month (JH = jours-homme, man-days). A decimal, so 12.5 is possible:
   * people are rarely staffed on one single project full time.
   */
  plannedDays: number;
}

/**
 * The days one person says he REALLY worked, on one project, in one month (mirror of the
 * Java record ChargeReelleResponse).
 *
 * THE LIFE OF ONE ROW - WORTH KNOWING BEFORE READING THE FIELDS
 *   1. the person declares his days   -> submittedAt is filled
 *   2. somebody else approves them    -> validatedAt, validatedById and validatedByName
 *                                        are filled
 * Only a VALIDATED row is counted as a real cost by the KPI engine (the query behind it
 * keeps the rows where validatedAt is not null). Days that were typed in but never
 * approved are shown on the screen and cost nothing.
 * Why that rule: without it, anybody could raise the consumed cost - and so lower the
 * margin - of a project simply by declaring days nobody ever checked.
 */
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
  /**
   * When the person declared the days. A date AND a time, as ISO text
   * ("2026-07-14T08:24:06"), because it comes from a Java LocalDateTime.
   * Optional in the type because it is only filled once the row has been submitted.
   */
  submittedAt?: string;
  /**
   * When the days were approved. MISSING means "not approved yet", and that is the single
   * field the whole cost calculation depends on. It is also how the screen colours a row
   * as pending and shows the approve button.
   * Replacing this null with a boolean flag would lose the date, and an audit could no
   * longer say when a cost was accepted.
   */
  validatedAt?: string;
  /**
   * Who approved. It is a DIFFERENT field from userId above, and that is the point: the
   * row keeps both "who declared" and "who approved", so an audit can read the two apart.
   * With one single "user" field, nobody could tell them apart afterwards.
   * The value is never chosen by the browser: the server takes the logged-in caller from
   * the token. Otherwise a screen could name somebody else as the approver.
   * What keeps a developer from approving his own days is the permission, not a comparison
   * of these two ids: declaring needs SUBMIT_WORKLOAD and approving needs
   * VALIDATE_WORKLOAD, and in the default matrix a developer holds only the first.
   * Missing while the row is still pending.
   */
  validatedById?: number;
  validatedByName?: string;
}
