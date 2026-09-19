import { inject } from '@angular/core';
import { CanActivateFn, ActivatedRouteSnapshot, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * WHAT THIS FILE IS
 * The front-end permission gate for a route. Each protected route declares the one
 * permission it needs next to itself, for example 'data: { permission: 'MANAGE_DI' }',
 * and this guard checks that the signed-in user holds that permission.
 *
 * WHERE IT SITS IN THE FLOW
 * app.routes.ts adds 'canActivate: [permissionGuard]' to a route (it is used on about
 * fourteen of them). The router calls the guard with that route's snapshot. The guard
 * reads the permission name out of the snapshot's data, asks
 * AuthService.hasPermission() - the permission list arrived in the login response and
 * lives in the UserContext - and then either allows the navigation or rewrites it to
 * /dashboard with a 'forbidden=1' flag. DashboardComponent reads that flag and shows a
 * warning banner.
 *
 * WHY IT EXISTS
 * Without it, a user who does not hold MANAGE_DI could still open the Devis Interne
 * screen. The page would render, every request would come back 403, and the user would
 * face broken empty widgets instead of one clear "not allowed" message.
 *
 * WHY THE PERMISSION NAME LIVES IN THE ROUTE, NOT IN THIS FILE
 * One generic guard serves every protected route. The obvious alternative - one guard
 * file per permission (canViewProjectGuard, canManageDiGuard...) - would need a new
 * file every time an administrator creates a permission, which would break the whole
 * point of a dynamic permission model.
 *
 * AUTHORIZATION MODEL - DO NOT MISREAD THIS FILE
 * The test is on a PERMISSION name ('EDIT_PROJECT'), never on a role name. Roles are
 * rows in the database that an administrator can re-wire at any moment, so the code
 * must not know them. And this browser-side test is only for comfort: the server is the
 * one that decides, with @PreAuthorize("hasAuthority('...')") on the service methods
 * and, for any URL shaped like /api/projects/{id}/**, the ProjectScopeInterceptor,
 * which checks BOTH the permission AND that this user is attached to that project
 * (ADR-021). Holding the permission alone is not enough there.
 */

/**
 * Answers the router's question "may I enter this route?" for one protected screen.
 * Gives back 'true' when the user holds the permission named on the route, or a UrlTree
 * pointing at /dashboard?forbidden=1 when he does not.
 *
 * 'route' is the ActivatedRouteSnapshot: a frozen picture of the route being entered - its
 * URL pieces, its :id parameters, its query parameters and its static 'data' object.
 * Angular hands it over as the first argument of every CanActivateFn. There is a second
 * argument (the whole router state) that this guard does not need, so it is simply not
 * written; TypeScript allows a function to declare fewer parameters than it is called with.
 *
 * GUARDS ADD UP, THEY DO NOT REPLACE EACH OTHER
 * When a parent route and one of its children both carry permissionGuard, the router runs
 * the parent's guard first and the child's guard after, and BOTH must say yes.
 * Concrete example: to open 'projects/:id/edit' the user needs VIEW_PROJECT (asked by the
 * parent 'projects' route) and EDIT_PROJECT (asked by the child route). This is also why
 * one single 'permission' key per route is enough - a screen that needs two permissions
 * gets the second one from its parent.
 */
export const permissionGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  // Both services are taken from Angular's injector here, at the top of the guard, because
  // inject() is only legal while the router is running the guard (the "injection context").
  // Calling it later inside a callback, for instance inside a .then(), would throw
  // "inject() must be called from an injection context" at runtime.
  const auth = inject(AuthService);
  const router = inject(Router);

  // route.data is the static `data: { permission: '...' }` object written beside the
  // route in app.routes.ts. Angular types it as a loose string-keyed map, so the
  // `: string` annotation is our own promise about what we put in there; it is not
  // checked by the compiler.
  // Concrete example: on the route 'projects/:id/edit' this line yields 'EDIT_PROJECT'.
  const required: string = route.data['permission'];

  // Two different reasons to let the navigation pass:
  //  - `!required` - the route declared no permission (the `data` line is missing, or
  //    the route is open on purpose). We let it through instead of locking a page that
  //    nobody could ever reach again.
  //  - hasPermission(required) - the name is present in the permissions array of the
  //    current UserContext.
  // Drop the `!required` half and one forgotten `data:` line would redirect every user,
  // including the administrator, to the dashboard forever, with nothing on screen to
  // explain why.
  if (!required || auth.hasPermission(required)) return true;

  // M-8: communicate why the user was redirected so the dashboard can show a message.
  // DashboardComponent reads this with queryParamMap.get('forbidden') === '1' and
  // raises an alert banner that the user can close.
  // Without the query parameter, clicking a menu entry would silently drop the user on
  // the dashboard and the application would simply look broken.
  // A UrlTree is returned rather than router.navigate() + false for the same reason as
  // in auth.guard.ts: one clean cancel-and-redirect, and no dead history entry that
  // would send the user back into the refused page on Back.
  return router.createUrlTree(['/dashboard'], { queryParams: { forbidden: '1' } });
};
