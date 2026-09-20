import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

// The app's single HTTP interceptor: attaches the access token to every outgoing request,
// and reacts to 401 (expired token, silently refreshed and replayed) and the first-login
// 403 of H-2. Registered once in app.config.ts via withInterceptors([authInterceptor]).
// Comfort only, not security: the server enforces access via @PreAuthorize +
// ProjectScopeInterceptor (ADR-021). The access token is a short-lived JWT in localStorage;
// the refresh token lives in an HttpOnly cookie the browser attaches on its own, so this
// file never touches it directly - only AuthService.handleTokenRefresh() does.

/**
 * Runs around every HTTP call. A plain function (HttpInterceptorFn), not a class, since this
 * app is fully standalone with no NgModule. No retry counter is needed: the replayed request
 * goes further down the chain via next(retried), never back into this function, so one
 * request can trigger at most one refresh.
 */
export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // null before first login, or right after expireSession() wiped it.
  const token = auth.getAccessToken();
  // Only clone with a header when a token exists; sending "Bearer null" would break /auth/login.
  const authReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    catchError((err: HttpErrorResponse) => {
      // H-2: FirstLoginFilter answers 403/FIRST_LOGIN_REQUIRED for a temp-password account
      // on any URL but change-password. Optional chaining guards a body-less 403.
      if (err.status === 403 && err.error?.code === 'FIRST_LOGIN_REQUIRED') {
        router.navigate(['/change-password']);
        return throwError(() => err);
      }

      // Avoid an endless loop: a 401 from /auth/refresh or /auth/logout must not trigger
      // another refresh attempt.
      const isAuthEndpoint = req.url.includes('/auth/refresh') || req.url.includes('/auth/logout');
      if (err.status === 401 && !isAuthEndpoint) {
        // H-1: handleTokenRefresh() single-flights concurrent 401s onto one /auth/refresh
        // call, so several requests dying at once don't each rotate the refresh cookie and
        // kick each other out.
        return auth.handleTokenRefresh().pipe(
          switchMap(res => {
            // Re-clone from the original req (not authReq) so the replay carries only the
            // fresh token, never the dead one.
            const retried = req.clone({ setHeaders: { Authorization: `Bearer ${res.accessToken}` } });
            return next(retried);
          }),
          // The refresh itself failed (no/expired cookie, or a revoked tokenVersion): the
          // session can't be saved.
          catchError(() => {
            // Wipes the session and routes to /login?expired=true, which the login screen
            // reads to show a clear message.
            auth.expireSession();
            // Rethrow the original 401, not the refresh error - it's what the caller asked for.
            return throwError(() => err);
          })
        );
      }
      // Anything else is none of this file's business; pass it through untouched.
      return throwError(() => err);
    })
  );
};
