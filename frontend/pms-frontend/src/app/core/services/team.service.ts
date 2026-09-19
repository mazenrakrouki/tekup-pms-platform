import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { TeamAssignment, TeamAssignmentRequest } from '../models/team.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client for the team of a project: who works on it, in which role and between
 * which dates. Three addresses, no screen, no state.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/projects/project-detail/project-detail.component.ts (the team tab)
 *                and the screens that need to know who is on a project before offering a
 *                name in a dropdown - agile, missions, workload, governance.
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot TeamAssignmentController ->
 *                TeamAssignmentService, which carries the real checks and the rules.
 * The typed answers come from core/models/team.model.ts.
 *
 * WHY IT EXISTS
 * Several screens need the same list of people. Without this file each of them would build
 * the URL itself, and the day the server renames /team we would have to find every copy.
 *
 * WHY A TEAM MEMBER IS A ROW AND NOT A SIMPLE LINK BETWEEN USER AND PROJECT
 * The assignment carries its own data: the role held in the team and the period. That is
 * what lets a developer join a project in March and leave it in June, and still appear in
 * the figures of those months. A plain link would only be able to say "he is on it", and the
 * history of who worked when would be lost.
 *
 * SECURITY - READ THIS BEFORE THE JURY ASKS
 * Nothing in this file protects anything. Two checks run on the server:
 *   1. the permission: @PreAuthorize("hasAuthority('VIEW_TEAM')") on the read and
 *      hasAuthority('ASSIGN_DEVELOPER') on the two writes, placed on the SERVICE methods;
 *   2. the project scope (ADR-021): the URL has the shape /api/projects/{id}/team, so
 *      ProjectScopeInterceptor checks that this user may work on THAT project. Without it,
 *      holding ASSIGN_DEVELOPER would be enough to add oneself to any project of the
 *      company - and being on the team is what opens the rest of its data.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance for the whole application; the class holds
// no state, so a second instance would be pure waste.
@Injectable({ providedIn: 'root' })
export class TeamService {
  // Constructor injection of the shared HttpClient.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would leave
  // without the Authorization header and the server would answer 401 every time.
  constructor(private http: HttpClient) {}

  /**
   * Builds the only address of this service:
   * "http://localhost:8090/api" + "/projects/12/team".
   *
   * Why a private helper for three calls: the project id MUST stay inside the path, because
   * ProjectScopeInterceptor reads it from there to apply ADR-021. One place to build it is
   * one place to get it right.
   */
  private url(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/team`;
  }

  /**
   * Reads the team of one project (GET .../team).
   *
   * Observable<TeamAssignment[]>: an observable is a value that will arrive later; nothing
   * travels on the network until somebody subscribes.
   * Why an Observable and not a Promise: the component can cancel it when the user leaves
   * the screen, and the interceptor can replay it after renewing the token. A Promise can do
   * neither, so a slow answer would write into a screen that is already gone.
   * Each row carries userFullName, already assembled by the server. Why it matters: without
   * it every screen showing the team would have to call the users endpoint as well, only to
   * turn ids into names - and a user without MANAGE_USERS could not make that second call.
   */
  list(projectId: number): Observable<TeamAssignment[]> {
    // The <TeamAssignment[]> between the angle brackets is a generic: it tells TypeScript
    // the shape of the JSON body. It is a compile-time promise only; it is safe here because
    // the shape is fixed by TeamAssignmentResponse on the server.
    return this.http.get<TeamAssignment[]>(this.url(projectId));
  }

  /**
   * Puts somebody on the project (POST .../team) and gives back the saved assignment.
   *
   * TeamAssignmentRequest carries the user id, the role held in the team and the period.
   * startDate and endDate are ISO day strings such as "2026-03-31", not JavaScript Date
   * objects. Why: a Date is serialised with the time and the time zone, so a start date typed
   * in Tunis at 01:00 would reach a UTC server as the day before.
   * endDate is optional: an assignment with no end date is somebody still on the project.
   *
   * Why we return the server answer instead of reusing the object we sent: the id and the
   * full name of the person only exist in that answer. A screen displaying its own object
   * would show a row with no id, and the remove button on it would build .../team/undefined.
   */
  assign(projectId: number, req: TeamAssignmentRequest): Observable<TeamAssignment> {
    return this.http.post<TeamAssignment>(this.url(projectId), req);
  }

  /**
   * Takes somebody off the project (DELETE .../team/{assignmentId}).
   *
   * Careful with the argument: it is the id of the ASSIGNMENT, not the id of the user. The
   * row is the thing being removed, and the server identifies it by its own id. Sending the
   * user id instead would work only as long as one person can never have more than one row
   * here - the server does refuse a second ACTIVE assignment for the same person, but the
   * removed ones stay in the table, so the row id is the only value that is never ambiguous.
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   */
  remove(projectId: number, assignmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.url(projectId)}/${assignmentId}`);
  }
}
