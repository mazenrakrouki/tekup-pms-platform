import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BacklogItem, BacklogItemPayload, BacklogItemStatus, Sprint, SprintPayload
} from '../models/agile.model';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the Agile module. It is the only place in the front end that knows
 * the addresses of the sprint endpoints and of the backlog endpoints. It builds no screen
 * and holds no state: each method sends one request and gives back the answer.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : features/agile/agile.component.ts (the Scrum board screen). It is the
 *                only caller today.
 * What it calls: HttpClient, which goes through core/interceptors/auth.interceptor.ts
 *                (that file adds the Authorization header and renews a dead token), then
 *                over the network to Spring Boot, where SprintController and
 *                BacklogItemController answer, and where SprintService and
 *                BacklogItemService run the real permission checks.
 * The typed answers come from core/models/agile.model.ts (Sprint, BacklogItem, and the
 * two "Payload" shapes used when writing).
 *
 * WHY IT EXISTS
 * Delete it and the board component would have to write the URL strings itself, in about
 * ten places. The day the server renames /backlog, we would have to find every one of
 * them. Here there is one line to change. It also gives the component typed answers, so
 * a spelling mistake on a field name is caught while compiling, not by the user.
 *
 * SECURITY - READ THIS BEFORE THE JURY ASKS
 * Nothing in this file protects anything. The real checks are on the server, and there
 * are two of them for these URLs:
 *   1. the permission: @PreAuthorize("hasAuthority('VIEW_AGILE')") for reading and
 *      hasAuthority('MANAGE_AGILE') for writing, placed on the SERVICE methods;
 *   2. the project scope: every URL below has the shape /api/projects/{id}/..., so
 *      ProjectScopeInterceptor checks that this user may work on THAT project (ADR-021).
 * That is why projectId always stays in the path and never travels as a body field: the
 * interceptor reads it from the address.
 */
// @Injectable marks the class so Angular can build it and hand it to whoever asks for it.
// providedIn: 'root' creates ONE shared instance for the whole application, built the
// first time somebody injects it.
// Why: the class holds no data, so a second instance would be pure waste, and 'root' also
// lets the Angular build drop the class from the bundle if no screen ever uses it.
@Injectable({ providedIn: 'root' })
export class AgileService {
  // inject() is the modern way to receive a dependency, instead of writing a constructor.
  // readonly stops any code from replacing the HttpClient later by mistake.
  // Why not "new HttpClient()": we would lose the interceptor chain, so the requests would
  // leave without the Authorization header and the server would answer 401 every time.
  private readonly http = inject(HttpClient);

  /**
   * Builds the common start of every URL of this service:
   * "http://localhost:8090/api" + "/projects/12".
   *
   * Why a private helper and not the full string written in each method: the project id
   * MUST stay inside the path, because ProjectScopeInterceptor reads it from there to
   * apply ADR-021. Writing it by hand ten times is ten chances to forget it - and a URL
   * without the id would escape the scope check completely.
   */
  private base(projectId: number): string {
    return `${environment.apiUrl}/projects/${projectId}`;
  }

  // ── Sprints ────────────────────────────────────────────────────

  /**
   * Reads every sprint of one project (GET .../sprints) and gives back an array of Sprint.
   *
   * Observable<Sprint[]>: an observable is a value that will arrive later. Nothing is sent
   * to the network while nobody subscribes to it.
   * Why an Observable and not a Promise: the component can cancel it when the user leaves
   * the screen, and the interceptor can replay it after renewing the token. A Promise can
   * do neither, so a slow answer would keep writing into a screen that is already gone.
   */
  listSprints(projectId: number): Observable<Sprint[]> {
    // The <Sprint[]> between the angle brackets is a generic: it tells TypeScript what the
    // JSON body will look like, so the component gets a typed array.
    // Careful, it is only a compile-time promise - nothing checks the real answer at run
    // time. It is safe here because the shape is fixed by SprintResponse on the server.
    return this.http.get<Sprint[]>(`${this.base(projectId)}/sprints`);
  }

