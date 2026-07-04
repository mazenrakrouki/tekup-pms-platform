import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PlanCharge, ChargeReelle } from '../models/workload.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class WorkloadService {
  constructor(private http: HttpClient) {}

  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  listPlanCharges(projectId: number): Observable<PlanCharge[]> {
    return this.http.get<PlanCharge[]>(`${this.base(projectId)}/plan-charges`);
  }

  createPlanCharge(projectId: number, body: { userId: number; year: number; month: number; plannedDays: number }): Observable<PlanCharge> {
    return this.http.post<PlanCharge>(`${this.base(projectId)}/plan-charges`, body);
  }

  listChargesReelles(projectId: number): Observable<ChargeReelle[]> {
    return this.http.get<ChargeReelle[]>(`${this.base(projectId)}/charges-reelles`);
  }

  submitCharge(projectId: number, body: { userId: number; year: number; month: number; actualDays: number }): Observable<ChargeReelle> {
    return this.http.post<ChargeReelle>(`${this.base(projectId)}/charges-reelles`, body);
  }

  validateCharge(projectId: number, id: number): Observable<ChargeReelle> {
    return this.http.patch<ChargeReelle>(`${this.base(projectId)}/charges-reelles/${id}/validate`, {});
  }
}
