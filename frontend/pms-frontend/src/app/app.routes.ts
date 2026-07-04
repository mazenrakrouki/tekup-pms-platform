import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { permissionGuard } from './core/guards/permission.guard';
import { ShellComponent } from './layout/shell/shell.component';

export const routes: Routes = [
  {
    path: 'login',
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
        loadComponent: () => import('./features/auth/change-password/change-password.component').then(m => m.ChangePasswordComponent)
      },
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'projects',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_PROJECT' },
        children: [
          {
            path: '',
            loadComponent: () => import('./features/projects/project-list/project-list.component').then(m => m.ProjectListComponent)
          },
          {
            path: 'new',
            canActivate: [permissionGuard],
            data: { permission: 'CREATE_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          },
          {
            path: ':id',
            loadComponent: () => import('./features/projects/project-detail/project-detail.component').then(m => m.ProjectDetailComponent)
          },
          {
            path: ':id/edit',
            canActivate: [permissionGuard],
            data: { permission: 'EDIT_PROJECT' },
            loadComponent: () => import('./features/projects/project-form/project-form.component').then(m => m.ProjectFormComponent)
          }
        ]
      },
      {
        path: 'resources',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_RESOURCES' },
        loadComponent: () => import('./features/resources/resources.component').then(m => m.ResourcesComponent)
      },
      {
        path: 'workload',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_WORKLOAD' },
        loadComponent: () => import('./features/workload/workload.component').then(m => m.WorkloadComponent)
      },
      {
        path: 'workload/plan',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_WORKLOAD' },
        loadComponent: () => import('./features/workload/workload.component').then(m => m.WorkloadComponent)
      },
      {
        path: 'workload/actuals',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_WORKLOAD' },
        loadComponent: () => import('./features/workload/workload.component').then(m => m.WorkloadComponent)
      },
      {
        path: 'kpi',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_KPI' },
        loadComponent: () => import('./features/kpi/kpi.component').then(m => m.KpiComponent)
      },
      {
        path: 'billing',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_BILLING' },
        loadComponent: () => import('./features/billing/billing.component').then(m => m.BillingComponent)
      },
      {
        path: 'missions',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_MISSION' },
        loadComponent: () => import('./features/missions/missions.component').then(m => m.MissionsComponent)
      },
      {
        path: 'governance',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_GOVERNANCE' },
        loadComponent: () => import('./features/governance/governance.component').then(m => m.GovernanceComponent)
      },
      {
        path: 'risks',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_GOVERNANCE' },
        loadComponent: () => import('./features/governance/governance.component').then(m => m.GovernanceComponent)
      },
      {
        path: 'livrables',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_GOVERNANCE' },
        loadComponent: () => import('./features/governance/governance.component').then(m => m.GovernanceComponent)
      },
      {
        path: 'changes',
        canActivate: [permissionGuard],
        data: { permission: 'VIEW_GOVERNANCE' },
        loadComponent: () => import('./features/governance/governance.component').then(m => m.GovernanceComponent)
      },
      {
        path: 'admin/users',
        canActivate: [permissionGuard],
        data: { permission: 'MANAGE_USERS' },
        loadComponent: () => import('./features/admin/user-list/user-list.component').then(m => m.UserListComponent)
      }
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
