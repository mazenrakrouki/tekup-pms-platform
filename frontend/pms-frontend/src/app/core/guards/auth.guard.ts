import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

// Front-end "are you logged in?" gate. app.routes.ts applies it to the parent route (path
// '', ShellComponent), so it covers every screen. This is UX comfort, not security: the
// real checks are server-side (@PreAuthorize + ProjectScopeInterceptor, ADR-021).

/**
 * Answers the router's "may I enter this route?" with true, or a UrlTree to /login.
 * Never returns false: on a fresh load that would leave an empty shell with no way out.
 */
export const authGuard: CanActivateFn = () => {
  // inject() is legal because the router calls guards inside an injection context.
  const auth = inject(AuthService);
  const router = inject(Router);

  // isLoggedIn() reflects the UserContext restored from localStorage at start-up, so an
  // F5 reload doesn't log the user out.
  if (auth.isLoggedIn()) return true;

  // A UrlTree redirect is one router operation; navigate() + false would leave a dead
  // history entry that sends the user straight back to the blocked route on Back.
  return router.createUrlTree(['/login']);
};
