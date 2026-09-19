import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap, filter, take, switchMap } from 'rxjs/operators';
import { Observable, BehaviorSubject } from 'rxjs';
import { AuthResponse, LoginRequest, UserContext } from '../models/auth.model';
import { environment } from '../../../environments/environment';

/**
 * WHAT THIS FILE IS
 * The memory of the logged-in user on the browser side. It performs the login, the logout
 * and the password change, it keeps who the user is and what he is allowed to do, and it
 * owns the one and only renewal of the access token.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it:
 *   - features/auth/login/login.component.ts          -> login()
 *   - features/auth/change-password/...component.ts   -> changePassword()
 *   - core/interceptors/auth.interceptor.ts           -> getAccessToken(),
 *                                                        handleTokenRefresh(), expireSession()
 *   - core/guards/auth.guard.ts                       -> isLoggedIn()
 *   - core/guards/permission.guard.ts                 -> hasPermission()
 *   - many screens, to show or hide a button          -> hasPermission(), context()
 * What it calls: HttpClient on /api/auth/*, and Router to send the user to /login.
 *
 * WHY IT EXISTS
 * Delete it and three things break at once. Nobody could log in. The interceptor would
 * have no token to put on the requests and no way to renew it, so the user would be thrown
 * out as soon as the short-lived token dies. And every screen would have to read
 * localStorage itself to decide whether to show a button, which means the same fragile
 * code copied everywhere.
 *
 * THE TWO TOKENS (the jury always asks)
 *  - The ACCESS token is a short-lived JWT. A JWT is a signed text that carries who you
 *    are; the server can check the signature without reading the database. It is kept in
 *    localStorage and copied into the Authorization header of each request.
 *  - The REFRESH token never appears in this file. It lives in an HttpOnly cookie, which
 *    means JavaScript cannot read it - that is exactly why it is safe there. The browser
 *    attaches it by itself, which is what withCredentials: true asks for below. On every
 *    refresh the server hands back a NEW refresh cookie (rotation), and it keeps a
 *    tokenVersion counter per user, so raising that counter kills every open session of
 *    that user at once.
 *
 * WHAT IS *NOT* SECURITY HERE
 * permissions(), hasPermission() and the guards only decide what is DRAWN on the screen.
 * Anybody can change them with the browser developer tools. The real refusal happens on
 * the server, with @PreAuthorize("hasAuthority('X')") on the service methods, plus
 * ProjectScopeInterceptor for the /api/projects/{id}/** URLs, which checks the permission
 * AND the project scope (ADR-021). The front end never tests a role NAME, only permission
 * codes, so an administrator can invent a new role without any change in this code.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  // One single shared instance, so the whole application sees the same session. Two
  // instances would mean a screen still believing the user is logged in after a logout.
  private readonly API = environment.apiUrl;

  // A signal is a value that Angular watches: when it changes, every screen that reads it
  // is redrawn, with no manual subscription and no risk of a forgotten unsubscribe.
  // The underscore and the "private" keep the writing end inside this class.
  // Why a signal and not a plain field: with a plain field, the sidebar would keep showing
  // the old user name after a login until something else forced a redraw.
  private _context = signal<UserContext | null>(null);

  // asReadonly() hands out the same signal without its .set()/.update() methods.
  // Why: a component must be able to READ who is logged in, never to rewrite it. Without
  // this, a careless line in a screen could set a fake user, and every button would unlock.
  readonly context = this._context.asReadonly();
  // computed() derives a new signal from others and recalculates only when they change.
  // isLoggedIn is true as soon as a context exists.
  readonly isLoggedIn = computed(() => this._context() !== null);
  // The permission list of the logged-in user, or an empty array when nobody is logged in.
  // ?? [] is the "null coalescing" operator: it gives [] when the left side is null or
  // undefined. Without it, hasPermission() would call .includes() on undefined and the
  // whole page would crash on a logged-out visitor instead of simply hiding the buttons.
  readonly permissions = computed(() => this._context()?.permissions ?? []);

  // H-1: single-flight refresh state (shared with auth interceptor)
  // "Single flight" means: at most ONE /auth/refresh call travelling at any moment.
  // _isRefreshing says whether one is already on its way.
  private _isRefreshing = false;
  // A BehaviorSubject is a stream that also remembers its last value and replays it to
  // whoever subscribes later. Here it is the loudspeaker that tells the waiting callers
  // "the new token has arrived, here it is".
  // Why a BehaviorSubject and not a plain Subject: a caller that subscribes a millisecond
  // after the answer came back would hear nothing with a plain Subject, and its request
  // would hang for ever.
  private _refreshSubject = new BehaviorSubject<AuthResponse | null>(null);

  /**
   * Rebuilds the session from localStorage when the application starts.
   *
   * Why this matters: the user presses F5. Without this constructor the signal would be
   * null, every guard would refuse, and a simple page reload would look like a logout even
   * though the access token and the refresh cookie are both still valid.
   *
   * (This class receives its dependencies through the constructor. Other services of the
   * project use inject() instead - the two styles are equivalent, both give the instance
   * built by Angular, which is the one wired to the HTTP interceptor.)
   */
  constructor(private http: HttpClient, private router: Router) {
    // localStorage keeps text in the browser even after it is closed. 'pms_context' holds
    // the identity and the permissions of the last logged-in user, as JSON text.
    const stored = localStorage.getItem('pms_context');
    if (stored) {
      try {
        // JSON.parse turns that text back into an object. It THROWS when the text is not
        // valid JSON, which is why it sits inside a try.
        this._context.set(JSON.parse(stored));
      } catch {
        // M-7: corrupt localStorage must not brick app bootstrap
        // A half-written or hand-edited value would make JSON.parse throw inside the
        // constructor of a root service. Angular would fail to build the service, and the
        // application would show a blank white page - with no way for the user to get out,
        // because the bad value comes back at every reload. Dropping the key repairs it.
        localStorage.removeItem('pms_context');
      }
    }
  }

  /**
   * Sends the e-mail and the password to /api/auth/login and, if it works, opens the
   * session: token stored, identity and permissions stored, signal updated.
   *
   * Gives back the raw AuthResponse, because the login screen still needs res.firstLogin
   * to send a brand-new user to the change-password page (H-2).
   */
  login(req: LoginRequest): Observable<AuthResponse> {
    // withCredentials: true tells the browser to accept and keep the refresh COOKIE that
    // the server sets in this answer. It is required because the front end (port 4200) and
    // the API (port 8090) are two different origins.
    // Without it the cookie is silently dropped: login looks fine, then the first token
    // renewal fails and the user is kicked back to the login screen a few minutes later.
    return this.http.post<AuthResponse>(`${this.API}/auth/login`, req, { withCredentials: true }).pipe(
      // tap() runs a side effect while letting the value pass through untouched.
      // Why tap and not map: the login screen must still receive the full answer. With map
      // we would have to rebuild it, and firstLogin would be easy to lose on the way.
      tap(res => this.storeSession(res))
    );
  }

  logout(): void {
    // withCredentials ensures the pms_refresh cookie is sent so the server can clear it
    // The server needs that cookie to know WHICH refresh token to revoke. Without it the
    // refresh token would stay valid on the server side until it expires by itself, so a
    // stolen cookie would still open new sessions after the user pressed "log out".
    this.http.post(`${this.API}/auth/logout`, {}, { withCredentials: true }).subscribe({
      // We subscribe without waiting for the answer and we only log the failure. The local
      // session is cleared below whatever happens.
      // Why: if the server is unreachable, refusing to log out would leave the user's name
      // and permissions on a shared machine. Clearing locally is the safer of the two.
      error: err => console.warn('Révocation de session échouée (session effacée localement) :', err)
    });
    this.clearSession();
  }

  /**
   * Changes the password of the logged-in user. It is also the screen imposed on a first
   * login (H-2), when the account still carries its temporary password.
   *
   * There is no withCredentials here on purpose: this call is authorised by the access
   * token that the interceptor adds, not by the refresh cookie.
   * Note that the session is NOT cleared here. On the server a successful change raises
   * tokenVersion, which revokes the other sessions of the same user.
   */
  changePassword(current: string, next: string): Observable<void> {
    return this.http.post<void>(`${this.API}/auth/change-password`, {
      currentPassword: current,
      newPassword: next
    });
  }

  /**
   * H-1: single-flight refresh — at most one /auth/refresh call in flight.
   * Concurrent callers subscribe to the same BehaviorSubject and retry once it emits.
   *
   * WHO CALLS IT: only auth.interceptor.ts, when a request came back 401.
   *
   * WHY THE COMPLICATED PART IS NEEDED: one dashboard fires six requests at the same
   * moment. If the access token has just died, the six answers come back 401 together.
   * Six parallel refresh calls would each rotate the refresh cookie, so five of them would
   * end up presenting a cookie the server has already replaced - and the user would be
   * logged out in the middle of a screen that was loading perfectly well.
   */
  handleTokenRefresh(): Observable<AuthResponse> {
    if (this._isRefreshing) {
      // Another refresh is already in flight — wait for its result
      return this._refreshSubject.pipe(
        // filter lets a value through only when the test is true. The subject starts at
        // null and is reset to null at the beginning of each refresh, so null means
        // "no answer yet".
        // Without this filter the waiting callers would immediately receive null and would
        // replay their request with "Bearer null", getting a second 401 at once.
        filter(res => res !== null),
        // take(1) reads the first real value, then closes the subscription by itself.
        // Without it these callers would stay subscribed for the whole life of the
        // application and would replay their old request at every later refresh.
        take(1)
      ) as Observable<AuthResponse>;
      // The "as Observable<AuthResponse>" is a cast: filter() removes the nulls at run
      // time, but TypeScript still types the stream as "AuthResponse | null". The cast only
      // states what the filter above already guarantees; it changes nothing at run time.
    }

    // From here on, this call is THE refresh call. The flag is raised before the request
    // leaves, so any 401 arriving one millisecond later takes the waiting branch above.
    this._isRefreshing = true;
    // Reset the loudspeaker to null, so a caller that arrives now does not receive the
    // token of the PREVIOUS refresh - the one that has just expired - and replay its
    // request with a dead token.
    this._refreshSubject.next(null);

    // The body is empty on purpose: the proof of identity is the HttpOnly cookie, which
    // withCredentials tells the browser to attach. JavaScript cannot read it, so it cannot
    // put it in the body even if it wanted to.
    return this.http.post<AuthResponse>(`${this.API}/auth/refresh`, {}, { withCredentials: true }).pipe(
      tap(res => {
        // Lower the flag FIRST, so a later 401 can start a fresh refresh instead of
        // waiting on a subject that will never speak again.
        this._isRefreshing = false;
        // Save the new access token and the refreshed identity and permissions.
        this.storeSession(res);
        // Wake up every caller that was waiting in the branch above.
        this._refreshSubject.next(res);
      })
    );
  }

  /** The access token, or null when nobody is logged in. Read by the HTTP interceptor. */
  getAccessToken(): string | null {
    return localStorage.getItem('pms_access');
  }

  /**
   * Says whether the logged-in user holds one permission code, for example 'MANAGE_DI'.
   * Used by permission.guard.ts and by the screens to hide a button.
   *
   * Reminder: this only hides things. A user who forces the button anyway is refused by
   * @PreAuthorize on the server. Hiding is comfort, refusing is security.
   */
  hasPermission(perm: string): boolean {
    return this.permissions().includes(perm);
  }

  /**
   * The id of the logged-in user, or null.
   * Written as a getter so that screens can read "auth.currentUserId" like a field.
   * The ?. and the ?? null give null instead of crashing when nobody is logged in.
   */
  get currentUserId(): number | null {
    return this._context()?.userId ?? null;
  }

  /**
   * Ends the session because the SERVER refused to renew it: no cookie, expired cookie, or
   * a tokenVersion bump that revoked every session. Called by the HTTP interceptor.
   *
   * The only difference with clearSession() is the query parameter: the login screen reads
   * ?expired=true and shows "your session has expired" instead of an empty form. Without
   * it the user would land on a blank login page with no idea why, and would believe the
   * application had crashed.
   */
  expireSession(): void {
    // Lower the single-flight flag, otherwise the next 401 after a new login would wait
    // for a refresh that nobody will ever start.
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login'], { queryParams: { expired: 'true' } });
  }

  /**
   * Writes the session down after a successful login or refresh: the token in one key, the
   * identity plus the permissions in another, and the signal so the screens redraw.
   *
   * Private, because only login() and handleTokenRefresh() may open a session. A screen
   * able to call it could grant itself any permission it wanted in the menus.
   */
  private storeSession(res: AuthResponse): void {
    localStorage.setItem('pms_access', res.accessToken);
    const ctx: UserContext = {
      userId: res.userId,
      email: res.email,
      fullName: res.fullName,
      // The server sends ONE role name, the model keeps an array. Wrapping it keeps the
      // shape open for a user holding several roles later, with no change in the screens.
      // The role name is only ever displayed - no test in the code reads it to decide what
      // is allowed; that is the job of the permission codes below.
      roles: [res.role],
      // Array.isArray() guards against an answer where permissions is missing or is not an
      // array. Without it a malformed answer would put undefined in the context, and the
      // first hasPermission() call would crash every screen that hides a button.
      permissions: Array.isArray(res.permissions) ? res.permissions : []
    };
    // JSON.stringify turns the object into text, because localStorage only stores text.
    localStorage.setItem('pms_context', JSON.stringify(ctx));
    this._context.set(ctx);
  }

  /**
   * Ends the session because the USER asked for it. Same cleaning as expireSession(), but
   * it lands on a plain /login with no "expired" message, which would be confusing after a
   * deliberate logout.
   */
  private clearSession(): void {
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login']);
  }
}
