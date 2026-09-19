import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JalonFacturation, Avenant, Paiement } from '../models/billing.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the billing module. It knows the addresses of the three things that
 * make up the money side of a project: the billing milestones (jalons), the payments
 * received against a milestone (paiements) and the contract amendments (avenants).
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/billing/billing.component.ts (the billing screen) and
 *                features/projects/project-detail/project-detail.component.ts (the money
 *                tab of one project).
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot BillingController, which hands the work
 *                to JalonService, PaiementService and AvenantService.
 * The typed answers come from core/models/billing.model.ts.
 *
 * WHY IT EXISTS
 * Without it, two different screens would each build the same URLs by hand and each type
 * the answers their own way. One spelling difference between the two and the same figure
 * would be shown twice with two different meanings - on accounting data, which is exactly
 * the kind of mistake nobody forgives.
 *
 * THE LIFE OF A MILESTONE (needed to read the methods below)
 * PREVU (planned) -> FACTURE (invoiced, facturer() below) -> PAYE (paid, once the payments
 * cover the amount). The order is enforced on the server: a payment is refused while the
 * milestone is still PREVU. The amount of a milestone is not typed in; it is a percentage
 * of the project budget, which is why createJalon() sends "pourcentage" and never "montant".
 *
 * SECURITY
 * Nothing here protects anything. On the server, reading needs VIEW_BILLING and writing
 * needs MANAGE_BILLING, checked with @PreAuthorize on the SERVICE methods; and because
 * every URL below looks like /api/projects/{id}/..., ProjectScopeInterceptor also checks
 * that this user may touch THAT project (ADR-021).
 */
@Injectable({ providedIn: 'root' })
export class BillingService {
  // One shared instance for the whole application (providedIn: 'root' above). HttpClient
  // is received through the constructor, so the request goes through the interceptor chain.
  constructor(private http: HttpClient) {}

  /**
   * Builds the start of every URL here: ".../api" + "/projects/12".
   *
   * Why a helper: the project id has to stay in the PATH, because that is where
   * ProjectScopeInterceptor reads it to apply ADR-021. Retyping it in ten methods is ten
   * chances to forget it, and a URL without the id would slip past the scope check.
   */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  /**
   * Reads the billing plan of one project (GET .../jalons): every milestone with its
   * percentage, its computed amount and its status.
   *
   * Observable<JalonFacturation[]>: nothing leaves the browser until a component
   * subscribes; and the component can cancel it by leaving the screen, which a Promise
   * could not do.
   */
  listJalons(projectId: number): Observable<JalonFacturation[]> {
    return this.http.get<JalonFacturation[]>(`${this.base(projectId)}/jalons`);
  }

  /**
   * Marks one milestone as invoiced (PATCH .../jalons/{id}/facturer) and gives back the
   * milestone with its new status FACTURE and its invoice date.
   *
   * Why PATCH on a named sub-URL instead of a PUT on the milestone: this is a business
   * event, not an edit. The server moves the status itself and refuses an illegal move.
   * With a plain PUT the browser would be the one deciding the new status, and a screen
   * could push a milestone straight from PREVU to PAYE without an invoice ever existing.
   *
   * dateFacture is a plain string, in the ISO form "2026-07-14". JSON has no date type, so
   * dates always travel as text; the server parses it into a LocalDate and refuses a null.
   * Without that date a milestone could sit in status FACTURE with no invoice date, and
   * nobody could match it with the paper invoice during an audit.
   */
  facturer(projectId: number, jalonId: number, dateFacture: string): Observable<JalonFacturation> {
    return this.http.patch<JalonFacturation>(
      `${this.base(projectId)}/jalons/${jalonId}/facturer`,
      { dateFacture }
    );
  }

  /**
   * Creates one milestone (POST .../jalons).
   *
   * The body type is written inline rather than in a model file because it is used once.
   * Read it as: a label, a percentage of the project budget, and an optional planned date.
   * The "?" on datePrevue means the field may be left out - a milestone can be defined
   * before its date is known.
   *
   * Notice what is NOT sent: the amount. The server multiplies the percentage by the
   * budget of the project. If the browser sent an amount, two screens could disagree with
   * the contract, and a revised budget would leave every old milestone showing a stale
   * figure.
   */
  createJalon(projectId: number, body: { label: string; pourcentage: number; datePrevue?: string }): Observable<JalonFacturation> {
    return this.http.post<JalonFacturation>(`${this.base(projectId)}/jalons`, body);
  }

  /**
   * Removes one milestone (DELETE .../jalons/{id}), answered 204 with no body - hence
   * Observable<void>.
   *
   * On the server the row is only marked deleted, not erased: billing data is accounting
   * history that an audit may still need. The server also refuses to delete a milestone
   * that is already invoiced or paid, and answers 422; the screen shows that message.
   */
  deleteJalon(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/jalons/${id}`);
  }

  // ── Paiements ────────────────────────────────────────────────────

  /**
   * Reads the payments received against ONE milestone
   * (GET .../jalons/{jalonId}/paiements).
   *
   * Why the payments hang under a milestone in the URL and not under the project: a
   * payment has no meaning on its own, it always settles one invoice. Keeping it in the
   * address means the server can refuse straight away a payment id that belongs to another
   * milestone, instead of trusting a field in the body.
   */
  listPaiements(projectId: number, jalonId: number): Observable<Paiement[]> {
    return this.http.get<Paiement[]>(`${this.base(projectId)}/jalons/${jalonId}/paiements`);
  }

  /**
   * Records money received (POST .../jalons/{jalonId}/paiements).
   *
   * montantRecu is the amount actually received, which may be less than the invoice: a
   * client can pay in several instalments. The server adds up the payments and moves the
   * milestone to PAYE once they cover the amount, so nobody has to set that status by hand
   * and forget it.
   * reference is the bank or accounting reference ("VIR-2026-0142"); it is what lets an
   * auditor match this row with the bank statement.
   */
  createPaiement(projectId: number, jalonId: number, body: { montantRecu: number; datePaiement: string; reference: string }): Observable<Paiement> {
    return this.http.post<Paiement>(`${this.base(projectId)}/jalons/${jalonId}/paiements`, body);
  }

  /**
   * Reads the contract amendments of the project (GET .../avenants).
   *
   * An "avenant" is a signed change to the contract: extra money, extra days of work, or
   * both. It is kept apart from the project row because the revised budget of the project
   * is the initial budget plus the amendments - a figure that must stay explainable line
   * by line in front of the client.
   */
  listAvenants(projectId: number): Observable<Avenant[]> {
    return this.http.get<Avenant[]>(`${this.base(projectId)}/avenants`);
  }

  /**
   * Creates one amendment (POST .../avenants).
   *
   * numero is the reference written on the signed paper; objet says what was agreed;
   * montant is the money added; workloadDays is optional because some amendments only add
   * time, with no extra money; dateAvenant is the signature date, as ISO text.
   */
  createAvenant(projectId: number, body: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string }): Observable<Avenant> {
    return this.http.post<Avenant>(`${this.base(projectId)}/avenants`, body);
  }

  /**
   * Removes one amendment (DELETE .../avenants/{id}), answered 204 with no body.
   * Soft delete on the server, for the same accounting-history reason as the milestones.
   */
  deleteAvenant(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/avenants/${id}`);
  }
}
