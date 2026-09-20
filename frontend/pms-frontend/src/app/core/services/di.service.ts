import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DevisInterneResponse, LigneDiRequest } from '../models/di.model';
import { environment } from '../../../environments/environment';

// HTTP client for the Devis Interne (internal quote) — the four endpoints for reading and
// editing lines. Holds no state; the project id always stays in the URL path, which is what
// ProjectScopeInterceptor relies on for ADR-021.
//
// The DI is the most sensitive screen in the app: it holds internal cost prices and margin.
// The server enforces MANAGE_DI (VIEW_PROJECT/VIEW_KPI are not enough) plus the project-scope
// check on every call — this file protects nothing, it just shapes the requests. Computed
// amounts (montantTnd, prixRevient, coutFinal, margeNette, margePct) only ever come back in
// answers, never sent by the browser, so a client can't push its own margin figure.
//
// Migrations create the DI's table STRUCTURE only; no cost data is ever seeded.
@Injectable({ providedIn: 'root' })
export class DiService {
  constructor(private http: HttpClient) {}

  /** Common URL prefix; keeps projectId in the path where ProjectScopeInterceptor needs it. */
  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}/devis-interne`;
  }

  /** Reads the whole quote (lines + totals), not line-by-line: AUTRES_FRAIS lines are
   *  percentages of the total sold, so a single line means nothing without the totals. */
  get(projectId: number): Observable<DevisInterneResponse> {
    return this.http.get<DevisInterneResponse>(this.base(projectId));
  }

  /** Adds a line and returns the WHOLE recomputed quote: one new line can shift the total
   *  sold and every percentage-based line with it, so a partial answer would go stale at once. */
  addLigne(projectId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.post<DevisInterneResponse>(`${this.base(projectId)}/lignes`, req);
  }

  /** PUT, not PATCH: LigneDiRequest is the complete row. Server also verifies the line
   *  belongs to this project, so a borrowed id 404s instead of silently cross-editing. */
  updateLigne(projectId: number, ligneId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.put<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`, req);
  }

  /** Returns the recomputed quote (not void, unlike most deletes here) since removing a
   *  line changes every total. Soft delete server-side, to keep the commercial history. */
  deleteLigne(projectId: number, ligneId: number): Observable<DevisInterneResponse> {
    return this.http.delete<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`);
  }
}
