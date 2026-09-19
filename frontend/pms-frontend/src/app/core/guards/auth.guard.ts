import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * WHAT THIS FILE IS
 * The front-end "are you logged in?" gate for routes. A guard is a small function the
 * Angular Router runs before it builds a page; it answers yes or no.
 *
 * WHERE IT SITS IN THE FLOW
 * app.routes.ts puts 'canActivate: [authGuard]' on the parent route (path '', the one
 * that draws ShellComponent). Every screen of the application is a child of that route,
 * so this one guard covers them all. The guard asks AuthService
 * (core/services/auth.service.ts) whether a session is held, then either lets the
 * navigation continue or sends the browser to /login.
 *
 * WHY IT EXISTS
 * Delete it and typing /dashboard with no session still builds the whole shell, fires a
 * dozen HTTP calls, collects a wall of 401 errors, and only then bounces the user out.
 * The guard stops the navigation before any of that happens.
 *
 * IMPORTANT - THIS IS COMFORT, NOT SECURITY
 * A guard runs in the browser, so anyone can switch it off with the developer tools.
 * The real protection is on the server: @PreAuthorize("hasAuthority('...')") on the
 * service methods, plus the ProjectScopeInterceptor for /api/projects/{id}/** URLs
 * (ADR-021). This guard only makes the application behave politely.
 */

/**
 * Answers the router's question "may I enter this route?".
 * Gives back 'true' when a session is held, or a UrlTree pointing at /login when it is not.
 *
 * CanActivateFn is the type Angular gives to an entry guard written as a plain function.
 * The answers it accepts are: 'true' (carry on), 'false' (cancel and stay where we are),
 * or a UrlTree (cancel and go to that address instead). A UrlTree is Angular's parsed form
 * of a URL - the object the router builds internally from a string like '/login'.
 * This guard never answers 'false': a bare 'false' on a fresh page load would leave the
 * user staring at an empty shell with no link to the login form.
 *
 * Why it takes no parameter while permissionGuard takes the route snapshot: the question
 * "is anybody logged in?" gets the same answer on every route, so there is nothing to read
 * from the route being entered.
 */
export const authGuard: CanActivateFn = () => {
  // inject() replaces constructor injection. A guard is a plain function, not a class,
  // so it has no constructor to receive services through. The router calls the guard
  // inside an "injection context", which is what makes inject() legal here.
  // Without inject() we would have to go back to the old class-based CanActivate guard,
  // which Angular has deprecated.
  const auth = inject(AuthService);
  const router = inject(Router);

  // isLoggedIn() is a computed signal on AuthService: it is true while a UserContext
  // object is held. That context is restored at start-up from the localStorage key
  // 'pms_context', so a page reload (F5) does not log the user out.
  // The guard reads the value once and is not reactive - it does not need to be,
  // because the router calls it again on every navigation.
  if (auth.isLoggedIn()) return true;

  // Returning a UrlTree means "cancel this navigation and go there instead", as one
  // single router operation.
  // Why not router.navigate(['/login']) followed by `return false`: that is two
  // navigations running against each other, and the browser history keeps an entry for
  // the page that was refused. The user presses Back and lands straight on the blocked
  // route again.
  return router.createUrlTree(['/login']);
};
