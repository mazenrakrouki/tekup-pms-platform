// Read screen for one project (/projects/{id}): identity/KPI, team, workload, billing,
// missions, governance across six lazily-loaded tabs; also handles status/archive/manager/
// snapshot changes. Permission checks here are display only — server enforces via @PreAuthorize + ADR-021.

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
import { Project, ProjectStatus } from '../../../core/models/project.model';
import { KpiResponse } from '../../../core/models/kpi.model';
import { TeamAssignment } from '../../../core/models/team.model';
import { PlanCharge, ChargeReelle } from '../../../core/models/workload.model';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { JalonFacturation, Avenant } from '../../../core/models/billing.model';
import { Mission } from '../../../core/models/mission.model';
import { Risk, Livrable, DemandeChangement } from '../../../core/models/governance.model';

// Exact-string union (used as a TAB_PERM key, in the URL's ?tab=, and in a switch) so a typo
// fails to compile instead of silently leaving a tab empty.
type Tab = 'info' | 'equipe' | 'charges' | 'facturation' | 'missions' | 'gouvernance';

@Component({
  selector: 'app-project-detail',
  standalone: true,
  providers: [provideTranslocoScope('project')],
  imports: [CommonModule, FormsModule, RouterLink, PaginationComponent, TranslocoModule],
  styles: [`
    /* CSS variables (var(--...)), never a raw color, so light/dark theming works. */
    .act-danger { color: var(--c-danger); }
    /* Interactive status control (Jira/Linear style) */
    .status-ctl { position: relative; display: inline-flex; }
    .status-btn { display: inline-flex; align-items: center; gap: .35rem; border: 1px solid var(--border);
      background: var(--surface); border-radius: var(--r); padding: .15rem .4rem; cursor: pointer;
      transition: border-color var(--t), background var(--t); }
    .status-btn:hover { border-color: var(--border-2); background: var(--surface-2); }
    /* :focus-visible (not :focus): ring only for keyboard navigation, not mouse clicks. */
    .status-btn:focus-visible { outline: 2px solid var(--c-brand); outline-offset: 2px; }
    .status-btn .bi-chevron-down { font-size: 10px; color: var(--text-3); }
    /* Full-window click-catcher under the menu (z-index 300 vs 301): closes it on outside click. */
    .status-backdrop { position: fixed; inset: 0; z-index: 300; }
    /* Relative to .status-ctl (position: relative above); without that parent the menu would
       anchor to the page and stay in the corner when scrolled. */
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
    /* tabular-nums keeps digits aligned when a tile refreshes. */
    .kpi-tile .fs-5 { font-variant-numeric: tabular-nums; }
    .kpi-tile--brand   { background: var(--c-brand-dim); }
    .kpi-tile--warning { background: var(--c-warning-dim); }
    .kpi-tile--teal     { background: var(--c-teal-dim); }
    .kpi-tile--success { background: var(--c-success-dim); }
    .kpi-tile--neutral { background: var(--surface-2, var(--bg)); }

    /* Candidate picker list (chef / team modals): search box + scrollable list, never a
       giant dropdown. Fixed max-height keeps Cancel/Assign visible with 200+ employees. */
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
        <!-- Replays the list's search/filter/page so Back doesn't lose it. -->
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <i class="bi bi-arrow-left"></i> {{ 'projects.title' | transloco }}
        </a>
        <span class="bc-sep">›</span>
        <!-- ?./?? cover project() still being null while the GET is in flight. -->
        <span class="bc-curr">{{ project()?.code ?? '—' }}</span>
        @if (project()?.archived) {
          <span class="badge-draft" style="margin-left:.5rem"><i class="bi bi-archive me-1"></i>{{ 'project.archived' | transloco }}</span>
        }
      </div>
      <div class="tb-right">
        <!-- Every hasPermission() check in this file only hides UI; the server enforces
             it (@PreAuthorize + ADR-021 project scope). -->
        @if (auth.hasPermission('MANAGE_DI')) {
          <a [routerLink]="['/projects', project()?.id, 'devis-interne']" class="btn btn-outline-secondary btn-sm">
            <i class="bi bi-file-earmark-lock2"></i>DI
          </a>
        }
        @if (auth.hasPermission('EDIT_PROJECT')) {
          <!-- Archived is read-only for everybody: Edit and the Unarchive button are exclusive. -->
          @if (!project()?.archived) {
            <a [routerLink]="['/projects', project()?.id, 'edit']" class="btn btn-outline-secondary btn-sm">
              <i class="bi bi-pencil"></i>{{ 'common.edit' | transloco }}
            </a>
            @if (project()?.status === 'COMPLETED') {
              <button class="btn btn-outline-secondary btn-sm" (click)="archiveProject()">
                <i class="bi bi-archive"></i>{{ 'project.actions.archive' | transloco }}
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
          <!-- "as p" reads the signal once for the block; p is known non-null inside it. -->
          @if (project(); as p) {
            <div class="page-subtitle" style="display:flex;align-items:center;gap:.5rem">
              @if (p.client) { <span>{{ p.client }}</span><span class="bc-sep">·</span> }
              <!-- Button only when there's a real next status to offer; else the plain badge below. -->
              @if (auth.hasPermission('EDIT_PROJECT') && !p.archived && allowedTransitions(p.status).length) {
                <span class="status-ctl">
                  <!-- aria-expanded/aria-haspopup: without them a screen reader only hears the status name. -->
                  <button class="status-btn" (click)="statusMenuOpen.set(!statusMenuOpen())"
                          [attr.aria-expanded]="statusMenuOpen()" aria-haspopup="menu" [title]="'project.actions.changeStatus' | transloco">
                    <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
                    <i class="bi bi-chevron-down"></i>
                  </button>
                  @if (statusMenuOpen()) {
                    <div class="status-backdrop" (click)="statusMenuOpen.set(false)"></div>
                    <div class="status-menu" role="menu">
                      <div class="status-menu-label">{{ 'project.actions.changeStatus' | transloco }}</div>
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
        <!-- Each tab's @if mirrors TAB_PERM in the class, which is what actually guards the
             ?tab= URL parameter (hiding the button alone wouldn't). -->
        <div class="pms-tabs">
          <button class="tab-item" [class.active]="tab()==='info'" (click)="setTab('info')">
            <!-- Key chosen before translation: a non-VIEW_KPI user never sees a label promising KPI. -->
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
      <!-- @if actually removes the other five tabs' markup, not just hides it. -->
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
                      <button class="btn btn-sm btn-link p-0 ms-2" (click)="openChefModal()" [title]="'project.manager.assign' | transloco">
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
                    <!-- locale() signal in the 3rd pipe arg: redraws the date on a language switch. -->
                    <dd class="col-7">{{ p.createdAt | date:'mediumDate':undefined:locale() }}, {{ p.createdAt | date:'shortTime':undefined:locale() }}</dd>
                  }
                  <!-- BR-050: budgets hidden without VIEW_KPI; server also refuses them independently. -->
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
                    <dt class="col-5 text-muted">{{ 'project.info.soldWorkload' | transloco }}</dt>
                    <dd class="col-7">{{ p.soldWorkloadDays | number:'1.0-0' }} {{ 'common.manDays' | transloco }}
                      @if (p.warrantyWorkloadDays) { <span class="text-muted">({{ 'project.info.warranty' | transloco }} {{ p.warrantyWorkloadDays | number:'1.0-0' }} {{ 'common.manDays' | transloco }})</span> }
                    </dd>
                  }
                </dl>
              </div>
            </div>
          </div>

          <!-- kpi() test: it arrives later than project() (fired second in ngOnInit), so
               without this guard kpi()! below would read a property of null. -->
          @if (auth.hasPermission('VIEW_KPI') && kpi()) {
            <div class="col-lg-7">
              <div class="card h-100">
                <div class="card-header">{{ 'project.kpi.title' | transloco }}</div>
                <div class="card-body">
                  <!-- Server-computed at read time, never a stored column, so two screens
                       can never disagree on the margin. -->
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
                      <!-- Bound background: a losing project must be visible at a glance. -->
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
                        <div class="small text-muted">{{ 'project.kpi.consumptionRate' | transloco }}</div>
                        <!-- Server sends a 0-1 ratio; *100 here for display. -->
                        <div class="fs-5 fw-bold">{{ (kpi()!.tauxConsommation * 100) | number:'1.1-1' }}%</div>
                        <div class="progress mt-1" style="height:4px">
                          <div class="progress-bar" [style.width.%]="kpi()!.tauxConsommation * 100"></div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <!-- EVM (F-AFF-13) tiles: empty until the first monthly snapshot, hence the
                       != null ? ... : '—' guards below (else a new project would show "null %"). -->
                  <hr class="my-3">
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--brand">
                        <div class="small text-muted">{{ 'project.kpi.earnedValue' | transloco }}</div>
                        <div class="fs-5 fw-bold text-primary">
                          {{ kpi()!.evPct != null ? (kpi()!.evPct | number:'1.0-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.monthlyReview' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--success">
                        <div class="small text-muted">{{ 'project.kpi.delivery' | transloco }}</div>
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
                          {{ kpi()!.deriveJh != null ? (kpi()!.deriveJh | number:'1.0-1') + ' ' + ('common.manDays' | transloco) : '—' }}
                        </div>
                        <div class="small text-muted">
                          {{ kpi()!.consommeJh | number:'1.0-1' }} {{ 'project.kpi.consumedShort' | transloco }} / {{ kpi()!.rafJh | number:'1.0-1' }} {{ 'project.kpi.remainingShort' | transloco }}
                        </div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--teal">
                        <div class="small text-muted">{{ 'project.kpi.caProduction' | transloco }}</div>
                        <div class="fs-5 fw-bold text-info">
                          {{ kpi()!.caProduction != null ? (kpi()!.caProduction | number:'1.0-0') : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.contractByEv' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--warning">
                        <div class="small text-muted">{{ 'project.kpi.faeStock' | transloco }}</div>
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

                  <!-- Monthly review snapshot (F-AFF-13): freezes today's KPI + hand-typed EV%,
                       so next month still shows where the project stood. Money is server-recomputed,
                       never sent by the browser. -->
                  @if (auth.hasPermission('EDIT_PROJECT') && !project()?.archived) {
                    <hr class="my-3">
                    <div class="row g-2 align-items-end">
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">EV % (avancement)</label>
                        <!-- [(ngModel)] here (vs. the modals' split ngModel/change below): a plain
                             field, not a signal, so two-way binding is fine. min/max: browser comfort only. -->
                        <input type="number" class="form-control form-control-sm" min="0" max="100"
                               [(ngModel)]="snapEvPct" [placeholder]="'project.kpi.evPlaceholder' | transloco">
                      </div>
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">{{ 'project.kpi.estimatedEndDate' | transloco }}</label>
                        <input type="date" class="form-control form-control-sm" [(ngModel)]="snapDateFin">
                      </div>
                      <div class="col-sm-4">
                        <label class="form-label small fw-semibold mb-1">{{ 'project.kpi.highlights' | transloco }}</label>
                        <input class="form-control form-control-sm" [(ngModel)]="snapFaits" maxlength="2000">
                      </div>
                      <div class="col-sm-2 d-grid">
                        <!-- Disabled while saving: a second POST would 409 (one snapshot/month). -->
                        <button class="btn btn-sm btn-primary" (click)="createSnapshot()" [disabled]="snapshotLoading()">
                          @if (snapshotLoading()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                          <i class="bi bi-camera me-1"></i>{{ 'project.kpi.snapshot' | transloco }}
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

      <!-- ===== TAB: TEAM ===== -->
      <!-- TeamService.list, fired once by setTab; rows already carry userFullName from the server. -->
      @if (tab() === 'equipe') {
        <div class="card">
          <div class="card-header d-flex justify-content-between align-items-center">
            <span><i class="bi bi-people me-2"></i>{{ 'project.team.title' | transloco }}</span>
            <div class="d-flex align-items-center gap-2">
              <span class="badge-draft">{{ team().length }} membres</span>
              @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                <button class="btn btn-primary btn-sm" (click)="openTeamModal()">
                  <i class="bi bi-person-plus me-1"></i>{{ 'project.team.assignTitle' | transloco }}
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
                <!-- track m.id (the assignment row, not the user id): a person can have both
                     an old closed and a new assignment on the same project. -->
                @for (m of team(); track m.id) {
                  <tr>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.roleInTeam ?? '—' }}</td>
                    <td>{{ m.startDate }}</td>
                    <td>{{ m.endDate ?? 'Actif' }}</td>
                    @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                      <td class="text-end">
                        <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="removeMember(m)" [title]="'project.team.remove' | transloco" [attr.aria-label]="'project.team.removeAria' | transloco">
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

      <!-- ===== TAB: WORKLOAD ===== -->
      <!-- Planned vs. actually-spent days; both lists paged in the browser (already loaded, see
           pagedPlanCharges in the class), so paging costs no HTTP call. -->
      @if (tab() === 'charges') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-calendar3 me-2"></i>{{ 'project.workload.planTitle' | transloco }}
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
              <!-- Only drawn with more than one page ("page 1 of 1" is noise). Page reset to 0
                   on page-size change, else a shrink could land past the end. -->
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
                          <!-- Read from validatedAt (not a status field): unfilled means not yet counted. -->
                          @if (c.validatedAt) {
                            <span class="badge-active">{{ 'labels.workload.VALIDEE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">{{ 'labels.workload.SOUMISE' | transloco }}</span>
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

      <!-- ===== TAB: BILLING ===== -->
      <!-- "jalon" = billing milestone (PREVU -> FACTURE -> PAYE). "avenant" = contract
           amendment; amount can be negative (colored red below). -->
      @if (tab() === 'facturation') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header d-flex justify-content-between">
                <span><i class="bi bi-receipt me-2"></i>{{ 'project.billing.milestones' | transloco }}</span>
                <!-- Display total only; the server's figure is what counts for the accounts. -->
                <span class="text-muted small">{{ 'common.total' | transloco }} {{ jalonTotal() | number:'1.0-0' }} TND</span>
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
                <i class="bi bi-file-earmark-plus me-2"></i>{{ 'project.billing.amendments' | transloco }}
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
                          <div class="es-title">{{ 'project.billing.emptyAmendments' | transloco }}</div>
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
      <!-- Read-only here; the missions screen is where trips are created with their cost lines. -->
      @if (tab() === 'missions') {
        <div class="card">
          <div class="card-header">
            <i class="bi bi-airplane me-2"></i>{{ 'project.tabs.missions' | transloco }}
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
                      <div class="es-title">{{ 'project.missions.empty' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ===== TAB: GOVERNANCE ===== -->
      <!-- Three lists a steering committee asks about, loaded together, so they share one tab. -->
      @if (tab() === 'gouvernance') {
        <div class="row g-4">
          <!-- Risks -->
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header">
                <i class="bi bi-exclamation-triangle me-2 text-warning"></i>{{ 'governance.riskRegister' | transloco }}
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
                        <!-- niveauBadge() picks the color, transloco picks the text — kept separate. -->
                        <td><span [class]="niveauBadge(r.probabilite)">{{ 'riskLevel.' + r.probabilite | transloco }}</span></td>
                        <td><span [class]="niveauBadge(r.impact)">{{ 'riskLevel.' + r.impact | transloco }}</span></td>
                        <td>
                          @if (r.statut === 'FERME') {
                            <span class="badge-active">{{ 'labels.risk.FERME' | transloco }}</span>
                          } @else if (r.statut === 'MITIGE') {
                            <span class="badge-on-hold">{{ 'labels.risk.MITIGE' | transloco }}</span>
                          } @else {
                            <span class="badge-cancelled">{{ 'labels.risk.OUVERT' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4" class="text-center py-3 text-muted small">{{ 'project.governance.emptyRisks' | transloco }}</td></tr>
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
                <i class="bi bi-check2-square me-2 text-success"></i>{{ 'project.governance.deliverables' | transloco }}
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
                        <td><span [class]="livrableBadge(l.statut)">{{ 'labels.deliverable.' + l.statut | transloco }}</span></td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="3" class="text-center py-3 text-muted small">{{ 'project.governance.emptyDeliverables' | transloco }}</td></tr>
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
                <i class="bi bi-arrow-repeat me-2"></i>{{ 'project.governance.changeRequests' | transloco }}
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
                        <td><span [class]="prioriteBadge(dc.priorite)">{{ 'changePriority.' + dc.priorite | transloco }}</span></td>
                        <td class="small">{{ dc.dateDemande ?? '—' }}</td>
                        <td>
                          @if (dc.statut === 'APPROUVE') {
                            <span class="badge-active">{{ 'labels.changeRequest.APPROUVE' | transloco }}</span>
                          } @else if (dc.statut === 'REJETE') {
                            <span class="badge-cancelled">{{ 'labels.changeRequest.REJETE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">{{ 'labels.changeRequest.EN_ATTENTE' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5" class="text-center py-3 text-muted small">{{ 'project.governance.emptyChangeRequests' | transloco }}</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

    </div>

    <!-- Hand-written, not Bootstrap JS: the block only exists while the signal is true. -->
    @if (showChefModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showChefModal.set(false)">
        <!-- stopPropagation: a click inside the form must not close the window. -->
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'project.manager.assignTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showChefModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.manager.label' | transloco }} <span class="text-danger">*</span></label>
                <div class="input-wrap mb-2">
                  <i class="bi bi-search input-icon"></i>
                  <!-- Split ngModel/change: target is a signal, [(ngModel)] can't write .set(). No
                       debounce needed: filteredChefs() only filters an already in-memory list. -->
                  <input type="search" class="form-control form-control-sm"
                         [placeholder]="'common.searchByName' | transloco"
                         [ngModel]="chefSearch()" (ngModelChange)="chefSearch.set($event)">
                </div>
                <!-- Search + clickable list, never a <select> of every employee. -->
                <div class="picker-list">
                  @for (u of filteredChefs(); track u.id) {
                    <!-- Plain field (not a signal): only read when Assign is pressed. -->
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
              <button class="btn btn-secondary" (click)="showChefModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveChef()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Assigner
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Same construction as above; also filterable by role, and excludes people already on the team. -->
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
                <label class="form-label fw-semibold">{{ 'project.team.member' | transloco }} <span class="text-danger">*</span></label>
                <!-- Search box + role filter, to make the choice easier. -->
                <div class="d-flex gap-2 mb-2">
                  <div class="input-wrap" style="flex:1">
                    <i class="bi bi-search input-icon"></i>
                    <input type="search" class="form-control form-control-sm"
                           [placeholder]="'common.searchByName' | transloco"
                           [ngModel]="memberSearch()" (ngModelChange)="memberSearch.set($event)">
                  </div>
                  <!-- A <select> is fine here: a handful of roles, not a large set of rows. -->
                  <select class="form-select form-select-sm" style="max-width:170px"
                          [ngModel]="memberRoleFilter()" (ngModelChange)="memberRoleFilter.set($event)">
                    <option value="">{{ 'project.team.allRoles' | transloco }}</option>
                    @for (r of memberRoles(); track r) { <option [value]="r">{{ r }}</option> }
                  </select>
                </div>
                <!-- Clickable list of the filtered candidates. -->
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
                <!-- Free text (developer, analyst...) — the job in this team, not the account's
                     security role. maxlength matches the server column, pre-empting a 400. -->
                <input type="text" class="form-control" maxlength="50" [(ngModel)]="teamForm.roleInTeam" [placeholder]="'project.team.rolePlaceholder' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.startDate' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="teamForm.startDate">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showTeamModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveTeamMember()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'project.team.assign' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
// Reads the id in the URL, asks the right service for the active tab's data, puts answers
// into signals, sends back the few allowed changes. implements OnInit since the route isn't
// ready at construction time. 'auth' and 'listState' are public: the template reads them directly.
export class ProjectDetailComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly t = inject(TranslocoService);
  private readonly lang = inject(LanguageService);
  // Recomputes on language change, so month names/separators don't stay stuck until a reload.
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

  // Display-only workflow (server refuses a forbidden move regardless). Record<ProjectStatus,
  // ProjectStatus[]> forces every status to have an entry, so a new enum value fails to compile
  // if forgotten here.
  private readonly TRANSITIONS: Record<ProjectStatus, ProjectStatus[]> = {
    DRAFT:     ['ACTIVE', 'CANCELLED'],
    ACTIVE:    ['ON_HOLD', 'COMPLETED', 'CANCELLED'],
    ON_HOLD:   ['ACTIVE', 'CANCELLED'],
    COMPLETED: ['ACTIVE'],
    CANCELLED: ['DRAFT'],
  };
  statusMenuOpen = signal(false);

  // ── The data of the screen, one signal per box ──────────────────────────────
  // Signals (not plain fields): with OnPush, a plain field's change wouldn't even be noticed.
  // Two null-at-start boxes hold one object not yet arrived; the rest start as empty arrays
  // so tables can render before any call returns.

  /** The project sheet itself: GET /api/projects/{id}. */
  project = signal<Project | null>(null);
  /** The live indicators: GET /api/projects/{id}/kpi. Loaded only with VIEW_KPI. */
  kpi = signal<KpiResponse | null>(null);
  /** The people on the project (team tab). */
  team = signal<TeamAssignment[]>([]);
  /** The days planned per person and per month (workload tab). */
  planCharges = signal<PlanCharge[]>([]);
  /** The days declared as really spent (workload tab). */
  chargesReelles = signal<ChargeReelle[]>([]);

  // Paged in the browser: rows are already loaded, so turning a page needs no new call.
  planPage       = signal(0);
  planPageSize   = signal(10);
  actualPage     = signal(0);
  actualPageSize = signal(10);

  // computed() (not a template method) so the slice is rebuilt only when its inputs change.
  readonly pagedPlanCharges = computed(() =>
    slicePage(this.planCharges(), this.planPage(), this.planPageSize()));
  readonly pagedChargesReelles = computed(() =>
    slicePage(this.chargesReelles(), this.actualPage(), this.actualPageSize()));
  /** Billing milestones (billing tab). */
  jalons = signal<JalonFacturation[]>([]);
  /** Contract amendments (billing tab). */
  avenants = signal<Avenant[]>([]);
  /** Trips made for this project (missions tab). */
  missions = signal<Mission[]>([]);
  /** Risk register (governance tab). */
  risks = signal<Risk[]>([]);
  /** Deliverables (governance tab). */
  livrables = signal<Livrable[]>([]);
  /** Change requests (governance tab). */
  changes = signal<DemandeChangement[]>([]);

  tab = signal<Tab>('info');
  // Set (not a signal, template never reads it): setTab() asks a service only for a tab not
  // already in it, else switching tabs back and forth would re-fire the same calls.
  private loadedTabs = new Set<Tab>();

  // ── Project manager / team management (B7/B8) ──────────────────────────────
  // Inline type (not the full User model): the server returns more fields, this narrows to
  // just what the two lists need.
  allUsers = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);
  showChefModal = signal(false);
  showTeamModal = signal(false);
  saving = signal(false);
  modalError = signal('');
  chefUserId = 0;
  // Plain object: [(ngModel)], read only when Assign is pressed. split('T')[0]: an <input
  // type="date"> only accepts YYYY-MM-DD; a full Date could shift a day via timezone.
  teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };

  memberSearch = signal('');
  memberRoleFilter = signal('');

  // Built from the data (not a fixed list): roles are dynamic, an admin can add one.
  readonly memberRoles = computed(() =>
    [...new Set(this.allUsers().map(u => u.roleName))].sort()
  );

  // Set of already-assigned user ids for O(1) lookup instead of scanning per candidate.
  // Excluding them avoids the server refusing a duplicate active assignment.
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

  chefSearch = signal('');

  readonly filteredChefs = computed(() => {
    const q = this.chefSearch().trim().toLowerCase();
    return this.chefCandidates().filter(u =>
      !q || `${u.firstName} ${u.lastName}`.toLowerCase().includes(q)
    );
  });

  // ── Monthly review (the EVM snapshot, F-AFF-13) ────────────────────────────
  // Plain fields, bound with [(ngModel)]; read once when Snapshot is pressed.
  snapEvPct: number | null = null;
  snapDateFin = '';
  snapFaits = '';
  snapshotLoading = signal(false);
  snapshotMsg = signal('');
  snapshotError = signal(false);

  // Plain number (not a signal): set once in ngOnInit, never changes while the screen lives.
  private projectId = 0;

  // Actually protects the ?tab= parameter: a hidden tab button doesn't stop someone typing
  // /projects/12?tab=facturation by hand. Still just tidiness — the server refuses the data
  // independently.
  private readonly TAB_PERM: Record<Tab, string | null> = {
    info: null, equipe: 'VIEW_TEAM', charges: 'VIEW_WORKLOAD',
    facturation: 'VIEW_BILLING', missions: 'VIEW_MISSION', gouvernance: 'VIEW_GOVERNANCE'
  };

  ngOnInit(): void {
    // snapshot: enough since leaving this screen for another project rebuilds the component.
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    this.svc.get(this.projectId).subscribe(p => {
      this.project.set(p);
      // Nested inside the first answer (not fired alongside) so a 404 project skips this call.
      if (this.auth.hasPermission('VIEW_KPI')) {
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      }
    });
    // Already loaded by the two calls above; without this the first click back on it would
    // re-fetch the project.
    this.loadedTabs.add('info');

    // Restore active tab from the URL, permission re-checked since the URL can be hand-typed.
    const t = this.route.snapshot.queryParamMap.get('tab') as Tab | null;
    if (t && t !== 'info' && (!this.TAB_PERM[t] || this.auth.hasPermission(this.TAB_PERM[t]!))) {
      this.setTab(t);
    }
  }

  // Only 3 values sent; the server recomputes every money figure at that moment. || undefined
  // on the two texts: an empty box must mean "no value", not an empty string overwriting one.
  // 409 = one snapshot per month, gets its own message instead of a raw technical error.
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
        // Re-read so the tiles pick up the EV % just typed, instead of staying stale.
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

  // Archiving is NOT deleting: data, KPI history and quote all stay. async/await for
  // ConfirmService (not window.confirm, which freezes the tab and can't be translated).
  async archiveProject(): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.msg.archiveConfirm'))) return;
    this.svc.archive(this.projectId).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.archived')); },
      error: e => this.toast.error(e.error?.detail ?? 'Erreur lors de l\'archivage.')
    });
  }

  // No confirmation here: reversible in one click, unlike archiving.
  unarchiveProject(): void {
    this.svc.unarchive(this.projectId).subscribe(p => {
      this.project.set(p);
      this.toast.success(this.t.translate('project.msg.unarchived'));
    });
  }

  // ── Status management ────────────────────────────────────────────
  // ?? []: an unknown status shows no move instead of crashing on undefined.
  allowedTransitions(status: ProjectStatus): ProjectStatus[] {
    return this.TRANSITIONS[status] ?? [];
  }

  // Label computed once and reused in both the question and the success message, so they
  // can't drift apart.
  async changeStatus(target: ProjectStatus): Promise<void> {
    this.statusMenuOpen.set(false);
    const label = this.t.translate('status.' + target);
    if (!await this.confirm.ask(
      this.t.translate('project.msg.statusChangeConfirm', { status: label }),
      this.t.translate('project.msg.statusChangeTitle'))) return;
    this.svc.changeStatus(this.projectId, target).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.statusUpdated', { status: label })); },
      error: e => this.toast.error(e.error?.detail ?? e.error?.message
        ?? this.t.translate('project.msg.statusChangeError'))
    });
  }

  // Heart of the screen: the loadedTabs guard is what stops every tab click re-firing its
  // HTTP calls. Trade-off accepted: a tab's data doesn't refresh while the user stays on
  // the screen (except the team list, refreshed by hand after add/remove).
  setTab(t: Tab): void {
    this.tab.set(t);
    // replaceUrl: true, else 5 tab clicks would need 5 Backs to leave. info sends {} for a
    // clean /projects/12.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: t === 'info' ? {} : { tab: t },
      replaceUrl: true,
    });
    if (this.loadedTabs.has(t)) return;
    // Marked BEFORE the calls fire, so two fast clicks can't both start the same requests.
    this.loadedTabs.add(t);

    // Calls of one tab fired side by side: they're independent, so sequencing would only be slower.
    switch (t) {
      case 'equipe':
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        break;
      case 'charges':
        // One wide page (size 1000) then paged in the browser — an overview only, the
        // dedicated workload screen pages server-side. d.content: the Spring page envelope,
        // not a plain array.
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

  // Display total; the server's figure is what counts for the accounts.
  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  // Intl (not a hard-coded array) so the name follows the active language.
  monthLabel(m: number): string {
    if (m < 1 || m > 12) return String(m);
    // m - 1: JS Date months are 0-based; year 2000 and day 1 are just a valid-date carrier.
    return new Intl.DateTimeFormat(this.locale(), { month: 'short' }).format(new Date(2000, m - 1, 1));
  }

  // The four badge helpers below share one shape: a code-to-class map with a '??' neutral
  // fallback, kept here instead of a repeated chain of template tests.
  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }

  /** Colour of a risk level: high is red, medium is orange, anything else is green. */
  niveauBadge(n: string): string {
    return n === 'ELEVE' ? 'badge-cancelled' : n === 'MOYEN' ? 'badge-on-hold' : 'badge-active';
  }

  /** Colour of a deliverable state: waiting, in progress, delivered, accepted. */
  livrableBadge(s: string): string {
    const m: Record<string, string> = {
      EN_ATTENTE: 'badge-draft', EN_COURS: 'badge-active',
      LIVRE: 'badge-completed', VALIDE: 'badge-active'
    };
    return m[s] ?? 'badge-draft';
  }

  /** Colour of the priority of a change request, from low to critical. */
  prioriteBadge(p: string): string {
    const m: Record<string, string> = {
      FAIBLE: 'badge-active', NORMALE: 'badge-draft',
      ELEVEE: 'badge-on-hold', CRITIQUE: 'badge-cancelled'
    };
    return m[p] ?? 'badge-draft';
  }

  // ── Project manager (B7) ─────────────────────────────────────────
  // Guarded: both modals call this, and each can be opened several times.
  private ensureUsersLoaded(): void {
    if (this.allUsers().length) return;
    // Server accepts ASSIGN_CHEF_PROJET, ASSIGN_DEVELOPER or MANAGE_USERS — deliberately not
    // MANAGE_USERS alone, so assigning people doesn't require being an account administrator.
    this.svc.listAssignableUsers().subscribe(u => this.allUsers.set(u));
  }

  // Comfort filter, not a security rule: falls back to the whole list if no CHEF_PROJET role
  // exists (roles are dynamic; the server decides who may really be assigned).
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

  // ASSIGN_CHEF_PROJET, not EDIT_PROJECT server-side: choosing a manager is a management
  // decision, separate from day-to-day sheet edits.
  saveChef(): void {
    if (!this.chefUserId) { this.modalError.set(this.t.translate('project.manager.required')); return; }
    this.saving.set(true);
    this.svc.assignChef(this.projectId, this.chefUserId).subscribe({
      next: (p) => { this.project.set(p); this.showChefModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Team management (B8) ─────────────────────────────────────────
  // teamForm rebuilt whole (not cleared field by field) so nothing from the previous opening survives.
  openTeamModal(): void {
    this.ensureUsersLoaded();
    this.teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };
    this.memberSearch.set('');
    this.memberRoleFilter.set('');
    this.modalError.set('');
    this.showTeamModal.set(true);
  }

  // Scope check (ADR-021) matters here: without it the permission alone would let someone add
  // themself to any project — and team membership is what opens the rest of that data.
  // Re-fetches the team after save (not a local push): the server row carries id + full name
  // the remove button needs.
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

  // m.id (the assignment row), not the user id: unambiguous even with an old closed
  // assignment plus a new one for the same person.
  async removeMember(m: TeamAssignment): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.team.removeConfirm', { name: m.userFullName }))) return;
    this.teamSvc.remove(this.projectId, m.id).subscribe(() =>
      this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d))
    );
  }
}

// Generic (works for planned and declared rows) since it uses nothing of the component.
// Math.min clamps the page so a shrunk list (e.g. after a reload) can't point past the end.
function slicePage<T>(rows: T[], page: number, size: number): T[] {
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const p = Math.min(page, pages - 1);
  return rows.slice(p * size, p * size + size);
}
