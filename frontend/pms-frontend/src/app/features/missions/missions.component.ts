import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { MissionService } from '../../core/services/mission.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { Project } from '../../core/models/project.model';
import { Mission, Composante } from '../../core/models/mission.model';

@Component({
  selector: 'app-missions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-airplane me-2"></i>Missions</h5>
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
          </div>
        </div>
      </div>

      @if (selected()) {
        <div class="card">
          <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
            <span><i class="bi bi-airplane me-2"></i>Missions — {{ selected()!.name }}</span>
            @if (canManage()) {
              <button class="btn btn-primary btn-sm" (click)="openMissionModal()">
                <i class="bi bi-plus-lg me-1"></i>Nouvelle mission
              </button>
            }
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead class="table-light">
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
                        <button class="btn btn-sm btn-outline-danger" (click)="deleteMission(m)" title="Supprimer">
                          <i class="bi bi-trash"></i>
                        </button>
                      }
                    </td>
                  </tr>
                  @if (expandedId() === m.id) {
                    <tr class="bg-light">
                      <td></td>
                      <td [attr.colspan]="canManage() ? 7 : 6" class="py-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                          <span class="fw-semibold small text-muted">COMPOSANTES DE COÛT</span>
                          @if (canManage()) {
                            <button class="btn btn-sm btn-outline-primary" (click)="openComposanteModal(m)">
                              <i class="bi bi-plus-lg me-1"></i>Ajouter une composante
                            </button>
                          }
                        </div>
                        <table class="table table-sm mb-0 bg-white align-middle">
                          <thead>
                            <tr><th>Type</th><th>Description</th><th class="text-end">Montant</th><th>Devise</th><th></th></tr>
                          </thead>
                          <tbody>
                            @for (c of composantes(); track c.id) {
                              <tr>
                                <td><span class="badge bg-info text-dark">{{ c.typeComposante }}</span></td>
                                <td class="small">{{ c.description ?? '—' }}</td>
                                <td class="text-end fw-semibold">{{ c.montant | number:'1.0-2' }}</td>
                                <td>{{ c.devise }}</td>
                                <td class="text-end">
                                  @if (canManage()) {
                                    <button class="btn btn-sm btn-outline-danger" (click)="deleteComposante(m, c)">
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
                  <tr><td colspan="8" class="text-center py-4 text-muted">Aucune mission</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      } @else {
        <div class="text-center py-5 text-muted">
          <i class="bi bi-airplane fs-1 d-block mb-3 opacity-25"></i>
          Sélectionnez un projet pour afficher ses missions
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
                <label class="form-label fw-semibold">Collaborateur <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="missionForm.userId">
                  <option [value]="0" disabled>Sélectionner</option>
                  @for (t of teamMembers(); track t.userId) {
                    <option [value]="t.userId">{{ t.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Objet <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.objet" placeholder="Objet de la mission">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Lieu</label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.lieu" placeholder="Ville / pays">
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Date début <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateDebut">
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Date fin <span class="text-danger">*</span></label>
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
                <label class="form-label fw-semibold">Type <span class="text-danger">*</span></label>
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
                  <label class="form-label fw-semibold">Montant <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="composanteForm.montant" min="0" step="0.01">
                </div>
                <div class="col-5">
                  <label class="form-label fw-semibold">Devise <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="composanteForm.devise">
                    <option value="TND">TND</option>
                    <option value="EUR">EUR</option>
                    <option value="USD">USD</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Description</label>
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
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);

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
    this.projectSvc.list().subscribe(list => this.projects.set(list));
  }

  select(p: Project): void {
    this.selected.set(p);
    this.expandedId.set(null);
    this.missionSvc.list(p.id).subscribe(d => this.missions.set(d));
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
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
        this.showMissionModal.set(false); this.saving.set(false);
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deleteMission(m: Mission): void {
    if (!confirm(`Supprimer la mission "${m.objet}" ?`)) return;
    this.missionSvc.delete(this.selected()!.id, m.id).subscribe(() => {
      this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
      if (this.expandedId() === m.id) this.expandedId.set(null);
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
        this.showComposanteModal.set(false); this.saving.set(false);
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deleteComposante(m: Mission, c: Composante): void {
    if (!confirm('Supprimer cette composante ?')) return;
    this.missionSvc.deleteComposante(this.selected()!.id, m.id, c.id).subscribe(() =>
      this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d))
    );
  }
}
