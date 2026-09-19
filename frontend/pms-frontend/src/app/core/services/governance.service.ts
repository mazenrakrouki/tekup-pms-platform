import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Risk, Livrable, DemandeChangement } from '../models/governance.model';
import { PartiePrenante } from '../models/partie-prenante.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the governance module. One single service for the four objects that
 * steer a project: the risks, the deliverables (livrables), the change requests (demandes
 * de changement) and the stakeholders (parties prenantes). It draws no screen and keeps no
 * value: each method sends one request and gives back the answer.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/governance/governance.component.ts (the governance screen) and
 *                features/projects/project-detail/project-detail.component.ts (the
 *                governance tab of one project).
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot RiskController, LivrableController,
 *                DemandeChangementController and PartiePrenanteController, which hand the
 *                work to RiskService, LivrableService, DemandeChangementService and
 *                PartiePrenanteService. Those services carry the real checks.
 * The typed answers come from core/models/governance.model.ts and
 * core/models/partie-prenante.model.ts.
 *
 * WHY IT EXISTS
 * Delete it and the governance screen would hold about twenty URL strings of its own. The
 * day the server renames /demandes-changement we would have to find every one of them.
 * Here there is one line to change. It also keeps the four families together: they share
 * the same base URL and the same pair of permissions, so splitting them into four files
 * would repeat base() four times for no gain.
 *
 * WHY ONE SERVICE FOR FOUR OBJECTS ON THE FRONT SIDE AND FOUR SERVICES ON THE SERVER
 * The server splits them because each one has its own business rules and its own
 * transaction. Here there is no rule at all, only addresses, so one file is enough and the
 * screen injects one dependency instead of four.
 *
 * SECURITY - READ THIS BEFORE THE JURY ASKS
 * Nothing in this file protects anything. Two checks run on the server for every call:
 *   1. the permission: @PreAuthorize("hasAuthority('VIEW_GOVERNANCE')") on the reads and
 *      hasAuthority('MANAGE_GOVERNANCE') on every write, placed on the SERVICE methods;
 *   2. the project scope (ADR-021): every URL below has the shape /api/projects/{id}/...,
 *      so ProjectScopeInterceptor checks that this user may work on THAT project.
 * That is why projectId always stays in the path and never travels as a body field: the
 * interceptor reads it from the address.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance for the whole application.
// Why: the class holds no state, so a second instance would be pure waste, and 'root' lets
// the build drop the class from the bundle if no screen uses it.
@Injectable({ providedIn: 'root' })
export class GovernanceService {
  // Constructor injection: Angular hands over the shared HttpClient.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would
  // leave without the Authorization header and the server would answer 401 every time.
  constructor(private http: HttpClient) {}

  /**
   * Builds the common start of every URL of this service:
   * "http://localhost:8090/api" + "/projects/12".
   *
   * Why a private helper and not the full string written in each method: the project id
   * MUST stay inside the path, because ProjectScopeInterceptor reads it from there to apply
   * ADR-021. Writing it by hand twenty times is twenty chances to forget it, and a URL
   * without the id would escape the scope check completely.
   */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  /**
   * Reads every risk of one project (GET .../risks).
   *
   * Observable<Risk[]>: an observable is a value that will arrive later; nothing is sent on
   * the network until somebody subscribes.
   * Why an Observable and not a Promise: the component can cancel it when the user leaves
   * the screen, and the interceptor can replay it after renewing the token. A Promise can
   * do neither, so a slow answer would write into a screen that no longer exists.
   */
  listRisks(projectId: number): Observable<Risk[]> {
    // The <Risk[]> between the angle brackets is a generic: it tells TypeScript the shape
    // of the JSON body, so the screen gets a typed array.
    // Careful: it is a compile-time promise only, nothing checks the real answer at run
    // time. It is safe here because the shape is fixed by the response record on the server.
    return this.http.get<Risk[]>(`${this.base(projectId)}/risks`);
  }

  /**
   * Reads every deliverable of one project (GET .../livrables).
   */
  listLivrables(projectId: number): Observable<Livrable[]> {
    return this.http.get<Livrable[]>(`${this.base(projectId)}/livrables`);
  }

  /**
   * Reads every change request of one project (GET .../demandes-changement).
   */
  listChanges(projectId: number): Observable<DemandeChangement[]> {
    return this.http.get<DemandeChangement[]>(`${this.base(projectId)}/demandes-changement`);
  }

  /**
   * Creates one risk (POST .../risks) and gives back the row as the server saved it.
   *
   * The body type is written inline here instead of using a named interface. probabilite
   * and impact are the three steps of the risk scale, and statut is the state of the risk;
   * they travel as plain strings and the server converts them into its own enums. A word
   * that is not part of the list is answered 400, not saved as text.
   *
   * Why we return the server answer instead of reusing the object we sent: the id and the
   * audit fields only exist in that answer. A screen that displayed its own object would
   * show a row with no id, and the next click on it would build the URL .../risks/undefined.
   */
  createRisk(projectId: number, body: { description: string; probabilite: string; impact: string; planMitigation: string; statut: string }): Observable<Risk> {
    return this.http.post<Risk>(`${this.base(projectId)}/risks`, body);
  }

