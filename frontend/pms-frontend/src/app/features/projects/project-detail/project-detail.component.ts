import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink, ActivatedRoute } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { ProjectService } from '../../../core/services/project.service';
import { TeamService } from '../../../core/services/team.service';
import { WorkloadService } from '../../../core/services/workload.service';
import { BillingService } from '../../../core/services/billing.service';
import { MissionService } from '../../../core/services/mission.service';
import { GovernanceService } from '../../../core/services/governance.service';
import { AuthService } from '../../../core/services/auth.service';
import { Project, PROJECT_STATUS_LABELS } from '../../../core/models/project.model';
import { KpiResponse } from '../../../core/models/kpi.model';
import { TeamAssignment } from '../../../core/models/team.model';
import { PlanCharge, ChargeReelle } from '../../../core/models/workload.model';
import { JalonFacturation, Avenant } from '../../../core/models/billing.model';
import { Mission } from '../../../core/models/mission.model';
import { Risk, Livrable, DemandeChangement } from '../../../core/models/governance.model';

type Tab = 'info' | 'equipe' | 'charges' | 'facturation' | 'missions' | 'gouvernance';

@Component({
  selector: 'app-project-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="topbar d-flex align-items-center justify-content-between">
      <div>
        <a routerLink="/projects" class="text-muted text-decoration-none small">
          <i class="bi bi-chevron-left"></i> Projets
        </a>
        <h5 class="mb-0 fw-semibold mt-1">
          {{ project()?.code }} — {{ project()?.name }}
          @if (project()?.archived) { <span class="badge bg-secondary ms-2"><i class="bi bi-archive me-1"></i>Archivé</span> }
        </h5>
      </div>
      @if (auth.hasPermission('EDIT_PROJECT')) {
        <div class="d-flex gap-2">
          @if (!project()?.archived) {
            <a [routerLink]="['/projects', project()?.id, 'edit']" class="btn btn-sm btn-outline-primary">
              <i class="bi bi-pencil me-1"></i>Modifier
            </a>
            @if (project()?.status === 'COMPLETED') {
              <button class="btn btn-sm btn-outline-secondary" (click)="archiveProject()" title="Archiver ce projet terminé">
                <i class="bi bi-archive me-1"></i>Archiver
              </button>
            }
          } @else {
            <button class="btn btn-sm btn-outline-primary" (click)="unarchiveProject()">
              <i class="bi bi-arrow-counterclockwise me-1"></i>Désarchiver
            </button>
          }
        </div>
      }
    </div>

    <div class="px-4 pt-3">
      <!-- Nav tabs -->
      <ul class="nav nav-tabs border-bottom-0">
        <li class="nav-item">
          <button class="nav-link" [class.active]="tab()==='info'" (click)="setTab('info')">
            <i class="bi bi-info-circle me-1"></i>Infos & KPI
          </button>
        </li>
        @if (auth.hasPermission('VIEW_TEAM')) {
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab()==='equipe'" (click)="setTab('equipe')">
              <i class="bi bi-people me-1"></i>Équipe
            </button>
          </li>
        }
        @if (auth.hasPermission('VIEW_WORKLOAD')) {
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab()==='charges'" (click)="setTab('charges')">
              <i class="bi bi-calendar3 me-1"></i>Charges
            </button>
          </li>
        }
        @if (auth.hasPermission('VIEW_BILLING')) {
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab()==='facturation'" (click)="setTab('facturation')">
              <i class="bi bi-receipt me-1"></i>Facturation
            </button>
          </li>
        }
        @if (auth.hasPermission('VIEW_MISSION')) {
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab()==='missions'" (click)="setTab('missions')">
              <i class="bi bi-airplane me-1"></i>Missions
            </button>
          </li>
        }
        @if (auth.hasPermission('VIEW_GOVERNANCE')) {
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab()==='gouvernance'" (click)="setTab('gouvernance')">
              <i class="bi bi-shield-check me-1"></i>Gouvernance
            </button>
          </li>
        }
      </ul>
    </div>

    <div class="p-4">

      <!-- ===== TAB: INFOS & KPI ===== -->
      @if (tab() === 'info' && project(); as p) {
        <div class="row g-4">
          <div class="col-lg-5">
            <div class="card h-100">
              <div class="card-header bg-white fw-semibold py-3">Informations du projet</div>
              <div class="card-body">
                <dl class="row small mb-0">
                  <dt class="col-5 text-muted">Code</dt>
                  <dd class="col-7 fw-semibold">{{ p.code }}</dd>
                  <dt class="col-5 text-muted">Statut</dt>
                  <dd class="col-7"><span [class]="badge(p.status)">{{ statusLabel(p.status) }}</span></dd>
                  @if (p.contractId) {
                    <dt class="col-5 text-muted">Réf. contrat</dt>
                    <dd class="col-7">{{ p.contractId }}</dd>
                  }
                  @if (p.client) {
                    <dt class="col-5 text-muted">Client</dt>
                    <dd class="col-7">{{ p.client }}</dd>
                  }
                  @if (p.funder) {
                    <dt class="col-5 text-muted">Bailleur</dt>
                    <dd class="col-7">{{ p.funder }}</dd>
                  }
                  @if (p.businessModel || p.engagementType) {
                    <dt class="col-5 text-muted">Engagement</dt>
                    <dd class="col-7">
                      {{ p.businessModel === 'GROUPEMENT' ? 'Groupement' : (p.businessModel === 'SEUL' ? 'Seul' : '') }}
                      @if (p.businessModel && p.engagementType) { · }
                      {{ p.engagementType === 'FORFAIT' ? 'Forfait' : (p.engagementType === 'REGIE' ? 'Régie' : '') }}
                    </dd>
                  }
                  <dt class="col-5 text-muted">Chef de projet</dt>
                  <dd class="col-7">
                    {{ p.chefProjetName ?? '—' }}
                    @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                      <button class="btn btn-sm btn-link p-0 ms-2" (click)="openChefModal()" title="Assigner un chef de projet">
                        <i class="bi bi-pencil-square"></i>
                      </button>
                    }
                  </dd>
                  <dt class="col-5 text-muted">Période</dt>
                  <dd class="col-7">
                    {{ p.startDate ?? '—' }} → {{ p.endDate ?? '—' }}
                    @if (p.durationDays) { <span class="text-muted">({{ p.durationDays }} j)</span> }
                  </dd>
                  <dt class="col-5 text-muted">Budget initial</dt>
                  <dd class="col-7">{{ (p.initialBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                  @if (p.revisedBudget) {
                    <dt class="col-5 text-muted">Budget révisé</dt>
                    <dd class="col-7">{{ p.revisedBudget | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                  }
                  <dt class="col-5 text-muted">Budget effectif</dt>
                  <dd class="col-7 fw-bold text-primary">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                  @if (p.currency && p.currency !== 'TND' && p.budgetTnd) {
                    <dt class="col-5 text-muted">Budget (TND)</dt>
                    <dd class="col-7">{{ p.budgetTnd | number:'1.0-0' }} TND</dd>
                  }
                  @if (p.pprTnd) {
                    <dt class="col-5 text-muted">PPR (5%)</dt>
                    <dd class="col-7">{{ p.pprTnd | number:'1.0-0' }} TND</dd>
                  }
                  @if (p.soldWorkloadDays) {
                    <dt class="col-5 text-muted">Workload vendu</dt>
                    <dd class="col-7">{{ p.soldWorkloadDays | number:'1.0-0' }} JH
                      @if (p.warrantyWorkloadDays) { <span class="text-muted">(garantie {{ p.warrantyWorkloadDays | number:'1.0-0' }} JH)</span> }
                    </dd>
                  }
                </dl>
              </div>
            </div>
          </div>

          @if (auth.hasPermission('VIEW_KPI') && kpi()) {
            <div class="col-lg-7">
              <div class="card h-100">
                <div class="card-header bg-white fw-semibold py-3">KPI temps réel</div>
                <div class="card-body">
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="rounded-3 p-3 bg-primary bg-opacity-10 mb-2">
                        <div class="small text-muted">Budget planifié</div>
                        <div class="fs-5 fw-bold text-primary">{{ kpi()!.budgetPlanifie | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="rounded-3 p-3 bg-warning bg-opacity-10 mb-2">
                        <div class="small text-muted">Budget consommé</div>
                        <div class="fs-5 fw-bold text-warning">{{ kpi()!.budgetConsome | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="rounded-3 p-3 bg-info bg-opacity-10 mb-2">
                        <div class="small text-muted">EAC</div>
                        <div class="fs-5 fw-bold text-info">{{ kpi()!.eac | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="rounded-3 p-3 mb-2"
                           [class]="kpi()!.marge >= 0 ? 'bg-success bg-opacity-10' : 'bg-danger bg-opacity-10'">
                        <div class="small text-muted">Marge</div>
                        <div class="fs-5 fw-bold" [class]="kpi()!.marge >= 0 ? 'text-success' : 'text-danger'">
                          {{ kpi()!.marge | number:'1.0-0' }}
                        </div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="rounded-3 p-3 bg-secondary bg-opacity-10 mb-2">
                        <div class="small text-muted">Taux conso.</div>
                        <div class="fs-5 fw-bold">{{ (kpi()!.tauxConsommation * 100) | number:'1.1-1' }}%</div>
                        <div class="progress mt-1" style="height:4px">
                          <div class="progress-bar" [style.width.%]="kpi()!.tauxConsommation * 100"></div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          }
        </div>
      }

      <!-- ===== TAB: ÉQUIPE ===== -->
      @if (tab() === 'equipe') {
        <div class="card">
          <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
            <span><i class="bi bi-people me-2"></i>Membres de l'équipe</span>
            <div class="d-flex align-items-center gap-2">
              <span class="badge bg-secondary">{{ team().length }} membres</span>
              @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                <button class="btn btn-primary btn-sm" (click)="openTeamModal()">
                  <i class="bi bi-person-plus me-1"></i>Affecter un membre
                </button>
              }
            </div>
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead class="table-light">
                <tr><th>Membre</th><th>Rôle</th><th>Depuis</th><th>Jusqu'au</th>
                  @if (auth.hasPermission('ASSIGN_DEVELOPER')) { <th class="text-end">Actions</th> }
                </tr>
              </thead>
              <tbody>
                @for (m of team(); track m.id) {
                  <tr>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.roleInTeam ?? '—' }}</td>
                    <td>{{ m.startDate }}</td>
                    <td>{{ m.endDate ?? 'Actif' }}</td>
                    @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                      <td class="text-end">
                        <button class="btn btn-sm btn-outline-danger" (click)="removeMember(m)" title="Retirer">
                          <i class="bi bi-person-dash"></i>
                        </button>
                      </td>
                    }
                  </tr>
                }
                @empty {
                  <tr><td colspan="5" class="text-center py-4 text-muted">Aucun membre affecté</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ===== TAB: CHARGES ===== -->
      @if (tab() === 'charges') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-calendar3 me-2"></i>Plan de charge
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
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-clock-history me-2"></i>Charges réelles
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
                      <tr><td colspan="5" class="text-center py-4 text-muted">Aucune charge réelle saisie</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

      <!-- ===== TAB: FACTURATION ===== -->
      @if (tab() === 'facturation') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between">
                <span><i class="bi bi-receipt me-2"></i>Jalons de facturation</span>
                <span class="text-muted small">Total : {{ jalonTotal() | number:'1.0-0' }} TND</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Libellé</th><th class="text-end">%</th><th class="text-end">Montant (TND)</th><th>Date prévue</th><th>Statut</th></tr>
                  </thead>
                  <tbody>
                    @for (j of jalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }}</td>
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>
                          @if (j.statut === 'PAYE') {
                            <span class="badge bg-success">Payé</span>
                          } @else if (j.statut === 'FACTURE') {
                            <span class="badge bg-primary">Facturé</span>
                          } @else {
                            <span class="badge bg-secondary">Prévu</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5" class="text-center py-4 text-muted">Aucun jalon défini</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-file-earmark-plus me-2"></i>Avenants
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Numéro</th><th>Objet</th><th class="text-end">Montant (TND)</th><th>Date</th></tr>
                  </thead>
                  <tbody>
                    @for (a of avenants(); track a.id) {
                      <tr>
                        <td class="fw-semibold">{{ a.numero }}</td>
                        <td>{{ a.objet }}</td>
                        <td class="text-end" [class.text-danger]="a.montant < 0" [class.text-success]="a.montant >= 0">
                          {{ a.montant | number:'1.0-0' }}
                        </td>
                        <td>{{ a.dateAvenant }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4" class="text-center py-4 text-muted">Aucun avenant</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

      <!-- ===== TAB: MISSIONS ===== -->
      @if (tab() === 'missions') {
        <div class="card">
          <div class="card-header bg-white fw-semibold py-3">
            <i class="bi bi-airplane me-2"></i>Missions
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead class="table-light">
                <tr><th>Collaborateur</th><th>Objet</th><th>Lieu</th><th>Début</th><th>Fin</th></tr>
              </thead>
              <tbody>
                @for (m of missions(); track m.id) {
                  <tr>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.objet }}</td>
                    <td>{{ m.lieu }}</td>
                    <td>{{ m.dateDebut }}</td>
                    <td>{{ m.dateFin }}</td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="5" class="text-center py-4 text-muted">Aucune mission</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ===== TAB: GOUVERNANCE ===== -->
      @if (tab() === 'gouvernance') {
        <div class="row g-4">
          <!-- Risks -->
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-exclamation-triangle me-2 text-warning"></i>Registre des risques
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Description</th><th>Prob.</th><th>Impact</th><th>Statut</th></tr>
                  </thead>
                  <tbody>
                    @for (r of risks(); track r.id) {
                      <tr>
                        <td class="small">{{ r.description }}</td>
                        <td><span [class]="niveauBadge(r.probabilite)">{{ r.probabilite }}</span></td>
                        <td><span [class]="niveauBadge(r.impact)">{{ r.impact }}</span></td>
                        <td>
                          @if (r.statut === 'FERME') {
                            <span class="badge bg-success">Fermé</span>
                          } @else if (r.statut === 'MITIGE') {
                            <span class="badge bg-warning text-dark">Mitigé</span>
                          } @else {
                            <span class="badge bg-danger">Ouvert</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4" class="text-center py-3 text-muted small">Aucun risque</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Livrables -->
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-check2-square me-2 text-success"></i>Livrables
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Titre</th><th>Échéance</th><th>Statut</th></tr>
                  </thead>
                  <tbody>
                    @for (l of livrables(); track l.id) {
                      <tr>
                        <td class="fw-semibold small">{{ l.titre }}</td>
                        <td class="small">{{ l.dateEcheance ?? '—' }}</td>
                        <td><span [class]="livrableBadge(l.statut)">{{ l.statut | titlecase }}</span></td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="3" class="text-center py-3 text-muted small">Aucun livrable</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Demandes de changement -->
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3">
                <i class="bi bi-arrow-repeat me-2"></i>Demandes de changement
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>Titre</th><th>Demandeur</th><th>Priorité</th><th>Date</th><th>Statut</th></tr>
                  </thead>
                  <tbody>
                    @for (dc of changes(); track dc.id) {
                      <tr>
                        <td class="fw-semibold small">{{ dc.titre }}</td>
                        <td class="small">{{ dc.demandeurFullName }}</td>
                        <td><span [class]="prioriteBadge(dc.priorite)">{{ dc.priorite }}</span></td>
                        <td class="small">{{ dc.dateDemande ?? '—' }}</td>
                        <td>
                          @if (dc.statut === 'APPROUVE') {
                            <span class="badge bg-success">Approuvé</span>
                          } @else if (dc.statut === 'REJETE') {
                            <span class="badge bg-danger">Rejeté</span>
                          } @else {
                            <span class="badge bg-warning text-dark">En attente</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5" class="text-center py-3 text-muted small">Aucune demande de changement</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

    </div>

    <!-- Modal Assigner chef de projet -->
    @if (showChefModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showChefModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Assigner un chef de projet</h5>
              <button type="button" class="btn-close" (click)="showChefModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Chef de projet <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="chefUserId">
                  <option [value]="0" disabled>Sélectionner</option>
                  @for (u of chefCandidates(); track u.id) {
                    <option [value]="u.id">{{ u.firstName }} {{ u.lastName }} ({{ u.roleName }})</option>
                  }
                </select>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChefModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveChef()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Assigner
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Affecter un membre -->
    @if (showTeamModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showTeamModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Affecter un membre à l'équipe</h5>
              <button type="button" class="btn-close" (click)="showTeamModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Membre <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="teamForm.userId">
                  <option [value]="0" disabled>Sélectionner</option>
                  @for (u of allUsers(); track u.id) {
                    <option [value]="u.id">{{ u.firstName }} {{ u.lastName }} ({{ u.roleName }})</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Rôle dans l'équipe</label>
                <input type="text" class="form-control" [(ngModel)]="teamForm.roleInTeam" placeholder="Ex: Développeur, Analyste...">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date de début <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="teamForm.startDate">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showTeamModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="saveTeamMember()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Affecter
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class ProjectDetailComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly svc = inject(ProjectService);
  private readonly http = inject(HttpClient);
  private readonly teamSvc = inject(TeamService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly billingSvc = inject(BillingService);
  private readonly missionSvc = inject(MissionService);
  private readonly govSvc = inject(GovernanceService);
  private readonly route = inject(ActivatedRoute);

  project = signal<Project | null>(null);
  kpi = signal<KpiResponse | null>(null);
  team = signal<TeamAssignment[]>([]);
  planCharges = signal<PlanCharge[]>([]);
  chargesReelles = signal<ChargeReelle[]>([]);
  jalons = signal<JalonFacturation[]>([]);
  avenants = signal<Avenant[]>([]);
  missions = signal<Mission[]>([]);
  risks = signal<Risk[]>([]);
  livrables = signal<Livrable[]>([]);
  changes = signal<DemandeChangement[]>([]);

  tab = signal<Tab>('info');
  private loadedTabs = new Set<Tab>();

  // Gestion chef / équipe (B7/B8)
  allUsers = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);
  showChefModal = signal(false);
  showTeamModal = signal(false);
  saving = signal(false);
  modalError = signal('');
  chefUserId = 0;
  teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };

  private projectId = 0;

  ngOnInit(): void {
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    this.svc.get(this.projectId).subscribe(p => {
      this.project.set(p);
      if (this.auth.hasPermission('VIEW_KPI')) {
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      }
    });
    this.loadedTabs.add('info');
  }

  archiveProject(): void {
    if (!confirm('Archiver ce projet terminé ? Il sera déplacé dans les projets archivés.')) return;
    this.svc.archive(this.projectId).subscribe({
      next: p => this.project.set(p),
      error: e => alert(e.error?.detail ?? 'Erreur lors de l\'archivage.')
    });
  }

  unarchiveProject(): void {
    this.svc.unarchive(this.projectId).subscribe(p => this.project.set(p));
  }

  setTab(t: Tab): void {
    this.tab.set(t);
    if (this.loadedTabs.has(t)) return;
    this.loadedTabs.add(t);

    switch (t) {
      case 'equipe':
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        break;
      case 'charges':
        this.workloadSvc.listPlanCharges(this.projectId).subscribe(d => this.planCharges.set(d));
        this.workloadSvc.listChargesReelles(this.projectId).subscribe(d => this.chargesReelles.set(d));
        break;
      case 'facturation':
        this.billingSvc.listJalons(this.projectId).subscribe(d => this.jalons.set(d));
        this.billingSvc.listAvenants(this.projectId).subscribe(d => this.avenants.set(d));
        break;
      case 'missions':
        this.missionSvc.list(this.projectId).subscribe(d => this.missions.set(d));
        break;
      case 'gouvernance':
        this.govSvc.listRisks(this.projectId).subscribe(d => this.risks.set(d));
        this.govSvc.listLivrables(this.projectId).subscribe(d => this.livrables.set(d));
        this.govSvc.listChanges(this.projectId).subscribe(d => this.changes.set(d));
        break;
    }
  }

  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  monthLabel(m: number): string {
    return ['Jan','Fév','Mar','Avr','Mai','Jun','Jul','Aoû','Sep','Oct','Nov','Déc'][m - 1] ?? String(m);
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

  niveauBadge(n: string): string {
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

  // ── Chef de projet (B7) ──────────────────────────────────────────
  private ensureUsersLoaded(): void {
    if (this.allUsers().length) return;
    // /users/assignable : accessible au Directeur/PM (pas besoin de MANAGE_USERS)
    this.svc.listAssignableUsers().subscribe(u => this.allUsers.set(u));
  }

  chefCandidates(): { id: number; firstName: string; lastName: string; roleName: string }[] {
    const chefs = this.allUsers().filter(u => u.roleName === 'CHEF_PROJET');
    return chefs.length ? chefs : this.allUsers();
  }

  openChefModal(): void {
    this.ensureUsersLoaded();
    this.chefUserId = this.project()?.chefProjetId ?? 0;
    this.modalError.set('');
    this.showChefModal.set(true);
  }

  saveChef(): void {
    if (!this.chefUserId) { this.modalError.set('Sélectionnez un chef de projet.'); return; }
    this.saving.set(true);
    this.svc.assignChef(this.projectId, this.chefUserId).subscribe({
      next: (p) => { this.project.set(p); this.showChefModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Gestion d'équipe (B8) ────────────────────────────────────────
  openTeamModal(): void {
    this.ensureUsersLoaded();
    this.teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    this.showTeamModal.set(true);
  }

  saveTeamMember(): void {
    if (!this.teamForm.userId || !this.teamForm.startDate) {
      this.modalError.set('Membre et date de début sont requis.');
      return;
    }
    this.saving.set(true);
    this.teamSvc.assign(this.projectId, this.teamForm).subscribe({
      next: () => {
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        this.showTeamModal.set(false); this.saving.set(false);
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  removeMember(m: TeamAssignment): void {
    if (!confirm(`Retirer ${m.userFullName} de l'équipe ?`)) return;
    this.teamSvc.remove(this.projectId, m.id).subscribe(() =>
      this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d))
    );
  }
}
