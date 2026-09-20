import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { TeamAssignment, TeamAssignmentRequest } from '../models/team.model';
import { environment } from '../../../environments/environment';

// HTTP client for a project's team: who works on it, in which role, between which dates.
// A row instead of a plain user-project link so a developer's March-to-June stint still shows
// up correctly in each month's figures.
// Server-side: VIEW_TEAM (read), ASSIGN_DEVELOPER (writes), plus ADR-021 project-scope checks —
// without the latter, ASSIGN_DEVELOPER alone could add anyone to any project.
@Injectable({ providedIn: 'root' })
export class TeamService {
  constructor(private http: HttpClient) {}

  // Project id stays in the path (not query) so ProjectScopeInterceptor can read it for ADR-021.
  private url(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/team`;
  }

  // userFullName is pre-assembled by the server so screens don't need a second, permission-gated
  // call to the users endpoint just to turn ids into names.
  list(projectId: number): Observable<TeamAssignment[]> {
    return this.http.get<TeamAssignment[]>(this.url(projectId));
  }

  // startDate/endDate are ISO day strings, not Date objects, to avoid a time-zone day shift.
  // endDate is optional: no end date means still on the project.
  assign(projectId: number, req: TeamAssignmentRequest): Observable<TeamAssignment> {
    return this.http.post<TeamAssignment>(this.url(projectId), req);
  }

  // assignmentId is the row's own id, not the user id — a person can have multiple
  // (non-active) rows over time, so only the row id is unambiguous.
  remove(projectId: number, assignmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.url(projectId)}/${assignmentId}`);
  }
}
