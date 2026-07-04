import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { TeamAssignment, TeamAssignmentRequest } from '../models/team.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class TeamService {
  constructor(private http: HttpClient) {}

  private url(projectId: number) {
    return `${environment.apiUrl}/projects/${projectId}/team`;
  }

  list(projectId: number): Observable<TeamAssignment[]> {
    return this.http.get<TeamAssignment[]>(this.url(projectId));
  }

  assign(projectId: number, req: TeamAssignmentRequest): Observable<TeamAssignment> {
    return this.http.post<TeamAssignment>(this.url(projectId), req);
  }

  remove(projectId: number, assignmentId: number): Observable<void> {
    return this.http.delete<void>(`${this.url(projectId)}/${assignmentId}`);
  }
}
