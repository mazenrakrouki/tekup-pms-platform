import { Component, OnInit, computed, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { WorkloadService } from '../../core/services/workload.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { PlanCharge, ChargeReelle } from '../../core/models/workload.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

// "Plan de charge" (workload) screen for one project: month-by-month matrix of planned vs.
// actual days, summary figures, CSV export, and pending declarations awaiting approval.
// canPlan()/canSubmit()/canValidate() only control what's drawn; server enforces via @PreAuthorize.

// A month as two numbers (not a Date): avoids ambiguity over which day of the month to use.
interface Period { year: number; month: number; }

// role is joined once here (from TeamService, a separate call) so per-cell rendering needs no lookup.
interface MatrixResource { userId: number; name: string; role: string; }

@Component({
  selector: 'app-workload',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  styles: [`
    .wl-metric-card { padding: 1.1rem 1.15rem; }
    .wl-value-lg { font-size: 1.5rem; }
    .wl-value-md { font-size: 1.35rem; }
    .wl-metric-sub { font-size: 11px; color: var(--text-3); margin-top: .35rem; }
    /* Scroll happens inside this box (not the page) so the sticky header stays with it. */
    .occ-wrap { overflow-x: auto; }
    table.occ { width: 100%; border-collapse: separate; border-spacing: 0; }
    table.occ th, table.occ td { padding: .625rem .75rem; border-bottom: 1px solid var(--border); white-space: nowrap; }
    table.occ thead th { position: sticky; top: 0; z-index: 2; background: var(--surface-2, var(--surface));
      font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); text-transform: uppercase; }
    /* z-index order matters: corner (3) above header row (2) above frozen name column (1). */
    .occ-res-col { position: sticky; left: 0; z-index: 3; background: var(--surface-2, var(--surface)); min-width: 230px; text-align: left; }
    td.occ-res { position: sticky; left: 0; z-index: 1; background: var(--surface-1, var(--surface)); min-width: 230px; }
    tr:hover td.occ-res { background: var(--surface-2, rgba(0,0,0,.02)); }
    tr:hover td { background: var(--surface-2, rgba(0,0,0,.02)); }
    .occ-res-inner { display: flex; align-items: center; gap: .625rem; }
    .occ-avatar { width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0; color: #fff; font-size: 12px;
      font-weight: 700; display: grid; place-items: center; }
    .occ-name { font-size: 13px; font-weight: 600; color: var(--text-1); }
    .occ-role { font-size: 10px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase; color: var(--text-3); }
    /* tabular-nums keeps digits aligned column-wise. */
    .occ-cell { display: inline-flex; align-items: baseline; gap: .5rem; justify-content: center; font-variant-numeric: tabular-nums; }
    .occ-plan { font-size: 13px; color: var(--text-3); }
    .occ-real { font-size: 14px; font-weight: 700; }
    /* Three states set by realClass(): ok/warn/empty. */
    .occ-real-ok    { color: var(--c-brand); }
    .occ-real-warn  { color: var(--c-danger, #dc2626); }
    .occ-real-empty { color: var(--text-3); font-weight: 400; }
    .occ-year { font-size: 9px; font-weight: 600; color: var(--text-3); }
    /* !important needed: the row-hover rule also sets a td background and would otherwise
       erase the peak-month highlight on hover. */
    th.occ-peak, td.occ-peak-cell { background: var(--c-brand-dim) !important; }
    tfoot .occ-totals td { background: var(--surface-2, rgba(0,0,0,.03)); font-weight: 700; border-top: 2px solid var(--border);
      position: sticky; bottom: 0; }
    tfoot .occ-totals .occ-tot-label { position: sticky; left: 0; z-index: 1; background: var(--surface-2, var(--surface));
      font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-2); }
    .occ-legend { display: inline-flex; align-items: center; gap: 1rem; font-size: 11px; color: var(--text-2); }
    .occ-legend .sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; margin-right: .35rem; vertical-align: -1px; }
    .occ-progress { height: 6px; border-radius: 3px; background: var(--surface-3); overflow: hidden; margin-top: .5rem; }
    .occ-progress > div { height: 100%; background: var(--c-brand); border-radius: 3px; transition: width .5s; }
    .m-unit { font-size: .7rem; color: var(--text-3); font-weight: 600; }
    .seg-years { display: inline-flex; background: var(--surface-2, rgba(0,0,0,.04)); border: 1px solid var(--border);
      border-radius: 8px; padding: 2px; gap: 2px; }
    .seg-years .sy-btn { border: 0; background: transparent; color: var(--text-2); font-size: 12px; font-weight: 600;
      padding: .3rem .65rem; border-radius: 6px; cursor: pointer; font-variant-numeric: tabular-nums; }
    .seg-years .sy-btn:hover { color: var(--text-1); }
    .seg-years .sy-on { background: var(--surface-1, var(--surface)); color: var(--c-brand); box-shadow: 0 1px 2px rgba(0,0,0,.1); }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-calendar3 fs-13" style="color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" [title]="'workload.backToPicker' | transloco">
            <i class="bi bi-arrow-left"></i> {{ 'workload.title' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'workload.title' | transloco }}</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.workload' | transloco }}</h1>
      </div>
      <!-- Shared picker instead of a plain <select>: unusable once a company has many projects. -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-calendar3"
                            (projectSelected)="select($event)" />
      </div>

      @if (selected()) {
        <!-- Header -->
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h2 class="section-title">{{ 'workload.matrix' | transloco }}</h2>
          </div>
          <div class="d-flex gap-2">
            <button class="btn btn-outline-secondary btn-sm" (click)="exportCsv()" [disabled]="periods().length === 0">
              <i class="bi bi-download me-1"></i>{{ 'workload.export' | transloco }}
            </button>
            <!-- Two buttons, two permissions: declaring your own days (SUBMIT_WORKLOAD) is not
                 the same right as planning someone else's (VALIDATE_WORKLOAD). -->
            @if (canSubmit()) {
              <button class="btn btn-outline-secondary btn-sm" (click)="openChargeModal()">
                <i class="bi bi-clock-history me-1"></i>{{ 'workload.enterActual' | transloco }}
              </button>
            }
            @if (canPlan()) {
              <button class="btn btn-primary btn-sm" (click)="openPlanModal()">
                <i class="bi bi-plus-lg me-1"></i>{{ 'workload.planWorkload' | transloco }}
              </button>
            }
          </div>
        </div>

        <!-- Every figure below is computed from the two lists already loaded; same DI rule:
             derived on read, never stored (nothing to drift). -->
        <div class="row g-3 mb-4">
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--brand"><i class="bi bi-calendar-check"></i></div>
              </div>
              <div class="metric-label">{{ 'workload.actualOccupancy' | transloco }}</div>
              <div class="metric-value wl-value-lg">{{ totalActual() | number:'1.0-1' }} <span class="m-unit">{{ 'common.manDays' | transloco }}</span></div>
              <!-- Capped at 100: a project at 130% would otherwise overflow its own card. -->
              <div class="occ-progress"><div [style.width.%]="min(realizationPct(), 100)"></div></div>
              <div class="wl-metric-sub">{{ 'workload.ofPlannedDays' | transloco: { days: (totalPlanned() | number:'1.0-1') } }}</div>
            </div>
          </div>
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--purple"><i class="bi bi-people-fill"></i></div>
              </div>
              <div class="metric-label">{{ 'workload.activeResources' | transloco }}</div>
              <div class="metric-value wl-value-lg">{{ resources().length }} <span class="m-unit">{{ (resources().length > 1 ? 'workload.consultants' : 'workload.consultant') | transloco }}</span></div>
              <div class="wl-metric-sub">{{ 'workload.monthsPlanned' | transloco: { count: periods().length } }}</div>
            </div>
          </div>
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--teal"><i class="bi bi-speedometer2"></i></div>
                <!-- 105% tolerance: a hard flip at 100 would paint almost every healthy project red. -->
                <span class="fs-11 fw-semibold" [class.text-success]="realizationPct() <= 105" [class.text-danger]="realizationPct() > 105">
                  {{ ecart() >= 0 ? '+' : '' }}{{ ecart() | number:'1.0-1' }} {{ 'common.manDays' | transloco }}
                </span>
              </div>
              <div class="metric-label">{{ 'workload.realizationRate' | transloco }}</div>
              <div class="metric-value wl-value-lg">{{ realizationPct() | number:'1.0-0' }}<span class="m-unit">%</span></div>
              <div class="wl-metric-sub">{{ 'workload.actualVsPlanned' | transloco }}</div>
            </div>
          </div>
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--amber"><i class="bi bi-graph-up"></i></div>
              </div>
              <div class="metric-label">{{ 'workload.peakMonth' | transloco }}</div>
              <div class="metric-value wl-value-md">{{ peakMonth()?.label ?? '—' }}</div>
              <div class="wl-metric-sub">
                @if (peakMonth(); as pk) { {{ 'workload.cumulativeDays' | transloco: { days: (pk.cumul | number:'1.0-1') } }} } @else { {{ 'workload.noData' | transloco }} }
              </div>
            </div>
          </div>
        </div>

        <!-- Matrix -->
        <div class="card">
          <div class="card-header justify-content-between flex-wrap gap-2">
            <span><i class="bi bi-grid-3x3-gap me-2"></i>{{ 'workload.matrixOf' | transloco }} — {{ selected()!.name }}</span>
            <div class="d-flex align-items-center gap-3 flex-wrap">
              <!-- Only drawn with multiple years; one year at a time keeps the table readable. -->
              @if (years().length > 1) {
                <div class="seg-years">
                  @for (y of years(); track y) {
                    <button type="button" class="sy-btn" [class.sy-on]="year() === y" (click)="year.set(y)">{{ y }}</button>
                  }
                </div>
              }
              <span class="occ-legend">
                <span><span class="sw" style="background:var(--surface-3)"></span>{{ 'workload.planned' | transloco }}</span>
                <span><span class="sw" style="background:var(--c-brand)"></span>{{ 'workload.actual' | transloco }}</span>
              </span>
            </div>
          </div>

          @if (periods().length === 0) {
            <div class="empty-state">
              <div class="es-icon"><i class="bi bi-calendar3"></i></div>
              <div class="es-title">{{ 'workload.empty' | transloco }}</div>
              @if (canPlan()) {
                <button class="btn btn-primary btn-sm mt-3" (click)="openPlanModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'workload.planWorkload' | transloco }}</button>
              }
            </div>
          } @else {
            <div class="occ-wrap">
              <table class="occ">
                <thead>
                  <tr>
                    <th class="occ-res-col">{{ 'workload.colResourceRole' | transloco }}</th>
                    <!-- Numeric track key (not the object, which periods() rebuilds each time),
                         so columns keep their scroll position across refreshes. -->
                    @for (per of periods(); track per.year * 100 + per.month) {
                      <th class="text-center" [class.occ-peak]="isPeak(per)">
                        {{ monthName(per.month) }}
                        <div class="occ-year">{{ per.year }}</div>
                      </th>
                    }
                  </tr>
                </thead>
                <tbody>
                  @for (r of resources(); track r.userId) {
                    <tr>
                      <td class="occ-res">
                        <div class="occ-res-inner">
                          <div class="occ-avatar" [style.background]="avatarColor(r.name)">{{ initials(r.name) }}</div>
                          <div>
                            <div class="occ-name">{{ r.name }}</div>
                            <div class="occ-role">{{ r.role || '—' }}</div>
                          </div>
                        </div>
                      </td>
                      @for (per of periods(); track per.year * 100 + per.month) {
                        <td class="text-center" [class.occ-peak-cell]="isPeak(per)">
                          <!-- Zero replaced by a small mark: a grid full of "0" is harder to read. -->
                          <span class="occ-cell">
                            <span class="occ-plan">{{ planOf(r.userId, per) || '·' }}</span>
                            <span class="occ-real" [class]="realClass(r.userId, per)">{{ actualOf(r.userId, per) || '—' }}</span>
                          </span>
                        </td>
                      }
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr class="occ-totals">
                    <td class="occ-tot-label">{{ 'workload.monthlyTotals' | transloco }}</td>
                    @for (per of periods(); track per.year * 100 + per.month) {
                      <td class="text-center" [class.occ-peak-cell]="isPeak(per)">
                        <span class="occ-cell">
                          <span class="occ-plan">{{ monthTotalPlanned(per) | number:'1.0-1' }}</span>
                          <span class="occ-real occ-real-ok">{{ monthTotalActual(per) | number:'1.0-1' }}</span>
                        </span>
                      </td>
                    }
                  </tr>
                </tfoot>
              </table>
            </div>
          }
        </div>

        <!-- KPI engine only reads APPROVED days: until approved here, consumed cost, EAC and
             margin stay at zero. Second condition avoids showing an empty card when nothing's pending. -->
        @if (canValidate() && pendingCharges().length > 0) {
          <div class="wl-card mt-3">
            <div class="wl-card-head">
              <span><i class="bi bi-hourglass-split me-2"></i>{{ 'workload.pendingTitle' | transloco }}</span>
              <span class="badge bg-warning text-dark">{{ pendingCharges().length }}</span>
            </div>
            <div class="p-3">
              <p class="text-caption mb-3">{{ 'workload.pendingHint' | transloco }}</p>
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <thead>
                    <tr>
                      <th>{{ 'workload.resource' | transloco }}</th>
                      <th>{{ 'workload.period' | transloco }}</th>
                      <th class="text-end">{{ 'workload.actualDays' | transloco }}</th>
                      <th class="text-end">{{ 'common.actions' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (c of pendingCharges(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ monthLabel(c.month) }} {{ c.year }}</td>
                        <td class="text-end">{{ c.actualDays | number:'1.0-2' }}</td>
                        <td class="text-end">
                          <!-- Disabled only for the row being saved, so the spinner sits on the
                               exact button clicked and a double click can't resend it. -->
                          <button class="btn btn-sm btn-success"
                                  (click)="validateCharge(c)"
                                  [disabled]="validatingId() === c.id">
                            @if (validatingId() === c.id) {
                              <span class="spinner-border spinner-border-sm me-1"></span>
                            } @else {
                              <i class="bi bi-check2 me-1"></i>
                            }
                            {{ 'workload.validate' | transloco }}
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        }
      }
    </div>

    <!-- Hand-written, not Bootstrap JS: open/closed is one signal Angular owns. -->
    @if (showChargeModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- stopPropagation: a click inside the form must not close the window. -->
      <div class="modal d-block" tabindex="-1" (click)="showChargeModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'workload.enterActualTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showChargeModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'workload.resource' | transloco }} <span class="text-danger">*</span></label>
                <!-- A declare-only user (SUBMIT but not VALIDATE) sees a locked field with his
                     own name: he can only declare for himself. -->
                @if (isDevOnly()) {
                  <input type="text" class="form-control" [value]="currentUserFullName" disabled>
                } @else {
                  <select class="form-select" [(ngModel)]="chargeForm.userId">
                    <option [value]="0" disabled>{{ 'workload.selectResource' | transloco }}</option>
                    @for (m of teamMembers(); track m.userId) {
                      <option [value]="m.userId">{{ m.userFullName }}</option>
                    }
                  </select>
                }
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'workload.year' | transloco }} <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="chargeForm.year" min="2000" max="2100">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'workload.month' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="chargeForm.month">
                    @for (m of months; track m.v) {
                      <option [value]="m.v">{{ m.l }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="mb-3 mt-3">
                <label class="form-label">{{ 'workload.daysWorked' | transloco }} <span class="text-danger">*</span></label>
                <!-- Browser bounds only; real validation is server-side. -->
                <input type="number" class="form-control" [(ngModel)]="chargeForm.actualDays" min="0" max="31" step="0.5">
              </div>
              @if (chargeError()) {
                <div class="alert alert-danger py-2">{{ chargeError() }}</div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChargeModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="submitCharge()" [disabled]="chargeSaving()">
                @if (chargeSaving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Same shape, but writes a PlanCharge (forecast) instead of a ChargeReelle (actual):
         different people, different moments, must never overwrite each other. -->
    @if (showPlanModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showPlanModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'workload.planWorkload' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showPlanModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'workload.resource' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="planForm.userId">
                  <option [value]="0" disabled>{{ 'workload.selectResource' | transloco }}</option>
                  @for (m of teamMembers(); track m.userId) {
                    <option [value]="m.userId">{{ m.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'workload.year' | transloco }} <span class="text-danger">*</span></label>
                  <input type="number" class="form-control" [(ngModel)]="planForm.year" min="2000" max="2100">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'workload.month' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="planForm.month">
                    @for (m of months; track m.v) {
                      <option [value]="m.v">{{ m.l }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="mb-3 mt-3">
                <label class="form-label">{{ 'workload.daysPlanned' | transloco }} <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="planForm.plannedDays" min="0" max="31" step="0.5">
              </div>
              @if (planError()) { <div class="alert alert-danger py-2">{{ planError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showPlanModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="submitPlan()" [disabled]="planSaving()">
                @if (planSaving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
// Two raw lists (fullPlan, fullCharges) as signals; everything else is a computed() built
// from them, so one reload refreshes the matrix, figures, peak month and pending list at once
// and they can never disagree. Nothing derivable is stored.
export class WorkloadComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly tr = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  // Permission codes, never role names: roles are built in the admin screens and can change.
  // Display only; server enforces via @PreAuthorize plus ProjectScopeInterceptor (ADR-021).
  canPlan = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  canSubmit = () => this.auth.hasPermission('SUBMIT_WORKLOAD');
  // Same permission as canPlan, named for what it gates here: VALIDATE_WORKLOAD covers both.
  canValidate = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  // Declare-only user (typically a developer): locks the declare form's resource field.
  isDevOnly = () => this.canSubmit() && !this.canPlan() && this.auth.currentUserId !== null;

  // ?? '' covers the brief null window while the session is restored.
  get currentUserFullName(): string { return this.auth.context()?.fullName ?? ''; }

  // ── The state of the screen ────────────────────────────────────
  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  // Raw API answers for ALL years; kept whole so switching year is instant, no extra request.
  fullPlan = signal<PlanCharge[]>([]);
  fullCharges = signal<ChargeReelle[]>([]);
  // From a separate endpoint: a person can appear in workload rows without still being on the team.
  teamMembers = signal<{ userId: number; userFullName: string; role: string }[]>([]);
  // null = "not chosen yet" (distinct from "no year"); initYear() uses that to pick a default.
  year = signal<number | null>(null);

  // Both lists read, not just plan: a declared month with no plan must still be reachable.
  readonly years = computed(() => {
    const s = new Set<number>();
    for (const p of this.fullPlan()) s.add(p.year);
    for (const c of this.fullCharges()) s.add(c.year);
    return [...s].sort((a, b) => a - b);
  });

  // Every table/total reads these (not fullPlan/fullCharges), so the year filter lives in
  // one place. y == null means "no year chosen yet": shows everything rather than nothing.
  private readonly planInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullPlan() : this.fullPlan().filter(p => p.year === y);
  });
  private readonly chargesInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullCharges() : this.fullCharges().filter(c => c.year === y);
  });

  // !c.validatedAt: the API omits that date while unapproved. Sorted oldest-month-first so
  // the manager works down a stable order.
  readonly pendingCharges = computed(() =>
    this.chargesInScope()
        .filter(c => !c.validatedAt)
        .sort((a, b) => (a.year - b.year) || (a.month - b.month)
                     || a.userFullName.localeCompare(b.userFullName)));

  validatingId = signal<number | null>(null);

  // Separate signals per modal: a shared "saving" flag would spinner both windows at once.
  showChargeModal = signal(false);
  chargeSaving = signal(false);
  chargeError = signal('');
  showPlanModal = signal(false);
  planSaving = signal(false);
  planError = signal('');

  // getMonth() + 1: JS months are 0-based, the API expects 1..12.
  chargeForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
  planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };

  // Cached by language (a getter re-runs every change-detection pass): avoids rebuilding the
  // Intl formatter and 12 objects constantly. Uses the browser's own month names, no i18n file needed.
  private monthsCache: { lang: string; list: { v: number; l: string }[] } | null = null;
  get months(): { v: number; l: string }[] {
    const lang = this.tr.getActiveLang();
    if (!this.monthsCache || this.monthsCache.lang !== lang) {
      const fmt = new Intl.DateTimeFormat(lang, { month: 'long' });
      this.monthsCache = {
        lang,
        // Date(2000, i, 1) is just a carrier; i is JS month (0-11), v is API month (1-12).
        list: Array.from({ length: 12 }, (_, i) => ({
          v: i + 1,
          l: fmt.format(new Date(2000, i, 1)),
        })),
      };
    }
    return this.monthsCache.list;
  }

  // Fixed (not random) palette so the same person always gets the same avatar color.
  private readonly avatarPalette = ['#2563eb', '#7c3aed', '#0891b2', '#059669', '#d97706', '#dc2626', '#db2777', '#4f46e5'];

  // ── Matrix model ───────────────────────────────────────────────
  // Built from workload rows, not the team list: someone can leave the team while their
  // logged days stay in the project's history. Role added from the team, '' if missing.
  readonly resources = computed<MatrixResource[]>(() => {
    const roles = new Map(this.teamMembers().map(m => [m.userId, m.role]));
    const names = new Map<number, string>();
    for (const p of this.planInScope()) names.set(p.userId, p.userFullName);
    for (const c of this.chargesInScope()) names.set(c.userId, c.userFullName);
    return [...names.entries()]
      .map(([userId, name]) => ({ userId, name, role: roles.get(userId) ?? '' }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  // Only months with data (not all 12), else a March-to-June project reads across 8 empty
  // columns. Set of "year-month" strings, since a Set compares objects by identity.
  readonly periods = computed<Period[]>(() => {
    const set = new Set<string>();
    for (const p of this.planInScope()) set.add(`${p.year}-${p.month}`);
    for (const c of this.chargesInScope()) set.add(`${c.year}-${c.month}`);
    return [...set]
      .map(s => { const [y, m] = s.split('-').map(Number); return { year: y, month: m }; })
      .sort((a, b) => a.year - b.year || a.month - b.month);
  });

  // Map built once ("userId:year:month" -> {planned, actual}) instead of re-scanning both
  // lists per cell. Values are ADDED, not replaced: two rows for the same person/month
  // (e.g. two declarations) must both count, not overwrite each other.
  readonly cells = computed(() => {
    const m = new Map<string, { planned: number; actual: number }>();
    const key = (u: number, y: number, mo: number) => `${u}:${y}:${mo}`;
    for (const p of this.planInScope()) {
      const k = key(p.userId, p.year, p.month);
      const c = m.get(k) ?? { planned: 0, actual: 0 }; c.planned += p.plannedDays; m.set(k, c);
    }
    for (const ch of this.chargesInScope()) {
      const k = key(ch.userId, ch.year, ch.month);
      const c = m.get(k) ?? { planned: 0, actual: 0 }; c.actual += ch.actualDays; m.set(k, c);
    }
    return m;
  });

  // Built from cells(), not the raw lists, so the totals row can never disagree with the
  // columns above it. Seeded at zero for every period first, so an empty month still shows 0.
  readonly monthTotals = computed(() => {
    const map = new Map<string, { planned: number; actual: number }>();
    for (const per of this.periods()) map.set(`${per.year}-${per.month}`, { planned: 0, actual: 0 });
    for (const [k, v] of this.cells()) {
      const [, y, mo] = k.split(':');
      const t = map.get(`${y}-${mo}`);
      if (t) { t.planned += v.planned; t.actual += v.actual; }
    }
    return map;
  });

  // ── The four figures of the cards ──────────────────────────────
  // totalActual: declared this year, approved or not (server-side cost engine counts only approved).
  // realizationPct guarded by totalPlanned() > 0 to avoid a 0/0 NaN.
  readonly totalActual = computed(() => this.chargesInScope().reduce((s, c) => s + c.actualDays, 0));
  readonly totalPlanned = computed(() => this.planInScope().reduce((s, p) => s + p.plannedDays, 0));
  readonly ecart = computed(() => this.totalActual() - this.totalPlanned());
  readonly realizationPct = computed(() => this.totalPlanned() > 0 ? (this.totalActual() / this.totalPlanned()) * 100 : 0);

  // "Busiest" = max(planned, actual), not their sum. bestVal starts at -1 so a 0-day month
  // still counts as seen; bestVal <= 0 at the end rejects an all-empty year (shows a dash).
  readonly peakMonth = computed(() => {
    let bestKey: string | null = null; let bestVal = -1;
    for (const [k, t] of this.monthTotals()) {
      const v = Math.max(t.planned, t.actual);
      if (v > bestVal) { bestVal = v; bestKey = k; }
    }
    if (!bestKey || bestVal <= 0) return null;
    const [y, mo] = bestKey.split('-').map(Number);
    return { key: bestKey, label: `${this.monthShort(mo)} ${y}`, cumul: bestVal };
  });

  // URL (?p=<id>) is the source of truth so the page survives bookmark/refresh/Back. The
  // subscription is nested inside the projects call: matching the id needs the list to exist first.
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        // String(p.id): a URL param is always text.
        const project = list.find(p => String(p.id) === pid);
        // Skips a duplicate fetch when the URL changes but the project doesn't.
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.loadAll();
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName, role: m.roleInTeam })))
          );
        }
      });
    });
  }

  // replaceUrl: false adds a history entry so Back returns to the picker (not out of the screen).
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.loadAll();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName, role: m.roleInTeam })))
    );
  }

  // Only navigates (doesn't null 'selected' directly): the ngOnInit subscription is the
  // single path that clears state, so screen and URL can't disagree.
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  // year.set(null) first: the previous project's year has no meaning here. size 500 (not
  // paged): totals must sum the WHOLE project. Both calls call initYear(); whichever lands
  // first sets it, the other finds it already set.
  private loadAll(): void {
    const p = this.selected();
    if (!p) return;
    this.year.set(null);
    this.workloadSvc.listPlanCharges(p.id, 0, 500).subscribe(res => { this.fullPlan.set(res.content); this.initYear(); });
    this.workloadSvc.listChargesReelles(p.id, 0, 500).subscribe(res => { this.fullCharges.set(res.content); this.initYear(); });
  }

  // Early return protects a click already made: once the user picked 2025, a later-landing
  // request must not throw them back to 2026.
  private initYear(): void {
    if (this.year() !== null) return;
    const ys = this.years();
    if (!ys.length) return;
    const now = new Date().getFullYear();
    this.year.set(ys.includes(now) ? now : ys[0]);
  }

  // ── Cell accessors ─────────────────────────────────────────────
  // Real object fallback (not undefined) so callers can read .planned with no null test.
  private cellOf(u: number, per: Period) {
    return this.cells().get(`${u}:${per.year}:${per.month}`) ?? { planned: 0, actual: 0 };
  }
  planOf(u: number, per: Period): number { return this.cellOf(u, per).planned; }
  actualOf(u: number, per: Period): number { return this.cellOf(u, per).actual; }
  // Two tests, not one: an absolute 5-day gap OR a quarter of the plan — a percentage alone
  // would over-flag small plans, an absolute alone would miss drift on large ones.
  realClass(u: number, per: Period): string {
    const c = this.cellOf(u, per);
    if (c.actual === 0) return 'occ-real-empty';
    const v = Math.abs(c.actual - c.planned);
    const warn = c.planned > 0 ? (v >= 5 || v / c.planned > 0.25) : c.actual >= 5;
    return warn ? 'occ-real-warn' : 'occ-real-ok';
  }
  monthTotalPlanned(per: Period): number { return this.monthTotals().get(`${per.year}-${per.month}`)?.planned ?? 0; }
  monthTotalActual(per: Period): number { return this.monthTotals().get(`${per.year}-${per.month}`)?.actual ?? 0; }
  isPeak(per: Period): boolean { return this.peakMonth()?.key === `${per.year}-${per.month}`; }

  // ── Presentation helpers ───────────────────────────────────────
  // filter(Boolean) drops empty pieces from a double space; '?' covers an empty name.
  initials(name: string): string {
    return name.split(' ').filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase() || '?';
  }
  // Small hash into the fixed palette, so a person keeps the same avatar color while scrolling.
  // >>> 0 keeps it a positive 32-bit index (else long names could overflow negative).
  avatarColor(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return this.avatarPalette[h % this.avatarPalette.length];
  }
  // m - 1: API month is 1-12, JS Date month is 0-11.
  monthName(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'long' })
      .format(new Date(2000, m - 1, 1)).toUpperCase();
  }
  // Short form ("juil.") for the peak card and CSV headers, where a full name wouldn't fit.
  monthShort(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'short' })
      .format(new Date(2000, m - 1, 1));
  }
  // Templates can't reach the global Math object.
  min(a: number, b: number): number { return Math.min(a, b); }

  // ── Export ─────────────────────────────────────────────────────
  // Built in the browser from what's already loaded, so it always matches what's on screen.
  // Semicolon separator + BOM are the two habits a French/European Excel expects.
  exportCsv(): void {
    const per = this.periods();
    const plan = this.tr.translate('workload.csvPlan');
    const real = this.tr.translate('workload.csvActual');
    // flatMap: two columns (planned, actual) per month, not a nested array per month.
    const header = [this.tr.translate('workload.resource'), this.tr.translate('workload.csvRole'), ...per.flatMap(p => [`${this.monthShort(p.month)} ${p.year} (${plan})`, `${this.monthShort(p.month)} ${p.year} (${real})`])];
    const rows = this.resources().map(r => [
      r.name, r.role || '',
      ...per.flatMap(p => [String(this.planOf(r.userId, p)), String(this.actualOf(r.userId, p))]),
    ]);
    const totals = [this.tr.translate('workload.csvMonthlyTotals'), '', ...per.flatMap(p => [String(this.monthTotalPlanned(p)), String(this.monthTotalActual(p))])];
    // Quoted fields with doubled internal quotes, so a name like André "Dédé" Ben Ali can't
    // break the column layout.
    const csv = [header, ...rows, totals]
      .map(line => line.map(v => `"${String(v).replace(/"/g, '""')}"`).join(';'))
      .join('\n');
    // BOM prefix: tells Excel the file is UTF-8, else accented letters render garbled.
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `matrice-occupation-${this.selected()?.code ?? 'projet'}.csv`;
    a.click();
    // Frees the temporary URL, else repeated exports leak memory.
    URL.revokeObjectURL(url);
  }

  // ── Modals ─────────────────────────────────────────────────────
  // Form REPLACED (not edited) so a cancelled attempt doesn't linger; declare-only users get
  // their own id pre-filled since the field is locked.
  openChargeModal(): void {
    const uid = this.isDevOnly() ? (this.auth.currentUserId ?? 0) : 0;
    this.chargeForm = { userId: uid, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
    this.chargeError.set('');
    this.showChargeModal.set(true);
  }

  // Row arrives NOT approved: shows in the pending list, costs nothing until VALIDATE_WORKLOAD
  // accepts it. !actualDays also rejects 0 — a zero-day line is an empty form, not a declaration.
  submitCharge(): void {
    if (!this.chargeForm.userId || !this.chargeForm.actualDays) {
      this.chargeError.set(this.tr.translate('workload.errResourceDays'));
      return;
    }
    this.chargeSaving.set(true);
    this.chargeError.set('');
    // selected()!: safe, this modal only opens inside @if (selected()).
    // Reloads rather than pushing locally: server may adjust/merge the row.
    this.workloadSvc.submitCharge(this.selected()!.id, this.chargeForm).subscribe({
      next: () => { this.loadAll(); this.showChargeModal.set(false); this.chargeSaving.set(false); this.toast.success(this.tr.translate('workload.okActualSaved')); },
      // In-modal (not a toast) so the user keeps what they typed. Spring's ProblemDetail puts
      // its text in 'detail', not 'message', so this usually falls back to the fixed sentence.
      error: (e) => { this.chargeError.set(e.error?.message ?? 'Erreur lors de la soumission.'); this.chargeSaving.set(false); }
    });
  }

  monthLabel(m: number): string {
    return this.months.find(x => x.v === m)?.l ?? String(m);
  }

  // Approval is what makes the money move: KpiService only counts validated charges.
  validateCharge(c: ChargeReelle): void {
    // One at a time: two quick clicks could otherwise race their reloads.
    if (this.validatingId() !== null) return;
    this.validatingId.set(c.id);
    this.workloadSvc.validateCharge(this.selected()!.id, c.id).subscribe({
      next: () => {
        this.validatingId.set(null);
        this.loadAll();
        this.toast.success(this.tr.translate('workload.okValidated', {
          name: c.userFullName, period: `${this.monthLabel(c.month)} ${c.year}`
        }));
      },
      error: (e: { error?: { detail?: string; message?: string } }) => {
        this.validatingId.set(null);
        // Spring's ProblemDetail text is in 'detail'; 'message' is normally undefined.
        this.toast.error(e.error?.detail ?? e.error?.message
                         ?? this.tr.translate('workload.errValidate'));
      }
    });
  }

  // No pre-filled resource: planning is always FOR someone else, must be chosen deliberately.
  openPlanModal(): void {
    this.planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };
    this.planError.set('');
    this.showPlanModal.set(true);
  }

  // A plan needs no approval: it's a PM decision, never a cost by itself.
  submitPlan(): void {
    if (!this.planForm.userId || !this.planForm.plannedDays) {
      this.planError.set(this.tr.translate('workload.errResourceDays'));
      return;
    }
    this.planSaving.set(true);
    this.planError.set('');
    this.workloadSvc.createPlanCharge(this.selected()!.id, this.planForm).subscribe({
      next: () => { this.loadAll(); this.showPlanModal.set(false); this.planSaving.set(false); this.toast.success(this.tr.translate('workload.okPlanSaved')); },
      error: (e) => { this.planError.set(e.error?.message ?? 'Erreur lors de la planification.'); this.planSaving.set(false); }
    });
  }
}
