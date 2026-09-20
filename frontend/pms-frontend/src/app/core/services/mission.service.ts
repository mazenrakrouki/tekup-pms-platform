import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Mission, Composante } from '../models/mission.model';
import { environment } from '../../../environments/environment';

// HTTP client for missions (a team member's trip for a project) and their cost components
// (transport, hotel, per diem...). No screen state, just the endpoints.
// Server-side: VIEW_MISSION / MANAGE_MISSION permissions, plus ADR-021 project-scope checks
// on every /api/projects/{id}/... URL below.
@Injectable({ providedIn: 'root' })
export class MissionService {
  constructor(private http: HttpClient) {}

  // Project id stays in the path (not query) so ProjectScopeInterceptor can read it for ADR-021.
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/missions`;
  }

  list(projectId: number): Observable<Mission[]> {
    return this.http.get<Mission[]>(this.base(projectId));
  }

  /**
   * dateDebut/dateFin are ISO day strings ("2026-03-31"), not Date objects — a Date would
   * serialize with a time zone and could shift the day by the time it reaches the server.
   */
  create(projectId: number, body: { userId: number; objet: string; lieu: string; dateDebut: string; dateFin: string }): Observable<Mission> {
    return this.http.post<Mission>(this.base(projectId), body);
  }

  delete(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${id}`);
  }

  // Composantes de coût: the cost lines making up a mission's expense.

  // Not embedded in the Mission response: a project can have many missions, each with many
  // lines, so they're fetched only when a mission's detail is actually opened.
  listComposantes(projectId: number, missionId: number): Observable<Composante[]> {
    return this.http.get<Composante[]>(`${this.base(projectId)}/${missionId}/composantes`);
  }

  // montant + devise travel together: an amount without its currency can't be converted later.
  createComposante(projectId: number, missionId: number, body: { typeComposante: string; montant: number; devise: string; description: string }): Observable<Composante> {
    return this.http.post<Composante>(`${this.base(projectId)}/${missionId}/composantes`, body);
  }

  // missionId stays in the path so the server can verify the line really belongs to that mission.
  deleteComposante(projectId: number, missionId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${missionId}/composantes/${id}`);
  }
}
