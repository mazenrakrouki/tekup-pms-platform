import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PlanCharge, ChargeReelle } from '../models/workload.model';
import { PagedResponse } from '../models/pagination.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the workload module. It covers the two sides of the same question, in
 * days per person and per month:
 *   - plan charges   : the days PLANNED for somebody on a project;
 *   - charges reelles: the days that person says he really SPENT, and their validation.
 * It draws no screen and keeps no state.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/workload/workload.component.ts (the workload screen) and
 *                features/projects/project-detail/project-detail.component.ts (the workload
 *                tab of one project).
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot WorkloadController -> PlanChargeService
 *                and ChargeReelleService, which carry the real checks and the rules.
 * The typed answers come from core/models/workload.model.ts and
 * core/models/pagination.model.ts.
 *
 * WHY IT EXISTS
 * Two screens need the same four calls, with the same paging and the same sort. Written by
 * hand in both, they would drift apart the day one of them changes.
 * These figures matter beyond the screen: the validated days are what the KPI uses to
 * compute the real cost of a project, so a service that quietly sent the wrong period would
 * move the margin of the whole project.
 *
 * SECURITY - THREE DIFFERENT PERMISSIONS, NOT TWO
 * Nothing in this file protects anything. On the server:
 *   reading (both lists)          VIEW_WORKLOAD;
 *   declaring one's own days      SUBMIT_WORKLOAD;
 *   planning days, and validating
 *   a declaration                 VALIDATE_WORKLOAD.
 * Why declaring and validating are two separate permissions: whoever declares the days must
 * not be the one who approves them. With a single "manage workload" permission, a developer
 * could approve his own declaration, and the real cost of the project would be whatever he
 * decided to type.
 * ADR-021 adds the scope on top: every URL below has the shape /api/projects/{id}/..., so
 * ProjectScopeInterceptor checks that this user may work on THAT project.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance for the whole application; the class holds
// no state, so a second instance would be pure waste.
@Injectable({ providedIn: 'root' })
export class WorkloadService {
  // Constructor injection of the shared HttpClient.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would leave
  // without the Authorization header and the server would answer 401 every time.
  constructor(private http: HttpClient) {}

  /**
   * Builds the common start of every URL of this service:
   * "http://localhost:8090/api" + "/projects/12".
   *
   * Why a private helper: the project id MUST stay inside the path, because
   * ProjectScopeInterceptor reads it from there to apply ADR-021. A URL built without it
   * would escape the scope check completely.
   */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  /**
   * Reads ONE page of the planned workload (GET .../plan-charges?page=&size=&sort=).
   * Gives back the Spring envelope: the rows in 'content', plus the counters for the pager.
   *
   * Why paging: one row per person and per month. A project running two years with six
   * people is already 144 rows, and it only grows. Sending everything would make the screen
   * slower every month for rows the user never scrolls to.
   */
  listPlanCharges(projectId: number, page = 0, size = 20): Observable<PagedResponse<PlanCharge>> {
    // HttpParams builds the query string and escapes the values. It is immutable: each
    // .set() returns a NEW object, which is why the calls are chained and the result kept.
    // Why not paste the values into the URL by hand: a value with a space or a comma would
    // break the address.
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    // 'period,desc' is the Spring format: field name, then direction. desc means the most
    // recent month first, because that is the one being worked on. The sort is done by the
    // database, not in the browser - sorting here would only sort the twenty rows of the
    // current page, so the oldest month could sit on page one.
    return this.http.get<PagedResponse<PlanCharge>>(`${this.base(projectId)}/plan-charges`, { params });
  }

  /**
   * Plans days for somebody on a month (POST .../plan-charges) and gives back the saved row.
   *
   * year and month travel as two numbers rather than one date. Why: a plan is about a whole
   * month, not a day. A real date would force the code to invent a day - the 1st? the last? -
   * and two screens would soon invent different ones for the same month.
   * plannedDays is a number of days, and it can hold a half day.
   *
   * This call needs VALIDATE_WORKLOAD on the server, not SUBMIT_WORKLOAD: planning the work
   * of somebody else is a manager action. Without that split, anybody able to declare his
   * own days could also decide how many he is supposed to spend.
   */
  createPlanCharge(projectId: number, body: { userId: number; year: number; month: number; plannedDays: number }): Observable<PlanCharge> {
    return this.http.post<PlanCharge>(`${this.base(projectId)}/plan-charges`, body);
  }

  /**
   * Reads ONE page of the declared workload (GET .../charges-reelles), most recent first,
   * same envelope and same reasons as the planned list above.
   */
  listChargesReelles(projectId: number, page = 0, size = 20): Observable<PagedResponse<ChargeReelle>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    return this.http.get<PagedResponse<ChargeReelle>>(`${this.base(projectId)}/charges-reelles`, { params });
  }

  /**
   * Declares the days really spent on a month (POST .../charges-reelles) and gives back the
   * saved row, which starts in the "waiting for validation" state.
   *
   * Same year/month pair as the plan, on purpose: it is what lets the screen put the planned
   * days and the declared days of the same month side by side. A different shape on the two
   * sides would make that comparison a conversion, and a conversion is where the off-by-one
   * month comes from.
   * Guarded by SUBMIT_WORKLOAD on the server. The row it creates is NOT counted in the cost
   * of the project until somebody validates it below.
   */
  submitCharge(projectId: number, body: { userId: number; year: number; month: number; actualDays: number }): Observable<ChargeReelle> {
    return this.http.post<ChargeReelle>(`${this.base(projectId)}/charges-reelles`, body);
  }

  /**
   * Approves a declaration (PATCH .../charges-reelles/{id}/validate) and gives back the
   * updated row.
   *
   * This is the call that makes the days count: the KPI adds up the VALIDATED days only, so
   * this click is what moves the real cost and the margin of the project.
   * Guarded by VALIDATE_WORKLOAD, which is why it is a different address from submitCharge
   * and not a field inside it: a status sent in the body would be a value the browser
   * chooses, so a modified request could declare and approve in one go.
   * The second argument {} is an empty body: PATCH needs one and the address already says
   * everything. Sending null instead would make Angular drop the Content-Type header and
   * some servers answer 415.
   */
  validateCharge(projectId: number, id: number): Observable<ChargeReelle> {
    return this.http.patch<ChargeReelle>(`${this.base(projectId)}/charges-reelles/${id}/validate`, {});
  }
}
