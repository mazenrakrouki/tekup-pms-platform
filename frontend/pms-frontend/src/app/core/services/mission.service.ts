import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Mission, Composante } from '../models/mission.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class MissionService {
  constructor(private http: HttpClient) {}

  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/missions`;
  }

  list(projectId: number): Observable<Mission[]> {
    return this.http.get<Mission[]>(this.base(projectId));
  }

  create(projectId: number, body: { userId: number; objet: string; lieu: string; dateDebut: string; dateFin: string }): Observable<Mission> {
    return this.http.post<Mission>(this.base(projectId), body);
  }

  delete(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${id}`);
  }

  // ── Composantes de coût ──────────────────────────────────────────
  listComposantes(projectId: number, missionId: number): Observable<Composante[]> {
    return this.http.get<Composante[]>(`${this.base(projectId)}/${missionId}/composantes`);
  }

  createComposante(projectId: number, missionId: number, body: { typeComposante: string; montant: number; devise: string; description: string }): Observable<Composante> {
    return this.http.post<Composante>(`${this.base(projectId)}/${missionId}/composantes`, body);
  }

  deleteComposante(projectId: number, missionId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${missionId}/composantes/${id}`);
  }
}
