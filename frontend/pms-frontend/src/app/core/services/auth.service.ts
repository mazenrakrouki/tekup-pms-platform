import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap, filter, take, switchMap } from 'rxjs/operators';
import { Observable, BehaviorSubject } from 'rxjs';
import { AuthResponse, LoginRequest, UserContext } from '../models/auth.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = environment.apiUrl;

  private _context = signal<UserContext | null>(null);

  readonly context = this._context.asReadonly();
  readonly isLoggedIn = computed(() => this._context() !== null);
  readonly permissions = computed(() => this._context()?.permissions ?? []);

  // H-1: single-flight refresh state (shared with auth interceptor)
  private _isRefreshing = false;
  private _refreshSubject = new BehaviorSubject<AuthResponse | null>(null);

  constructor(private http: HttpClient, private router: Router) {
    const stored = localStorage.getItem('pms_context');
    if (stored) {
      try {
        this._context.set(JSON.parse(stored));
      } catch {
        // M-7: corrupt localStorage must not brick app bootstrap
        localStorage.removeItem('pms_context');
      }
    }
  }

  login(req: LoginRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.API}/auth/login`, req, { withCredentials: true }).pipe(
      tap(res => this.storeSession(res))
    );
  }

  logout(): void {
    // withCredentials ensures the pms_refresh cookie is sent so the server can clear it
    this.http.post(`${this.API}/auth/logout`, {}, { withCredentials: true }).subscribe({
      error: err => console.warn('Révocation de session échouée (session effacée localement) :', err)
    });
    this.clearSession();
  }

  changePassword(current: string, next: string): Observable<void> {
    return this.http.post<void>(`${this.API}/auth/change-password`, {
      currentPassword: current,
      newPassword: next
    });
  }

  /**
   * H-1: single-flight refresh — at most one /auth/refresh call in flight.
   * Concurrent callers subscribe to the same BehaviorSubject and retry once it emits.
   */
  handleTokenRefresh(): Observable<AuthResponse> {
    if (this._isRefreshing) {
      // Another refresh is already in flight — wait for its result
      return this._refreshSubject.pipe(
        filter(res => res !== null),
        take(1)
      ) as Observable<AuthResponse>;
    }

    this._isRefreshing = true;
    this._refreshSubject.next(null);

    return this.http.post<AuthResponse>(`${this.API}/auth/refresh`, {}, { withCredentials: true }).pipe(
      tap(res => {
        this._isRefreshing = false;
        this.storeSession(res);
        this._refreshSubject.next(res);
      })
    );
  }

  getAccessToken(): string | null {
    return localStorage.getItem('pms_access');
  }

  hasPermission(perm: string): boolean {
    return this.permissions().includes(perm);
  }

  get currentUserId(): number | null {
    return this._context()?.userId ?? null;
  }

  expireSession(): void {
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login'], { queryParams: { expired: 'true' } });
  }

  private storeSession(res: AuthResponse): void {
    localStorage.setItem('pms_access', res.accessToken);
    const ctx: UserContext = {
      userId: res.userId,
      email: res.email,
      fullName: res.fullName,
      roles: [res.role],
      permissions: Array.isArray(res.permissions) ? res.permissions : []
    };
    localStorage.setItem('pms_context', JSON.stringify(ctx));
    this._context.set(ctx);
  }

  private clearSession(): void {
    this._isRefreshing = false;
    this._refreshSubject.next(null);
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login']);
  }
}
