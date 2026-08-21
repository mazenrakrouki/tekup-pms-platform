import { inject } from '@angular/core';
import { CanActivateFn, ActivatedRouteSnapshot, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const permissionGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const required: string = route.data['permission'];
  if (!required || auth.hasPermission(required)) return true;
  // M-8: communicate why the user was redirected so the dashboard can show a message
  return router.createUrlTree(['/dashboard'], { queryParams: { forbidden: '1' } });
};
