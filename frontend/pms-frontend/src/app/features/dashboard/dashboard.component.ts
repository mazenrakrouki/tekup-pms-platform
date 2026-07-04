import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';
import { ProjectService } from '../../core/services/project.service';
import { Project, PROJECT_STATUS_LABELS } from '../../core/models/project.model';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="topbar d-flex align-items-center justify-content-between">
      <div>
        <h5 class="mb-0 fw-semibold">Tableau de bord</h5>
        <small class="text-muted">Bonjour, {{ context()?.fullName }}</small>
      </div>
      @if (auth.hasPermission('CREATE_PROJECT')) {
        <a routerLink="/projects/new" class="btn btn-sm btn-primary">
          <i class="bi bi-plus-lg me-1"></i>Nouveau projet
        </a>
      }
    </div>

    <div class="p-4">
      @if (isProjectUser()) {
        <!-- ===== Tableau de bord PROJETS (Directeur / Chef / Dev) ===== -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
              <div class="rounded-3 p-3 bg-primary bg-opacity-10"><i class="bi bi-folder2-open fs-4 text-primary"></i></div>
              <div><div class="fs-3 fw-bold">{{ projects().length }}</div><div class="text-muted small">Total projets</div></div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
              <div class="rounded-3 p-3 bg-success bg-opacity-10"><i class="bi bi-check2-circle fs-4 text-success"></i></div>
              <div><div class="fs-3 fw-bold">{{ completed() }}</div><div class="text-muted small">Terminés</div></div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
              <div class="rounded-3 p-3 bg-warning bg-opacity-10"><i class="bi bi-hourglass-split fs-4 text-warning"></i></div>
              <div><div class="fs-3 fw-bold">{{ active() }}</div><div class="text-muted small">Actifs</div></div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body d-flex align-items-center gap-3">
              <div class="rounded-3 p-3 bg-info bg-opacity-10"><i class="bi bi-pencil-square fs-4 text-info"></i></div>
              <div><div class="fs-3 fw-bold">{{ draft() }}</div><div class="text-muted small">Brouillons</div></div>
            </div></div>
          </div>
        </div>

        <div class="card">
          <div class="card-header bg-white d-flex justify-content-between align-items-center py-3">
            <span class="fw-semibold">Projets récents</span>
            <a routerLink="/projects" class="btn btn-sm btn-outline-primary">Voir tout</a>
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead class="table-light">
                <tr><th>Code</th><th>Nom</th><th>Chef de projet</th><th>Statut</th><th class="text-end">Budget effectif</th></tr>
              </thead>
              <tbody>
                @for (p of projects().slice(0, 8); track p.id) {
                  <tr>
                    <td><a [routerLink]="['/projects', p.id]" class="fw-semibold text-decoration-none">{{ p.code }}</a></td>
                    <td>{{ p.name }}</td>
                    <td>{{ p.chefProjetName ?? '—' }}</td>
                    <td><span [class]="badge(p.status)">{{ statusLabel(p.status) }}</span></td>
                    <td class="text-end">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }} TND</td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="5" class="text-center py-4 text-muted">Aucun projet</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      } @else {
        <!-- ===== Tableau de bord ADMINISTRATION ===== -->
        <div class="alert alert-light border d-flex align-items-center gap-2 mb-4">
          <i class="bi bi-shield-lock fs-4 text-primary"></i>
          <div>
            <div class="fw-semibold">Espace administration</div>
            <div class="text-muted small">Gestion de la plateforme — utilisateurs, rôles et données de référence. L'administrateur ne gère pas les projets.</div>
          </div>
        </div>
        <div class="row g-3">
          @if (auth.hasPermission('MANAGE_USERS')) {
            <div class="col-sm-6 col-xl-4">
              <a routerLink="/admin/users" class="card h-100 text-decoration-none text-reset">
                <div class="card-body d-flex align-items-center gap-3">
                  <div class="rounded-3 p-3 bg-primary bg-opacity-10"><i class="bi bi-people fs-4 text-primary"></i></div>
                  <div>
                    <div class="fs-3 fw-bold">{{ userCount() }}</div>
                    <div class="text-muted small">Utilisateurs · gérer</div>
                  </div>
                </div>
              </a>
            </div>
          }
          @if (auth.hasPermission('VIEW_RESOURCES')) {
            <div class="col-sm-6 col-xl-4">
              <a routerLink="/resources" class="card h-100 text-decoration-none text-reset">
                <div class="card-body d-flex align-items-center gap-3">
                  <div class="rounded-3 p-3 bg-success bg-opacity-10"><i class="bi bi-cash-stack fs-4 text-success"></i></div>
                  <div>
                    <div class="fw-semibold">Ressources &amp; TCC</div>
                    <div class="text-muted small">Tarifs journaliers</div>
                  </div>
                </div>
              </a>
            </div>
          }
        </div>
      }
    </div>
  `
})
export class DashboardComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly projectService = inject(ProjectService);
  private readonly http = inject(HttpClient);

  readonly context = this.auth.context;
  projects = signal<Project[]>([]);
  userCount = signal<number>(0);

  isProjectUser = () => this.auth.hasPermission('VIEW_PROJECT');

  active    = () => this.projects().filter(p => p.status === 'ACTIVE').length;
  completed = () => this.projects().filter(p => p.status === 'COMPLETED').length;
  draft     = () => this.projects().filter(p => p.status === 'DRAFT').length;

  ngOnInit(): void {
    if (this.isProjectUser()) {
      this.projectService.list().subscribe(list => this.projects.set(list));
    } else if (this.auth.hasPermission('MANAGE_USERS')) {
      this.http.get<unknown[]>(`${environment.apiUrl}/users`).subscribe(u => this.userCount.set(u.length));
    }
  }

  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  badge(s: string): string {
    const m: Record<string, string> = {
      'ACTIVE': 'badge bg-primary', 'COMPLETED': 'badge bg-success',
      'DRAFT': 'badge bg-secondary', 'ON_HOLD': 'badge bg-warning text-dark',
      'CANCELLED': 'badge bg-danger'
    };
    return m[s] ?? 'badge bg-secondary';
  }
}
