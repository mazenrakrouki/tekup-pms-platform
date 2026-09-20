import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Risk, Livrable, DemandeChangement } from '../models/governance.model';
import { PartiePrenante } from '../models/partie-prenante.model';
import { environment } from '../../../environments/environment';

// HTTP client for the governance module: risks, deliverables (livrables), change requests
// (demandes de changement) and stakeholders (parties prenantes) — four families sharing one
// base URL and the same VIEW_GOVERNANCE/MANAGE_GOVERNANCE permission pair, so one file is
// enough even though the server splits them into four services. projectId always stays in
// the URL path (never a body field), which is where ProjectScopeInterceptor reads it (ADR-021).
@Injectable({ providedIn: 'root' })
export class GovernanceService {
  constructor(private http: HttpClient) {}

  /** Common URL prefix; keeps projectId in the path where ProjectScopeInterceptor needs it. */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  listRisks(projectId: number): Observable<Risk[]> {
    return this.http.get<Risk[]>(`${this.base(projectId)}/risks`);
  }

  listLivrables(projectId: number): Observable<Livrable[]> {
    return this.http.get<Livrable[]>(`${this.base(projectId)}/livrables`);
  }

  listChanges(projectId: number): Observable<DemandeChangement[]> {
    return this.http.get<DemandeChangement[]>(`${this.base(projectId)}/demandes-changement`);
  }

  /** probabilite/impact/statut travel as plain strings; the server maps them onto its own
   *  enums and rejects anything outside the list with a 400. */
  createRisk(projectId: number, body: { description: string; probabilite: string; impact: string; planMitigation: string; statut: string }): Observable<Risk> {
    return this.http.post<Risk>(`${this.base(projectId)}/risks`, body);
  }

  deleteRisk(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/risks/${id}`);
  }

  /** dateEcheance is an ISO date string, not a Date object — a Date would serialize with a
   *  timezone and could shift the deadline to the wrong day on the server. */
  createLivrable(projectId: number, body: { titre: string; description: string; dateEcheance?: string }): Observable<Livrable> {
    return this.http.post<Livrable>(`${this.base(projectId)}/livrables`, body);
  }

  deleteLivrable(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/livrables/${id}`);
  }

  /** Deliverable status: EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE. Three dedicated
   *  endpoints (demarrer/livrer/valider) instead of one generic "set status" call, so the
   *  legal step order is enforced server-side and can't be skipped by a modified request. */
  demarrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/demarrer`, {});
  }

  livrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/livrer`, {});
  }

  /** Last step; the server refuses to move a deliverable any further from here. */
  validerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/valider`, {});
  }

  /** demandeurId must resolve to a real user (404 otherwise); dateDemande is an ISO date
   *  string for the same timezone reason as the deliverable deadline above. */
  createChangement(projectId: number, body: { demandeurId: number; titre: string; description: string; priorite: string; dateDemande: string }): Observable<DemandeChangement> {
    return this.http.post<DemandeChangement>(`${this.base(projectId)}/demandes-changement`, body);
  }

  deleteChangement(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/demandes-changement/${id}`);
  }

  /** Dedicated endpoint rather than PATCHing statut: the decision is final server-side and
   *  its date must come from the server clock, not a browser that might have the wrong time. */
  approuverChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/approuver`, {});
  }

  /** Same rules as approuverChangement above. */
  rejeterChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/rejeter`, {});
  }

  // Parties prenantes (stakeholders)

  listParties(projectId: number): Observable<PartiePrenante[]> {
    return this.http.get<PartiePrenante[]>(`${this.base(projectId)}/parties-prenantes`);
  }

  /** Carries personal data (name, e-mail, phone), which is one more reason the server's
   *  VIEW_/MANAGE_GOVERNANCE + ADR-021 scope checks matter on this endpoint. */
  createPartie(projectId: number, body: { nom: string; fonction: string; email: string; telephone: string; influence: string; interet: string }): Observable<PartiePrenante> {
    return this.http.post<PartiePrenante>(`${this.base(projectId)}/parties-prenantes`, body);
  }

  deletePartie(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/parties-prenantes/${id}`);
  }
}
