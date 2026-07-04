import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../../core/services/project.service';
import { AuthService } from '../../../core/services/auth.service';
import { Project, PROJECT_STATUS_LABELS } from '../../../core/models/project.model';

@Component({
  selector: 'app-project-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule],
  template: `
    <div class="topbar d-flex align-items-center justify-content-between">
      <h5 class="mb-0 fw-semibold">Projets</h5>
      @if (auth.hasPermission('CREATE_PROJECT')) {
        <a routerLink="/projects/new" class="btn btn-sm btn-primary">
          <i class="bi bi-plus-lg me-1"></i>Nouveau
        </a>
      }
    </div>

    <div class="p-4">
      <!-- Bascule Actifs / Archivés -->
      <ul class="nav nav-pills mb-3">
        <li class="nav-item">
          <button class="nav-link" [class.active]="mode()==='active'" (click)="setMode('active')">
            <i class="bi bi-folder2-open me-1"></i>Actifs
          </button>
        </li>
        <li class="nav-item">
          <button class="nav-link" [class.active]="mode()==='archived'" (click)="setMode('archived')">
            <i class="bi bi-archive me-1"></i>Archivés
          </button>
        </li>
      </ul>

      <div class="card">
        <div class="card-header bg-white py-3">
          <input type="search" class="form-control form-control-sm" style="max-width:280px"
                 placeholder="Rechercher…" [(ngModel)]="search">
        </div>
        <div class="table-responsive">
          <table class="table table-hover mb-0 align-middle">
            <thead class="table-light">
              <tr>
                <th>Code</th><th>Nom</th><th>Chef de projet</th>
                <th>Début</th><th>Fin</th><th>Statut</th><th class="text-end">Budget effectif</th>
                @if (auth.hasPermission('EDIT_PROJECT')) { <th></th> }
              </tr>
            </thead>
            <tbody>
              @for (p of filtered(); track p.id) {
                <tr>
                  <td><a [routerLink]="['/projects', p.id]" class="fw-semibold text-decoration-none">{{ p.code }}</a></td>
                  <td>{{ p.name }}</td>
                  <td>{{ p.chefProjetName ?? '—' }}</td>
                  <td>{{ p.startDate ?? '—' }}</td>
                  <td>{{ p.endDate ?? '—' }}</td>
                  <td><span [class]="badge(p.status)">{{ statusLabel(p.status) }}</span></td>
                  <td class="text-end">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }} TND</td>
                  @if (auth.hasPermission('EDIT_PROJECT')) {
                    <td class="text-end">
                      @if (mode()==='active') {
                        <a [routerLink]="['/projects', p.id, 'edit']" class="btn btn-sm btn-outline-secondary" title="Modifier">
                          <i class="bi bi-pencil"></i>
                        </a>
                      } @else {
                        <button class="btn btn-sm btn-outline-primary" (click)="unarchive(p)" title="Désarchiver">
                          <i class="bi bi-arrow-counterclockwise me-1"></i>Restaurer
                        </button>
                      }
                    </td>
                  }
                </tr>
              }
              @empty {
                <tr><td colspan="8" class="text-center py-4 text-muted">
                  {{ mode()==='archived' ? 'Aucun projet archivé' : 'Aucun projet' }}
                </td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `
})
export class ProjectListComponent implements OnInit {
  projects = signal<Project[]>([]);
  mode = signal<'active' | 'archived'>('active');
  search = '';

  filtered = () => this.projects().filter(p =>
    !this.search || p.code.toLowerCase().includes(this.search.toLowerCase()) ||
    p.name.toLowerCase().includes(this.search.toLowerCase())
  );

  constructor(public auth: AuthService, private svc: ProjectService) {}

  ngOnInit(): void {
    this.load();
  }

  setMode(m: 'active' | 'archived'): void {
    if (this.mode() === m) return;
    this.mode.set(m);
    this.load();
  }

  load(): void {
    const obs = this.mode() === 'archived' ? this.svc.listArchived() : this.svc.list();
    obs.subscribe(list => this.projects.set(list));
  }

  unarchive(p: Project): void {
    this.svc.unarchive(p.id).subscribe(() => this.load());
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
