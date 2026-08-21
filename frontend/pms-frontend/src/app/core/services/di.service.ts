import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DevisInterneResponse, LigneDiRequest } from '../models/di.model';
import { environment } from '../../../environments/environment';

/** Devis Interne — accès restreint à MANAGE_DI (données sensibles, structure sans seed). */
@Injectable({ providedIn: 'root' })
export class DiService {
  constructor(private http: HttpClient) {}

  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}/devis-interne`;
  }

  get(projectId: number): Observable<DevisInterneResponse> {
    return this.http.get<DevisInterneResponse>(this.base(projectId));
  }

  addLigne(projectId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.post<DevisInterneResponse>(`${this.base(projectId)}/lignes`, req);
  }

  updateLigne(projectId: number, ligneId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.put<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`, req);
  }

  deleteLigne(projectId: number, ligneId: number): Observable<DevisInterneResponse> {
    return this.http.delete<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`);
  }
}