  /**
   * Removes one risk (DELETE .../risks/{id}).
   *
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   * Why void and not Risk: typing it Risk would push the component to display a row that
   * does not exist any more.
   */
  deleteRisk(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/risks/${id}`);
  }

  /**
   * Creates one deliverable (POST .../livrables) and gives back the saved row.
   *
   * dateEcheance carries a "?" : it is optional, so the caller may leave it out. It is a
   * plain string in the ISO form "2026-03-31", not a JavaScript Date.
   * Why a string and not a Date: a Date object is serialised with the time and the time
   * zone, and a deadline sent from Tunis at 01:00 would arrive as the day before on a
   * server reading UTC. The string keeps the day the user typed, exactly.
   */
  createLivrable(projectId: number, body: { titre: string; description: string; dateEcheance?: string }): Observable<Livrable> {
    return this.http.post<Livrable>(`${this.base(projectId)}/livrables`, body);
  }

  /**
   * Removes one deliverable (DELETE .../livrables/{id}), answered 204 with no body.
   */
  deleteLivrable(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/livrables/${id}`);
  }

  /**
   * Moves a deliverable to "started" (PATCH .../livrables/{id}/demarrer) and gives back the
   * updated row. The status of a deliverable goes EN_ATTENTE -> EN_COURS -> LIVRE -> VALIDE.
   *
   * Why three small dedicated endpoints (demarrer, livrer, valider) instead of one generic
   * "set the status" call: the allowed order of the steps is a business rule, and it must
   * live on the server. With a generic call the browser would be the one choosing the next
   * status, so a modified request could jump a deliverable straight from EN_ATTENTE to
   * VALIDE and skip the delivery step. Here each address means one single legal move.
   *
   * The second argument is {} - an empty body. PATCH needs a body, and there is nothing to
   * send: the address already says everything. Sending null instead would make Angular drop
   * the Content-Type header and some servers answer 415.
   */
  demarrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/demarrer`, {});
  }

  /**
   * Marks a deliverable as delivered (PATCH .../livrables/{id}/livrer): EN_COURS -> LIVRE.
   */
  livrerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/livrer`, {});
  }

  /**
   * Marks a deliverable as accepted by the client (PATCH .../livrables/{id}/valider):
   * LIVRE -> VALIDE. This is the last step, and the server refuses to leave it.
   */
  validerLivrable(projectId: number, id: number): Observable<Livrable> {
    return this.http.patch<Livrable>(`${this.base(projectId)}/livrables/${id}/valider`, {});
  }

  /**
   * Creates one change request (POST .../demandes-changement) and gives back the saved row.
   *
   * demandeurId is the id of the person asking for the change, chosen in the form. It is
   * sent as a number and the server turns it into a real link to the user row; an unknown
   * id is answered 404 instead of being stored as a dangling reference.
   * priorite is one of FAIBLE, NORMALE, ELEVEE, CRITIQUE, sent as a plain string.
   * dateDemande is an ISO day string such as "2026-03-31", for the same time-zone reason as
   * the deadline above.
   */
  createChangement(projectId: number, body: { demandeurId: number; titre: string; description: string; priorite: string; dateDemande: string }): Observable<DemandeChangement> {
    return this.http.post<DemandeChangement>(`${this.base(projectId)}/demandes-changement`, body);
  }

  /**
   * Removes one change request (DELETE .../demandes-changement/{id}), answered 204.
   */
  deleteChangement(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/demandes-changement/${id}`);
  }

  /**
   * Accepts a change request (PATCH .../demandes-changement/{id}/approuver):
   * EN_ATTENTE -> APPROUVE, and the server stamps the decision date.
   *
   * Why a dedicated address rather than sending statut: "APPROUVE" through the edit call:
   * the decision is final on the server (a decided request never goes back to EN_ATTENTE),
   * and the decision date must be the server clock, not the clock of the browser. A user
   * with a wrong date on his machine would otherwise write a decision dated last year.
   */
  approuverChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/approuver`, {});
  }

  /**
   * Refuses a change request (PATCH .../demandes-changement/{id}/rejeter):
   * EN_ATTENTE -> REJETE, with the same rules as approuver above.
   */
  rejeterChangement(projectId: number, id: number): Observable<DemandeChangement> {
    return this.http.patch<DemandeChangement>(`${this.base(projectId)}/demandes-changement/${id}/rejeter`, {});
  }

  // ── Parties prenantes ────────────────────────────────────────────

  /**
   * Reads every stakeholder of one project (GET .../parties-prenantes). A stakeholder is a
   * person touched by the project: client contact, sponsor, supplier.
   */
  listParties(projectId: number): Observable<PartiePrenante[]> {
    return this.http.get<PartiePrenante[]>(`${this.base(projectId)}/parties-prenantes`);
  }

  /**
   * Creates one stakeholder (POST .../parties-prenantes) and gives back the saved row.
   *
   * influence and interet are the two axes of the classic stakeholder map (who can change
   * the project, and who cares about it). They travel as plain strings and the server maps
   * them onto its own enum, so a value outside the list is answered 400.
   * Note that this body carries personal data - name, e-mail, phone number - which is one
   * more reason why the two server checks (VIEW_/MANAGE_GOVERNANCE and the ADR-021 scope)
   * matter on these URLs.
   */
  createPartie(projectId: number, body: { nom: string; fonction: string; email: string; telephone: string; influence: string; interet: string }): Observable<PartiePrenante> {
    return this.http.post<PartiePrenante>(`${this.base(projectId)}/parties-prenantes`, body);
  }

  /**
   * Removes one stakeholder (DELETE .../parties-prenantes/{id}), answered 204 with no body.
   */
  deletePartie(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/parties-prenantes/${id}`);
  }
}
