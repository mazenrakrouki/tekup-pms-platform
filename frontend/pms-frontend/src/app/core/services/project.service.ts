import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Project, ProjectRequest } from '../models/project.model';
import { PagedResponse } from '../models/pagination.model';
import { KpiResponse, SnapshotRequest } from '../models/kpi.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly API = `${environment.apiUrl}/projects`;

  constructor(private http: HttpClient) {}

  list(page = 0, size = 20, sort = 'code,asc'): Observable<PagedResponse<Project>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', sort);
    return this.http.get<PagedResponse<Project>>(this.API, { params });
  }

  /** Fetches all active projects (large page) for selectors/dropdowns. */
  listAll(): Observable<Project[]> {
    const params = new HttpParams().set('page', 0).set('size', 1000).set('sort', 'code,asc');
    return this.http.get<PagedResponse<Project>>(this.API, { params }).pipe(map(p => p.content));
  }

  listArchived(): Observable<Project[]> {
    return this.http.get<Project[]>(`${this.API}/archived`);
  }

  archive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/archive`, {});
  }

  unarchive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/unarchive`, {});
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.API}/${id}`);
  }

  create(req: ProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.API, req);
  }

  update(id: number, req: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.API}/${id}`, req);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.API}/${id}`);
  }

  getLiveKpi(id: number): Observable<KpiResponse> {
    return this.http.get<KpiResponse>(`${this.API}/${id}/kpi`);
  }

  getSnapshots(id: number): Observable<KpiResponse[]> {
    return this.http.get<KpiResponse[]>(`${this.API}/${id}/kpi/snapshots`);
  }

  /** Revue mensuelle : fige les KPI du jour avec l'EV % saisi par le CdP. */
  createSnapshot(id: number, req: SnapshotRequest): Observable<KpiResponse> {
    return this.http.post<KpiResponse>(`${this.API}/${id}/kpi/snapshots`, req);
  }

  assignChef(id: number, userId: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/assign-chef?userId=${userId}`, {});
  }

  /** Utilisateurs affectables (chef/équipe) — accessible sans MANAGE_USERS. */
  listAssignableUsers(): Observable<{ id: number; firstName: string; lastName: string; roleName: string }[]> {
    return this.http.get<{ id: number; firstName: string; lastName: string; roleName: string }[]>(`${environment.apiUrl}/users/assignable`);
  }

  changeStatus(id: number, status: string): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/status?status=${status}`, {});
  }
}
