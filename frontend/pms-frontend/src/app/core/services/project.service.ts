import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Project, ProjectRequest } from '../models/project.model';
import { PagedResponse } from '../models/pagination.model';
import { KpiResponse, SnapshotRequest } from '../models/kpi.model';
import { environment } from '../../../environments/environment';

// HTTP client for the projects module — the busiest service in the front end: the project
// list, single-project CRUD, archive/status moves, KPI and monthly snapshots. No screen, no state.
// Server-side permissions: VIEW_PROJECT/VIEW_KPI (reads), CREATE_PROJECT, EDIT_PROJECT
// (update/archive/unarchive/changeStatus/createSnapshot), DELETE_PROJECT, ASSIGN_CHEF_PROJET.
// ADR-021 also scopes every /api/projects/{id}/... URL to projects the caller may access;
// the list endpoints have no id to scope, so the server filters those itself.
@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly API = `${environment.apiUrl}/projects`;

  constructor(private http: HttpClient) {}

  // Paged read (GET /api/projects?page=&size=&sort=); defaults return the first page.
  list(page = 0, size = 20, sort = 'code,asc'): Observable<PagedResponse<Project>> {
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', sort);
    return this.http.get<PagedResponse<Project>>(this.API, { params });
  }

  /**
   * All active projects in one call, for pickers/selectors (size=1000, not real paging —
   * a dropdown can't page). Use the shared <app-project-picker> to search this, not a raw dropdown.
   */
  listAll(): Observable<Project[]> {
    const params = new HttpParams().set('page', 0).set('size', 1000).set('sort', 'code,asc');
    // Unwraps the Spring page envelope to a plain array so callers don't each have to know about `content`.
    return this.http.get<PagedResponse<Project>>(this.API, { params }).pipe(map(p => p.content));
  }

  // Separate endpoint (not a filter on list()) so archived projects can never leak into the
  // normal list by a forgotten default.
  listArchived(): Observable<Project[]> {
    return this.http.get<Project[]>(`${this.API}/archived`);
  }

  // Archiving keeps all data/KPI history (unlike delete()); it just leaves the active lists.
  archive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/archive`, {});
  }

  unarchive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/unarchive`, {});
  }

  // 404 (unknown id) vs 403 (out of ADR-021 scope) are distinct so the screen can tell them apart.
  get(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.API}/${id}`);
  }

  create(req: ProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.API, req);
  }

  // PUT, not PATCH: ProjectRequest is the full sheet, so a partial body would erase omitted fields.
  update(id: number, req: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.API}/${id}`, req);
  }

  // Rarely used — archive() is the normal way to close a project since it keeps the data.
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.API}/${id}`);
  }

  // Live figures computed on demand from workload/billing/DI — never read from a stored column,
  // so two screens can't disagree after someone forgets to recompute a cached value.
  getLiveKpi(id: number): Observable<KpiResponse> {
    return this.http.get<KpiResponse>(`${this.API}/${id}/kpi`);
  }

  // Frozen KPI from each monthly review, oldest to newest — the only way to see a trend,
  // since the live KPI above always reflects today.
  getSnapshots(id: number): Observable<KpiResponse[]> {
    return this.http.get<KpiResponse[]>(`${this.API}/${id}/kpi/snapshots`);
  }

  /**
   * Monthly review: freezes today's KPI with the EV % (F-AFF-13). Only evPct comes from the
   * browser — EV is a human judgement call, the money figures are always server-computed.
   */
  createSnapshot(id: number, req: SnapshotRequest): Observable<KpiResponse> {
    return this.http.post<KpiResponse>(`${this.API}/${id}/kpi/snapshots`, req);
  }

  // Needs ASSIGN_CHEF_PROJET, not EDIT_PROJECT — picking a manager is a separate decision
  // from day-to-day project edits.
  assignChef(id: number, userId: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/assign-chef?userId=${userId}`, {});
  }

  // /api/users, not /api/projects — lives here only because project screens are the callers.
  // Inline response shape keeps these dropdowns from pulling a colleague's email/account state.
  listAssignableUsers(): Observable<{ id: number; firstName: string; lastName: string; roleName: string }[]> {
    return this.http.get<{ id: number; firstName: string; lastName: string; roleName: string }[]>(`${environment.apiUrl}/users/assignable`);
  }

  // Dedicated endpoint, not routed through update(): valid state transitions are a server-side
  // rule, and going through the full update could overwrite a colleague's concurrent edit.
  changeStatus(id: number, status: string): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/status?status=${status}`, {});
  }
}
