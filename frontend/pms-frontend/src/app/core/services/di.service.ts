import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DevisInterneResponse, LigneDiRequest } from '../models/di.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the Devis Interne (internal quote). It is the only place in the front
 * end that knows the four addresses of the internal quote. It builds no screen, keeps no
 * value in memory: each method sends one request and gives back the answer.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/di/devis-interne.component.ts, the internal-quote screen. It is
 *                the only caller today.
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (it adds the
 *                Authorization header and renews a dead token) -> Spring Boot
 *                DevisInterneController -> DevisInterneService, where the real checks and
 *                the real arithmetic happen.
 * The typed answers come from core/models/di.model.ts.
 *
 * WHY IT EXISTS
 * Delete it and the screen would build the URL strings itself, in four places. More
 * important: it is here that we keep the rule "the project id lives in the PATH". The
 * whole scope protection of ADR-021 depends on that shape of URL.
 *
 * SECURITY - THE DI IS THE MOST SENSITIVE SCREEN OF THE APPLICATION
 * The internal quote holds the company cost prices and the margin. Two checks run on the
 * server for each of the four calls below:
 *   1. the permission: @PreAuthorize("hasAuthority('MANAGE_DI')") sits on the SERVICE
 *      methods of DevisInterneService. Holding VIEW_PROJECT or VIEW_KPI is not enough;
 *   2. the project scope (ADR-021): every URL has the shape /api/projects/{id}/...,
 *      so ProjectScopeInterceptor checks that this user may work on THAT project.
 * Nothing in this TypeScript file protects anything: anybody can call the same URL with
 * curl. Hiding the menu entry is comfort for the user, not security.
 *
 * WHY THE AMOUNTS ARE NOT SENT BACK WHEN WE WRITE
 * The computed amounts (montantTnd, prixRevient, coutFinal, margeNette, margePct) exist
 * only in the ANSWER, never in what we send. They are derived by the server every time
 * the quote is read and they are stored in no column. So the browser can never push a
 * margin figure of its own, and two screens can never show two different margins for the
 * same lines.
 */
// Devis Interne (internal quote) - access restricted to MANAGE_DI. The data is sensitive
// (internal cost prices), which is why the database migrations create the STRUCTURE of the
// quote but never seed any line of data into it.
// @Injectable lets Angular build this class and hand it to whoever asks for it.
// providedIn: 'root' creates ONE shared instance for the whole application.
// Why: the class holds no state, so a second instance would be pure waste, and 'root' also
// lets the build drop the class from the bundle if no screen uses it.
@Injectable({ providedIn: 'root' })
export class DiService {
  // Constructor injection: Angular passes the shared HttpClient in.
  // Why not "new HttpClient()": we would lose the interceptor chain, so every request would
  // leave without the Authorization header and the server would answer 401.
  constructor(private http: HttpClient) {}

  /**
   * Builds the start of the four URLs of this service:
   * "http://localhost:8090/api" + "/projects/12/devis-interne".
   *
   * Why a private helper instead of writing the string in each method: the project id MUST
   * stay inside the path, because ProjectScopeInterceptor reads it from there (ADR-021).
   * Writing it by hand four times is four chances to forget it, and a URL without the id
   * would escape the scope check completely.
   */
  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}/devis-interne`;
  }

  /**
   * Reads the whole internal quote of one project: the lines plus the totals.
   *
   * Observable<DevisInterneResponse>: an observable is a value that will arrive later.
   * Nothing travels on the network until somebody subscribes.
   * Why an Observable and not a Promise: the component can cancel it when the user leaves
   * the screen, and the interceptor can replay it after renewing the token. A Promise can
   * do neither, so a slow answer would write into a screen that is already gone.
   *
   * Why the whole quote and not one line at a time: the lines of the AUTRES_FRAIS section
   * are percentages of the total sold, so no line can be understood alone. Reading them one
   * by one would show percentages computed against a total that is not there yet.
   */
  get(projectId: number): Observable<DevisInterneResponse> {
    // The <DevisInterneResponse> between the angle brackets is a generic: it tells
    // TypeScript the shape of the JSON body, so the screen gets typed fields.
    // Careful: it is a compile-time promise only, nothing checks the real answer at run
    // time. It is safe here because the shape is fixed by DevisInterneResponse on the server.
    return this.http.get<DevisInterneResponse>(this.base(projectId));
  }

  /**
   * Adds one line to the quote (POST .../lignes) and gives back the WHOLE recomputed quote.
   *
   * Why the answer is the whole quote and not just the new line: adding a line changes the
   * total sold, and the total sold is the base of the percentage lines. One new line can
   * therefore change several other lines and every total. If the server returned only the
   * new line, the screen would keep showing totals that are already false, and the user
   * would have to reload the page to see the truth.
   */
  addLigne(projectId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.post<DevisInterneResponse>(`${this.base(projectId)}/lignes`, req);
  }

  /**
   * Changes one line (PUT .../lignes/{id}) and gives back the whole recomputed quote.
   *
   * Why PUT and not PATCH: LigneDiRequest carries the complete line as the form holds it.
   * PUT means "here is the full new version of that row".
   * The server also checks that the line really belongs to THIS project, so a line id
   * borrowed from another project answers 404 and not a silent cross-project edit.
   */
  updateLigne(projectId: number, ligneId: number, req: LigneDiRequest): Observable<DevisInterneResponse> {
    return this.http.put<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`, req);
  }

  /**
   * Removes one line (DELETE .../lignes/{id}) and gives back the whole recomputed quote.
   *
   * Why the return type is DevisInterneResponse and not void, unlike most delete calls in
   * this project: removing a line changes every total, exactly like adding one. The server
   * answers with the quote as it is after the removal, so the screen shows the new margin
   * without a second request.
   * On the server this is a SOFT delete: the row stays in the table with deleted = true, so
   * the commercial history of the contract is not lost.
   */
  deleteLigne(projectId: number, ligneId: number): Observable<DevisInterneResponse> {
    return this.http.delete<DevisInterneResponse>(`${this.base(projectId)}/lignes/${ligneId}`);
  }
}