  /**
   * Creates one sprint (POST .../sprints) and gives back the row as the server saved it.
   *
   * Why we return the server answer instead of reusing the object we sent: the id, the
   * project code and any value normalised by the server only exist in that answer. A
   * component that displayed its own object would show a card with no id, and the next
   * click on it would build the URL .../sprints/undefined.
   */
  createSprint(projectId: number, body: SprintPayload): Observable<Sprint> {
    return this.http.post<Sprint>(`${this.base(projectId)}/sprints`, body);
  }

  /**
   * Replaces one sprint (PUT .../sprints/{id}) and gives back the updated row.
   *
   * Why PUT and not PATCH: SprintPayload carries the WHOLE form - name, goal, dates,
   * status. PUT means "here is the complete new version". Sending half of it with PUT
   * would erase the fields left out.
   */
  updateSprint(projectId: number, id: number, body: SprintPayload): Observable<Sprint> {
    return this.http.put<Sprint>(`${this.base(projectId)}/sprints/${id}`, body);
  }

  /**
   * Removes one sprint (DELETE .../sprints/{id}).
   *
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   * Why void and not Sprint: typing it Sprint would push the component to display a row
   * that no longer exists on the board.
   * Note: on the server this is a SOFT delete - the row stays in the table with
   * deleted = true, so the history of the project is not lost.
   */
  deleteSprint(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/sprints/${id}`);
  }

  // ── Backlog ────────────────────────────────────────────────────

  /**
   * Reads every backlog item of the project (GET .../backlog), both the cards sitting in a
   * sprint and the ones still in the product backlog (sprintId = null).
   *
   * Why one single call and not one call per column: the board has to show the three
   * columns at once. Three calls would make the columns appear one after the other, and a
   * card moved between two of them could be counted twice or not at all.
   */
  listBacklog(projectId: number): Observable<BacklogItem[]> {
    return this.http.get<BacklogItem[]>(`${this.base(projectId)}/backlog`);
  }

  /**
   * Creates one backlog item (POST .../backlog) and gives back the saved card.
   */
  createItem(projectId: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.post<BacklogItem>(`${this.base(projectId)}/backlog`, body);
  }

  /**
   * Replaces one backlog item (PUT .../backlog/{id}) with the full content of the edit
   * form: title, description, priority, estimate, status, sprint and assignee.
   */
  updateItem(projectId: number, id: number, body: BacklogItemPayload): Observable<BacklogItem> {
    return this.http.put<BacklogItem>(`${this.base(projectId)}/backlog/${id}`, body);
  }

  /**
   * Moves a card: it changes the column, and possibly the sprint. A dedicated request, so
   * that a move does not have to send back the title, the description and the estimate.
   *
   * Why a separate PATCH instead of reusing updateItem():
   *  - a drag and drop only knows two things, the new column and the new sprint. Rebuilding
   *    a complete payload from what the board holds in memory would send stale values: if
   *    a colleague had just changed the title, our move would silently overwrite it.
   *  - the server can then validate much less, and answer faster on a gesture the user
   *    repeats all day long.
   *
   * sprintId is "number | null" on purpose. null is a real value here: it means "put the
   * card back in the product backlog, outside of every sprint". Without that null, a card
   * pulled out of an iteration could never leave it, and it would keep being counted in a
   * sprint that no longer contains it.
   */
  moveItem(projectId: number, id: number,
           status: BacklogItemStatus, sprintId: number | null): Observable<BacklogItem> {
    // The body { status, sprintId } is the short form of { status: status, sprintId: sprintId }.
    // It matches BacklogItemMoveRequest on the server, where status must not be null while
    // sprintId is allowed to be null.
    return this.http.patch<BacklogItem>(`${this.base(projectId)}/backlog/${id}/move`, { status, sprintId });
  }

  /**
   * Removes one backlog item (DELETE .../backlog/{id}), answered 204 with no body.
   * Like the sprint above, the server only marks the row deleted instead of erasing it.
   */
  deleteItem(projectId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(projectId)}/backlog/${id}`);
  }
}
