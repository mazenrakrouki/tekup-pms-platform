import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap } from 'rxjs/operators';
import { Observable } from 'rxjs';
import { AuthResponse, LoginRequest, UserContext } from '../models/auth.model';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = environment.apiUrl;

  private _context = signal<UserContext | null>(null);

  readonly context = this._context.asReadonly();
  readonly isLoggedIn = computed(() => this._context() !== null);
  readonly permissions = computed(() => this._context()?.permissions ?? []);

  constructor(private http: HttpClient, private router: Router) {
    const stored = localStorage.getItem('pms_context');
    if (stored) this._context.set(JSON.parse(stored));
  }

  login(req: LoginRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.API}/auth/login`, req).pipe(
      tap(res => {
        localStorage.setItem('pms_access', res.accessToken);
        localStorage.setItem('pms_refresh', res.refreshToken);
        const ctx: UserContext = {
          userId: res.userId,
          email: res.email,
          fullName: res.fullName,
          roles: [res.role],
          permissions: Array.isArray(res.permissions) ? res.permissions : []
        };
        localStorage.setItem('pms_context', JSON.stringify(ctx));
        this._context.set(ctx);
      })
    );
  }

  logout(): void {
    this.http.post(`${this.API}/auth/logout`, {}).subscribe();
    this.clearSession();
  }

  changePassword(current: string, next: string): Observable<void> {
    return this.http.post<void>(`${this.API}/auth/change-password`, {
      currentPassword: current,
      newPassword: next
    });
  }

  refreshToken(): Observable<AuthResponse> {
    const refreshToken = localStorage.getItem('pms_refresh');
    return this.http.post<AuthResponse>(`${this.API}/auth/refresh`, { refreshToken }).pipe(
      tap(res => {
        localStorage.setItem('pms_access', res.accessToken);
        localStorage.setItem('pms_refresh', res.refreshToken);
        const ctx: UserContext = {
          userId: res.userId,
          email: res.email,
          fullName: res.fullName,
          roles: [res.role],
          permissions: Array.isArray(res.permissions) ? res.permissions : []
        };
        localStorage.setItem('pms_context', JSON.stringify(ctx));
        this._context.set(ctx);
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
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_refresh');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login'], { queryParams: { expired: 'true' } });
  }

  private clearSession(): void {
    localStorage.removeItem('pms_access');
    localStorage.removeItem('pms_refresh');
    localStorage.removeItem('pms_context');
    this._context.set(null);
    this.router.navigate(['/login']);
  }
}
