import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Mission, Composante } from '../models/mission.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the missions module. A mission is a trip made by a team member for a
 * project (where, when, what for), and each mission carries its cost components
 * (transport, hotel, per diem...). This file knows the addresses of both, and nothing else:
 * no screen, no state, no rule.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/missions/missions.component.ts (the missions screen) and
 *                features/projects/project-detail/project-detail.component.ts (the missions
 *                tab of one project).
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot MissionController -> MissionService and
 *                ComposanteService, which carry the real checks and the business rules.
 * The typed answers come from core/models/mission.model.ts.
 *
 * WHY IT EXISTS
 * Delete it and the two screens above would each write the same URLs. Having them here
 * means the day the server renames /composantes there is one line to change, and it keeps
 * the project id inside the path, which is what the scope check needs.
 *
 * WHY THE COST COMPONENTS HANG UNDER A MISSION IN THE URL
 * .../missions/{missionId}/composantes says in the address itself that a cost component
 * belongs to one mission. A flat address such as /composantes/{id} would let a request
 * attach a hotel bill to a mission of another project, and the server would have to check
 * the link by hand on every call.
 *
 * SECURITY - READ THIS BEFORE THE JURY ASKS
 * Nothing in this file protects anything. Two checks run on the server:
 *   1. the permission: @PreAuthorize("hasAuthority('VIEW_MISSION')") on the reads and
 *      hasAuthority('MANAGE_MISSION') on the writes, placed on the SERVICE methods;
 *   2. the project scope (ADR-021): every URL below starts with /api/projects/{id}/..., so
 *      ProjectScopeInterceptor checks that this user may work on THAT project.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance for the whole application. The class holds
// no state, so a second instance would be pure waste.
@Injectable({ providedIn: 'root' })
export class MissionService {
  // Constructor injection of the shared HttpClient.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would leave
  // without the Authorization header and the server would answer 401 every time.
  constructor(private http: HttpClient) {}

  /**
   * Builds the common start of every URL of this service:
   * "http://localhost:8090/api" + "/projects/12/missions".
   *
   * Why a private helper: the project id MUST stay inside the path, because
   * ProjectScopeInterceptor reads it from there to apply ADR-021. Written by hand six times
   * it is six chances to forget it, and a URL without the id would escape the scope check.
   */
  private base(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/missions`;
  }

  /**
   * Reads every mission of one project (GET .../missions).
   *
   * Observable<Mission[]>: an observable is a value that will arrive later; nothing travels
   * on the network until somebody subscribes.
   * Why an Observable and not a Promise: the component can cancel it when the user leaves
   * the screen, and the interceptor can replay it after renewing the token. A Promise can do
   * neither, so a slow answer would write into a screen that is already gone.
   */
  list(projectId: number): Observable<Mission[]> {
    // The <Mission[]> between the angle brackets is a generic: it tells TypeScript the shape
    // of the JSON body, so the screen gets a typed array. It is a compile-time promise only,
    // nothing checks the real answer at run time; it is safe because the shape is fixed by
    // the response record on the server.
    return this.http.get<Mission[]>(this.base(projectId));
  }

  /**
   * Creates one mission (POST .../missions) and gives back the row as the server saved it.
   *
   * userId is the person who travels, chosen in the form. It is sent as a number and the
   * server turns it into a real link to the user row, so an unknown id is answered 404
   * instead of being stored as a dangling reference.
   * dateDebut and dateFin are ISO day strings such as "2026-03-31", not JavaScript Date
   * objects. Why: a Date is serialised with the time and the time zone, so a trip starting
   * on the 31st typed in Tunis at 01:00 would reach a UTC server as the 30th. The string
   * keeps the exact day the user typed.
   *
   * Why we return the server answer instead of reusing the object we sent: the id and the
   * name of the traveller only exist in that answer. A screen displaying its own object
   * would show a row with no id, and the next click would build .../missions/undefined.
   */
  create(projectId: number, body: { userId: number; objet: string; lieu: string; dateDebut: string; dateFin: string }): Observable<Mission> {
    return this.http.post<Mission>(this.base(projectId), body);
  }

  /**
   * Removes one mission (DELETE .../missions/{id}).
   *
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   * Why void and not Mission: typing it Mission would push the component to display a row
   * that does not exist any more.
   */
  delete(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${id}`);
  }

  // ── Composantes de coût ──────────────────────────────────────────
  // Cost components: the lines that make up what one mission costs.

  /**
   * Reads the cost components of ONE mission (GET .../missions/{missionId}/composantes).
   *
   * Why they are not already inside the Mission answer: a project can hold many missions and
   * each mission many lines. Sending everything in the list call would make the missions
   * screen slow for a detail the user opens on one row at a time.
   */
  listComposantes(projectId: number, missionId: number): Observable<Composante[]> {
    return this.http.get<Composante[]>(`${this.base(projectId)}/${missionId}/composantes`);
  }

  /**
   * Adds one cost line to a mission (POST .../missions/{missionId}/composantes).
   *
   * typeComposante is the kind of expense (transport, hotel...) sent as a plain string; the
   * server maps it onto its own enum, so a word outside the list is answered 400 and never
   * stored as free text.
   * montant is a number and devise the currency code of that amount. They travel together on
   * purpose: an amount without its currency cannot be converted later, and a line entered in
   * euros would silently be counted as dinars in the totals.
   */
  createComposante(projectId: number, missionId: number, body: { typeComposante: string; montant: number; devise: string; description: string }): Observable<Composante> {
    return this.http.post<Composante>(`${this.base(projectId)}/${missionId}/composantes`, body);
  }

  /**
   * Removes one cost line (DELETE .../missions/{missionId}/composantes/{id}), answered 204.
   *
   * Why missionId is still in the address although the line id would be enough to find the
   * row: the full path lets the server check that the line really belongs to that mission of
   * that project. Without it, a line id borrowed from another project could be deleted from
   * here, and the server would have no address to compare it with.
   */
  deleteComposante(projectId: number, missionId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/${missionId}/composantes/${id}`);
  }
}
