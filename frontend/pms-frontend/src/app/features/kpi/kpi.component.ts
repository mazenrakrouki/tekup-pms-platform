import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { Project, PROJECT_STATUS_LABELS } from '../../core/models/project.model';
import { KpiResponse } from '../../core/models/kpi.model';

type LoadState = 'loading' | 'done' | 'error';

@Component({
  selector: 'app-kpi',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-graph-up me-2"></i>Tableau de bord KPI</h5>
    </div>
    <div class="p-4">
      <div class="row g-3">
        @for (p of projects(); track p.id) {
          <div class="col-md-6 col-xl-4">
            <div class="card h-100">
              <div class="card-header bg-white py-3 d-flex justify-content-between align-items-center">
                <div class="me-2">
                  <div class="fw-bold">{{ p.code }}</div>
                  <div class="small text-muted text-truncate" style="max-width:220px">{{ p.name }}</div>
                </div>
                <span [class]="badge(p.status)">{{ statusLabel(p.status) }}</span>
              </div>

              @if (kpiMap()[p.id]; as k) {
                <div class="card-body p-3">
                  <div class="row g-2 text-center">
                    <div class="col-6">
                      <div class="small text-muted">Planifié</div>
                      <div class="fw-bold text-primary">{{ k.budgetPlanifie | number:'1.0-0' }} TND</div>
                    </div>
                    <div class="col-6">
                      <div class="small text-muted">Consommé</div>
                      <div class="fw-bold text-warning">{{ k.budgetConsome | number:'1.0-0' }} TND</div>
                    </div>
                    <div class="col-6">
                      <div class="small text-muted">EAC</div>
                      <div class="fw-bold text-info">{{ k.eac | number:'1.0-0' }} TND</div>
                    </div>
                    <div class="col-6">
                      <div class="small text-muted">Marge</div>
                      <div class="fw-bold" [class.text-success]="k.marge >= 0" [class.text-danger]="k.marge < 0">
                        {{ k.marge | number:'1.0-0' }} TND
                      </div>
                    </div>
                  </div>
                  <div class="mt-3">
                    <div class="d-flex justify-content-between small text-muted mb-1">
                      <span>Taux consommation</span>
                      <span>{{ (k.tauxConsommation * 100) | number:'1.1-1' }}%</span>
                    </div>
                    <div class="progress" style="height:6px">
                      <div class="progress-bar"
                           [class]="k.tauxConsommation > 1 ? 'bg-danger' : 'bg-primary'"
                           [style.width.%]="min(k.tauxConsommation * 100, 100)"></div>
                    </div>
                  </div>
                  <div class="text-end mt-2">
                    <a [routerLink]="['/projects', p.id]" class="btn btn-sm btn-outline-primary">
                      Détail <i class="bi bi-arrow-right ms-1"></i>
                    </a>
                  </div>
                </div>
              } @else if (state()[p.id] === 'error') {
                <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                     style="min-height:140px">
                  <i class="bi bi-exclamation-triangle text-warning fs-4"></i>
                  <span>Indicateurs indisponibles</span>
                  <button class="btn btn-sm btn-outline-secondary" (click)="load(p)">
                    <i class="bi bi-arrow-clockwise me-1"></i>Réessayer
                  </button>
                </div>
              } @else {
                <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                     style="min-height:140px">
                  <span class="spinner-border spinner-border-sm text-primary"></span>
                  <span>Chargement des indicateurs…</span>
                </div>
              }
            </div>
          </div>
        }
        @empty {
          <div class="col-12 text-center py-5 text-muted">
            <i class="bi bi-graph-up fs-1 d-block mb-3 opacity-25"></i>
            Aucun projet disponible
          </div>
        }
      </div>
    </div>
  `
})
export class KpiComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);

  projects = signal<Project[]>([]);
  kpiMap = signal<Record<number, KpiResponse>>({});
  state = signal<Record<number, LoadState>>({});

  ngOnInit(): void {
    this.projectSvc.list().subscribe(list => {
      this.projects.set(list);
      list.forEach(p => this.load(p));
    });
  }

  load(p: Project): void {
    this.state.update(s => ({ ...s, [p.id]: 'loading' }));
    this.projectSvc.getLiveKpi(p.id).subscribe({
      next: k => {
        this.kpiMap.update(m => ({ ...m, [p.id]: k }));
        this.state.update(s => ({ ...s, [p.id]: 'done' }));
      },
      error: () => this.state.update(s => ({ ...s, [p.id]: 'error' }))
    });
  }

  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge bg-primary', COMPLETED: 'badge bg-success',
      DRAFT: 'badge bg-secondary', ON_HOLD: 'badge bg-warning text-dark',
      CANCELLED: 'badge bg-danger'
    };
    return m[s] ?? 'badge bg-secondary';
  }

  min(a: number, b: number): number { return Math.min(a, b); }
}
