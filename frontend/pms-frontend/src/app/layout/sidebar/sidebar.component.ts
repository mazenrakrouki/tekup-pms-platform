import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  permission?: string;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  template: `
    <nav class="sidebar d-flex flex-column">
      <div class="sidebar-brand">
        <i class="bi bi-kanban me-2"></i>PMS
      </div>

      <div class="py-2 flex-grow-1">
        @for (section of visibleSections(); track section.title) {
          <div class="nav-section-title">{{ section.title }}</div>
          @for (item of section.items; track item.route) {
            <a class="nav-link d-flex align-items-center gap-2"
               [routerLink]="item.route" routerLinkActive="active">
              <i class="bi {{ item.icon }}"></i>{{ item.label }}
            </a>
          }
        }
      </div>

      <div class="border-top border-white border-opacity-10 p-3">
        <div class="small text-white-50 mb-2">
          <i class="bi bi-person-circle me-1"></i>{{ context()?.fullName }}
        </div>
        <button class="btn btn-sm btn-outline-light w-100" (click)="logout()">
          <i class="bi bi-box-arrow-right me-1"></i>Déconnexion
        </button>
      </div>
    </nav>
  `
})
export class SidebarComponent {
  private readonly auth = inject(AuthService);
  readonly context = this.auth.context;

  private readonly NAV: NavSection[] = [
    {
      title: 'Tableau de bord',
      items: [
        { label: 'Accueil', icon: 'bi-house', route: '/dashboard' }
      ]
    },
    {
      title: 'Projets',
      items: [
        { label: 'Mes projets',    icon: 'bi-folder2-open', route: '/projects',     permission: 'VIEW_PROJECT' },
        { label: 'Nouveau projet', icon: 'bi-plus-circle',  route: '/projects/new', permission: 'CREATE_PROJECT' }
      ]
    },
    {
      title: 'Équipe & Charges',
      items: [
        { label: 'Ressources',         icon: 'bi-people',       route: '/resources',       permission: 'VIEW_RESOURCES' },
        { label: 'Charges planifiées', icon: 'bi-calendar3',    route: '/workload/plan',   permission: 'VIEW_WORKLOAD' },
        { label: 'Charges réelles',    icon: 'bi-clock-history',route: '/workload/actuals',permission: 'VIEW_WORKLOAD' }
      ]
    },
    {
      title: 'Finance',
      items: [
        { label: 'KPIs', icon: 'bi-graph-up', route: '/kpi', permission: 'VIEW_KPI' },
        { label: 'Facturation', icon: 'bi-receipt', route: '/billing', permission: 'VIEW_BILLING' },
        { label: 'Missions', icon: 'bi-airplane', route: '/missions', permission: 'VIEW_MISSION' }
      ]
    },
    {
      title: 'Gouvernance',
      items: [
        { label: 'Risques', icon: 'bi-exclamation-triangle', route: '/risks', permission: 'VIEW_GOVERNANCE' },
        { label: 'Livrables', icon: 'bi-check2-square', route: '/livrables', permission: 'VIEW_GOVERNANCE' },
        { label: 'Changements', icon: 'bi-arrow-repeat', route: '/changes', permission: 'VIEW_GOVERNANCE' }
      ]
    },
    {
      title: 'Administration',
      items: [
        { label: 'Utilisateurs', icon: 'bi-person-gear', route: '/admin/users', permission: 'MANAGE_USERS' }
      ]
    }
  ];

  readonly visibleSections = computed(() => {
    const perms = this.auth.permissions();
    return this.NAV
      .map(s => ({
        ...s,
        items: s.items.filter(i => !i.permission || perms.includes(i.permission))
      }))
      .filter(s => s.items.length > 0);
  });

  logout(): void {
    this.auth.logout();
  }
}
