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

interface Period { year: number; month: number; }
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
    .occ-wrap { overflow-x: auto; }
    table.occ { width: 100%; border-collapse: separate; border-spacing: 0; }
    table.occ th, table.occ td { padding: .625rem .75rem; border-bottom: 1px solid var(--border); white-space: nowrap; }
    table.occ thead th { position: sticky; top: 0; z-index: 2; background: var(--surface-2, var(--surface));
      font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); text-transform: uppercase; }
    .occ-res-col { position: sticky; left: 0; z-index: 3; background: var(--surface-2, var(--surface)); min-width: 230px; text-align: left; }
    td.occ-res { position: sticky; left: 0; z-index: 1; background: var(--surface-1, var(--surface)); min-width: 230px; }
    tr:hover td.occ-res { background: var(--surface-2, rgba(0,0,0,.02)); }
    tr:hover td { background: var(--surface-2, rgba(0,0,0,.02)); }
    .occ-res-inner { display: flex; align-items: center; gap: .625rem; }
    .occ-avatar { width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0; color: #fff; font-size: 12px;
      font-weight: 700; display: grid; place-items: center; }
    .occ-name { font-size: 13px; font-weight: 600; color: var(--text-1); }
    .occ-role { font-size: 10px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase; color: var(--text-3); }
    .occ-cell { display: inline-flex; align-items: baseline; gap: .5rem; justify-content: center; font-variant-numeric: tabular-nums; }
    .occ-plan { font-size: 13px; color: var(--text-3); }
    .occ-real { font-size: 14px; font-weight: 700; }
    .occ-real-ok    { color: var(--c-brand); }
    .occ-real-warn  { color: var(--c-danger, #dc2626); }
    .occ-real-empty { color: var(--text-3); font-weight: 400; }
    .occ-year { font-size: 9px; font-weight: 600; color: var(--text-3); }
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
      <!-- Project selector -->
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

        <!-- Metric cards -->
        <div class="row g-3 mb-4">
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--brand"><i class="bi bi-calendar-check"></i></div>
              </div>
              <div class="metric-label">{{ 'workload.actualOccupancy' | transloco }}</div>
              <div class="metric-value wl-value-lg">{{ totalActual() | number:'1.0-1' }} <span class="m-unit">{{ 'common.manDays' | transloco }}</span></div>
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
      }
    </div>

    <!-- Modal saisie charge réelle -->
    @if (showChargeModal()) {
      <div class="modal-backdrop fade show"></div>
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

    <!-- Modal Plan de charge -->
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
export class WorkloadComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly tr = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  canPlan = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  canSubmit = () => this.auth.hasPermission('SUBMIT_WORKLOAD');
  isDevOnly = () => this.canSubmit() && !this.canPlan() && this.auth.currentUserId !== null;
  get currentUserFullName(): string { return this.auth.context()?.fullName ?? ''; }

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  fullPlan = signal<PlanCharge[]>([]);
  fullCharges = signal<ChargeReelle[]>([]);
  teamMembers = signal<{ userId: number; userFullName: string; role: string }[]>([]);
  year = signal<number | null>(null);

  /** Distinct years present across plan + actual, sorted. */
  readonly years = computed(() => {
    const s = new Set<number>();
    for (const p of this.fullPlan()) s.add(p.year);
    for (const c of this.fullCharges()) s.add(c.year);
    return [...s].sort((a, b) => a - b);
  });

  private readonly planInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullPlan() : this.fullPlan().filter(p => p.year === y);
  });
  private readonly chargesInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullCharges() : this.fullCharges().filter(c => c.year === y);
  });

  showChargeModal = signal(false);
  chargeSaving = signal(false);
  chargeError = signal('');
  showPlanModal = signal(false);
  planSaving = signal(false);
  planError = signal('');

  chargeForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
  planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };

  /** Month options for the pickers, named in the active language. */
  private monthsCache: { lang: string; list: { v: number; l: string }[] } | null = null;
  get months(): { v: number; l: string }[] {
    const lang = this.tr.getActiveLang();
    if (!this.monthsCache || this.monthsCache.lang !== lang) {
      const fmt = new Intl.DateTimeFormat(lang, { month: 'long' });
      this.monthsCache = {
        lang,
        list: Array.from({ length: 12 }, (_, i) => ({
          v: i + 1,
          l: fmt.format(new Date(2000, i, 1)),
        })),
      };
    }
    return this.monthsCache.list;
  }

  private readonly avatarPalette = ['#2563eb', '#7c3aed', '#0891b2', '#059669', '#d97706', '#dc2626', '#db2777', '#4f46e5'];

  // ── Matrix model ───────────────────────────────────────────────
  readonly resources = computed<MatrixResource[]>(() => {
    const roles = new Map(this.teamMembers().map(m => [m.userId, m.role]));
    const names = new Map<number, string>();
    for (const p of this.planInScope()) names.set(p.userId, p.userFullName);
    for (const c of this.chargesInScope()) names.set(c.userId, c.userFullName);
    return [...names.entries()]
      .map(([userId, name]) => ({ userId, name, role: roles.get(userId) ?? '' }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  readonly periods = computed<Period[]>(() => {
    const set = new Set<string>();
    for (const p of this.planInScope()) set.add(`${p.year}-${p.month}`);
    for (const c of this.chargesInScope()) set.add(`${c.year}-${c.month}`);
    return [...set]
      .map(s => { const [y, m] = s.split('-').map(Number); return { year: y, month: m }; })
      .sort((a, b) => a.year - b.year || a.month - b.month);
  });

  /** userId:year:month → { planned, actual } */
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

  readonly totalActual = computed(() => this.chargesInScope().reduce((s, c) => s + c.actualDays, 0));
  readonly totalPlanned = computed(() => this.planInScope().reduce((s, p) => s + p.plannedDays, 0));
  readonly ecart = computed(() => this.totalActual() - this.totalPlanned());
  readonly realizationPct = computed(() => this.totalPlanned() > 0 ? (this.totalActual() / this.totalPlanned()) * 100 : 0);

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

  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        const project = list.find(p => String(p.id) === pid);
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

  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.loadAll();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName, role: m.roleInTeam })))
    );
  }

  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  private loadAll(): void {
    const p = this.selected();
    if (!p) return;
    this.year.set(null);
    this.workloadSvc.listPlanCharges(p.id, 0, 500).subscribe(res => { this.fullPlan.set(res.content); this.initYear(); });
    this.workloadSvc.listChargesReelles(p.id, 0, 500).subscribe(res => { this.fullCharges.set(res.content); this.initYear(); });
  }

  /** Default the scope to the current year if present, else the first year with data. */
  private initYear(): void {
    if (this.year() !== null) return;
    const ys = this.years();
    if (!ys.length) return;
    const now = new Date().getFullYear();
    this.year.set(ys.includes(now) ? now : ys[0]);
  }

  // ── Cell accessors ─────────────────────────────────────────────
  private cellOf(u: number, per: Period) {
    return this.cells().get(`${u}:${per.year}:${per.month}`) ?? { planned: 0, actual: 0 };
  }
  planOf(u: number, per: Period): number { return this.cellOf(u, per).planned; }
  actualOf(u: number, per: Period): number { return this.cellOf(u, per).actual; }
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
  initials(name: string): string {
    return name.split(' ').filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase() || '?';
  }
  avatarColor(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return this.avatarPalette[h % this.avatarPalette.length];
  }
  monthName(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'long' })
      .format(new Date(2000, m - 1, 1)).toUpperCase();
  }
  monthShort(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'short' })
      .format(new Date(2000, m - 1, 1));
  }
  min(a: number, b: number): number { return Math.min(a, b); }

  // ── Export ─────────────────────────────────────────────────────
  exportCsv(): void {
    const per = this.periods();
    const plan = this.tr.translate('workload.csvPlan');
    const real = this.tr.translate('workload.csvActual');
    const header = [this.tr.translate('workload.resource'), this.tr.translate('workload.csvRole'), ...per.flatMap(p => [`${this.monthShort(p.month)} ${p.year} (${plan})`, `${this.monthShort(p.month)} ${p.year} (${real})`])];
    const rows = this.resources().map(r => [
      r.name, r.role || '',
      ...per.flatMap(p => [String(this.planOf(r.userId, p)), String(this.actualOf(r.userId, p))]),
    ]);
    const totals = [this.tr.translate('workload.csvMonthlyTotals'), '', ...per.flatMap(p => [String(this.monthTotalPlanned(p)), String(this.monthTotalActual(p))])];
    const csv = [header, ...rows, totals]
      .map(line => line.map(v => `"${String(v).replace(/"/g, '""')}"`).join(';'))
      .join('\n');
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `matrice-occupation-${this.selected()?.code ?? 'projet'}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  // ── Modals ─────────────────────────────────────────────────────
  openChargeModal(): void {
    const uid = this.isDevOnly() ? (this.auth.currentUserId ?? 0) : 0;
    this.chargeForm = { userId: uid, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
    this.chargeError.set('');
    this.showChargeModal.set(true);
  }

  submitCharge(): void {
    if (!this.chargeForm.userId || !this.chargeForm.actualDays) {
      this.chargeError.set(this.tr.translate('workload.errResourceDays'));
      return;
    }
    this.chargeSaving.set(true);
    this.chargeError.set('');
    this.workloadSvc.submitCharge(this.selected()!.id, this.chargeForm).subscribe({
      next: () => { this.loadAll(); this.showChargeModal.set(false); this.chargeSaving.set(false); this.toast.success(this.tr.translate('workload.okActualSaved')); },
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
