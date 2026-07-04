import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JalonFacturation, Avenant, Paiement } from '../models/billing.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class BillingService {
  constructor(private http: HttpClient) {}

  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  listJalons(projectId: number): Observable<JalonFacturation[]> {
    return this.http.get<JalonFacturation[]>(`${this.base(projectId)}/jalons`);
  }

  facturer(projectId: number, jalonId: number, dateFacture: string): Observable<JalonFacturation> {
    return this.http.patch<JalonFacturation>(
      `${this.base(projectId)}/jalons/${jalonId}/facturer`,
      { dateFacture }
    );
  }

  createJalon(projectId: number, body: { label: string; pourcentage: number; datePrevue?: string }): Observable<JalonFacturation> {
    return this.http.post<JalonFacturation>(`${this.base(projectId)}/jalons`, body);
  }

  deleteJalon(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/jalons/${id}`);
  }

  // ── Paiements ────────────────────────────────────────────────────
  listPaiements(projectId: number, jalonId: number): Observable<Paiement[]> {
    return this.http.get<Paiement[]>(`${this.base(projectId)}/jalons/${jalonId}/paiements`);
  }

  createPaiement(projectId: number, jalonId: number, body: { montantRecu: number; datePaiement: string; reference: string }): Observable<Paiement> {
    return this.http.post<Paiement>(`${this.base(projectId)}/jalons/${jalonId}/paiements`, body);
  }

  listAvenants(projectId: number): Observable<Avenant[]> {
    return this.http.get<Avenant[]>(`${this.base(projectId)}/avenants`);
  }

  createAvenant(projectId: number, body: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string }): Observable<Avenant> {
    return this.http.post<Avenant>(`${this.base(projectId)}/avenants`, body);
  }

  deleteAvenant(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/avenants/${id}`);
  }
}
