import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { permissionGuard } from './core/guards/permission.guard';
import { unsavedChangesGuard } from './core/guards/unsaved-changes.guard';
import { ShellComponent } from './layout/shell/shell.component';

// Maps each URL to its component and guards. provideRouter(routes) in app.config.ts hands
// this array to the router, which fills <router-outlet> in app.ts (or ShellComponent's
// inner outlet for signed-in screens). The guards below are UX only, not security: the
// real checks are server-side (@PreAuthorize + ProjectScopeInterceptor, ADR-021).
export const routes: Routes = [
  // Outside the guarded branch and outside the shell: an anonymous visitor must reach it,
  // and guarding it too would create a redirect loop with authGuard.
  {
    path: 'login',
    title: 'Connexion',
    // Lazy-loaded so the login bundle doesn't carry every other screen's code.
    loadComponent: () => import('./features/auth/login/login.component').then(m => m.LoginComponent)
  },
  // The signed-in part of the app; every real screen is a child of this one route.
  {
    path: '',
    // Parent route so the shell (sidebar/top bar) is built once and survives navigation.
    component: ShellComponent,
    // Covers every child with one line; without it, an unauthenticated visit would build
    // the whole shell and fire API calls before bouncing to /login.
    canActivate: [authGuard],
    children: [
      // pathMatch: 'full' is required, otherwise the empty path would prefix-match every URL.
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      // No permission guard: every signed-in user may change their own password.
      {
        path: 'change-password',
        title: 'Modifier le mot de passe',
        loadComponent: () => import('./features/auth/change-password/change-password.component').then(m => m.ChangePasswordComponent)
      },
      // Landing page of every user; protected by the session only, same as above.
      {
        path: 'dashboard',
        title: 'Tableau de bord',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      // The projects branch: the parent carries the read permission, and each writing
      // child adds the stronger permission it needs on top.
      {
        path: 'projects',
        // One generic guard reads the permission from `data` below, instead of one guard
        // file per permission (permissions are created by admins at runtime).
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_PROJECT' },
        children: [
          // '' here is the parent's own address, /projects.
          {
            path: '',
            title: 'Projets',
            loadComponent: () => import('./features/projects/project-list/project-list.component').then(m => m.ProjectListComponent)
          },
          // Must stay above ':id': the router takes the first match, and ':id' would
          // otherwise capture 'new' as a project id.
          {
            path: 'new',
            title: 'Nouveau projet',
            // Both guards run: parent's VIEW_PROJECT, then CREATE_PROJECT here.
            canActivate: [permissionGuard],
            data: { permission: 'CREATE_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          // Whether this user may see THIS project is decided server-side
          // (ProjectScopeInterceptor, ADR-021), not by a guard here.
          {
            path: ':id',
            title: 'Détail du projet',
            loadComponent: () => import('./features/projects/project-detail/project-detail.component').then(m => m.ProjectDetailComponent)
          },
          {
            path: ':id/edit',
            title: 'Modifier le projet',
            canActivate: [permissionGuard],
            // Confirms before leaving a form with unsent changes.
            canDeactivate: [unsavedChangesGuard],
            data: { permission: 'EDIT_PROJECT' },
            // Serves both 'new' and ':id/edit'; picks create vs update from the id.
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          // Devis Interne: the most sensitive screen, hence its own permission on top of
          // VIEW_PROJECT. The amounts are always computed server-side on read, never stored.
          {
            path: ':id/devis-interne',
            title: 'Devis Interne',
            canActivate: [permissionGuard],
            data: { permission: 'MANAGE_DI' },
            loadComponent: () => import('./features/di/devis-interne.component').then(m => m.DevisInterneComponent)
          }
        ]
      },
      // From here on, same pattern: one address, its own permission in `data`, lazy loading.
      {
        path: 'resources',
        title: 'Ressources & TCC',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_RESOURCES' },
        loadComponent: () => import('./features/resources/resources.component').then(m => m.ResourcesComponent)
      },
      {
        path: 'workload',
        title: 'Plan de charge',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_WORKLOAD' },
        loadComponent: () => import('./features/workload/workload.component').then(m => m.WorkloadComponent)
      },
      {
        path: 'kpi',
        title: 'KPIs',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_KPI' },
        loadComponent: () => import('./features/kpi/kpi.component').then(m => m.KpiComponent)
      },
      {
        path: 'billing',
        title: 'Facturation',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_BILLING' },
        loadComponent: () => import('./features/billing/billing.component').then(m => m.BillingComponent)
      },
      {
        path: 'missions',
        title: 'Missions',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_MISSION' },
        loadComponent: () => import('./features/missions/missions.component').then(m => m.MissionsComponent)
      },
      {
        path: 'governance',
        title: 'Gouvernance',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_GOVERNANCE' },
        loadComponent: () => import('./features/governance/governance.component').then(m => m.GovernanceComponent)
      },
      {
        path: 'agile',
        title: 'Planification agile',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_AGILE' },
        loadComponent: () => import('./features/agile/agile.component').then(m => m.AgileComponent)
      },
      // Admin screens: ordinary permission checks, never a role-name check, since roles
      // are created and renamed by admins at runtime.
      {
        path: 'admin/users',
        title: 'Gestion des utilisateurs',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_USERS' },
        loadComponent: () => import('./features/admin/user-list/user-list.component').then(m => m.UserListComponent)
      },
      {
        path: 'admin/roles',
        title: 'Rôles & permissions',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_ROLES' },
        loadComponent: () => import('./features/admin/roles/role-list.component').then(m => m.RoleListComponent)
      },
      // Reuses MANAGE_ROLES on purpose: same job (deciding who may do what) as role-list.
      {
        path: 'admin/permissions',
        title: 'Permissions',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_ROLES' },
        loadComponent: () => import('./features/admin/permissions/permission-list.component').then(m => m.PermissionListComponent)
      }
    ]
  },
  // Catch-all, must stay last (router stops at first match). Sends unknown URLs to the
  // dashboard; authGuard then redirects to /login if there's no session.
  { path: '**', redirectTo: 'dashboard' }
];
