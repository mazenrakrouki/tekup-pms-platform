import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap, filter, take, switchMap } from 'rxjs/operators';
import { Observable, BehaviorSubject } from 'rxjs';
import { AuthResponse, LoginRequest, UserContext } from '../models/auth.model';
import { environment } from '../../../environments/environment';

// Browser-side memory of the logged-in user: login/logout/password change, who they are and
// what they can do, and the sole owner of access-token renewal. The access token (short-lived
// JWT) lives in localStorage; the refresh token lives in an HttpOnly cookie this file never
// touches directly (withCredentials lets the browser attach it). permissions()/hasPermission()
// only control what's DRAWN — the real refusal is server-side @PreAuthorize + ProjectScopeInterceptor.
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = environment.apiUrl;

  // Signal, not a plain field: screens (e.g. the sidebar) redraw automatically on change.
  private _context = signal<UserContext | null>(null);

  // Read-only outward: only this service may open/close a session.
  readonly context = this._context.asReadonly();
  readonly isLoggedIn = computed(() => this._context() !== null);
  readonly permissions = computed(() => this._context()?.permissions ?? []);

  // H-1: single-flight refresh (shared with auth.interceptor.ts) — at most one /auth/refresh
  // call in flight; _isRefreshing flags it, _refreshSubject wakes waiting callers when it lands.
  private _isRefreshing = false;
  // BehaviorSubject, not Subject: a caller subscribing after the answer already arrived must
  // still receive it, or its request would hang forever.
  private _refreshSubject = new BehaviorSubject<AuthResponse | null>(null);

  // Rebuilds the session from localStorage on app start, so an F5 doesn't look like a logout.
  constructor(private http: HttpClient, private router: Router) {
    const stored = localStorage.getItem('pms_context');
    if (stored) {
      try {
        this._context.set(JSON.parse(stored));
      } catch {
        // M-7: corrupt localStorage must not brick bootstrap — drop it instead of throwing
        // inside a root service constructor (which would blank-page the whole app forever).
        localStorage.removeItem('pms_context');
      }
    }
  }

  /** POSTs to /api/auth/login and opens the session on success. Returns the raw AuthResponse
   *  since the login screen needs res.firstLogin to route to change-password (H-2). */
  login(req: LoginRequest): Observable<AuthResponse> {
    // withCredentials so the browser keeps the cross-origin refresh cookie the server sets —
    // without it, login looks fine but the first token renewal fails a few minutes later.
    return this.http.post<AuthResponse>(`${this.API}/auth/login`, req, { withCredentials: true }).pipe(
      tap(res => this.storeSession(res))
    );
  }

  logout(): void {
    // withCredentials sends the refresh cookie so the server knows which token to revoke.
    this.http.post(`${this.API}/auth/logout`, {}, { withCredentials: true }).subscribe({
      // Don't block local logout on this: on a shared machine, clearing locally is safer
      // than leaving the session up because the server was unreachable.
      error: err => console.warn('Révocation de session échouée (session effacée localement) :', err)
    });
    this.clearSession();
  }

  /** Changes the logged-in user's password; also the screen forced on first login (H-2).
   *  No withCredentials: authorised by the access token, not the refresh cookie. Session is
   *  NOT cleared here — the server bumps tokenVersion instead, revoking other sessions. */
  changePassword(current: string, next: string): Observable<void> {
    return this.http.post<void>(`${this.API}/auth/change-password`, {
      currentPassword: current,
      newPassword: next
    });
  }

  /** H-1: single-flight refresh, called only by auth.interceptor.ts on a 401. Without it, six
   *  requests dying together would fire six parallel refreshes that each rotate the refresh
   *  cookie, logging the user out mid-screen when the stale ones get rejected. */
  handleTokenRefresh(): Observable<AuthResponse> {
    if (this._isRefreshing) {
      // Wait for the in-flight refresh: skip the reset-to-null value, take the real answer once.
      return this._refreshSubject.pipe(
        filter(res => res !== null),
        take(1)
      ) as Observable<AuthResponse>;
    }

    // This call becomes THE refresh call; flag raised before the request leaves so a 401
    // arriving a moment later takes the waiting branch above instead of starting a second one.
    this._isRefreshing = true;
    // Reset to null first, so a caller arriving now doesn't get the PREVIOUS (now-dead) token.
    this._refreshSubject.next(null);

    // Empty body: identity comes from the HttpOnly refresh cookie, which JS can't read anyway.
    return this.http.post<AuthResponse>(`${this.API}/auth/refresh`, {}, { withCredentials: true }).pipe(
      tap(res => {
        // Lower the flag first, so a later 401 starts a fresh refresh instead of waiting forever.
        this._isRefreshing = false;
        this.storeSession(res);
        this._refreshSubject.next(res);
      })
    );
  }

  /** The access token, or null when nobody is logged in. Read by the HTTP interceptor. */
  getAccessToken(): string | null {
    return localStorage.getItem('pms_access');
  }

  /** Whether the logged-in user holds a permission code (e.g. 'MANAGE_DI'). Hides UI only —
   *  the server's @PreAuthorize is what actually refuses a forced request. */
  hasPermission(perm: string): boolean {
    return this.permissions().includes(perm);
  }

  /** Getter so screens can read "auth.currentUserId" like a field; null when logged out. */
  get currentUserId(): number | null {
    return this._context()?.userId ?? null;
  }

  /** Ends the session because the SERVER refused to renew it (no/expired cookie, or a
   *  tokenVersion bump). Unlike clearSession(), routes with ?expired=true so the login
   *  screen explains why, instead of looking like a crash. */
  expireSession(): void {
    // Lower the single-flight flag, or the next 401 after a new login would wait forever.
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login'], { queryParams: { expired: 'true' } });
  }

  /** Persists the session after login/refresh. Private: only login() and
   *  handleTokenRefresh() may open one — a public setter could let a screen grant itself
   *  permissions. */
  private storeSession(res: AuthResponse): void {
    localStorage.setItem('pms_access', res.accessToken);
    const ctx: UserContext = {
      userId: res.userId,
      email: res.email,
      fullName: res.fullName,
      // Server sends one role name; kept as an array here for a future multi-role user,
      // with no screen change needed. Display-only — permissions decide access, not this.
      roles: [res.role],
      // Array.isArray guards a malformed answer, so a missing/bad field can't crash every
      // hasPermission() caller with undefined instead of just showing no permissions.
      permissions: Array.isArray(res.permissions) ? res.permissions : []
    };
    localStorage.setItem('pms_context', JSON.stringify(ctx));
    this._context.set(ctx);
  }

  /** Ends the session because the USER asked for it — same cleanup as expireSession() but
   *  no "expired" message, since a deliberate logout doesn't need explaining. */
  private clearSession(): void {
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login']);
  }
}
