import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { GovernanceService } from '../../core/services/governance.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { Risk, Livrable, DemandeChangement, NiveauRisque } from '../../core/models/governance.model';
import { PartiePrenante } from '../../core/models/partie-prenante.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

type GovTab = 'risks' | 'livrables' | 'changes' | 'parties';

@Component({
  selector: 'app-governance',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-shield-check" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" title="Retour à la sélection de projet">
            <i class="bi bi-arrow-left"></i> Gouvernance
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">Gouvernance</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.governance' | transloco }}</h1>
      </div>
      <!-- Project selector -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-shield-check"
                            (projectSelected)="select($event)" />
      </div>

      @if (selected()) {
        <!-- Sub-tabs (design-system segmented control) -->
        <div class="pms-tabs mb-3" style="width:fit-content;max-width:100%;overflow-x:auto">
          <button class="tab-item" [class.active]="govTab()==='risks'" (click)="setGovTab('risks')">
            <i class="bi bi-exclamation-triangle me-1"></i>Risques ({{ risks().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='livrables'" (click)="setGovTab('livrables')">
            <i class="bi bi-check2-square me-1"></i>Livrables ({{ livrables().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='changes'" (click)="setGovTab('changes')">
            <i class="bi bi-arrow-repeat me-1"></i>Changements ({{ changes().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='parties'" (click)="setGovTab('parties')">
            <i class="bi bi-person-lines-fill me-1"></i>Parties prenantes ({{ parties().length }})
          </button>
        </div>

        <!-- RISQUES -->
        @if (govTab() === 'risks') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>Registre des risques — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openRiskModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter un risque
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>Description</th><th>Probabilité</th><th>Impact</th><th>Plan de mitigation</th><th>Statut</th><th></th></tr>
                </thead>
                <tbody>
                  @for (r of risks(); track r.id) {
                    <tr>
                      <td>{{ r.description }}</td>
                      <td><span [class]="niveauBadge(r.probabilite)">{{ 'riskLevel.' + r.probabilite | transloco }}</span></td>
                      <td><span [class]="niveauBadge(r.impact)">{{ 'riskLevel.' + r.impact | transloco }}</span></td>
                      <td class="text-muted small">{{ r.planMitigation ?? '—' }}</td>
                      <td>
                        @if (r.statut === 'FERME') { <span class="badge-active">{{ 'riskStatus.FERME' | transloco }}</span> }
                        @else if (r.statut === 'MITIGE') { <span class="badge-on-hold">{{ 'riskStatus.MITIGE' | transloco }}</span> }
                        @else { <span class="badge-cancelled">{{ 'riskStatus.OUVERT' | transloco }}</span> }
                      </td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteRisk(r)"
                                  title="Supprimer" aria-label="Supprimer le risque">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="6">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-exclamation-triangle"></i></div>
                        <div class="es-title">Aucun risque</div>
                        <div class="es-desc">Aucun risque n'a encore été enregistré pour ce projet.</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openRiskModal()"><i class="bi bi-plus-lg me-1"></i>Ajouter un risque</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- LIVRABLES -->
        @if (govTab() === 'livrables') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>Livrables — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openLivrableModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter un livrable
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>Titre</th><th>Description</th><th>Échéance</th><th>Statut</th><th>Actions</th></tr>
                </thead>
                <tbody>
                  @for (l of livrables(); track l.id) {
                    <tr>
                      <td class="fw-semibold">{{ l.titre }}</td>
                      <td class="text-muted small">{{ l.description ?? '—' }}</td>
                      <td>{{ l.dateEcheance ?? '—' }}</td>
                      <td><span [class]="livrableBadge(l.statut)">{{ 'deliverableStatus.' + l.statut | transloco }}</span></td>
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
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteLivrable(l)"
                                  title="Supprimer" aria-label="Supprimer le livrable">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="5">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-check2-square"></i></div>
                        <div class="es-title">Aucun livrable</div>
                        <div class="es-desc">Définissez les livrables attendus pour ce projet.</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openLivrableModal()"><i class="bi bi-plus-lg me-1"></i>Ajouter un livrable</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- CHANGEMENTS -->
        @if (govTab() === 'changes') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>Demandes de changement — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openChangeModal()">
                  <i class="bi bi-plus-lg me-1"></i>Nouvelle demande
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>Titre</th><th>Demandeur</th><th>Priorité</th><th>Date</th><th>Statut</th><th>Actions</th></tr>
                </thead>
                <tbody>
                  @for (dc of changes(); track dc.id) {
                    <tr>
                      <td class="fw-semibold">{{ dc.titre }}</td>
                      <td>{{ dc.demandeurFullName }}</td>
                      <td><span [class]="prioriteBadge(dc.priorite)">{{ 'changePriority.' + dc.priorite | transloco }}</span></td>
                      <td>{{ dc.dateDemande ?? '—' }}</td>
                      <td>
                        @if (dc.statut === 'APPROUVE') { <span class="badge-active">{{ 'changeStatus.APPROUVE' | transloco }}</span> }
                        @else if (dc.statut === 'REJETE') { <span class="badge-cancelled">{{ 'changeStatus.REJETE' | transloco }}</span> }
                        @else { <span class="badge-on-hold">{{ 'changeStatus.EN_ATTENTE' | transloco }}</span> }
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
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteChange(dc)"
                                  title="Supprimer" aria-label="Supprimer la demande">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="6">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-arrow-repeat"></i></div>
                        <div class="es-title">Aucune demande de changement</div>
                        <div class="es-desc">Les demandes de changement du projet apparaîtront ici.</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openChangeModal()"><i class="bi bi-plus-lg me-1"></i>Nouvelle demande</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- PARTIES PRENANTES -->
        @if (govTab() === 'parties') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>Parties prenantes — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openPartieModal()">
                  <i class="bi bi-plus-lg me-1"></i>Ajouter une partie prenante
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>Nom</th><th>Fonction</th><th>Email</th><th>Téléphone</th><th>Influence</th><th>Intérêt</th><th></th></tr>
                </thead>
                <tbody>
                  @for (pp of parties(); track pp.id) {
                    <tr>
                      <td class="fw-semibold">{{ pp.nom }}</td>
                      <td>{{ pp.fonction ?? '—' }}</td>
                      <td class="small">{{ pp.email ?? '—' }}</td>
                      <td class="small">{{ pp.telephone ?? '—' }}</td>
                      <td><span [class]="niveauBadge(pp.influence)">{{ 'riskLevel.' + pp.influence | transloco }}</span></td>
                      <td><span [class]="niveauBadge(pp.interet)">{{ 'riskLevel.' + pp.interet | transloco }}</span></td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deletePartie(pp)"
                                  title="Supprimer" aria-label="Supprimer la partie prenante">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="7">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-person-lines-fill"></i></div>
                        <div class="es-title">Aucune partie prenante</div>
                        <div class="es-desc">Recensez les parties prenantes impliquées dans le projet.</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openPartieModal()"><i class="bi bi-plus-lg me-1"></i>Ajouter une partie prenante</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
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
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Intérêt <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="partieForm.interet">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
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
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">Impact <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="riskForm.impact">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
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
                  <option value="OUVERT">{{ 'riskStatus.OUVERT' | transloco }}</option>
                  <option value="MITIGE">{{ 'riskStatus.MITIGE' | transloco }}</option>
                  <option value="FERME">{{ 'riskStatus.FERME' | transloco }}</option>
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
                    <option value="FAIBLE">{{ 'changePriority.FAIBLE' | transloco }}</option>
                    <option value="NORMALE">{{ 'changePriority.NORMALE' | transloco }}</option>
                    <option value="ELEVEE">{{ 'changePriority.ELEVEE' | transloco }}</option>
                    <option value="CRITIQUE">{{ 'changePriority.CRITIQUE' | transloco }}</option>
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
  private readonly govSvc     = inject(GovernanceService);
  private readonly teamSvc    = inject(TeamService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

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
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        const tab = (params.get('tab') as GovTab) || 'risks';
        this.govTab.set(tab);
        if (!pid) { this.selected.set(null); return; }
        const project = list.find(p => String(p.id) === pid);
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.reload();
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
          );
        }
      });
    });
  }

  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id, tab: this.govTab() }, replaceUrl: false });
    this.reload();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  setGovTab(tab: GovTab): void {
    this.govTab.set(tab);
    if (this.selected()) {
      this.router.navigate([], { queryParams: { p: this.selected()!.id, tab }, replaceUrl: true });
    }
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
      next: () => { this.reload(); this.showPartieModal.set(false); this.saving.set(false); this.toast.success('Partie prenante ajoutée.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deletePartie(pp: PartiePrenante): Promise<void> {
    if (!await this.confirm.ask(`Supprimer « ${pp.nom} » ?`, 'Supprimer la partie prenante')) return;
    this.govSvc.deletePartie(this.selected()!.id, pp.id).subscribe({
      next: () => { this.reload(); this.toast.success('Partie prenante supprimée.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
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
      next: () => { this.reload(); this.showRiskModal.set(false); this.saving.set(false); this.toast.success('Risque enregistré.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteRisk(r: Risk): Promise<void> {
    if (!await this.confirm.ask('Supprimer ce risque ?', 'Supprimer le risque')) return;
    this.govSvc.deleteRisk(this.selected()!.id, r.id).subscribe({
      next: () => { this.reload(); this.toast.success('Risque supprimé.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
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
      next: () => { this.reload(); this.showLivrableModal.set(false); this.saving.set(false); this.toast.success('Livrable créé.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  demarrerLivrable(l: Livrable): void {
    this.govSvc.demarrerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success('Livrable démarré.'); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  livrerLivrable(l: Livrable): void {
    this.govSvc.livrerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success('Livrable marqué comme livré.'); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  validerLivrable(l: Livrable): void {
    this.govSvc.validerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success('Livrable validé.'); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  async deleteLivrable(l: Livrable): Promise<void> {
    if (!await this.confirm.ask(`Supprimer le livrable « ${l.titre} » ?`, 'Supprimer le livrable')) return;
    this.govSvc.deleteLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success('Livrable supprimé.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
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
      next: () => { this.reload(); this.showChangeModal.set(false); this.saving.set(false); this.toast.success('Demande de changement créée.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  approuver(dc: DemandeChangement): void {
    this.govSvc.approuverChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success('Demande approuvée.'); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  rejeter(dc: DemandeChangement): void {
    this.govSvc.rejeterChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success('Demande rejetée.'); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  async deleteChange(dc: DemandeChangement): Promise<void> {
    if (!await this.confirm.ask(`Supprimer la demande « ${dc.titre} » ?`, 'Supprimer la demande')) return;
    this.govSvc.deleteChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success('Demande supprimée.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Badges ───────────────────────────────────────────────────────
  niveauBadge(n: NiveauRisque): string {
    return n === 'ELEVE' ? 'badge-cancelled' : n === 'MOYEN' ? 'badge-on-hold' : 'badge-active';
  }

  livrableBadge(s: string): string {
    const m: Record<string, string> = {
      EN_ATTENTE: 'badge-draft', EN_COURS: 'badge-active',
      LIVRE: 'badge-completed', VALIDE: 'badge-active'
    };
    return m[s] ?? 'badge-draft';
  }

  prioriteBadge(p: string): string {
    const m: Record<string, string> = {
      FAIBLE: 'badge-active', NORMALE: 'badge-draft',
      ELEVEE: 'badge-on-hold', CRITIQUE: 'badge-cancelled'
    };
    return m[p] ?? 'badge-draft';
  }
}
