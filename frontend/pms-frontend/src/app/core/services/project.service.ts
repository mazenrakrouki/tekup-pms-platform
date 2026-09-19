import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Project, ProjectRequest } from '../models/project.model';
import { PagedResponse } from '../models/pagination.model';
import { KpiResponse, SnapshotRequest } from '../models/kpi.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The HTTP client of the projects module, the busiest service of the front end. It knows
 * the addresses of the project list and of one project, of the archive and status moves, of
 * the KPI of a project and of its monthly snapshots. It draws no screen and keeps no state.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : almost every screen - projects (list, form, detail), dashboard, kpi,
 *                agile, billing, di, governance, missions and workload all need the list of
 *                projects to let the user pick one.
 * What it calls: HttpClient -> core/interceptors/auth.interceptor.ts (Authorization header
 *                and token renewal) -> Spring Boot ProjectController, KpiController and
 *                UserController -> ProjectService, KpiService and UserCrudService on the
 *                server, which carry the real checks.
 * The typed answers come from core/models/project.model.ts, core/models/kpi.model.ts and
 * core/models/pagination.model.ts.
 *
 * WHY IT EXISTS
 * Twelve screens ask for projects. Without this file each of them would write the URL, the
 * paging parameters and the sort by hand, and the day the server changes the default sort
 * the twelve would disagree. It also gives every screen the same typed answer, so a
 * misspelled field is caught while compiling, not by the user.
 *
 * SECURITY - THE PERMISSIONS BEHIND THESE CALLS
 * Nothing here protects anything; the checks sit on the SERVICE methods of the server:
 *   read (list, get, archived, kpi, snapshots)  VIEW_PROJECT, and VIEW_KPI for the KPI;
 *   create                                      CREATE_PROJECT;
 *   update, archive, unarchive, changeStatus,
 *   createSnapshot                              EDIT_PROJECT;
 *   delete                                      DELETE_PROJECT;
 *   assignChef                                  ASSIGN_CHEF_PROJET.
 * And ADR-021 adds the scope on top: every URL with a number in it
 * (/api/projects/{id}/...) goes through ProjectScopeInterceptor, which refuses a project
 * outside the perimeter of the caller. Holding VIEW_PROJECT says "may read projects", it
 * does not say WHICH ones. The list endpoints have no id in the address, so they cannot be
 * filtered by the interceptor; ProjectService on the server filters them itself with the
 * same rule.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance for the whole application. The class holds
// no state, so a second instance would be pure waste.
@Injectable({ providedIn: 'root' })
export class ProjectService {
  // The base address, built once from the environment file so that the development build
  // talks to localhost and the production build to the real server, with no code change.
  // readonly stops any code from pointing this service at another server by mistake.
  private readonly API = `${environment.apiUrl}/projects`;

  // Constructor injection of the shared HttpClient.
  // Why not "new HttpClient()": we would lose the interceptor chain, so requests would leave
  // without the Authorization header and the server would answer 401 every time.
  constructor(private http: HttpClient) {}

  /**
   * Reads ONE page of projects (GET /api/projects?page=&size=&sort=).
   * Gives back the Spring envelope: the rows in 'content', plus the counters the pager needs.
   *
   * The three default values (page 0, size 20, sort 'code,asc') mean a caller who just wants
   * "the first screen of projects" writes list() with no argument.
   * Why paging at all: a company can hold hundreds of projects. Sending them all would make
   * the first screen slow and would grow slower every year, for rows nobody looks at.
   */
  list(page = 0, size = 20, sort = 'code,asc'): Observable<PagedResponse<Project>> {
    // HttpParams builds the query string "?page=0&size=20&sort=code,asc" and escapes the
    // values for us. It is immutable: each .set() returns a NEW object, which is why the
    // calls are chained and the result is kept.
    // Why not paste the values into the URL by hand: a value containing a space or a comma
    // would break the address, and a sort typed by a user could inject anything into it.
    const params = new HttpParams()
      .set('page', page).set('size', size).set('sort', sort);
    // 'code,asc' is the Spring format: field name, then the direction. The server turns it
    // into a Pageable, so the sorting is done by the database and not on twenty rows that
    // are only a part of the result.
    return this.http.get<PagedResponse<Project>>(this.API, { params });
  }

