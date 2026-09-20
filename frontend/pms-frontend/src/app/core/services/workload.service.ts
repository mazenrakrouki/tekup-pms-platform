import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PlanCharge, ChargeReelle } from '../models/workload.model';
import { PagedResponse } from '../models/pagination.model';
import { environment } from '../../../environments/environment';

// HTTP client for the workload module: plan charges (days PLANNED per person/month on a
// project) and charges reelles (days actually SPENT, plus their validation). No screen, no state.
// Server-side permissions are split three ways: VIEW_WORKLOAD (read), SUBMIT_WORKLOAD (declare
// own days), VALIDATE_WORKLOAD (plan days, approve a declaration) — so nobody can approve their
// own declared hours. ADR-021 scopes every /api/projects/{id}/... URL below.
@Injectable({ providedIn: 'root' })
export class WorkloadService {
  constructor(private http: HttpClient) {}

  // Project id stays in the path (not query) so ProjectScopeInterceptor can read it for ADR-021.
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  // Paged: one row per person per month, so a long project accumulates fast.
  listPlanCharges(projectId: number, page = 0, size = 20): Observable<PagedResponse<PlanCharge>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    return this.http.get<PagedResponse<PlanCharge>>(`${this.base(projectId)}/plan-charges`, { params });
  }

  /**
   * year/month as two numbers, not a Date, since a plan covers a whole month with no
   * particular day. Needs VALIDATE_WORKLOAD (not SUBMIT_WORKLOAD): planning someone else's
   * work is a manager action, kept separate from declaring one's own days.
   */
  createPlanCharge(projectId: number, body: { userId: number; year: number; month: number; plannedDays: number }): Observable<PlanCharge> {
    return this.http.post<PlanCharge>(`${this.base(projectId)}/plan-charges`, body);
  }

  listChargesReelles(projectId: number, page = 0, size = 20): Observable<PagedResponse<ChargeReelle>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    return this.http.get<PagedResponse<ChargeReelle>>(`${this.base(projectId)}/charges-reelles`, { params });
  }

  // Same year/month shape as the plan so the screen can compare planned vs. declared for a
  // month without converting. Guarded by SUBMIT_WORKLOAD; not counted in project cost until validated.
  submitCharge(projectId: number, body: { userId: number; year: number; month: number; actualDays: number }): Observable<ChargeReelle> {
    return this.http.post<ChargeReelle>(`${this.base(projectId)}/charges-reelles`, body);
  }

  // The KPI sums VALIDATED days only, so this call is what actually moves a project's cost
  // and margin. Separate endpoint (not a status field) guarded by VALIDATE_WORKLOAD, so a
  // modified submit request can't self-approve.
  validateCharge(projectId: number, id: number): Observable<ChargeReelle> {
    return this.http.patch<ChargeReelle>(`${this.base(projectId)}/charges-reelles/${id}/validate`, {});
  }
}
