import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { permissionGuard } from './core/guards/permission.guard';
import { unsavedChangesGuard } from './core/guards/unsaved-changes.guard';
import { ShellComponent } from './layout/shell/shell.component';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Connexion',
    loadComponent: () => import('./features/auth/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'change-password',
        title: 'Modifier le mot de passe',
        loadComponent: () => import('./features/auth/change-password/change-password.component').then(m => m.ChangePasswordComponent)
      },
      {
        path: 'dashboard',
        title: 'Tableau de bord',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'projects',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_PROJECT' },
        children: [
          {
            path: '',
            title: 'Projets',
            loadComponent: () => import('./features/projects/project-list/project-list.component').then(m => m.ProjectListComponent)
          },
          {
            path: 'new',
            title: 'Nouveau projet',
            canActivate: [permissionGuard],
            data: { permission: 'CREATE_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          {
            path: ':id',
            title: 'Détail du projet',
            loadComponent: () => import('./features/projects/project-detail/project-detail.component').then(m => m.ProjectDetailComponent)
          },
          {
            path: ':id/edit',
            title: 'Modifier le projet',
            canActivate: [permissionGuard],
            canDeactivate: [unsavedChangesGuard],
            data: { permission: 'EDIT_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          {
            path: ':id/devis-interne',
            title: 'Devis Interne',
            canActivate: [permissionGuard],
            data: { permission: 'MANAGE_DI' },
            loadComponent: () => import('./features/di/devis-interne.component').then(m => m.DevisInterneComponent)
          }
        ]
      },
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
      {
        path: 'admin/permissions',
        title: 'Permissions',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_ROLES' },
        loadComponent: () => import('./features/admin/permissions/permission-list.component').then(m => m.PermissionListComponent)
      }
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