  /**
   * Fetches all the active projects in ONE call, for the pickers and the selectors.
   * Gives back a plain array, not the paged envelope.
   *
   * Why size = 1000 and not real paging: the pickers need to search among every project, and
   * a dropdown cannot page. One big page is the simple way to get everything from an endpoint
   * that only speaks pages. The limit is a known one: a company holding more than 1000 live
   * projects would not see the last ones here.
   * Note the project rule about screens: we do not put a giant list in a raw dropdown; the
   * shared <app-project-picker> uses this data with a search box.
   */
  listAll(): Observable<Project[]> {
    const params = new HttpParams().set('page', 0).set('size', 1000).set('sort', 'code,asc');
    // .pipe(map(...)) transforms the answer as it passes by: the envelope arrives, only
    // `content` goes out. The request itself is untouched.
    // Why here and not in each component: without it every picker would have to remember
    // that this call answers an envelope, and one of them would read res as an array, get
    // undefined, and show an empty list with no error anywhere.
    return this.http.get<PagedResponse<Project>>(this.API, { params }).pipe(map(p => p.content));
  }

  /**
   * Reads the archived projects (GET /api/projects/archived). A plain array, because an
   * archive screen is short and needs no pager.
   *
   * Why a separate address instead of a filter on the list: the normal list must NEVER show
   * archived projects. A filter defaulting to "active" is one forgotten parameter away from
   * showing three-year-old projects inside the pickers of every screen.
   */
  listArchived(): Observable<Project[]> {
    return this.http.get<Project[]>(`${this.API}/archived`);
  }

