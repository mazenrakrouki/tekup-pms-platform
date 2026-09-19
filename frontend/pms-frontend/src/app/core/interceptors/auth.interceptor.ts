import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * WHAT THIS FILE IS
 * The single HTTP interceptor of the front end. An interceptor is a function that Angular
 * runs around every HTTP call the application makes: it sees the request before it leaves
 * the browser, and it sees the answer (or the error) when it comes back. This one does two
 * jobs - it puts the access token on every outgoing request, and it reacts to the two error
 * answers that mean "this session needs attention" (401, and the first-login 403 of H-2).
 *
 * WHERE IT SITS IN THE FLOW
 * It is registered once, in app.config.ts, with
 *     provideHttpClient(withInterceptors([authInterceptor]))
 * so it wraps every HttpClient call of every service in the application.
 * Going out : component -> feature service -> HttpClient -> THIS FILE -> network -> Spring
 *             Boot, where JwtAuthenticationFilter reads the Authorization header we add.
 * Coming in : an error from Spring Boot -> THIS FILE -> either a redirect, or a silent
 *             token refresh followed by a replay of the request, or the error is simply
 *             handed back to the service that made the call.
 * It leans on two collaborators: AuthService (core/services/auth.service.ts) for the token,
 * for the single-flight refresh and for killing the session, and Router for the redirects.
 *
 * WHY IT EXISTS
 * Delete it and two things break. First, no request would ever carry the Authorization
 * header, so every protected endpoint would answer 401 and the application would look
 * completely empty straight after a successful login. Second, the access token is short
 * lived on purpose; when it dies, the user would be thrown back to the login screen in the
 * middle of his work. This file renews the token behind his back and replays the request,
 * so he never notices that the token expired.
 *
 * THE TWO TOKENS (needed to read the code below)
 * - The access token is a short-lived JWT. It sits in localStorage, JavaScript can read it,
 *   and this file copies it into the Authorization header of each request.
 * - The refresh token sits in an HttpOnly cookie, which means JavaScript CANNOT read it.
 *   That is why this file never handles the refresh token itself: it only calls
 *   AuthService.handleTokenRefresh(), and the browser attaches the cookie on its own
 *   (AuthService sends that single call with withCredentials: true). The server hands back
 *   a new refresh cookie on every refresh (rotation) and keeps a tokenVersion counter, so
 *   every session of one user can be revoked at once.
 *
 * IMPORTANT - THIS IS COMFORT, NOT SECURITY
 * Everything here runs in the browser, so anyone can switch it off with the developer
 * tools. The real protection is on the server: @PreAuthorize("hasAuthority('...')") on the
 * service methods, plus ProjectScopeInterceptor for /api/projects/{id}/** URLs, which
 * checks the permission AND the project scope (ADR-021).
 *
 * THIS FILE IS THE ONLY ONE OF ITS FOLDER
 * core/interceptors/ holds this single file. Its closest neighbours live one folder away,
 * in core/guards/: auth.guard.ts and permission.guard.ts act BEFORE a screen is opened
 * (they can stop a navigation), while this interceptor acts AFTER a request came back.
 * The two are complementary: a guard cannot see that a token died in the middle of a
 * screen, and an interceptor cannot stop a user from typing a URL by hand.
 */

/**
 * The interceptor itself. Angular calls this function once for every HTTP request.
 *
 * WHAT IT TAKES
 *  - req  : the request the calling service wants to send. It is immutable (read-only),
 *           so the only way to add a header is to make a copy of it with req.clone().
 *  - next : the rest of the chain. Calling next(request) hands the request to the next
 *           interceptor, or straight to the network when there is none left.
 *
 * WHAT IT GIVES BACK
 * An Observable of the HTTP answer. The service that made the call subscribes to it and
 * never has to know whether the answer came from the first try or from a replay that
 * happened after a silent token renewal.
 *
 * WHY A PLAIN FUNCTION (HttpInterceptorFn) AND NOT A CLASS
 * The older way was a class implementing HttpInterceptor, registered through the
 * HTTP_INTERCEPTORS multi-provider, which needs an NgModule. This front end is fully
 * standalone and has no NgModule at all, so a function registered with
 * withInterceptors([authInterceptor]) is the matching style.
 *
 * WHY THERE IS NO RETRY COUNTER HERE
 * The replayed request is sent with next(retried), which continues DOWN the chain; it does
 * not come back into this function. So one request can cause at most one refresh. Without
 * that property we would need a counter, otherwise a server answering 401 again and again
 * would make the browser refresh and replay for ever.
 */
