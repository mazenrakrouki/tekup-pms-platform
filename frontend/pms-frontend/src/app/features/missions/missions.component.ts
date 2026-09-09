import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
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
          <button class="bc-back-btn" (click)="clearSelection()" title="Retour à la sélection de projet">
            <i class="bi bi-arrow-left"></i> Missions
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">Missions</span>
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
            <span><i class="bi bi-airplane me-2"></i>Missions — {{ selected()!.name }}</span>
            @if (canManage()) {
              <button class="btn btn-primary btn-sm" (click)="openMissionModal()">
                <i class="bi bi-plus-lg me-1"></i>Nouvelle mission
              </button>
            }
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr>
                  <th></th><th>Collaborateur</th><th>Objet</th><th>Lieu</th><th>Début</th><th>Fin</th><th>Durée</th>
                  @if (canManage()) { <th class="text-end">Actions</th> }
                </tr>
              </thead>
              <tbody>
                @for (m of missions(); track m.id) {
                  <tr>
                    <td>
                      <button class="btn btn-sm btn-link p-0" (click)="toggleComposantes(m)" title="Composantes de coût">
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
                                title="Supprimer" aria-label="Supprimer la mission">
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
                          <span class="sub-title">COMPOSANTES DE COÛT</span>
                          @if (canManage()) {
                            <button class="btn btn-sm btn-outline-primary" (click)="openComposanteModal(m)">
                              <i class="bi bi-plus-lg me-1"></i>Ajouter une composante
                            </button>
                          }
                        </div>
                        <table class="table table-sm mb-0 sub-table align-middle">
                          <thead>
                            <tr><th>Type</th><th>Description</th><th class="text-end">Montant</th><th>Devise</th><th></th></tr>
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
                                            title="Supprimer" aria-label="Supprimer la composante">
                                      <i class="bi bi-trash"></i>
                                    </button>
                                  }
                                </td>
                              </tr>
                            }
                            @empty {
                              <tr><td colspan="5" class="text-center py-2 text-muted small">Aucune composante</td></tr>
                            }
                          </tbody>
                          @if (composantes().length) {
                            <tfoot>
                              <tr class="fw-semibold">
                                <td colspan="2" class="text-end">Total</td>
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
                      <div class="es-title">Aucune mission</div>
                      <div class="es-desc">Planifiez les déplacements et missions de l'équipe.</div>
                      @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openMissionModal()"><i class="bi bi-plus-lg me-1"></i>Nouvelle mission</button> }
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
              <h5 class="modal-title">Nouvelle mission</h5>
              <button type="button" class="btn-close" (click)="showMissionModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">Collaborateur <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="missionForm.userId">
                  <option [value]="0" disabled>Sélectionner</option>
                  @for (t of teamMembers(); track t.userId) {
                    <option [value]="t.userId">{{ t.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label">Objet <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.objet" placeholder="Objet de la mission">
              </div>
              <div class="mb-3">
                <label class="form-label">Lieu</label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.lieu" placeholder="Ville / pays">
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">Date début <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateDebut">
                </div>
                <div class="col-6">
                  <label class="form-label">Date fin <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateFin">
                </div>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showMissionModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveMission()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
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
              <h5 class="modal-title">Ajouter une composante de coût</h5>
              <button type="button" class="btn-close" (click)="showComposanteModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">Type <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="composanteForm.typeComposante">
                  <option value="PERDIEM">Per diem</option>
                  <option value="BILLET">Billet</option>
                  <option value="TRANSPORT">Transport</option>
                  <option value="SEJOUR">Séjour</option>
                  <option value="TIMBRE">Timbre</option>
                </select>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-7">
                  <label class="form-label">Montant <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="composanteForm.montant" min="0" step="0.01">
                </div>
                <div class="col-5">
                  <label class="form-label">Devise <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="composanteForm.devise">
                    <option value="TND">TND</option>
                    <option value="EUR">EUR</option>
                    <option value="USD">USD</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label">Description</label>
                <input type="text" class="form-control" [(ngModel)]="composanteForm.description" placeholder="Optionnel">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showComposanteModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveComposante()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
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
      this.modalError.set('Collaborateur, objet et dates sont requis.');
      return;
    }
    if (f.dateFin < f.dateDebut) { this.modalError.set('La date de fin doit être après la date de début.'); return; }
    this.saving.set(true);
    this.missionSvc.create(this.selected()!.id, f).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        this.showMissionModal.set(false); this.saving.set(false); this.toast.success('Mission créée.');
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteMission(m: Mission): Promise<void> {
    if (!await this.confirm.ask(`Supprimer la mission « ${m.objet} » ?`, 'Supprimer la mission')) return;
    this.missionSvc.delete(this.selected()!.id, m.id).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        if (this.expandedId() === m.id) this.expandedId.set(null);
        this.toast.success('Mission supprimée.');
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
      this.modalError.set('Le montant doit être positif.');
      return;
    }
    this.saving.set(true);
    this.missionSvc.createComposante(this.selected()!.id, this.composanteMissionId, this.composanteForm).subscribe({
      next: () => {
        this.missionSvc.listComposantes(this.selected()!.id, this.composanteMissionId).subscribe(d => this.composantes.set(d));
        this.showComposanteModal.set(false); this.saving.set(false); this.toast.success('Composante ajoutée.');
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteComposante(m: Mission, c: Composante): Promise<void> {
    if (!await this.confirm.ask('Supprimer cette composante ?', 'Supprimer la composante')) return;
    this.missionSvc.deleteComposante(this.selected()!.id, m.id, c.id).subscribe({
      next: () => { this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d)); this.toast.success('Composante supprimée.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