  /**
   * Archives one project (PATCH /api/projects/{id}/archive) and gives back the updated row.
   *
   * Archiving is not deleting: the project leaves the daily lists and keeps all its data,
   * its KPI history and its quote. Why it matters: a closed project is still needed for the
   * yearly figures, so removing the row would destroy the history of the company.
   * The second argument {} is an empty body. PATCH needs one and there is nothing to send -
   * the address already says everything. Sending null instead would make Angular drop the
   * Content-Type header and some servers answer 415.
   */
  archive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/archive`, {});
  }

  /**
   * Brings an archived project back into the active lists
   * (PATCH /api/projects/{id}/unarchive).
   */
  unarchive(id: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/unarchive`, {});
  }

  /**
   * Reads one project by its id (GET /api/projects/{id}).
   * The server answers 404 when the id does not exist, and 403 when the project is outside
   * the perimeter of the caller (ADR-021) - two different answers on purpose, so the screen
   * can say "not found" or "not allowed" instead of one vague error.
   */
  get(id: number): Observable<Project> {
    return this.http.get<Project>(`${this.API}/${id}`);
  }

  /**
   * Creates a project (POST /api/projects) and gives back the row as the server saved it.
   *
   * Why we return the server answer instead of reusing the object we sent: the id and the
   * computed fields only exist in that answer. A screen displaying its own object would show
   * a project with no id, and the next click would build the URL /api/projects/undefined.
   */
  create(req: ProjectRequest): Observable<Project> {
    return this.http.post<Project>(this.API, req);
  }

  /**
   * Replaces one project (PUT /api/projects/{id}) with the full content of the form.
   *
   * Why PUT and not PATCH: ProjectRequest carries the whole identification sheet. PUT means
   * "here is the complete new version". Sending half of it with PUT would erase the fields
   * left out.
   */
  update(id: number, req: ProjectRequest): Observable<Project> {
    return this.http.put<Project>(`${this.API}/${id}`, req);
  }

  /**
   * Removes one project (DELETE /api/projects/{id}).
   *
   * Observable<void>: the server answers 204 No Content, so there is no body to read.
   * This is the rarest call of the file - the normal way to close a project is archive()
   * above, which keeps the data.
   */
  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.API}/${id}`);
  }

  /**
   * Reads the KPI of a project as they stand TODAY (GET /api/projects/{id}/kpi).
   *
   * "Live" is the important word: every figure in the answer is computed by the server when
   * it is asked for, from the workload, the billing and the internal quote. Nothing is read
   * from a column of saved indicators.
   * Why: a stored indicator starts to lie the moment somebody adds a line of workload
   * without recomputing it, and two screens would then show two different margins for the
   * same project.
   */
  getLiveKpi(id: number): Observable<KpiResponse> {
    return this.http.get<KpiResponse>(`${this.API}/${id}/kpi`);
  }

  /**
   * Reads the saved snapshots of a project (GET /api/projects/{id}/kpi/snapshots): the KPI
   * as they were frozen at each monthly review, oldest to newest.
   *
   * Why keep them although the live KPI can always be recomputed: the live figures change
   * every day, so they cannot tell where the project stood last March. The snapshots are the
   * memory that makes a trend possible.
   */
  getSnapshots(id: number): Observable<KpiResponse[]> {
    return this.http.get<KpiResponse[]>(`${this.API}/${id}/kpi/snapshots`);
  }

  /**
   * Monthly review: freezes today's KPI together with the EV % typed by the project manager
   * (POST /api/projects/{id}/kpi/snapshots). Gives back the snapshot that was written.
   *
   * SnapshotRequest carries very little: evPct, the estimated end date and the highlights.
   * Everything else in the snapshot is computed by the server at that moment.
   * Why the browser only sends evPct: EV (Earned Value, the share of the work really done,
   * 0 to 100) is a human judgement - only the project manager can say "we are at 60 %". The
   * money figures are not a judgement, and letting the browser send them would let a
   * modified request write a margin that matches no line of the project (F-AFF-13).
   */
  createSnapshot(id: number, req: SnapshotRequest): Observable<KpiResponse> {
    return this.http.post<KpiResponse>(`${this.API}/${id}/kpi/snapshots`, req);
  }

  /**
   * Puts a project manager on a project (PATCH /api/projects/{id}/assign-chef?userId=...).
   *
   * This call needs its own permission on the server, ASSIGN_CHEF_PROJET, which is NOT
   * EDIT_PROJECT. Why: choosing who leads a project is a management decision, while editing
   * the sheet of a project is day-to-day work. Someone may be allowed to correct a budget
   * line and still not be allowed to change the manager.
   * Here userId travels in the query string and not in the body. Careful, this is the older
   * style of the two: it works because a user id is a plain number and nothing secret, but a
   * value in a URL ends up in the server logs, so it must never be used for personal data.
   */
  assignChef(id: number, userId: number): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/assign-chef?userId=${userId}`, {});
  }

  /**
   * Lists the users who can be put on a project, as manager or as team member
   * (GET /api/users/assignable).
   *
   * Note the address: /api/users, NOT /api/projects. It is here only because the screens
   * that need it are the project screens.
   * The answer type is written inline (id, first name, last name, role name) instead of
   * reusing the full user model, because that is all these dropdowns need. A lighter shape
   * also means the endpoint never has to send the e-mail or the account state of a colleague
   * to a screen that just wants a name in a list.
   * On the server this endpoint accepts ASSIGN_CHEF_PROJET, ASSIGN_DEVELOPER or MANAGE_USERS.
   * Why it is not reserved to MANAGE_USERS: a project manager must be able to build his team
   * without being an administrator of the accounts of the company.
   */
  listAssignableUsers(): Observable<{ id: number; firstName: string; lastName: string; roleName: string }[]> {
    return this.http.get<{ id: number; firstName: string; lastName: string; roleName: string }[]>(`${environment.apiUrl}/users/assignable`);
  }

  /**
   * Changes the state of a project (PATCH /api/projects/{id}/status?status=...), for example
   * from ACTIVE to COMPLETED.
   *
   * status is a plain string here and the server turns it into its own enum, so a word
   * outside the list is answered 400 and never written into the table.
   * Why a dedicated address rather than sending the status through update(): the allowed
   * moves between states are a business rule and they belong to the server. Going through
   * the full update would also mean sending the whole sheet back, so a stale form in the
   * browser could quietly overwrite a budget that a colleague had just corrected.
   */
  changeStatus(id: number, status: string): Observable<Project> {
    return this.http.patch<Project>(`${this.API}/${id}/status?status=${status}`, {});
  }
}
