import { inject } from '@angular/core';
import { CanActivateFn, ActivatedRouteSnapshot, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

// Front-end permission gate. Each protected route declares 'data: { permission: '...' }'
// and this one generic guard checks AuthService.hasPermission() for it, instead of one
// guard file per permission (permissions are created dynamically by admins). Tests a
// PERMISSION name, never a role name - roles can be re-wired at any time. Browser-side
// only, for comfort: the server decides, via @PreAuthorize + ProjectScopeInterceptor
// (ADR-021).

/**
 * Answers "may I enter this route?" for one protected screen: true if the user holds the
 * route's declared permission, else a UrlTree to /dashboard?forbidden=1.
 * Parent and child guards both must pass (e.g. 'projects/:id/edit' needs the parent's
 * VIEW_PROJECT and its own EDIT_PROJECT) - that's why one 'permission' key per route suffices.
 */
export const permissionGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // The `: string` annotation is our own promise; route.data is a loose map, unchecked by the compiler.
  const required: string = route.data['permission'];

  // `!required` lets an unprotected route through instead of locking it forever.
  if (!required || auth.hasPermission(required)) return true;

  // M-8: the 'forbidden' flag lets DashboardComponent show a warning banner instead of
  // silently dropping the user with nothing on screen to explain why.
  return router.createUrlTree(['/dashboard'], { queryParams: { forbidden: '1' } });
};
