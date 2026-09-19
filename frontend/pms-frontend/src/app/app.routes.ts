import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { permissionGuard } from './core/guards/permission.guard';
import { unsavedChangesGuard } from './core/guards/unsaved-changes.guard';
import { ShellComponent } from './layout/shell/shell.component';

// =============================================================================
// app.routes.ts - the map of the application: which URL shows which screen.
//
// WHAT IT IS: one array read by the Angular router. Each entry says "for this
// address, load this component, but only if these guards say yes".
//
// WHERE IT SITS IN THE FLOW:
//   app.config.ts -> provideRouter(routes, ...) hands this array to the router
//   the router matches the URL, runs the guards, then draws the component
//   inside the <router-outlet> of app.ts (and, for the signed-in part of the
//   app, inside the inner outlet of ShellComponent).
//
// WHY IT EXISTS: without it every address would fall through and the page
// would stay empty. It is also the single place where the front end says which
// permission each screen needs, which makes the navigation rules easy to read.
//
// IMPORTANT - THE GUARDS HERE ARE NOT THE SECURITY.
// Everything in this file runs in the browser, so a user can switch it off with
// the developer tools. The real protection is on the server:
// @PreAuthorize("hasAuthority('...')") on the SERVICE methods, plus the
// ProjectScopeInterceptor which, for URLs like /api/projects/{id}/**, checks
// BOTH the permission AND that this user belongs to that project (ADR-021).
// The guards below only stop the user from opening a screen he could not use
// anyway, so he gets a clean redirect instead of a wall of 403 errors.
// =============================================================================
export const routes: Routes = [
  // The login page sits OUTSIDE the guarded branch below, and outside the
  // shell. Why: an anonymous visitor must be able to reach it, and the sidebar
  // / top bar make no sense before sign-in.
  // Without this exception the guard would send the user to /login, /login
  // would be guarded too, and the browser would loop forever.
  {
    path: 'login',
    // `title` is what the router writes in the browser tab.
    title: 'Connexion',
    // loadComponent loads the screen's JavaScript only when the URL is opened
    // (lazy loading). Why: the first download stays small. Without it the code
    // of all thirteen screens would be in the very first bundle, so the login
    // page would carry the admin screens of a user who may never open them.
    loadComponent: () => import('./features/auth/login/login.component').then(m => m.LoginComponent)
  },
  // The signed-in part of the application. Every real screen is a CHILD of this
  // one route, on purpose.
  {
    path: '',
    // ShellComponent draws the layout (sidebar, top bar) and owns the inner
    // <router-outlet> where the children below appear.
    // Why a parent route instead of putting the shell inside every screen: the
    // shell is then built once and stays alive while the user moves from one
    // page to another, so the sidebar does not blink on each navigation.
    component: ShellComponent,
    // authGuard runs before this route and therefore before ALL its children.
    // It answers "is there a session?", and sends the visitor to /login if not.
    // Why place it here and not on each child: one line covers the whole
    // application, and a new screen added below is protected without anybody
    // having to remember it.
    // Without it, typing /dashboard with no session would build the whole
    // shell, fire many API calls and collect 401 errors before bouncing out.
    canActivate: [authGuard],
    children: [
      // The empty address (the site root) is not a screen: it forwards to the
      // dashboard. pathMatch: 'full' means "only when the WHOLE remaining URL
      // is empty".
      // Why 'full' is required: with the default ('prefix') the empty path
      // matches the start of every address, so /projects would also be sent to
      // /dashboard and no other page could ever be reached.
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      // No permission guard here: changing your own password is something every
      // signed-in user must be able to do, whatever his role.
      {
        path: 'change-password',
        title: 'Modifier le mot de passe',
        loadComponent: () => import('./features/auth/change-password/change-password.component').then(m => m.ChangePasswordComponent)
      },
      // Same reasoning: the dashboard is the landing page of every user, so it
      // is protected by the session only.
      {
        path: 'dashboard',
        title: 'Tableau de bord',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      // The projects branch. The parent carries the "read" permission, and each
      // child that writes adds the stronger permission it needs on top.
      {
        path: 'projects',
        // permissionGuard reads the name written in `data` just below and asks
        // AuthService whether the signed-in user holds it.
        // Why one generic guard with a parameter instead of one guard file per
        // permission: permissions are created by administrators at runtime, so
        // a file-per-permission design would need a code change every time.
        canActivate: [permissionGuard],
        // `data` is a static bag of values attached to the route; the guard
        // reads route.data['permission'] out of it.
        // Because this is the parent, VIEW_PROJECT is required for the list,
        // the detail, the form and the DI screen alike.
        data: { permission: 'VIEW_PROJECT' },
        children: [
          // '' here means the parent's own address, /projects.
          {
            path: '',
            title: 'Projets',
            loadComponent: () => import('./features/projects/project-list/project-list.component').then(m => m.ProjectListComponent)
          },
          // ORDER MATTERS: 'new' must stay ABOVE ':id'. The router takes the
          // first entry that matches, and ':id' matches any single segment.
          // If ':id' came first, /projects/new would be read as a project whose
          // id is the text "new" and the detail screen would ask the API for a
          // project that does not exist.
          {
            path: 'new',
            title: 'Nouveau projet',
            // A second permissionGuard on the child. Both guards run: the
            // parent's VIEW_PROJECT first, then CREATE_PROJECT here.
            // Why: seeing projects and creating one are two different rights.
            canActivate: [permissionGuard],
            data: { permission: 'CREATE_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          // ':id' is a route parameter: the segment is captured under the name
          // `id`. Thanks to withComponentInputBinding() in app.config.ts, it
          // lands directly in the component's `id` @Input().
          // Only the parent's VIEW_PROJECT is needed to open a project sheet;
          // whether this user may see THIS project is decided by the server
          // (ProjectScopeInterceptor, ADR-021), not here.
          {
            path: ':id',
            title: 'Détail du projet',
            loadComponent: () => import('./features/projects/project-detail/project-detail.component').then(m => m.ProjectDetailComponent)
          },
          {
            path: ':id/edit',
            title: 'Modifier le projet',
            canActivate: [permissionGuard],
            // canDeactivate runs when the user tries to LEAVE this screen.
            // unsavedChangesGuard asks him to confirm when the form still holds
            // changes that were never sent.
            // Why: the edit form is long. Without it, one click on the sidebar
            // would throw away everything he typed, with no warning.
            canDeactivate: [unsavedChangesGuard],
            data: { permission: 'EDIT_PROJECT' },
            // The same component serves 'new' and ':id/edit'; it decides
            // between create and update from the presence of the id.
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          // Devis Interne (internal quote) - the most sensitive screen of the
          // application, hence its own permission on top of VIEW_PROJECT.
          // Reminder for the defence: the amounts this screen shows are always
          // computed by the server when the data is read, never kept in a
          // column, so there is no stored total that could drift.
          {
            path: ':id/devis-interne',
            title: 'Devis Interne',
            canActivate: [permissionGuard],
            data: { permission: 'MANAGE_DI' },
            loadComponent: () => import('./features/di/devis-interne.component').then(m => m.DevisInterneComponent)
          }
        ]
      },
      // From here on the pattern is always the same: one address, the screen's
      // own permission in `data`, permissionGuard to check it, and lazy
      // loading. Adding a module means adding one block like these.
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
      // The three administration screens. They are ordinary routes with an
      // ordinary permission: the code never tests a role NAME such as "ADMIN".
      // Why this matters: roles are created and edited by administrators at
      // runtime. A check on the name "ADMIN" would break the day somebody
      // renames the role, and would leave out a new role that legitimately
      // received the same permission.
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
      // The permissions catalog reuses MANAGE_ROLES on purpose: the two screens
      // are two views of the same job (deciding who may do what), so splitting
      // them into two permissions would only make the matrix harder to read.
      {
        path: 'admin/permissions',
        title: 'Permissions',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_ROLES' },
        loadComponent: () => import('./features/admin/permissions/permission-list.component').then(m => m.PermissionListComponent)
      }
    ]
  },
  // '**' is the catch-all: any address that matched nothing above.
  // It is LAST because the router stops at the first match; placed higher it
  // would swallow every URL of the application.
  // It sends the user to the dashboard instead of showing a dead page. Note
  // that an unknown address is not answered with a 404 screen; if the visitor
  // has no session, authGuard then moves him on to /login.
  { path: '**', redirectTo: 'dashboard' }
];
