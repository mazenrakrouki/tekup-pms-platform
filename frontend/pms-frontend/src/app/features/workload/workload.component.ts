import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { WorkloadService } from '../../core/services/workload.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { Project } from '../../core/models/project.model';
import { PlanCharge, ChargeReelle } from '../../core/models/workload.model';

@Component({
  selector: 'app-workload',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-calendar3 me-2"></i>Charges de travail</h5>
    </div>
    <div class="p-4">
      <!-- Project selector -->
      <div class="card mb-4">
        <div class="card-body py-3">
          <div class="d-flex align-items-center gap-3 flex-wrap">
            <span class="fw-semibold text-muted small">PROJET :</span>
            @for (p of projects(); track p.id) {
              <button class="btn btn-sm"
                      [class]="selected()?.id === p.id ? 'btn-primary' : 'btn-outline-secondary'"
                      (click)="select(p)">
                {{ p.code }}
              </button>
            }
            @empty {
              <span class="text-muted small">Chargement…</span>
            }
          </div>
        </div>
      </div>

      @if (selected()) {
        <div class="row g-4">
          <!-- Plan de charge -->
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
                <span><i class="bi bi-calendar-week me-2"></i>Plan de charge — {{ selected()!.name }}</span>
                @if (canPlan()) {
                  <button class="btn btn-primary btn-sm" (click)="openPlanModal()">
                    <i class="bi bi-plus-lg me-1"></i>Planifier une charge
                  </button>
                }
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Ressource</th><th>Année</th><th>Mois</th><th class="text-end">Jours prévus</th></tr>
                  </thead>
                  <tbody>
                    @for (c of planCharges(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.plannedDays }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4" class="text-center py-4 text-muted">Aucune charge planifiée</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Charges réelles -->
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
                <span><i class="bi bi-clock-history me-2"></i>Charges réelles — {{ selected()!.name }}</span>
                @if (canSubmit()) {
                  <button class="btn btn-primary btn-sm" (click)="openChargeModal()">
                    <i class="bi bi-plus-lg me-1"></i>Saisir une charge
                  </button>
                }
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Ressource</th><th>Année</th><th>Mois</th><th class="text-end">Jours réels</th><th>Statut</th></tr>
                  </thead>
                  <tbody>
                    @for (c of chargesReelles(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.actualDays }}</td>
                        <td>
                          @if (c.validatedAt) {
                            <span class="badge bg-success">Validée</span>
                          } @else {
                            <span class="badge bg-warning text-dark">Soumise</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5" class="text-center py-4 text-muted">Aucune charge réelle</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      } @else {
        <div class="text-center py-5 text-muted">
          <i class="bi bi-calendar3 fs-1 d-block mb-3 opacity-25"></i>
          Sélectionnez un projet pour afficher ses charges
        </div>
      }
    </div>

    <!-- Modal saisie charge réelle -->
    @if (showChargeModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showChargeModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Saisir une charge réelle</h5>
              <button type="button" class="btn-close" (click)="showChargeModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Ressource <span class="text-danger">*</span></label>
                @if (isDevOnly()) {
                  <input type="text" class="form-control" [value]="currentUserFullName" disabled>
                } @else {
                  <select class="form-select" [(ngModel)]="chargeForm.userId">
                    <option [value]="0" disabled>Sélectionner une ressource</option>
                    @for (m of teamMembers(); track m.userId) {
                      <option [value]="m.userId">{{ m.userFullName }}</option>
                    }
                  </select>
                }
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Année <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="chargeForm.year" min="2000" max="2100">
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Mois <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="chargeForm.month">
                    @for (m of months; track m.v) {
                      <option [value]="m.v">{{ m.l }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="mb-3 mt-3">
                <label class="form-label fw-semibold">Jours travaillés <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="chargeForm.actualDays" min="0" max="31" step="0.5">
              </div>
              @if (chargeError()) {
                <div class="alert alert-danger py-2">{{ chargeError() }}</div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChargeModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="submitCharge()" [disabled]="chargeSaving()">
                @if (chargeSaving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Plan de charge -->
    @if (showPlanModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showPlanModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Planifier une charge</h5>
              <button type="button" class="btn-close" (click)="showPlanModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Ressource <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="planForm.userId">
                  <option [value]="0" disabled>Sélectionner une ressource</option>
                  @for (m of teamMembers(); track m.userId) {
                    <option [value]="m.userId">{{ m.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Année <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="planForm.year" min="2000" max="2100">
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Mois <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="planForm.month">
                    @for (m of months; track m.v) {
                      <option [value]="m.v">{{ m.l }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="mb-3 mt-3">
                <label class="form-label fw-semibold">Jours planifiés <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="planForm.plannedDays" min="0" max="31" step="0.5">
              </div>
              @if (planError()) { <div class="alert alert-danger py-2">{{ planError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showPlanModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="submitPlan()" [disabled]="planSaving()">
                @if (planSaving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class WorkloadComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);

  canPlan = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  canSubmit = () => this.auth.hasPermission('SUBMIT_WORKLOAD');
  isDevOnly = () => this.canSubmit() && !this.canPlan() && this.auth.currentUserId !== null;
  get currentUserFullName(): string { return this.auth.context()?.fullName ?? ''; }

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  planCharges = signal<PlanCharge[]>([]);
  chargesReelles = signal<ChargeReelle[]>([]);
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  showChargeModal = signal(false);
  chargeSaving = signal(false);
  chargeError = signal('');

  showPlanModal = signal(false);
  planSaving = signal(false);
  planError = signal('');

  chargeForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
  planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };

  readonly months = [
    { v: 1, l: 'Janvier' }, { v: 2, l: 'Février' }, { v: 3, l: 'Mars' },
    { v: 4, l: 'Avril' }, { v: 5, l: 'Mai' }, { v: 6, l: 'Juin' },
    { v: 7, l: 'Juillet' }, { v: 8, l: 'Août' }, { v: 9, l: 'Septembre' },
    { v: 10, l: 'Octobre' }, { v: 11, l: 'Novembre' }, { v: 12, l: 'Décembre' }
  ];

  ngOnInit(): void {
    this.projectSvc.list().subscribe(list => this.projects.set(list));
  }

  select(p: Project): void {
    this.selected.set(p);
    this.workloadSvc.listPlanCharges(p.id).subscribe(d => this.planCharges.set(d));
    this.workloadSvc.listChargesReelles(p.id).subscribe(d => this.chargesReelles.set(d));
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  openChargeModal(): void {
    const uid = this.isDevOnly() ? (this.auth.currentUserId ?? 0) : 0;
    this.chargeForm = { userId: uid, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
    this.chargeError.set('');
    this.showChargeModal.set(true);
  }

  submitCharge(): void {
    if (!this.chargeForm.userId || !this.chargeForm.actualDays) {
      this.chargeError.set('Ressource et jours sont requis.');
      return;
    }
    this.chargeSaving.set(true);
    this.chargeError.set('');
    this.workloadSvc.submitCharge(this.selected()!.id, this.chargeForm).subscribe({
      next: () => {
        this.workloadSvc.listChargesReelles(this.selected()!.id).subscribe(d => this.chargesReelles.set(d));
        this.showChargeModal.set(false);
        this.chargeSaving.set(false);
      },
      error: (e) => { this.chargeError.set(e.error?.message ?? 'Erreur lors de la soumission.'); this.chargeSaving.set(false); }
    });
  }

  openPlanModal(): void {
    this.planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };
    this.planError.set('');
    this.showPlanModal.set(true);
  }

  submitPlan(): void {
    if (!this.planForm.userId || !this.planForm.plannedDays) {
      this.planError.set('Ressource et jours sont requis.');
      return;
    }
    this.planSaving.set(true);
    this.planError.set('');
    this.workloadSvc.createPlanCharge(this.selected()!.id, this.planForm).subscribe({
      next: () => {
        this.workloadSvc.listPlanCharges(this.selected()!.id).subscribe(d => this.planCharges.set(d));
        this.showPlanModal.set(false);
        this.planSaving.set(false);
      },
      error: (e) => { this.planError.set(e.error?.message ?? 'Erreur lors de la planification.'); this.planSaving.set(false); }
    });
  }

  monthLabel(m: number): string {
    return ['Jan','Fév','Mar','Avr','Mai','Jun','Jul','Aoû','Sep','Oct','Nov','Déc'][m - 1] ?? String(m);
  }
}
