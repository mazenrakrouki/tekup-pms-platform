import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Risk, Livrable, DemandeChangement } from '../models/governance.model';
import { PartiePrenante } from '../models/partie-prenante.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class GovernanceService {
  constructor(private http: HttpClient) {}

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

  createRisk(projectId: number, body: { description: string; probabilite: string; impact: string; planMitigation: string; statut: string }): Observable<Risk> {
    return this.http.post<Risk>(`${this.base(projectId)}/risks`, body);
  }

  deleteRisk(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/risks/${id}`);
  }

  createLivrable(projectId: number, body: { titre: string; description: string; dateEcheance?: string }): Observable<Livrable> {
    return this.http.post<Livrable>(`${this.base(projectId)}/livrables`, body);
  }

  deleteLivrable(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/livrables/${id}`);
  }

  demarrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/demarrer`, {});
  }

  livrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/livrer`, {});
  }

  validerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/valider`, {});
  }

  createChangement(projectId: number, body: { demandeurId: number; titre: string; description: string; priorite: string; dateDemande: string }): Observable<DemandeChangement> {
    return this.http.post<DemandeChangement>(`${this.base(projectId)}/demandes-changement`, body);
  }

  deleteChangement(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/demandes-changement/${id}`);
  }

  approuverChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/approuver`, {});
  }

  rejeterChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/rejeter`, {});
  }

  // ── Parties prenantes ────────────────────────────────────────────
  listParties(projectId: number): Observable<PartiePrenante[]> {
    return this.http.get<PartiePrenante[]>(`${this.base(projectId)}/parties-prenantes`);
  }

  createPartie(projectId: number, body: { nom: string; fonction: string; email: string; telephone: string; influence: string; interet: string }): Observable<PartiePrenante> {
    return this.http.post<PartiePrenante>(`${this.base(projectId)}/parties-prenantes`, body);
  }

  deletePartie(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/parties-prenantes/${id}`);
  }
}
