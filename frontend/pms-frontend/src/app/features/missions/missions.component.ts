import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { MissionService } from '../../core/services/mission.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { Mission, Composante } from '../../core/models/mission.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

@Component({
  selector: 'app-missions',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  styles: [`
    /* Expanded composantes sub-row — token-based so it adapts to dark mode (replaces bg-light/bg-white) */
    .sub-row > td { background: var(--surface-2); }
    .sub-table { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-sm); }
    .sub-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-airplane" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" [title]="'missions.backToPicker' | transloco">
            <i class="bi bi-arrow-left"></i> {{ 'nav.missions' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'nav.missions' | transloco }}</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.missions' | transloco }}</h1>
      </div>
      <!-- Project selector -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-airplane"
                            (projectSelected)="select($event)" />
      </div>

      @if (selected()) {
        <div class="card">
          <div class="card-header justify-content-between">
            <span><i class="bi bi-airplane me-2"></i>{{ 'nav.missions' | transloco }} — {{ selected()!.name }}</span>
            @if (canManage()) {
              <button class="btn btn-primary btn-sm" (click)="openMissionModal()">
                <i class="bi bi-plus-lg me-1"></i>{{ 'missions.newMission' | transloco }}
              </button>
            }
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr>
                  <th></th><th>{{ 'missions.colStaff' | transloco }}</th><th>{{ 'missions.colSubject' | transloco }}</th><th>{{ 'missions.colPlace' | transloco }}</th><th>{{ 'projects.colStart' | transloco }}</th><th>{{ 'projects.colEnd' | transloco }}</th><th>{{ 'missions.colDuration' | transloco }}</th>
                  @if (canManage()) { <th class="text-end">{{ 'common.actions' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
                @for (m of missions(); track m.id) {
                  <tr>
                    <td>
                      <button class="btn btn-sm btn-link p-0" (click)="toggleComposantes(m)" [title]="'missions.costComponents' | transloco">
                        <i class="bi" [class.bi-chevron-right]="expandedId() !== m.id" [class.bi-chevron-down]="expandedId() === m.id"></i>
                      </button>
                    </td>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.objet }}</td>
                    <td><i class="bi bi-geo-alt me-1 text-muted"></i>{{ m.lieu }}</td>
                    <td>{{ m.dateDebut }}</td>
                    <td>{{ m.dateFin }}</td>
                    <td class="text-muted small">{{ duration(m) }} j</td>
                    <td class="text-end">
                      @if (canManage()) {
                        <button class="btn btn-sm btn-outline-danger" (click)="deleteMission(m)"
                                [title]="'common.delete' | transloco" [attr.aria-label]="'missions.deleteMission' | transloco">
                          <i class="bi bi-trash"></i>
                        </button>
                      }
                    </td>
                  </tr>
                  @if (expandedId() === m.id) {
                    <tr class="sub-row">
                      <td></td>
                      <td [attr.colspan]="canManage() ? 7 : 6" class="py-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                          <span class="sub-title">{{ 'missions.costComponents' | transloco }}</span>
                          @if (canManage()) {
                            <button class="btn btn-sm btn-outline-primary" (click)="openComposanteModal(m)">
                              <i class="bi bi-plus-lg me-1"></i>{{ 'missions.addComponent' | transloco }}
                            </button>
                          }
                        </div>
                        <table class="table table-sm mb-0 sub-table align-middle">
                          <thead>
                            <tr><th>{{ 'missions.colType' | transloco }}</th><th>{{ 'common.description' | transloco }}</th><th class="text-end">{{ 'missions.colAmount' | transloco }}</th><th>{{ 'missions.colCurrency' | transloco }}</th><th></th></tr>
                          </thead>
                          <tbody>
                            @for (c of composantes(); track c.id) {
                              <tr>
                                <td><span class="badge-draft">{{ 'componentType.' + c.typeComposante | transloco }}</span></td>
                                <td class="small">{{ c.description ?? '—' }}</td>
                                <td class="text-end fw-semibold">{{ c.montant | number:'1.0-2' }}</td>
                                <td>{{ c.devise }}</td>
                                <td class="text-end">
                                  @if (canManage()) {
                                    <button class="btn btn-sm btn-outline-danger" (click)="deleteComposante(m, c)"
                                            [title]="'common.delete' | transloco" [attr.aria-label]="'missions.deleteComponent' | transloco">
                                      <i class="bi bi-trash"></i>
                                    </button>
                                  }
                                </td>
                              </tr>
                            }
                            @empty {
                              <tr><td colspan="5" class="text-center py-2 text-muted small">{{ 'missions.noComponents' | transloco }}</td></tr>
                            }
                          </tbody>
                          @if (composantes().length) {
                            <tfoot>
                              <tr class="fw-semibold">
                                <td colspan="2" class="text-end">{{ 'common.total' | transloco }}</td>
                                <td class="text-end">{{ composanteTotal() | number:'1.0-2' }}</td>
                                <td colspan="2"></td>
                              </tr>
                            </tfoot>
                          }
                        </table>
                      </td>
                    </tr>
                  }
                }
                @empty {
                  <tr><td colspan="8">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-airplane"></i></div>
                      <div class="es-title">{{ 'missions.empty' | transloco }}</div>
                      <div class="es-desc">{{ 'missions.emptyDesc' | transloco }}</div>
                      @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openMissionModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'missions.newMission' | transloco }}</button> }
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>

    <!-- Modal Mission -->
    @if (showMissionModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showMissionModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'missions.newMission' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showMissionModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colStaff' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="missionForm.userId">
                  <option [value]="0" disabled>{{ 'missions.select' | transloco }}</option>
                  @for (t of teamMembers(); track t.userId) {
                    <option [value]="t.userId">{{ t.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colSubject' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.objet" [placeholder]="'missions.subjectPh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colPlace' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.lieu" [placeholder]="'missions.placePh' | transloco">
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'missions.startDate' | transloco }} <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateDebut">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'missions.endDate' | transloco }} <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateFin">
                </div>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showMissionModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveMission()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Composante -->
    @if (showComposanteModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showComposanteModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'missions.addComponentTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showComposanteModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colType' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="composanteForm.typeComposante">
                  <option value="PERDIEM">{{ 'labels.missionItem.PERDIEM' | transloco }}</option>
                  <option value="BILLET">{{ 'labels.missionItem.BILLET' | transloco }}</option>
                  <option value="TRANSPORT">{{ 'labels.missionItem.TRANSPORT' | transloco }}</option>
                  <option value="SEJOUR">{{ 'labels.missionItem.SEJOUR' | transloco }}</option>
                  <option value="TIMBRE">{{ 'labels.missionItem.TIMBRE' | transloco }}</option>
                </select>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-7">
                  <label class="form-label">{{ 'missions.colAmount' | transloco }} <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="composanteForm.montant" min="0" step="0.01">
                </div>
                <div class="col-5">
                  <label class="form-label">{{ 'missions.colCurrency' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="composanteForm.devise">
                    <option value="TND">TND</option>
                    <option value="EUR">EUR</option>
                    <option value="USD">USD</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'common.description' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="composanteForm.description" [placeholder]="'missions.optionalPh' | transloco">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showComposanteModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveComposante()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class MissionsComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly missionSvc = inject(MissionService);
  private readonly teamSvc    = inject(TeamService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  canManage = () => this.auth.hasPermission('MANAGE_MISSION');

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  missions = signal<Mission[]>([]);
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  expandedId = signal<number | null>(null);
  composantes = signal<Composante[]>([]);

  showMissionModal = signal(false);
  showComposanteModal = signal(false);
  saving = signal(false);
  modalError = signal('');
  private composanteMissionId = 0;

  missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
  composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };

  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        const project = list.find(p => String(p.id) === pid);
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.expandedId.set(null);
          this.missionSvc.list(project.id).subscribe(d => this.missions.set(d));
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
          );
        }
      });
    });
  }

  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.expandedId.set(null);
    this.missionSvc.list(p.id).subscribe(d => this.missions.set(d));
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  duration(m: Mission): number {
    const d = (new Date(m.dateFin).getTime() - new Date(m.dateDebut).getTime()) / 86_400_000;
    return Math.max(1, Math.round(d) + 1);
  }

  toggleComposantes(m: Mission): void {
    if (this.expandedId() === m.id) { this.expandedId.set(null); return; }
    this.expandedId.set(m.id);
    this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d));
  }

  composanteTotal(): number {
    return this.composantes().reduce((s, c) => s + Number(c.montant), 0);
  }

  // ── Mission ──────────────────────────────────────────────────────
  openMissionModal(): void {
    this.missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
    this.modalError.set('');
    this.showMissionModal.set(true);
  }

  saveMission(): void {
    const f = this.missionForm;
    if (!f.userId || !f.objet || !f.dateDebut || !f.dateFin) {
      this.modalError.set(this.tr.translate('missions.errRequired'));
      return;
    }
    if (f.dateFin < f.dateDebut) { this.modalError.set(this.tr.translate('missions.errDateOrder')); return; }
    this.saving.set(true);
    this.missionSvc.create(this.selected()!.id, f).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        this.showMissionModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('missions.okMissionCreated'));
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteMission(m: Mission): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteMission', { name: m.objet }), this.tr.translate('missions.deleteMission'))) return;
    this.missionSvc.delete(this.selected()!.id, m.id).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        if (this.expandedId() === m.id) this.expandedId.set(null);
        this.toast.success(this.tr.translate('missions.okMissionDeleted'));
      },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Composante ───────────────────────────────────────────────────
  openComposanteModal(m: Mission): void {
    this.composanteMissionId = m.id;
    this.composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };
    this.modalError.set('');
    this.showComposanteModal.set(true);
  }

  saveComposante(): void {
    if (!this.composanteForm.montant || this.composanteForm.montant <= 0) {
      this.modalError.set(this.tr.translate('missions.errAmountPositive'));
      return;
    }
    this.saving.set(true);
    this.missionSvc.createComposante(this.selected()!.id, this.composanteMissionId, this.composanteForm).subscribe({
      next: () => {
        this.missionSvc.listComposantes(this.selected()!.id, this.composanteMissionId).subscribe(d => this.composantes.set(d));
        this.showComposanteModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('missions.okComponentAdded'));
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteComposante(m: Mission, c: Composante): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteComponent'), this.tr.translate('missions.deleteComponent'))) return;
    this.missionSvc.deleteComposante(this.selected()!.id, m.id, c.id).subscribe({
      next: () => { this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d)); this.toast.success(this.tr.translate('missions.okComponentDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
