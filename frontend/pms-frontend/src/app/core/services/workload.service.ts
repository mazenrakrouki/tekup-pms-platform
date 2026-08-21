import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PlanCharge, ChargeReelle } from '../models/workload.model';
import { PagedResponse } from '../models/pagination.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class WorkloadService {
  constructor(private http: HttpClient) {}

  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  listPlanCharges(projectId: number, page = 0, size = 20): Observable<PagedResponse<PlanCharge>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    return this.http.get<PagedResponse<PlanCharge>>(`${this.base(projectId)}/plan-charges`, { params });
  }

  createPlanCharge(projectId: number, body: { userId: number; year: number; month: number; plannedDays: number }): Observable<PlanCharge> {
    return this.http.post<PlanCharge>(`${this.base(projectId)}/plan-charges`, body);
  }

  listChargesReelles(projectId: number, page = 0, size = 20): Observable<PagedResponse<ChargeReelle>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', 'period,desc');
    return this.http.get<PagedResponse<ChargeReelle>>(`${this.base(projectId)}/charges-reelles`, { params });
  }

  submitCharge(projectId: number, body: { userId: number; year: number; month: number; actualDays: number }): Observable<ChargeReelle> {
    return this.http.post<ChargeReelle>(`${this.base(projectId)}/charges-reelles`, body);
  }

  validateCharge(projectId: number, id: number): Observable<ChargeReelle> {
    return this.http.patch<ChargeReelle>(`${this.base(projectId)}/charges-reelles/${id}/validate`, {});
  }
}
