import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JalonFacturation, Avenant, Paiement } from '../models/billing.model';
import { environment } from '../../../environments/environment';

// HTTP client for the billing module: billing milestones (jalons), payments against a
// milestone (paiements) and contract amendments (avenants). Milestone lifecycle: PREVU ->
// FACTURE (facturer() below) -> PAYE once payments cover the amount, enforced server-side.
// A milestone's amount is a percentage of the project budget, never typed in directly.
@Injectable({ providedIn: 'root' })
export class BillingService {
  constructor(private http: HttpClient) {}

  /** Common URL prefix; keeps projectId in the path where ProjectScopeInterceptor needs it. */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  listJalons(projectId: number): Observable<JalonFacturation[]> {
    return this.http.get<JalonFacturation[]>(`${this.base(projectId)}/jalons`);
  }

  /** Marks a milestone invoiced. A dedicated PATCH, not a PUT, because this is a business
   *  event the server validates (e.g. can't skip straight to PAYE with no invoice). */
  facturer(projectId: number, jalonId: number, dateFacture: string): Observable<JalonFacturation> {
    return this.http.patch<JalonFacturation>(
      `${this.base(projectId)}/jalons/${jalonId}/facturer`,
      { dateFacture }
    );
  }

  /** Creates a milestone. Sends "pourcentage", never an amount — the server derives the
   *  amount from the project budget so it can't drift from the contract. */
  createJalon(projectId: number, body: { label: string; pourcentage: number; datePrevue?: string }): Observable<JalonFacturation> {
    return this.http.post<JalonFacturation>(`${this.base(projectId)}/jalons`, body);
  }

  /** Soft delete server-side; refused with 422 if already invoiced or paid. */
  deleteJalon(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/jalons/${id}`);
  }

  // Paiements

  /** Payments hang under their milestone in the URL, not the project, so a mismatched
   *  payment id is rejected outright instead of trusting a body field. */
  listPaiements(projectId: number, jalonId: number): Observable<Paiement[]> {
    return this.http.get<Paiement[]>(`${this.base(projectId)}/jalons/${jalonId}/paiements`);
  }

  /** Records a payment (may be a partial instalment). The server sums payments and flips
   *  the milestone to PAYE once they cover it — no manual status change needed. */
  createPaiement(projectId: number, jalonId: number, body: { montantRecu: number; datePaiement: string; reference: string }): Observable<Paiement> {
    return this.http.post<Paiement>(`${this.base(projectId)}/jalons/${jalonId}/paiements`, body);
  }

  /** An avenant is a signed contract change (money and/or days), kept separate from the
   *  project row so the revised budget stays explainable line by line. */
  listAvenants(projectId: number): Observable<Avenant[]> {
    return this.http.get<Avenant[]>(`${this.base(projectId)}/avenants`);
  }

  createAvenant(projectId: number, body: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string }): Observable<Avenant> {
    return this.http.post<Avenant>(`${this.base(projectId)}/avenants`, body);
  }

  /** Soft delete server-side, same accounting-history reasoning as the milestones. */
  deleteAvenant(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/avenants/${id}`);
  }
}
