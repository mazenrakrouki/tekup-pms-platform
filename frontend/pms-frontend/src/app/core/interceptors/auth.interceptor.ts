import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const token = auth.getAccessToken();
  const authReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    catchError((err: HttpErrorResponse) => {
      // H-2: server-side first-login gate returned 403 with FIRST_LOGIN_REQUIRED
      if (err.status === 403 && err.error?.code === 'FIRST_LOGIN_REQUIRED') {
        router.navigate(['/change-password']);
        return throwError(() => err);
      }

      const isAuthEndpoint = req.url.includes('/auth/refresh') || req.url.includes('/auth/logout');
      if (err.status === 401 && !isAuthEndpoint) {
        // H-1: handleTokenRefresh() ensures only one /auth/refresh call goes out at a time.
        // Concurrent 401 callers subscribe to the shared BehaviorSubject and all get
        // the same refreshed token without firing duplicate refresh requests.
        return auth.handleTokenRefresh().pipe(
          switchMap(res => {
            const retried = req.clone({ setHeaders: { Authorization: `Bearer ${res.accessToken}` } });
            return next(retried);
          }),
          catchError(() => {
            auth.expireSession();
            return throwError(() => err);
          })
        );
      }
      return throwError(() => err);
    })
  );
};
