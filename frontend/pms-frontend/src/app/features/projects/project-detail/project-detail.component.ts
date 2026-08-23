import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { ProjectService } from '../../../core/services/project.service';
import { TeamService } from '../../../core/services/team.service';
import { WorkloadService } from '../../../core/services/workload.service';
import { BillingService } from '../../../core/services/billing.service';
import { MissionService } from '../../../core/services/mission.service';
import { GovernanceService } from '../../../core/services/governance.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsListStateService } from '../projects-list-state.service';
import { LanguageService } from '../../../core/i18n/language.service';
import { Project, ProjectStatus, PROJECT_STATUS_LABELS } from '../../../core/models/project.model';
import { KpiResponse } from '../../../core/models/kpi.model';
import { TeamAssignment } from '../../../core/models/team.model';
import { PlanCharge, ChargeReelle } from '../../../core/models/workload.model';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { JalonFacturation, Avenant } from '../../../core/models/billing.model';
import { Mission } from '../../../core/models/mission.model';
import { Risk, Livrable, DemandeChangement } from '../../../core/models/governance.model';

type Tab = 'info' | 'equipe' | 'charges' | 'facturation' | 'missions' | 'gouvernance';

@Component({
  selector: 'app-project-detail',
  standalone: true,
  providers: [provideTranslocoScope('project')],
  imports: [CommonModule, FormsModule, RouterLink, PaginationComponent, TranslocoModule],
  styles: [`
    .act-danger { color: var(--c-danger); }
    /* Interactive status control (Jira/Linear style) */
    .status-ctl { position: relative; display: inline-flex; }
    .status-btn { display: inline-flex; align-items: center; gap: .35rem; border: 1px solid var(--border);
      background: var(--surface); border-radius: var(--r); padding: .15rem .4rem; cursor: pointer;
      transition: border-color var(--t), background var(--t); }
    .status-btn:hover { border-color: var(--border-2); background: var(--surface-2); }
    .status-btn:focus-visible { outline: 2px solid var(--c-brand); outline-offset: 2px; }
    .status-btn .bi-chevron-down { font-size: 10px; color: var(--text-3); }
    .status-backdrop { position: fixed; inset: 0; z-index: 300; }
    .status-menu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 301; min-width: 190px;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-md);
      box-shadow: var(--sh-lg); padding: .3rem; }
    .status-menu-label { font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: .06em;
      color: var(--text-3); padding: .35rem .5rem .3rem; }
    .status-menu-item { display: flex; align-items: center; width: 100%; border: 0; background: transparent;
      padding: .4rem .5rem; border-radius: var(--r-sm); cursor: pointer; transition: background var(--t); }
    .status-menu-item:hover { background: var(--surface-2); }
    .status-menu-item:focus-visible { outline: 2px solid var(--c-brand); outline-offset: -2px; }

    /* KPI / financial tiles */
    .kpi-tile { border-radius: var(--r-md); padding: .875rem; margin-bottom: .5rem; text-align: center; }
    .kpi-tile .fs-5 { font-variant-numeric: tabular-nums; }
    .kpi-tile--brand   { background: var(--c-brand-dim); }
    .kpi-tile--warning { background: var(--c-warning-dim); }
    .kpi-tile--teal     { background: var(--c-teal-dim); }
    .kpi-tile--success { background: var(--c-success-dim); }
    .kpi-tile--neutral { background: var(--surface-2, var(--bg)); }

    /* Candidate picker list (chef / team modals) */
    .picker-list { max-height: 230px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--r-sm); }
    .picker-item { display: flex; align-items: center; gap: .5rem; width: 100%; border: 0;
      border-bottom: 1px solid var(--border); padding: .5rem .75rem; text-align: left; cursor: pointer;
      color: var(--text-1); background: transparent; transition: background var(--t); }
    .picker-item:last-child { border-bottom: 0; }
    .picker-item:hover { background: var(--surface-2); }
    .picker-item.is-selected { background: var(--c-brand-dim); }
    .picker-item .pi-name { flex: 1; font-size: 13px; }
    .picker-empty { padding: .75rem; text-align: center; color: var(--text-3); font-size: 12px; }
    .picker-count { font-size: 11px; color: var(--text-3); margin-top: .25rem; }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <i class="bi bi-arrow-left"></i> Projets
        </a>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ project()?.code ?? '—' }}</span>
        @if (project()?.archived) {
          <span class="badge-draft" style="margin-left:.5rem"><i class="bi bi-archive me-1"></i>{{ 'project.archived' | transloco }}</span>
        }
      </div>
      <div class="tb-right">
        @if (auth.hasPermission('MANAGE_DI')) {
          <a [routerLink]="['/projects', project()?.id, 'devis-interne']" class="btn btn-outline-secondary btn-sm">
            <i class="bi bi-file-earmark-lock2"></i>DI
          </a>
        }
        @if (auth.hasPermission('EDIT_PROJECT')) {
          @if (!project()?.archived) {
            <a [routerLink]="['/projects', project()?.id, 'edit']" class="btn btn-outline-secondary btn-sm">
              <i class="bi bi-pencil"></i>Modifier
            </a>
            @if (project()?.status === 'COMPLETED') {
              <button class="btn btn-outline-secondary btn-sm" (click)="archiveProject()">
                <i class="bi bi-archive"></i>Archiver
              </button>
            }
          } @else {
            <button class="btn btn-outline-secondary btn-sm" (click)="unarchiveProject()">
              <i class="bi bi-arrow-counterclockwise"></i>{{ 'project.unarchive' | transloco }}
            </button>
          }
        }
      </div>
    </div>

    <div class="page-body">
      <div class="page-header" style="flex-direction:column;align-items:flex-start;gap:.75rem;padding-bottom:.75rem">
        <div>
          <h1 class="page-title">{{ project()?.name ?? '...' }}</h1>
          @if (project(); as p) {
            <div class="page-subtitle" style="display:flex;align-items:center;gap:.5rem">
              @if (p.client) { <span>{{ p.client }}</span><span class="bc-sep">·</span> }
              @if (auth.hasPermission('EDIT_PROJECT') && !p.archived && allowedTransitions(p.status).length) {
                <span class="status-ctl">
                  <button class="status-btn" (click)="statusMenuOpen.set(!statusMenuOpen())"
                          [attr.aria-expanded]="statusMenuOpen()" aria-haspopup="menu" title="Changer le statut">
                    <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
                    <i class="bi bi-chevron-down"></i>
                  </button>
                  @if (statusMenuOpen()) {
                    <div class="status-backdrop" (click)="statusMenuOpen.set(false)"></div>
                    <div class="status-menu" role="menu">
                      <div class="status-menu-label">Changer le statut</div>
                      @for (s of allowedTransitions(p.status); track s) {
                        <button class="status-menu-item" role="menuitem" (click)="changeStatus(s)">
                          <span [class]="badge(s)">{{ 'status.' + s | transloco }}</span>
                        </button>
                      }
                    </div>
                  }
                </span>
              } @else {
                <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
              }
            </div>
          }
        </div>
        <div class="pms-tabs">
          <button class="tab-item" [class.active]="tab()==='info'" (click)="setTab('info')">
            <i class="bi bi-info-circle me-1"></i>{{ (auth.hasPermission('VIEW_KPI') ? 'project.tabs.overview' : 'project.tabs.overviewShort') | transloco }}
          </button>
          @if (auth.hasPermission('VIEW_TEAM')) {
            <button class="tab-item" [class.active]="tab()==='equipe'" (click)="setTab('equipe')">
              <i class="bi bi-people me-1"></i>{{ 'project.tabs.team' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_WORKLOAD')) {
            <button class="tab-item" [class.active]="tab()==='charges'" (click)="setTab('charges')">
              <i class="bi bi-calendar3 me-1"></i>{{ 'project.tabs.workload' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_BILLING')) {
            <button class="tab-item" [class.active]="tab()==='facturation'" (click)="setTab('facturation')">
              <i class="bi bi-receipt me-1"></i>{{ 'project.tabs.billing' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_MISSION')) {
            <button class="tab-item" [class.active]="tab()==='missions'" (click)="setTab('missions')">
              <i class="bi bi-airplane me-1"></i>{{ 'project.tabs.missions' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_GOVERNANCE')) {
            <button class="tab-item" [class.active]="tab()==='gouvernance'" (click)="setTab('gouvernance')">
              <i class="bi bi-shield-check me-1"></i>{{ 'project.tabs.governance' | transloco }}
            </button>
          }
        </div>
      </div>

      <!-- ===== TAB: INFOS & KPI ===== -->
      @if (tab() === 'info' && project(); as p) {
        <div class="row g-4">
          <div class="col-lg-5">
            <div class="card h-100">
              <div class="card-header">{{ 'project.info.title' | transloco }}</div>
              <div class="card-body">
                <dl class="row small mb-0">
                  <dt class="col-5 text-muted">Code</dt>
                  <dd class="col-7 fw-semibold">{{ p.code }}</dd>
                  <dt class="col-5 text-muted">{{ 'project.info.status' | transloco }}</dt>
                  <dd class="col-7"><span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span></dd>
                  @if (p.contractId) {
                    <dt class="col-5 text-muted">{{ 'project.info.contractRef' | transloco }}</dt>
                    <dd class="col-7">{{ p.contractId }}</dd>
                  }
                  @if (p.client) {
                    <dt class="col-5 text-muted">{{ 'project.info.client' | transloco }}</dt>
                    <dd class="col-7">{{ p.client }}</dd>
                  }
                  @if (p.funder) {
                    <dt class="col-5 text-muted">{{ 'project.info.funder' | transloco }}</dt>
                    <dd class="col-7">{{ p.funder }}</dd>
                  }
                  @if (p.businessModel || p.engagementType) {
                    <dt class="col-5 text-muted">{{ 'project.info.engagement' | transloco }}</dt>
                    <dd class="col-7">
                      {{ p.businessModel === 'GROUPEMENT' ? 'Groupement' : (p.businessModel === 'SEUL' ? 'Seul' : '') }}
                      @if (p.businessModel && p.engagementType) { · }
                      {{ p.engagementType ? ('labels.engagement.' + p.engagementType | transloco) : '' }}
                    </dd>
                  }
                  <dt class="col-5 text-muted">{{ 'project.info.manager' | transloco }}</dt>
                  <dd class="col-7">
                    {{ p.chefProjetName ?? '—' }}
                    @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                      <button class="btn btn-sm btn-link p-0 ms-2" (click)="openChefModal()" title="Assigner un chef de projet">
                        <i class="bi bi-pencil-square"></i>
                      </button>
                    }
                  </dd>
                  <dt class="col-5 text-muted">{{ 'project.info.period' | transloco }}</dt>
                  <dd class="col-7">
                    {{ p.startDate ?? '—' }} → {{ p.endDate ?? '—' }}
                    @if (p.durationDays) { <span class="text-muted">({{ p.durationDays }} j)</span> }
                  </dd>
                  @if (p.createdAt) {
                    <dt class="col-5 text-muted">{{ 'project.info.createdAt' | transloco }}</dt>
                    <dd class="col-7">{{ p.createdAt | date:'mediumDate':undefined:locale() }}, {{ p.createdAt | date:'shortTime':undefined:locale() }}</dd>
                  }
                  <!-- Données financières : BR-050 — masquées sans VIEW_KPI (ex. développeur) -->
                  @if (auth.hasPermission('VIEW_KPI')) {
                    <dt class="col-5 text-muted">{{ 'project.info.initialBudget' | transloco }}</dt>
                    <dd class="col-7">{{ (p.initialBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    @if (p.revisedBudget) {
                      <dt class="col-5 text-muted">{{ 'project.info.revisedBudget' | transloco }}</dt>
                      <dd class="col-7">{{ p.revisedBudget | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    }
                    <dt class="col-5 text-muted">{{ 'project.info.effectiveBudget' | transloco }}</dt>
                    <dd class="col-7 fw-bold text-primary">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    @if (p.currency && p.currency !== 'TND' && p.budgetTnd) {
                      <dt class="col-5 text-muted">{{ 'project.info.budgetTnd' | transloco }}</dt>
                      <dd class="col-7">{{ p.budgetTnd | number:'1.0-0' }} TND</dd>
                    }
                    @if (p.pprTnd) {
                      <dt class="col-5 text-muted">PPR (5%)</dt>
                      <dd class="col-7">{{ p.pprTnd | number:'1.0-0' }} TND</dd>
                    }
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
                <div class="card-header">{{ 'project.kpi.title' | transloco }}</div>
                <div class="card-body">
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--brand">
                        <div class="small text-muted">{{ 'project.kpi.plannedBudget' | transloco }}</div>
                        <div class="fs-5 fw-bold text-primary">{{ kpi()!.budgetPlanifie | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--warning">
                        <div class="small text-muted">{{ 'project.kpi.consumedBudget' | transloco }}</div>
                        <div class="fs-5 fw-bold text-warning">{{ kpi()!.budgetConsome | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--teal">
                        <div class="small text-muted">EAC</div>
                        <div class="fs-5 fw-bold text-info">{{ kpi()!.eac | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile"
                           [style.background]="kpi()!.marge >= 0 ? 'var(--c-success-dim)' : 'var(--c-danger-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.margin' | transloco }}</div>
                        <div class="fs-5 fw-bold" [class]="kpi()!.marge >= 0 ? 'text-success' : 'text-danger'">
                          {{ kpi()!.marge | number:'1.0-0' }}
                        </div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--neutral">
                        <div class="small text-muted">Taux conso.</div>
                        <div class="fs-5 fw-bold">{{ (kpi()!.tauxConsommation * 100) | number:'1.1-1' }}%</div>
                        <div class="progress mt-1" style="height:4px">
                          <div class="progress-bar" [style.width.%]="kpi()!.tauxConsommation * 100"></div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <!-- ── Indicateurs EVM (F-AFF-13) ── -->
                  <hr class="my-3">
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--brand">
                        <div class="small text-muted">Earned Value</div>
                        <div class="fs-5 fw-bold text-primary">
                          {{ kpi()!.evPct != null ? (kpi()!.evPct | number:'1.0-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">saisie revue mensuelle</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--success">
                        <div class="small text-muted">Delivery</div>
                        <div class="fs-5 fw-bold text-success">
                          {{ kpi()!.deliveryPct != null ? (kpi()!.deliveryPct | number:'1.0-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.deliveredPlanned' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile"
                           [style.background]="(kpi()!.deriveJh ?? 0) < 0 ? 'var(--c-danger-dim)' : 'var(--c-brand-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.variance' | transloco }}</div>
                        <div class="fs-5 fw-bold" [class.text-danger]="(kpi()!.deriveJh ?? 0) < 0">
                          {{ kpi()!.deriveJh != null ? (kpi()!.deriveJh | number:'1.0-1') + ' JH' : '—' }}
                        </div>
                        <div class="small text-muted">
                          {{ kpi()!.consommeJh | number:'1.0-1' }} conso. / {{ kpi()!.rafJh | number:'1.0-1' }} RAF
                        </div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--teal">
                        <div class="small text-muted">CA Production</div>
                        <div class="fs-5 fw-bold text-info">
                          {{ kpi()!.caProduction != null ? (kpi()!.caProduction | number:'1.0-0') : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.contractByEv' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--warning">
                        <div class="small text-muted">FAE / Stock</div>
                        <div class="fs-5 fw-bold text-warning">
                          {{ kpi()!.fae != null ? (kpi()!.fae | number:'1.0-0') : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.invoiced' | transloco: { amount: (kpi()!.totalFacture | number:'1.0-0') } }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile"
                           [style.background]="(kpi()!.margeActuellePct ?? 0) >= (kpi()!.margeVenduePct ?? 0) ? 'var(--c-success-dim)' : 'var(--c-danger-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.actualVsSold' | transloco }}</div>
                        <div class="fs-5 fw-bold">
                          {{ kpi()!.margeActuellePct != null ? ((kpi()!.margeActuellePct! * 100) | number:'1.1-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">
                          vendue : {{ kpi()!.margeVenduePct != null ? ((kpi()!.margeVenduePct! * 100) | number:'1.1-1') + ' %' : '—' }}
                        </div>
                      </div>
                    </div>
                  </div>

                  @if (kpi()!.warnings?.length) {
                    <div class="alert alert-warning py-2 small mt-3 mb-0 d-flex align-items-start gap-2" role="alert">
                      <i class="bi bi-exclamation-triangle-fill flex-shrink-0 mt-1"></i>
                      <ul class="mb-0 ps-2">
                        @for (w of kpi()!.warnings; track w) {
                          <li>{{ w }}</li>
                        }
                      </ul>
                    </div>
                  }

                  <!-- ── Revue mensuelle : snapshot avec saisie EV (F-AFF-13 "Situation actuelle") ── -->
                  @if (auth.hasPermission('EDIT_PROJECT') && !project()?.archived) {
                    <hr class="my-3">
                    <div class="row g-2 align-items-end">
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">EV % (avancement)</label>
                        <input type="number" class="form-control form-control-sm" min="0" max="100"
                               [(ngModel)]="snapEvPct" placeholder="ex. 75">
                      </div>
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">{{ 'project.kpi.estimatedEndDate' | transloco }}</label>
                        <input type="date" class="form-control form-control-sm" [(ngModel)]="snapDateFin">
                      </div>
                      <div class="col-sm-4">
                        <label class="form-label small fw-semibold mb-1">Faits marquants</label>
                        <input class="form-control form-control-sm" [(ngModel)]="snapFaits" maxlength="2000">
                      </div>
                      <div class="col-sm-2 d-grid">
                        <button class="btn btn-sm btn-primary" (click)="createSnapshot()" [disabled]="snapshotLoading()">
                          @if (snapshotLoading()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                          <i class="bi bi-camera me-1"></i>Snapshot
                        </button>
                      </div>
                    </div>
                    @if (snapshotMsg()) {
                      <div class="small mt-2" [class.text-success]="!snapshotError()" [class.text-danger]="snapshotError()">
                        {{ snapshotMsg() }}
                      </div>
                    }
                  }
                </div>
              </div>
            </div>
          }
        </div>
      }

      <!-- ===== TAB: ÉQUIPE ===== -->
      @if (tab() === 'equipe') {
        <div class="card">
          <div class="card-header d-flex justify-content-between align-items-center">
            <span><i class="bi bi-people me-2"></i>{{ 'project.team.title' | transloco }}</span>
            <div class="d-flex align-items-center gap-2">
              <span class="badge-draft">{{ team().length }} membres</span>
              @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                <button class="btn btn-primary btn-sm" (click)="openTeamModal()">
                  <i class="bi bi-person-plus me-1"></i>Affecter un membre
                </button>
              }
            </div>
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr><th>{{ 'project.team.member' | transloco }}</th><th>{{ 'project.team.role' | transloco }}</th><th>{{ 'project.team.from' | transloco }}</th><th>{{ 'project.team.until' | transloco }}</th>
                  @if (auth.hasPermission('ASSIGN_DEVELOPER')) { <th class="text-end">{{ 'common.actions' | transloco }}</th> }
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
                        <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="removeMember(m)" title="Retirer" aria-label="Retirer le membre">
                          <i class="bi bi-person-dash"></i>
                        </button>
                      </td>
                    }
                  </tr>
                }
                @empty {
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-people"></i></div>
                      <div class="es-title">{{ 'project.team.empty' | transloco }}</div>
                    </div>
                  </td></tr>
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
              <div class="card-header">
                <i class="bi bi-calendar3 me-2"></i>Plan de charge
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.workload.resource' | transloco }}</th><th>{{ 'project.workload.year' | transloco }}</th><th>{{ 'project.workload.month' | transloco }}</th><th class="text-end">{{ 'project.workload.plannedDays' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (c of pagedPlanCharges(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.plannedDays }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-calendar3"></i></div>
                          <div class="es-title">{{ 'project.workload.emptyPlan' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (planCharges().length > planPageSize()) {
                <app-pagination
                  [page]="planPage()" [pageSize]="planPageSize()" [total]="planCharges().length"
                  (pageChange)="planPage.set($event)"
                  (pageSizeChange)="planPageSize.set($event); planPage.set(0)" />
              }
            </div>
          </div>
          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-clock-history me-2"></i>{{ 'project.workload.actualTitle' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.workload.resource' | transloco }}</th><th>{{ 'project.workload.year' | transloco }}</th><th>{{ 'project.workload.month' | transloco }}</th><th class="text-end">{{ 'project.workload.actualDays' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (c of pagedChargesReelles(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.actualDays }}</td>
                        <td>
                          @if (c.validatedAt) {
                            <span class="badge-active">{{ 'labels.workload.VALIDEE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">Soumise</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-clock-history"></i></div>
                          <div class="es-title">{{ 'project.workload.emptyActual' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (chargesReelles().length > actualPageSize()) {
                <app-pagination
                  [page]="actualPage()" [pageSize]="actualPageSize()" [total]="chargesReelles().length"
                  (pageChange)="actualPage.set($event)"
                  (pageSizeChange)="actualPageSize.set($event); actualPage.set(0)" />
              }
            </div>
          </div>
        </div>
      }

      <!-- ===== TAB: FACTURATION ===== -->
      @if (tab() === 'facturation') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header d-flex justify-content-between">
                <span><i class="bi bi-receipt me-2"></i>Jalons de facturation</span>
                <span class="text-muted small">Total : {{ jalonTotal() | number:'1.0-0' }} TND</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.billing.label' | transloco }}</th><th class="text-end">%</th><th class="text-end">{{ 'project.billing.amountTnd' | transloco }}</th><th>{{ 'project.billing.dueDatePlanned' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
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
                            <span class="badge-active">{{ 'labels.milestone.PAYE' | transloco }}</span>
                          } @else if (j.statut === 'FACTURE') {
                            <span class="badge-completed">{{ 'labels.milestone.FACTURE' | transloco }}</span>
                          } @else {
                            <span class="badge-draft">{{ 'labels.milestone.PREVU' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-list-check"></i></div>
                          <div class="es-title">{{ 'project.billing.emptyMilestones' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-file-earmark-plus me-2"></i>Avenants
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.billing.number' | transloco }}</th><th>{{ 'project.billing.subject' | transloco }}</th><th class="text-end">{{ 'project.billing.amountTnd' | transloco }}</th><th>{{ 'project.billing.date' | transloco }}</th></tr>
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
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-file-earmark-plus"></i></div>
                          <div class="es-title">Aucun avenant</div>
                        </div>
                      </td></tr>
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
          <div class="card-header">
            <i class="bi bi-airplane me-2"></i>Missions
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr><th>{{ 'project.missions.collaborator' | transloco }}</th><th>{{ 'project.missions.subject' | transloco }}</th><th>{{ 'project.missions.place' | transloco }}</th><th>{{ 'project.missions.start' | transloco }}</th><th>{{ 'project.missions.end' | transloco }}</th></tr>
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
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-kanban"></i></div>
                      <div class="es-title">Aucune mission</div>
                    </div>
                  </td></tr>
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
              <div class="card-header">
                <i class="bi bi-exclamation-triangle me-2 text-warning"></i>Registre des risques
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.description' | transloco }}</th><th>{{ 'project.governance.probability' | transloco }}</th><th>{{ 'project.governance.impact' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (r of risks(); track r.id) {
                      <tr>
                        <td class="small">{{ r.description }}</td>
                        <td><span [class]="niveauBadge(r.probabilite)">{{ r.probabilite }}</span></td>
                        <td><span [class]="niveauBadge(r.impact)">{{ r.impact }}</span></td>
                        <td>
                          @if (r.statut === 'FERME') {
                            <span class="badge-active">{{ 'labels.risk.FERME' | transloco }}</span>
                          } @else if (r.statut === 'MITIGE') {
                            <span class="badge-on-hold">{{ 'labels.risk.MITIGE' | transloco }}</span>
                          } @else {
                            <span class="badge-cancelled">Ouvert</span>
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
              <div class="card-header">
                <i class="bi bi-check2-square me-2 text-success"></i>Livrables
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.title' | transloco }}</th><th>{{ 'project.governance.dueDate' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
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
              <div class="card-header">
                <i class="bi bi-arrow-repeat me-2"></i>Demandes de changement
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.title' | transloco }}</th><th>{{ 'project.governance.requester' | transloco }}</th><th>{{ 'project.governance.priority' | transloco }}</th><th>{{ 'project.billing.date' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
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
                            <span class="badge-active">{{ 'labels.changeRequest.APPROUVE' | transloco }}</span>
                          } @else if (dc.statut === 'REJETE') {
                            <span class="badge-cancelled">{{ 'labels.changeRequest.REJETE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">En attente</span>
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
                <label class="form-label fw-semibold">{{ 'project.manager.label' | transloco }} <span class="text-danger">*</span></label>
                <div class="input-wrap mb-2">
                  <i class="bi bi-search input-icon"></i>
                  <input type="search" class="form-control form-control-sm"
                         placeholder="Rechercher par nom..."
                         [ngModel]="chefSearch()" (ngModelChange)="chefSearch.set($event)">
                </div>
                <div class="picker-list">
                  @for (u of filteredChefs(); track u.id) {
                    <button type="button" class="picker-item" [class.is-selected]="chefUserId === u.id"
                            (click)="chefUserId = u.id">
                      <span class="pi-name">{{ u.firstName }} {{ u.lastName }}</span>
                      <span class="badge-draft" style="font-size:10px">{{ u.roleName }}</span>
                      @if (chefUserId === u.id) { <i class="bi bi-check-lg" style="color:var(--c-brand)"></i> }
                    </button>
                  } @empty {
                    <div class="picker-empty">{{ 'project.manager.noneFound' | transloco }}</div>
                  }
                </div>
                <div class="picker-count">{{ filteredChefs().length }} candidat{{ filteredChefs().length !== 1 ? 's' : '' }}</div>
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
              <h5 class="modal-title">{{ 'project.team.assignTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showTeamModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">Membre <span class="text-danger">*</span></label>
                <!-- Recherche + filtre rôle pour faciliter la sélection -->
                <div class="d-flex gap-2 mb-2">
                  <div class="input-wrap" style="flex:1">
                    <i class="bi bi-search input-icon"></i>
                    <input type="search" class="form-control form-control-sm"
                           placeholder="Rechercher par nom..."
                           [ngModel]="memberSearch()" (ngModelChange)="memberSearch.set($event)">
                  </div>
                  <select class="form-select form-select-sm" style="max-width:170px"
                          [ngModel]="memberRoleFilter()" (ngModelChange)="memberRoleFilter.set($event)">
                    <option value="">{{ 'project.team.allRoles' | transloco }}</option>
                    @for (r of memberRoles(); track r) { <option [value]="r">{{ r }}</option> }
                  </select>
                </div>
                <!-- Liste cliquable des candidats filtrés -->
                <div class="picker-list">
                  @for (u of filteredMembers(); track u.id) {
                    <button type="button" class="picker-item" [class.is-selected]="teamForm.userId === u.id"
                            (click)="teamForm.userId = u.id">
                      <span class="pi-name">{{ u.firstName }} {{ u.lastName }}</span>
                      <span class="badge-draft" style="font-size:10px">{{ u.roleName }}</span>
                      @if (teamForm.userId === u.id) { <i class="bi bi-check-lg" style="color:var(--c-brand)"></i> }
                    </button>
                  } @empty {
                    <div class="picker-empty">{{ 'project.team.noMemberFound' | transloco }}</div>
                  }
                </div>
                <div class="picker-count">{{ filteredMembers().length }} membre{{ filteredMembers().length !== 1 ? 's' : '' }} disponible{{ filteredMembers().length !== 1 ? 's' : '' }}</div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.roleInTeam' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" maxlength="50" [(ngModel)]="teamForm.roleInTeam" [placeholder]="'project.team.rolePlaceholder' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.startDate' | transloco }} <span class="text-danger">*</span></label>
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
  private readonly t = inject(TranslocoService);
  private readonly lang = inject(LanguageService);
  /** Locale réactive : les pipes date/number suivent le changement de langue sans rechargement. */
  readonly locale = computed(() => this.lang.current() === 'en' ? 'en-US' : 'fr');
  private readonly svc        = inject(ProjectService);
  private readonly http       = inject(HttpClient);
  private readonly teamSvc    = inject(TeamService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly billingSvc = inject(BillingService);
  private readonly missionSvc = inject(MissionService);
  private readonly govSvc     = inject(GovernanceService);
  private readonly route      = inject(ActivatedRoute);
  private readonly router     = inject(Router);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  readonly listState          = inject(ProjectsListStateService);

  /** Allowed status transitions (enterprise workflow). Empty = terminal via this control. */
  private readonly TRANSITIONS: Record<ProjectStatus, ProjectStatus[]> = {
    DRAFT:     ['ACTIVE', 'CANCELLED'],
    ACTIVE:    ['ON_HOLD', 'COMPLETED', 'CANCELLED'],
    ON_HOLD:   ['ACTIVE', 'CANCELLED'],
    COMPLETED: ['ACTIVE'],
    CANCELLED: ['DRAFT'],
  };
  statusMenuOpen = signal(false);

  project = signal<Project | null>(null);
  kpi = signal<KpiResponse | null>(null);
  team = signal<TeamAssignment[]>([]);
  planCharges = signal<PlanCharge[]>([]);
  chargesReelles = signal<ChargeReelle[]>([]);

  // Ces deux tableaux peuvent compter des centaines de lignes : une ressource par mois
  // et par année. Ils étaient rendus d'un bloc, ce qui allongeait l'onglet indéfiniment.
  // Pagination côté client : les données sont déjà chargées, inutile de rappeler le serveur.
  planPage       = signal(0);
  planPageSize   = signal(10);
  actualPage     = signal(0);
  actualPageSize = signal(10);

  readonly pagedPlanCharges = computed(() =>
    slicePage(this.planCharges(), this.planPage(), this.planPageSize()));
  readonly pagedChargesReelles = computed(() =>
    slicePage(this.chargesReelles(), this.actualPage(), this.actualPageSize()));
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

  // Recherche & filtre pour l'affectation d'un membre
  memberSearch = signal('');
  memberRoleFilter = signal('');

  /** Rôles distincts présents dans la liste des utilisateurs affectables (pour le filtre). */
  readonly memberRoles = computed(() =>
    [...new Set(this.allUsers().map(u => u.roleName))].sort()
  );

  /** Candidats filtrés : recherche par nom + filtre rôle, en excluant les membres déjà dans l'équipe. */
  readonly filteredMembers = computed(() => {
    const q = this.memberSearch().trim().toLowerCase();
    const role = this.memberRoleFilter();
    const already = new Set(this.team().map(m => m.userId));
    return this.allUsers().filter(u =>
      !already.has(u.id) &&
      (!role || u.roleName === role) &&
      (!q || `${u.firstName} ${u.lastName}`.toLowerCase().includes(q))
    );
  });

  // Recherche pour l'assignation du chef de projet
  chefSearch = signal('');

  /** Candidats chef filtrés par recherche (la liste est déjà restreinte aux CHEF_PROJET). */
  readonly filteredChefs = computed(() => {
    const q = this.chefSearch().trim().toLowerCase();
    return this.chefCandidates().filter(u =>
      !q || `${u.firstName} ${u.lastName}`.toLowerCase().includes(q)
    );
  });

  // Revue mensuelle (snapshot EVM)
  snapEvPct: number | null = null;
  snapDateFin = '';
  snapFaits = '';
  snapshotLoading = signal(false);
  snapshotMsg = signal('');
  snapshotError = signal(false);

  private projectId = 0;

  /** Permission required to open each tab (null = always allowed). */
  private readonly TAB_PERM: Record<Tab, string | null> = {
    info: null, equipe: 'VIEW_TEAM', charges: 'VIEW_WORKLOAD',
    facturation: 'VIEW_BILLING', missions: 'VIEW_MISSION', gouvernance: 'VIEW_GOVERNANCE'
  };

  ngOnInit(): void {
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    this.svc.get(this.projectId).subscribe(p => {
      this.project.set(p);
      if (this.auth.hasPermission('VIEW_KPI')) {
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      }
    });
    this.loadedTabs.add('info');

    // Restore the active tab from the URL (?tab=), honouring permissions.
    const t = this.route.snapshot.queryParamMap.get('tab') as Tab | null;
    if (t && t !== 'info' && (!this.TAB_PERM[t] || this.auth.hasPermission(this.TAB_PERM[t]!))) {
      this.setTab(t);
    }
  }

  /** Revue mensuelle : fige les KPI du jour avec l'EV % saisi (F-AFF-13 "Situation actuelle"). */
  createSnapshot(): void {
    this.snapshotLoading.set(true);
    this.snapshotMsg.set('');
    this.svc.createSnapshot(this.projectId, {
      evPct: this.snapEvPct ?? undefined,
      dateFinEstimee: this.snapDateFin || undefined,
      faitsMarquants: this.snapFaits || undefined
    }).subscribe({
      next: () => {
        this.snapshotLoading.set(false);
        this.snapshotError.set(false);
        this.snapshotMsg.set(this.t.translate('project.msg.snapshotCreated'));
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      },
      error: (e: { status: number; error?: { detail?: string } }) => {
        this.snapshotLoading.set(false);
        this.snapshotError.set(true);
        this.snapshotMsg.set(e.status === 409
          ? this.t.translate('project.msg.snapshotExists')
          : (e.error?.detail ?? this.t.translate('project.msg.snapshotError')));
      }
    });
  }

  async archiveProject(): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.msg.archiveConfirm'))) return;
    this.svc.archive(this.projectId).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.archived')); },
      error: e => this.toast.error(e.error?.detail ?? 'Erreur lors de l\'archivage.')
    });
  }

  unarchiveProject(): void {
    this.svc.unarchive(this.projectId).subscribe(p => {
      this.project.set(p);
      this.toast.success(this.t.translate('project.msg.unarchived'));
    });
  }

  // ── Status management ────────────────────────────────────────────
  allowedTransitions(status: ProjectStatus): ProjectStatus[] {
    return this.TRANSITIONS[status] ?? [];
  }

  async changeStatus(target: ProjectStatus): Promise<void> {
    this.statusMenuOpen.set(false);
    const label = this.t.translate('status.' + target);
    if (!await this.confirm.ask(`Changer le statut du projet vers « ${label} » ?`, 'Changer le statut')) return;
    this.svc.changeStatus(this.projectId, target).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.statusUpdated', { status: label })); },
      error: e => this.toast.error(e.error?.detail ?? e.error?.message ?? 'Erreur lors du changement de statut.')
    });
  }

  setTab(t: Tab): void {
    this.tab.set(t);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: t === 'info' ? {} : { tab: t },
      replaceUrl: true,
    });
    if (this.loadedTabs.has(t)) return;
    this.loadedTabs.add(t);

    switch (t) {
      case 'equipe':
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        break;
      case 'charges':
        // Onglet aperçu : liste complète (page large), la pagination UI vit dans l'écran Charges
        this.workloadSvc.listPlanCharges(this.projectId, 0, 1000).subscribe(d => this.planCharges.set(d.content));
        this.workloadSvc.listChargesReelles(this.projectId, 0, 1000).subscribe(d => this.chargesReelles.set(d.content));
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
    // Intl fournit le mois dans la langue courante : un tableau figé resterait français.
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.locale(), { month: 'short' }).format(new Date(2000, m - 1, 1));
  }

  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }

  niveauBadge(n: string): string {
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
    this.chefSearch.set('');
    this.modalError.set('');
    this.showChefModal.set(true);
  }

  saveChef(): void {
    if (!this.chefUserId) { this.modalError.set(this.t.translate('project.manager.required')); return; }
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
    this.memberSearch.set('');
    this.memberRoleFilter.set('');
    this.modalError.set('');
    this.showTeamModal.set(true);
  }

  saveTeamMember(): void {
    if (!this.teamForm.userId || !this.teamForm.roleInTeam.trim() || !this.teamForm.startDate) {
      this.modalError.set(this.t.translate('project.msg.teamFormRequired'));
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

  async removeMember(m: TeamAssignment): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.team.removeConfirm', { name: m.userFullName }))) return;
    this.teamSvc.remove(this.projectId, m.id).subscribe(() =>
      this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d))
    );
  }
}

/**
 * Tranche de page bornée. `Math.min` empêche une page vide lorsque la liste rétrécit
 * (filtre, rechargement) alors que l'index de page courant pointe au-delà de la fin.
 */
function slicePage<T>(rows: T[], page: number, size: number): T[] {
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const p = Math.min(page, pages - 1);
  return rows.slice(p * size, p * size + size);
}