export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {
  // inject() replaces constructor injection. A functional interceptor is a plain function,
  // not a class, so it has no constructor to receive services through. Angular calls it
  // inside an "injection context", which is what makes inject() legal here.
  // Without it we would have to go back to the old class-based HTTP_INTERCEPTORS provider.
  const auth = inject(AuthService);
  const router = inject(Router);

  // Reads the access token from localStorage (key 'pms_access', see AuthService).
  // It is null before the first login, and also just after expireSession() wiped it.
  const token = auth.getAccessToken();
  // An HttpRequest is immutable: a header cannot be added to it, it can only be copied with
  // one header more. clone({ setHeaders: ... }) makes that copy. "Bearer <token>" is the
  // shape the Spring Security filter expects.
  // Why the ternary and not always cloning: when there is no token yet - the /auth/login
  // call itself, for example - the request is sent untouched. Sending "Bearer null" would
  // make the server reject a login attempt that is perfectly legitimate.
  const authReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    // catchError is the RxJS operator that runs only when the request FAILED. A successful
    // answer flows through this whole file untouched, with no extra work.
    catchError((err: HttpErrorResponse) => {
      // H-2: server-side first-login gate returned 403 with FIRST_LOGIN_REQUIRED.
      // FirstLoginFilter on the server answers 403 with the body
      // {"code":"FIRST_LOGIN_REQUIRED", ...} for a user who still carries his temporary
      // password, on every URL except change-password. That code is an agreed contract
      // between the two sides; here we read it and send him to the change-password screen.
      // Why also test err.error?.code and not the status alone: a 403 usually means "you do
      // not hold this permission", and those must keep bubbling up to the screen. The ?.
      // (optional chaining) protects the read when the body is absent or is plain text -
      // without it, a 403 with an empty body would throw inside the interceptor and the
      // real error would never reach the service that made the call.
      if (err.status === 403 && err.error?.code === 'FIRST_LOGIN_REQUIRED') {
        router.navigate(['/change-password']);
        // throwError(() => err) builds a stream that fails at once with the same error, so
        // the service that made the call still learns that it failed. Without it the caller
        // would sit on an observable that never emits and never completes, and its loading
        // spinner would keep turning behind the redirect.
        return throwError(() => err);
      }

      // Guard against an endless loop. /auth/refresh answers 401 when the refresh cookie is
      // missing, expired or revoked, and /auth/logout can answer 401 when the session is
      // already gone. Without this test, a 401 on /auth/refresh would ask for a refresh,
      // which would answer 401 again, for ever.
      // Note for the reader: /auth/login is not part of the URL test below, so a 401 caused
      // by a wrong password on the login screen also walks through the refresh branch.
      const isAuthEndpoint = req.url.includes('/auth/refresh') || req.url.includes('/auth/logout');
      if (err.status === 401 && !isAuthEndpoint) {
        // H-1: handleTokenRefresh() ensures only one /auth/refresh call goes out at a time.
        // Concurrent 401 callers subscribe to the shared BehaviorSubject and all get
        // the same refreshed token without firing duplicate refresh requests.
        // Why this matters: one dashboard fires six calls at the same moment. If the access
        // token has just died, the six answers come back 401 together. Six parallel refresh
        // calls would each rotate the refresh cookie, so five of them would end up using a
        // cookie the server has already replaced - and the user would be logged out for
        // nothing, in the middle of a screen that was loading correctly.
        return auth.handleTokenRefresh().pipe(
          // switchMap swaps one observable for another: the refresh answer goes in, the
          // replayed request comes out, and the caller only ever sees the final data.
          // Why not map: map would hand the caller the AuthResponse of the refresh call
          // instead of the data he asked for, and the screen would show nothing useful.
          switchMap(res => {
            // Clone the ORIGINAL request once more, now with the brand new token. Starting
            // again from req, and not from authReq, keeps it clear: req never carried a
            // token, so the header written here is the only Authorization the replayed call
            // can hold. Replaying authReq untouched would send the dead token again and the
            // server would answer 401 a second time.
            const retried = req.clone({ setHeaders: { Authorization: `Bearer ${res.accessToken}` } });
            return next(retried);
          }),
          // This inner catchError fires when the REFRESH itself failed: no cookie, an
          // expired cookie, or a server-side tokenVersion bump that revoked every session.
          // At that point there is no way to save the session.
          catchError(() => {
            // expireSession() wipes 'pms_access' and 'pms_context' from localStorage, clears
            // the user signal, and routes to /login?expired=true. The login screen reads
            // that query parameter (login.component.ts) and shows "your session has expired"
            // instead of a blank form, so the user understands why he is back there.
            auth.expireSession();
            // We rethrow err - the FIRST error, the 401 of the business call - and not the
            // error of the refresh call. The service that made the call cares about its own
            // request; an error coming from /auth/refresh would mean nothing to it and its
            // error message on screen would be misleading.
            return throwError(() => err);
          })
        );
      }
      // Anything else - 400, 403 on a missing permission, 404, 409, 500, a network cut - is
      // none of this file's business. It is passed on untouched, so the feature service and
      // its screen can show the right message to the user.
      return throwError(() => err);
    })
  );
};
