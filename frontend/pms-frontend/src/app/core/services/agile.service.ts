import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BacklogItem, BacklogItemPayload, BacklogItemStatus, Sprint, SprintPayload
} from '../models/agile.model';

@Injectable({ providedIn: 'root' })
export class AgileService {
  private readonly http = inject(HttpClient);

  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  // ── Sprints ────────────────────────────────────────────────────
  listSprints(projectId: number): Observable<Sprint[]> {
    return this.http.get<Sprint[]>(`${this.base(projectId)}/sprints`);
  }

  createSprint(projectId: number, body: SprintPayload): Observable<Sprint> {
    return this.http.post<Sprint>(`${this.base(projectId)}/sprints`, body);
  }

  updateSprint(projectId: number, id: number, body: SprintPayload): Observable<Sprint> {
    return this.http.put<Sprint>(`${this.base(projectId)}/sprints/${id}`, body);
  }

  deleteSprint(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/sprints/${id}`);
  }

  // ── Backlog ────────────────────────────────────────────────────
  listBacklog(projectId: number): Observable<BacklogItem[]> {
    return this.http.get<BacklogItem[]>(`${this.base(projectId)}/backlog`);
  }

  createItem(projectId: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.post<BacklogItem>(`${this.base(projectId)}/backlog`, body);
  }

  updateItem(projectId: number, id: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.put<BacklogItem>(`${this.base(projectId)}/backlog/${id}`, body);
  }

  /**
   * Déplacement d'une carte : colonne, et éventuellement sprint. Requête dédiée pour
   * qu'un déplacement n'ait pas à renvoyer titre, description et estimation.
   */
  moveItem(projectId: number, id: number,
           status: BacklogItemStatus, sprintId: number | null): Observable<BacklogItem> {
    return this.http.patch<BacklogItem>(`${this.base(projectId)}/backlog/${id}/move`, { status, sprintId });
  }

  deleteItem(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/backlog/${id}`);
  }
}
