import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { GovernanceService } from '../../core/services/governance.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { Project } from '../../core/models/project.model';
import { Risk, Livrable, DemandeChangement, NiveauRisque } from '../../core/models/governance.model';
import { PartiePrenante } from '../../core/models/partie-prenante.model';

type GovTab = 'risks' | 'livrables' | 'changes' | 'parties';

@Component({
  selector: 'app-governance',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-shield-check me-2"></i>Gouvernance de projet</h5>
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
        <!-- Sub-tabs -->
        <ul class="nav nav-pills mb-3">
          <li class="nav-item">
            <button class="nav-link" [class.active]="govTab()==='risks'" (click)="govTab.set('risks')">
              <i class="bi bi-exclamation-triangle me-1"></i>Risques ({{ risks().length }})
            </button>
          </li>
          <li class="nav-item">
            <button class="nav-link" [class.active]="govTab()==='livrables'" (click)="govTab.set('livrables')">
              <i class="bi bi-check2-square me-1"></i>Livrables ({{ livrables().length }})
            </button>
          </li>
          <li class="nav-item">
            <button class="nav-link" [class.active]="govTab()==='changes'" (click)="govTab.set('changes')">
              <i class="bi bi-arrow-repeat me-1"></i>Changements ({{ changes().length }})
            </button>
          </li>
          <li class="nav-item">
            <button class="nav-link" [class.active]="govTab()==='parties'" (click)="govTab.set('parties')">
              <i class="bi bi-person-lines-fill me-1"></i>Parties prenantes ({{ parties().length }})
            </button>
          </li>
        </ul>

        <!-- RISQUES -->
        @if (govTab() === 'risks') {
          <div class="card">
            <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
              <span>Registre des risques — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openRiskModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter un risque
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead class="table-light">
                  <tr><th>Description</th><th>Probabilité</th><th>Impact</th><th>Plan de mitigation</th><th>Statut</th><th></th></tr>
                </thead>
                <tbody>
                  @for (r of risks(); track r.id) {
                    <tr>
                      <td>{{ r.description }}</td>
                      <td><span [class]="niveauBadge(r.probabilite)">{{ r.probabilite }}</span></td>
                      <td><span [class]="niveauBadge(r.impact)">{{ r.impact }}</span></td>
                      <td class="text-muted small">{{ r.planMitigation ?? '—' }}</td>
                      <td>
                        @if (r.statut === 'FERME') { <span class="badge bg-success">Fermé</span> }
                        @else if (r.statut === 'MITIGE') { <span class="badge bg-warning text-dark">Mitigé</span> }
                        @else { <span class="badge bg-danger">Ouvert</span> }
                      </td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteRisk(r)">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="6" class="text-center py-4 text-muted">Aucun risque enregistré</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- LIVRABLES -->
        @if (govTab() === 'livrables') {
          <div class="card">
            <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
              <span>Livrables — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openLivrableModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter un livrable
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead class="table-light">
                  <tr><th>Titre</th><th>Description</th><th>Échéance</th><th>Statut</th><th>Actions</th></tr>
                </thead>
                <tbody>
                  @for (l of livrables(); track l.id) {
                    <tr>
                      <td class="fw-semibold">{{ l.titre }}</td>
                      <td class="text-muted small">{{ l.description ?? '—' }}</td>
                      <td>{{ l.dateEcheance ?? '—' }}</td>
                      <td><span [class]="livrableBadge(l.statut)">{{ l.statut }}</span></td>
                      <td>
                        @if (canManage()) {
                          @if (l.statut === 'EN_ATTENTE') {
                            <button class="btn btn-sm btn-outline-primary me-1" (click)="demarrerLivrable(l)" title="Démarrer">
                              <i class="bi bi-play-fill"></i>
                            </button>
                          }
                          @if (l.statut === 'EN_COURS') {
                            <button class="btn btn-sm btn-outline-info me-1" (click)="livrerLivrable(l)" title="Livrer">
                              <i class="bi bi-box-arrow-up"></i>
                            </button>
                          }
                          @if (l.statut === 'LIVRE') {
                            <button class="btn btn-sm btn-outline-success me-1" (click)="validerLivrable(l)" title="Valider">
                              <i class="bi bi-check-lg"></i>
                            </button>
                          }
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteLivrable(l)">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="5" class="text-center py-4 text-muted">Aucun livrable</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- CHANGEMENTS -->
        @if (govTab() === 'changes') {
          <div class="card">
            <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
              <span>Demandes de changement — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openChangeModal()">
                  <i class="bi bi-plus-lg me-1"></i>Nouvelle demande
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead class="table-light">
                  <tr><th>Titre</th><th>Demandeur</th><th>Priorité</th><th>Date</th><th>Statut</th><th>Actions</th></tr>
                </thead>
                <tbody>
                  @for (dc of changes(); track dc.id) {
                    <tr>
                      <td class="fw-semibold">{{ dc.titre }}</td>
                      <td>{{ dc.demandeurFullName }}</td>
                      <td><span [class]="prioriteBadge(dc.priorite)">{{ dc.priorite }}</span></td>
                      <td>{{ dc.dateDemande ?? '—' }}</td>
                      <td>
                        @if (dc.statut === 'APPROUVE') { <span class="badge bg-success">Approuvé</span> }
                        @else if (dc.statut === 'REJETE') { <span class="badge bg-danger">Rejeté</span> }
                        @else { <span class="badge bg-warning text-dark">En attente</span> }
                      </td>
                      <td>
                        @if (canManage()) {
                          @if (dc.statut === 'EN_ATTENTE') {
                            <button class="btn btn-sm btn-outline-success me-1" (click)="approuver(dc)" title="Approuver">
                              <i class="bi bi-check-lg"></i>
                            </button>
                            <button class="btn btn-sm btn-outline-danger me-1" (click)="rejeter(dc)" title="Rejeter">
                              <i class="bi bi-x-lg"></i>
                            </button>
                          }
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteChange(dc)">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="6" class="text-center py-4 text-muted">Aucune demande de changement</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- PARTIES PRENANTES -->
        @if (govTab() === 'parties') {
          <div class="card">
            <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
              <span>Parties prenantes — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openPartieModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter une partie prenante
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead class="table-light">
                  <tr><th>Nom</th><th>Fonction</th><th>Email</th><th>Téléphone</th><th>Influence</th><th>Intérêt</th><th></th></tr>
                </thead>
                <tbody>
                  @for (pp of parties(); track pp.id) {
                    <tr>
                      <td class="fw-semibold">{{ pp.nom }}</td>
                      <td>{{ pp.fonction ?? '—' }}</td>
                      <td class="small">{{ pp.email ?? '—' }}</td>
                      <td class="small">{{ pp.telephone ?? '—' }}</td>
                      <td><span [class]="niveauBadge(pp.influence)">{{ pp.influence }}</span></td>
                      <td><span [class]="niveauBadge(pp.interet)">{{ pp.interet }}</span></td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deletePartie(pp)">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="7" class="text-center py-4 text-muted">Aucune partie prenante</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      } @else {
        <div class="text-center py-5 text-muted">
          <i class="bi bi-shield-check fs-1 d-block mb-3 opacity-25"></i>
          Sélectionnez un projet pour afficher sa gouvernance
        </div>
      }
    </div>

    <!-- Modal Partie prenante -->
    @if (showPartieModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showPartieModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Ajouter une partie prenante</h5>
              <button type="button" class="btn-close" (click)="showPartieModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Nom <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="partieForm.nom" placeholder="Nom complet">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Fonction</label>
                <input type="text" class="form-control" [(ngModel)]="partieForm.fonction" placeholder="Ex: Sponsor, Client...">
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Email</label>
                  <input type="email" class="form-control" [(ngModel)]="partieForm.email" placeholder="email@example.com">
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Téléphone</label>
                  <input type="text" class="form-control" [(ngModel)]="partieForm.telephone" placeholder="+216 ...">
                </div>
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Influence <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="partieForm.influence">
                    <option value="FAIBLE">Faible</option>
                    <option value="MOYEN">Moyen</option>
                    <option value="ELEVE">Élevé</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Intérêt <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="partieForm.interet">
                    <option value="FAIBLE">Faible</option>
                    <option value="MOYEN">Moyen</option>
                    <option value="ELEVE">Élevé</option>
                  </select>
                </div>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showPartieModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="savePartie()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Risque -->
    @if (showRiskModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showRiskModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Ajouter un risque</h5>
              <button type="button" class="btn-close" (click)="showRiskModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Description <span class="text-danger">*</span></label>
                <textarea class="form-control" rows="2" [(ngModel)]="riskForm.description" placeholder="Description du risque"></textarea>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Probabilité <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="riskForm.probabilite">
                    <option value="FAIBLE">Faible</option>
                    <option value="MOYEN">Moyen</option>
                    <option value="ELEVE">Élevé</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Impact <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="riskForm.impact">
                    <option value="FAIBLE">Faible</option>
                    <option value="MOYEN">Moyen</option>
                    <option value="ELEVE">Élevé</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Plan de mitigation</label>
                <textarea class="form-control" rows="2" [(ngModel)]="riskForm.planMitigation" placeholder="Actions pour mitiger le risque"></textarea>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Statut <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="riskForm.statut">
                  <option value="OUVERT">Ouvert</option>
                  <option value="MITIGE">Mitigé</option>
                  <option value="FERME">Fermé</option>
                </select>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showRiskModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveRisk()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Livrable -->
    @if (showLivrableModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showLivrableModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Ajouter un livrable</h5>
              <button type="button" class="btn-close" (click)="showLivrableModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Titre <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="livrableForm.titre" placeholder="Titre du livrable">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Description</label>
                <textarea class="form-control" rows="2" [(ngModel)]="livrableForm.description" placeholder="Description optionnelle"></textarea>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date d'échéance</label>
                <input type="date" class="form-control" [(ngModel)]="livrableForm.dateEcheance">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showLivrableModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveLivrable()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Changement -->
    @if (showChangeModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showChangeModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Nouvelle demande de changement</h5>
              <button type="button" class="btn-close" (click)="showChangeModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Titre <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="changeForm.titre" placeholder="Titre de la demande">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Demandeur <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="changeForm.demandeurId">
                  <option [value]="0" disabled>Sélectionner un membre</option>
                  @for (m of teamMembers(); track m.userId) {
                    <option [value]="m.userId">{{ m.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Description</label>
                <textarea class="form-control" rows="2" [(ngModel)]="changeForm.description" placeholder="Description optionnelle"></textarea>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">Priorité <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="changeForm.priorite">
                    <option value="FAIBLE">Faible</option>
                    <option value="NORMALE">Normale</option>
                    <option value="ELEVEE">Élevée</option>
                    <option value="CRITIQUE">Critique</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Date <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="changeForm.dateDemande">
                </div>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChangeModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveChange()" [disabled]="saving()">
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
export class GovernanceComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly govSvc = inject(GovernanceService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);

  canManage = () => this.auth.hasPermission('MANAGE_GOVERNANCE');

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  govTab = signal<GovTab>('risks');
  risks = signal<Risk[]>([]);
  livrables = signal<Livrable[]>([]);
  changes = signal<DemandeChangement[]>([]);
  parties = signal<PartiePrenante[]>([]);
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  showRiskModal = signal(false);
  showLivrableModal = signal(false);
  showChangeModal = signal(false);
  showPartieModal = signal(false);
  saving = signal(false);
  modalError = signal('');

  riskForm = { description: '', probabilite: 'MOYEN', impact: 'MOYEN', planMitigation: '', statut: 'OUVERT' };
  livrableForm = { titre: '', description: '', dateEcheance: '' };
  changeForm = { demandeurId: 0, titre: '', description: '', priorite: 'NORMALE', dateDemande: new Date().toISOString().split('T')[0] };
  partieForm = { nom: '', fonction: '', email: '', telephone: '', influence: 'MOYEN', interet: 'MOYEN' };

  ngOnInit(): void {
    this.projectSvc.list().subscribe(list => this.projects.set(list));
  }

  select(p: Project): void {
    this.selected.set(p);
    this.reload();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  reload(): void {
    const p = this.selected();
    if (!p) return;
    this.govSvc.listRisks(p.id).subscribe(d => this.risks.set(d));
    this.govSvc.listLivrables(p.id).subscribe(d => this.livrables.set(d));
    this.govSvc.listChanges(p.id).subscribe(d => this.changes.set(d));
    this.govSvc.listParties(p.id).subscribe(d => this.parties.set(d));
  }

  // ── Parties prenantes ────────────────────────────────────────────
  openPartieModal(): void {
    this.partieForm = { nom: '', fonction: '', email: '', telephone: '', influence: 'MOYEN', interet: 'MOYEN' };
    this.modalError.set('');
    this.showPartieModal.set(true);
  }

  savePartie(): void {
    if (!this.partieForm.nom) { this.modalError.set('Le nom est requis.'); return; }
    this.saving.set(true);
    this.govSvc.createPartie(this.selected()!.id, this.partieForm).subscribe({
      next: () => { this.reload(); this.showPartieModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deletePartie(pp: PartiePrenante): void {
    if (!confirm(`Supprimer "${pp.nom}" ?`)) return;
    this.govSvc.deletePartie(this.selected()!.id, pp.id).subscribe(() => this.reload());
  }

  // ── Risques ──────────────────────────────────────────────────────
  openRiskModal(): void {
    this.riskForm = { description: '', probabilite: 'MOYEN', impact: 'MOYEN', planMitigation: '', statut: 'OUVERT' };
    this.modalError.set('');
    this.showRiskModal.set(true);
  }

  saveRisk(): void {
    if (!this.riskForm.description) { this.modalError.set('Description requise.'); return; }
    this.saving.set(true);
    this.govSvc.createRisk(this.selected()!.id, this.riskForm).subscribe({
      next: () => { this.reload(); this.showRiskModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deleteRisk(r: Risk): void {
    if (!confirm('Supprimer ce risque ?')) return;
    this.govSvc.deleteRisk(this.selected()!.id, r.id).subscribe(() => this.reload());
  }

  // ── Livrables ────────────────────────────────────────────────────
  openLivrableModal(): void {
    this.livrableForm = { titre: '', description: '', dateEcheance: '' };
    this.modalError.set('');
    this.showLivrableModal.set(true);
  }

  saveLivrable(): void {
    if (!this.livrableForm.titre) { this.modalError.set('Titre requis.'); return; }
    this.saving.set(true);
    this.govSvc.createLivrable(this.selected()!.id, this.livrableForm).subscribe({
      next: () => { this.reload(); this.showLivrableModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  demarrerLivrable(l: Livrable): void {
    this.govSvc.demarrerLivrable(this.selected()!.id, l.id).subscribe(() => this.reload());
  }

  livrerLivrable(l: Livrable): void {
    this.govSvc.livrerLivrable(this.selected()!.id, l.id).subscribe(() => this.reload());
  }

  validerLivrable(l: Livrable): void {
    this.govSvc.validerLivrable(this.selected()!.id, l.id).subscribe(() => this.reload());
  }

  deleteLivrable(l: Livrable): void {
    if (!confirm(`Supprimer le livrable "${l.titre}" ?`)) return;
    this.govSvc.deleteLivrable(this.selected()!.id, l.id).subscribe(() => this.reload());
  }

  // ── Changements ──────────────────────────────────────────────────
  openChangeModal(): void {
    this.changeForm = { demandeurId: 0, titre: '', description: '', priorite: 'NORMALE', dateDemande: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    this.showChangeModal.set(true);
  }

  saveChange(): void {
    if (!this.changeForm.titre || !this.changeForm.demandeurId) {
      this.modalError.set('Titre et demandeur sont requis.');
      return;
    }
    this.saving.set(true);
    this.govSvc.createChangement(this.selected()!.id, this.changeForm).subscribe({
      next: () => { this.reload(); this.showChangeModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  approuver(dc: DemandeChangement): void {
    this.govSvc.approuverChangement(this.selected()!.id, dc.id).subscribe(() => this.reload());
  }

  rejeter(dc: DemandeChangement): void {
    this.govSvc.rejeterChangement(this.selected()!.id, dc.id).subscribe(() => this.reload());
  }

  deleteChange(dc: DemandeChangement): void {
    if (!confirm(`Supprimer la demande "${dc.titre}" ?`)) return;
    this.govSvc.deleteChangement(this.selected()!.id, dc.id).subscribe(() => this.reload());
  }

  // ── Badges ───────────────────────────────────────────────────────
  niveauBadge(n: NiveauRisque): string {
    return n === 'ELEVE' ? 'badge bg-danger' : n === 'MOYEN' ? 'badge bg-warning text-dark' : 'badge bg-success';
  }

  livrableBadge(s: string): string {
    const m: Record<string, string> = {
      EN_ATTENTE: 'badge bg-secondary', EN_COURS: 'badge bg-primary',
      LIVRE: 'badge bg-info text-dark', VALIDE: 'badge bg-success'
    };
    return m[s] ?? 'badge bg-secondary';
  }

  prioriteBadge(p: string): string {
    const m: Record<string, string> = {
      FAIBLE: 'badge bg-success', NORMALE: 'badge bg-info text-dark',
      ELEVEE: 'badge bg-warning text-dark', CRITIQUE: 'badge bg-danger'
    };
    return m[p] ?? 'badge bg-secondary';
  }
}
