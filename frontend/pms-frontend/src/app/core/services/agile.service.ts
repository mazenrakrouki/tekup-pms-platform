import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BacklogItem, BacklogItemPayload, BacklogItemStatus, Sprint, SprintPayload
} from '../models/agile.model';

// HTTP client for the Agile module (sprints + backlog). Holds no state, builds no screen —
// used today only by features/agile/agile.component.ts. projectId always stays in the URL
// path (never a body field) because ProjectScopeInterceptor reads it from there for ADR-021.
@Injectable({ providedIn: 'root' })
export class AgileService {
  private readonly http = inject(HttpClient);

  /** Common URL prefix for this project's endpoints; keeps projectId reliably in the path. */
  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  // Sprints

  listSprints(projectId: number): Observable<Sprint[]> {
    return this.http.get<Sprint[]>(`${this.base(projectId)}/sprints`);
  }

  createSprint(projectId: number, body: SprintPayload): Observable<Sprint> {
    return this.http.post<Sprint>(`${this.base(projectId)}/sprints`, body);
  }

  /** PUT, not PATCH: SprintPayload carries the whole form, so a partial body would erase
   *  the fields left out. */
  updateSprint(projectId: number, id: number, body: SprintPayload): Observable<Sprint> {
    return this.http.put<Sprint>(`${this.base(projectId)}/sprints/${id}`, body);
  }

  /** Soft delete server-side (deleted = true), so project history isn't lost. */
  deleteSprint(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/sprints/${id}`);
  }

  // Backlog

  /** One call for all three board columns (sprintId = null means product backlog), so they
   *  render in sync instead of populating one after another. */
  listBacklog(projectId: number): Observable<BacklogItem[]> {
    return this.http.get<BacklogItem[]>(`${this.base(projectId)}/backlog`);
  }

  createItem(projectId: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.post<BacklogItem>(`${this.base(projectId)}/backlog`, body);
  }

  updateItem(projectId: number, id: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.put<BacklogItem>(`${this.base(projectId)}/backlog/${id}`, body);
  }

  /** Separate PATCH instead of reusing updateItem(): a drag-and-drop only knows the new
   *  column/sprint, and building a full payload from stale in-memory state could silently
   *  overwrite a concurrent edit. sprintId: null means "back to the product backlog". */
  moveItem(projectId: number, id: number,
           status: BacklogItemStatus, sprintId: number | null): Observable<BacklogItem> {
    return this.http.patch<BacklogItem>(`${this.base(projectId)}/backlog/${id}/move`, { status, sprintId });
  }

  /** Soft delete server-side, like deleteSprint above. */
  deleteItem(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/backlog/${id}`);
  }
}
