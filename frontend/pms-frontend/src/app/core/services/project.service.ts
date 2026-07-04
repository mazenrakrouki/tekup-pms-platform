import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Project, ProjectRequest } from '../models/project.model';
import { KpiResponse } from '../models/kpi.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly API = `${environment.apiUrl}/projects`;

  constructor(private http: HttpClient) {}

  list(): Observable<Project[]> {
    return this.http.get<Project[]>(this.API);
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
