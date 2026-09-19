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

/*
 * FILE: workload.component.ts
 *
 * WHAT THIS FILE IS
 * The whole "Plan de charge" (workload) screen of one project, in one standalone Angular
 * component: the month-by-month matrix "who is planned how many days, and how many days did
 * he really work", the four figures above it, the CSV export, the two small forms (plan a
 * month / declare a month) and the list of declared months still waiting for approval.
 *
 * WHERE IT SITS IN THE FLOW
 *   the router opens this component (with ?p=<projectId> in the URL)
 *     -> ProjectService.listAll() fills the project picker
 *     -> WorkloadService.listPlanCharges() / listChargesReelles() call
 *        GET /api/projects/{id}/plan-charges and /charges-reelles
 *     -> the two answers are kept raw in fullPlan / fullCharges, and every table, total and
 *        colour on the page is COMPUTED from them (signals below)
 *     -> writing goes back through WorkloadService.submitCharge / createPlanCharge /
 *        validateCharge.
 *   On the server those service methods carry @PreAuthorize('SUBMIT_WORKLOAD') and
 *   @PreAuthorize('VALIDATE_WORKLOAD'), and because the URLs match /api/projects/{id}/**,
 *   ProjectScopeInterceptor also checks that this user belongs to THAT project (ADR-021).
 *
 * WHY IT EXISTS
 * Without it there is no way to enter or read the days worked, and the day is the unit the
 * whole cost of a project is built on: the KPI engine turns validated days into consumed
 * cost. Delete this screen and the project figures stay frozen at zero for ever.
 *
 * WHAT THE BUTTONS HIDE ARE ONLY COMFORT
 * canPlan() / canSubmit() / canValidate() below only decide whether a button is drawn. They
 * are NOT the security. A user who calls the API by hand is still refused by the server
 * checks above. The rule of this project is that authorization is dynamic and
 * permission-based: nowhere does this file test a role NAME, only permission names.
 */

/** One column of the matrix: a month, written as two plain numbers (2026 and 7 = July 2026).
 *  Why not a Date: a workload line is about a whole month; with a Date each screen would have
 *  to agree on which day of the month to use, and 2026-07-01 and 2026-07-31 would look like
 *  two different columns for the same month. month runs 1..12, like the Java value. */
interface Period { year: number; month: number; }

/** One row of the matrix: a person, with the name to print and the role to print under it.
 *  role is kept here (and not looked up again while rendering) because the role comes from a
 *  different request (TeamService) than the workload rows; joining once here keeps the table
 *  drawing from doing a lookup per cell. */
interface MatrixResource { userId: number; name: string; role: string; }

// @Component turns this class into a screen Angular can draw.
// standalone: true means it declares its own dependencies in `imports` below instead of
// belonging to an NgModule. Why: the rest of the app is standalone too, so the router can
// lazy-load this file on its own. Without it Angular would refuse to render the component
// ("not part of any NgModule").
// imports lists exactly what the template uses: CommonModule for the `number` pipe,
// FormsModule for [(ngModel)] in the two modals, ProjectPickerComponent for the
// <app-project-picker> tag, TranslocoModule for the `transloco` pipe. Forgetting one of them
// does not crash at runtime, it silently prints raw text (e.g. the translation key itself).
@Component({
  selector: 'app-workload',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  // styles: these rules are scoped to this component only, so class names as generic as
  // `.occ-name` cannot leak onto another screen.
  // Inside this block ONLY /* */ comments are legal.
  styles: [`
    /* The matrix can be wider than the screen (one column per month), so the sticky
       first column and sticky header below are what keep the reader oriented while he
       scrolls sideways: without them he sees numbers with no idea whose or which month. */
    .wl-metric-card { padding: 1.1rem 1.15rem; }
    .wl-value-lg { font-size: 1.5rem; }
    .wl-value-md { font-size: 1.35rem; }
    .wl-metric-sub { font-size: 11px; color: var(--text-3); margin-top: .35rem; }
    /* The scroll happens inside this box, not on the page. Why: the sticky header below
       sticks to its scrolling box; if the page itself scrolled, the month row would slide
       away and the totals row at the bottom would never be visible at the same time. */
    .occ-wrap { overflow-x: auto; }
    table.occ { width: 100%; border-collapse: separate; border-spacing: 0; }
    table.occ th, table.occ td { padding: .625rem .75rem; border-bottom: 1px solid var(--border); white-space: nowrap; }
    table.occ thead th { position: sticky; top: 0; z-index: 2; background: var(--surface-2, var(--surface));
      font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); text-transform: uppercase; }
    /* The three z-index values are deliberate and must stay in this order: the top-left
       corner cell (3) above the header row (2) above the frozen name column (1). Give them
       all the same value and the person's name would be painted over the month names when
       the table is scrolled both ways. */
    .occ-res-col { position: sticky; left: 0; z-index: 3; background: var(--surface-2, var(--surface)); min-width: 230px; text-align: left; }
    td.occ-res { position: sticky; left: 0; z-index: 1; background: var(--surface-1, var(--surface)); min-width: 230px; }
    tr:hover td.occ-res { background: var(--surface-2, rgba(0,0,0,.02)); }
    tr:hover td { background: var(--surface-2, rgba(0,0,0,.02)); }
    .occ-res-inner { display: flex; align-items: center; gap: .625rem; }
    .occ-avatar { width: 34px; height: 34px; border-radius: 50%; flex-shrink: 0; color: #fff; font-size: 12px;
      font-weight: 700; display: grid; place-items: center; }
    .occ-name { font-size: 13px; font-weight: 600; color: var(--text-1); }
    .occ-role { font-size: 10px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase; color: var(--text-3); }
    /* font-variant-numeric: tabular-nums makes every digit take the same width, so the
       numbers of one column line up under each other. Without it "1.5" and "11.5" wobble
       left and right and a column of 12 months is hard to compare at a glance. */
    .occ-cell { display: inline-flex; align-items: baseline; gap: .5rem; justify-content: center; font-variant-numeric: tabular-nums; }
    .occ-plan { font-size: 13px; color: var(--text-3); }
    .occ-real { font-size: 14px; font-weight: 700; }
    /* The three states of an actual figure, set by realClass() in the class below:
       ok = close to plan, warn = drifting, empty = nothing declared yet.
       Colours come from the design-system variables, never from raw hex, so the screen
       follows the light/dark theme like the rest of the app. */
    .occ-real-ok    { color: var(--c-brand); }
    .occ-real-warn  { color: var(--c-danger, #dc2626); }
    .occ-real-empty { color: var(--text-3); font-weight: 400; }
    .occ-year { font-size: 9px; font-weight: 600; color: var(--text-3); }
    /* The busiest month gets a tinted column. !important is needed because the row-hover
       rule above also sets a background on every td; without it, hovering a line would
       erase the highlight of the peak month exactly when the reader looks at it. */
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
  // template: the HTML lives here, inside backticks, instead of in a separate .html file.
  // Inside this block ONLY <!-- --> comments are legal; // or /* */ would be printed on the
  // page as text. @if / @for are Angular's built-in blocks (v17+), not HTML.
  template: `
    <!-- Whole page in two states: no project chosen -> only the picker; project chosen ->
         figures, matrix and the pending list. The @if (selected()) further down is what
         switches between the two. -->
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
      <!-- Project selector. A shared picker component is used on purpose instead of a plain
           <select>: with many projects a dropdown becomes unusable, so the whole app reuses
           <app-project-picker> (search + pagination) for large lists. -->
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
            <!-- Two different buttons for two different permissions: declaring your own
                 days (SUBMIT_WORKLOAD) is not the same right as planning somebody else's
                 (VALIDATE_WORKLOAD). Hiding here is only comfort; the server refuses the
                 call anyway if the permission is missing. -->
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

        <!-- Metric cards. Every figure shown here is computed from the two lists already
           loaded (see the computed signals in the class); nothing is asked again from the
           server and no total is stored anywhere. Same rule as the DI: an amount is derived
           when it is read, never kept in a field that could drift from its own data. -->
        <div class="row g-3 mb-4">
          <div class="col-6 col-xl-3">
            <div class="metric-card wl-metric-card">
              <div class="d-flex align-items-start justify-content-between mb-2">
                <div class="metric-icon metric-icon--brand"><i class="bi bi-calendar-check"></i></div>
              </div>
              <div class="metric-label">{{ 'workload.actualOccupancy' | transloco }}</div>
              <div class="metric-value wl-value-lg">{{ totalActual() | number:'1.0-1' }} <span class="m-unit">{{ 'common.manDays' | transloco }}</span></div>
              <!-- min(...,100) caps the bar. Why: a project at 130% of its plan would
                   otherwise ask for a bar 130% wide and overflow its own card. The real
                   number is still shown in the "realization rate" card next to it. -->
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
                <!-- 105% is a tolerance, not 100%: finishing a month a little over plan is
                     normal, so a green/red flip at exactly 100 would paint almost every
                     healthy project red and the colour would stop meaning anything. -->
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
                <!-- The form @if (x; as pk) calls peakMonth() once and keeps the result in pk.
                     Why: peakMonth() can return null, and without the alias the template
                     would have to call it twice (test, then read) with a ! on the second
                     call. Here, no null check can be forgotten. -->
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
              <!-- Year switch, drawn only when the project really spans several years.
                   With one single year the buttons would be a control that changes nothing.
                   The matrix shows one year at a time so the table stays readable: three
                   years side by side would be 36 columns. -->
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
                    <!-- track per.year * 100 + per.month gives Angular a stable identity
                         for each month column (July 2026 -> 202607). Why: without a good
                         track key Angular throws away and rebuilds every column on each
                         refresh, which loses the sideways scroll position of the table.
                         The object itself cannot be used: periods() rebuilds new objects
                         each time, so every column would look new. -->
                    @for (per of periods(); track per.year * 100 + per.month) {
                      <th class="text-center" [class.occ-peak]="isPeak(per)">
                        {{ monthName(per.month) }}
                        <div class="occ-year">{{ per.year }}</div>
                      </th>
                    }
                  </tr>
                </thead>
                <tbody>
                  <!-- One line per person; userId is the natural stable key, so a person
                       keeps his row (and its hover state) when the data reloads. -->
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
                          <!-- Each cell prints two numbers: planned (grey) then actual
                               (coloured). The || '·' and || '—' fallbacks replace a zero by a small
                               mark, because a grid full of "0" is much harder to read than
                               a grid where only the months that carry work show a figure.
                               The colour class comes from realClass(): it is the drift
                               warning, computed, never stored. -->
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

        <!-- Declared days still waiting for approval.
             The KPI engine only reads APPROVED days (findValidatedByProjectId), so as long
             as the project manager has not approved them here, the consumed cost, the EAC
             and the margin stay at zero.
             Two conditions guard the block: the right to approve, and at least one row to
             approve. Without the second one the screen would show an empty "pending" card
             on every healthy project. -->
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
                          <!-- Disabled only for the row being saved, not for all of them:
                               the spinner then sits on the button the user actually
                               clicked, and a double click cannot send the same approval
                               twice. -->
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

    <!-- Modal: declare the days really worked.
         It is written by hand instead of using the Bootstrap JavaScript modal, so that the
         open/closed state is one signal (showChargeModal) that Angular owns. -->
    @if (showChargeModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Click on the grey area closes the window; $event.stopPropagation() on the white
           box stops that same click from bubbling up. Without it, clicking inside the form
           (even on an input) would close the window and lose what was typed. -->
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
                <!-- A person who may only declare (SUBMIT_WORKLOAD but not
                     VALIDATE_WORKLOAD) sees his own name in a locked field instead of the
                     list of his colleagues: he declares for himself only. Without this, the
                     form would invite him to pick someone else and the server would answer
                     with an error he cannot understand. -->
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
                <!-- step="0.5" allows half days (somebody split between two projects), and
                     max="31" is the longest month. These bounds are only a first filter for
                     the user; the real check is on the server, because the browser ones can
                     be removed with the developer tools. -->
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

    <!-- Modal: plan a month ahead. Same shape as the one above, but it writes a PlanCharge
         (the forecast) instead of a ChargeReelle (what really happened). The two are kept
         apart because they are written by different people, at different moments, and one
         must never overwrite the other. -->
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
/**
 * The workload screen of one project.
 *
 * Shape of the class, and why it is built this way:
 *  - two raw lists are kept as signals (fullPlan, fullCharges), everything the user sees is
 *    a computed() built from them. So one reload refreshes the matrix, the four figures, the
 *    peak month and the pending list at once, and they can never disagree with each other.
 *  - nothing that can be derived is stored. Storing a total in a field would mean keeping it
 *    in step with every save, and the first forgotten update shows a wrong number of days -
 *    which is a wrong cost.
 *
 * implements OnInit: Angular calls ngOnInit() once the component exists and its inputs are
 * set. The loading is done there, not in the constructor, which stays free of side effects.
 */
export class WorkloadComponent implements OnInit {
  // inject() instead of constructor parameters: same dependency injection, but it works in
  // field initializers, so the signals below can already use these services.
  private readonly projectSvc = inject(ProjectService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly tr = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  // These three test a PERMISSION name, never a role name: roles are built in the admin
  // screens and can change without touching this code. Asking "is he a MANAGER" would break
  // the day a new role is created with the same rights.
  // They only decide what is drawn. The real barrier is @PreAuthorize on the server service
  // methods, plus ProjectScopeInterceptor for the project scope (ADR-021).
  canPlan = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  canSubmit = () => this.auth.hasPermission('SUBMIT_WORKLOAD');
  /** Same permission as canPlan, named for what it actually gates here.
   *  VALIDATE_WORKLOAD covers both planning and accepting declared days
   *  (see ChargeReelleService.validate, @PreAuthorize VALIDATE_WORKLOAD). */
  canValidate = () => this.auth.hasPermission('VALIDATE_WORKLOAD');
  /** True for a user who may declare his own days but not plan or approve: typically a
   *  developer on the team. The declare form then locks the resource field on himself.
   *  The 'currentUserId !== null' part matters: without a known id there would be nothing to
   *  put in the form, and it would send userId 0 to the server. */
  isDevOnly = () => this.canSubmit() && !this.canPlan() && this.auth.currentUserId !== null;

  /** Name shown in that locked field. '?? ''' because the auth context is null for a split
   *  second while the session is being restored; without it the template would crash on a
   *  property of null instead of simply showing nothing for one frame. */
  get currentUserFullName(): string { return this.auth.context()?.fullName ?? ''; }

  // ── The state of the screen ────────────────────────────────────
  // A signal is a value Angular watches: set it and every template part and every computed()
  // that reads it is refreshed, by itself. Why signals rather than plain fields: with plain
  // fields the page would only repaint when Angular happens to run a change detection pass,
  // and a total saved from a modal could stay stale on screen.
  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  /** The two raw lists answered by the API, for ALL years. Everything shown is derived from
   *  these two. They are kept whole (not already filtered) so switching year is instant and
   *  costs no extra request. */
  fullPlan = signal<PlanCharge[]>([]);
  fullCharges = signal<ChargeReelle[]>([]);
  /** Team of the project, used for the resource dropdowns and to print each person's role.
   *  It comes from another endpoint, because a person can appear in the workload rows
   *  without being in the team any more. */
  teamMembers = signal<{ userId: number; userFullName: string; role: string }[]>([]);
  /** Year currently shown. null means "not chosen yet", which is not the same as "no year":
   *  initYear() uses that null to know it may still pick a default. */
  year = signal<number | null>(null);

  /** Distinct years present across plan + actual, sorted.
   *  A Set is used because the same year appears on hundreds of rows and we want it once.
   *  Both lists are read, not only the plan: a month can be declared although nobody had
   *  planned it, and that year must still be reachable in the year switch. */
  readonly years = computed(() => {
    const s = new Set<number>();
    for (const p of this.fullPlan()) s.add(p.year);
    for (const c of this.fullCharges()) s.add(c.year);
    return [...s].sort((a, b) => a - b);
  });

  // The two lists narrowed to the chosen year. Every table and total below reads THESE, not
  // fullPlan/fullCharges, so the year filter is applied in exactly one place.
  // y == null (loose ==, so it also covers undefined) means "no year chosen yet": everything
  // is shown. Filtering on null instead would hide every row and the screen would look
  // empty during the first moments after loading.
  private readonly planInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullPlan() : this.fullPlan().filter(p => p.year === y);
  });
  private readonly chargesInScope = computed(() => {
    const y = this.year();
    return y == null ? this.fullCharges() : this.fullCharges().filter(c => c.year === y);
  });

  /** Actual workload waiting for the project manager to accept it.
   *  '!c.validatedAt' is the whole test: the API leaves that date out while the row is not
   *  approved. The sort puts the oldest month first, then the name, so the manager works
   *  down the list in a stable order instead of seeing rows jump after each approval. */
  readonly pendingCharges = computed(() =>
    this.chargesInScope()
        .filter(c => !c.validatedAt)
        .sort((a, b) => (a.year - b.year) || (a.month - b.month)
                     || a.userFullName.localeCompare(b.userFullName)));

  /** Id of the row being validated, so only that button shows a spinner. */
  validatingId = signal<number | null>(null);

  // Open/closed, saving, and error message of each of the two modals. They are separate
  // signals per modal on purpose: sharing one "saving" flag would put a spinner on both
  // windows at once if they were ever opened one after the other.
  showChargeModal = signal(false);
  chargeSaving = signal(false);
  chargeError = signal('');
  showPlanModal = signal(false);
  planSaving = signal(false);
  planError = signal('');

  // The two forms are plain objects, not signals, because [(ngModel)] writes into them
  // directly and nothing else on the page needs to react to what is being typed.
  // `getMonth() + 1`: JavaScript counts months from 0 (January = 0) while the API expects
  // 1..12. Forget the +1 and every default would point at the previous month - a declared
  // month landing on the wrong one, which is a wrong cost on a wrong period.
  chargeForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
  planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };

  /**
   * Month options for the pickers ({ v: 1, l: "janvier" }...), named in the language the
   * user is reading.
   *
   * It is a getter, and a getter is read again on every change detection pass, which is why
   * the result is cached together with the language it was built for. Without that cache,
   * a new Intl formatter and twelve new objects would be created many times per second, and
   * the <option> list would be rebuilt each time. The language is part of the cache key so
   * that switching to English really changes the names instead of keeping the French ones.
   *
   * Intl.DateTimeFormat is the browser's own month naming, so no month name has to be
   * translated by hand in the i18n files.
   */
  private monthsCache: { lang: string; list: { v: number; l: string }[] } | null = null;
  get months(): { v: number; l: string }[] {
    const lang = this.tr.getActiveLang();
    if (!this.monthsCache || this.monthsCache.lang !== lang) {
      const fmt = new Intl.DateTimeFormat(lang, { month: 'long' });
      this.monthsCache = {
        lang,
        // `new Date(2000, i, 1)` is only a carrier to get a month name out of the browser;
        // the year 2000 is arbitrary and never shown. Here i is the JavaScript month (0..11)
        // while v is the API month (1..12) - that is the whole reason for the `i + 1`.
        list: Array.from({ length: 12 }, (_, i) => ({
          v: i + 1,
          l: fmt.format(new Date(2000, i, 1)),
        })),
      };
    }
    return this.monthsCache.list;
  }

  /** Fixed colours for the round initials. A fixed list, not a random colour, so the same
   *  person always keeps the same colour - see avatarColor(). */
  private readonly avatarPalette = ['#2563eb', '#7c3aed', '#0891b2', '#059669', '#d97706', '#dc2626', '#db2777', '#4f46e5'];

  // ── Matrix model ───────────────────────────────────────────────
  /**
   * The lines of the matrix: everybody who appears in the chosen year, planned OR declared,
   * sorted by name.
   *
   * Why it is built from the workload rows and not from the team list: somebody can have
   * left the team while the days he worked stay in the project's history. Building the rows
   * from the team would make those days disappear from the screen although they still count
   * in the cost. The team is only used to add the role, and a missing role falls back to ''
   * rather than crashing.
   * A Map keyed by userId removes the duplicates: one person has one row, whatever the
   * number of months he appears in.
   */
  readonly resources = computed<MatrixResource[]>(() => {
    const roles = new Map(this.teamMembers().map(m => [m.userId, m.role]));
    const names = new Map<number, string>();
    for (const p of this.planInScope()) names.set(p.userId, p.userFullName);
    for (const c of this.chargesInScope()) names.set(c.userId, c.userFullName);
    return [...names.entries()]
      .map(([userId, name]) => ({ userId, name, role: roles.get(userId) ?? '' }))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  /**
   * The columns of the matrix: every month that carries at least one planned or declared
   * row, in order.
   *
   * Only months with data are shown, instead of the twelve months of the year: a project
   * that runs from March to June would otherwise be read across eight empty columns.
   * The Set holds "year-month" as text because a Set compares objects by identity, so two
   * separate { year, month } objects for the same month would both be kept and the month
   * would appear twice.
   */
  readonly periods = computed<Period[]>(() => {
    const set = new Set<string>();
    for (const p of this.planInScope()) set.add(`${p.year}-${p.month}`);
    for (const c of this.chargesInScope()) set.add(`${c.year}-${c.month}`);
    return [...set]
      .map(s => { const [y, m] = s.split('-').map(Number); return { year: y, month: m }; })
      .sort((a, b) => a.year - b.year || a.month - b.month);
  });

  /**
   * The content of every cell of the matrix, in one Map: "userId:year:month" ->
   * { planned, actual }.
   *
   * Why a Map built once instead of searching the two lists for each cell: a matrix of 20
   * people over 12 months is 240 cells, and each cell would walk both lists again on every
   * repaint. Here the lists are walked once and each cell is then an instant lookup.
   * The values are ADDED ('+='), not replaced: the same person can have two rows for the
   * same month (for example two separate declarations), and replacing would silently drop
   * one of them - days worked that vanish from the cost.
   */
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

  /**
   * The totals row at the bottom: "year-month" -> { planned, actual } for the whole team.
   *
   * It is built from cells(), not from the raw lists, so the bottom row can never disagree
   * with the columns printed above it.
   * Every month of periods() is seeded at zero first, so a month where nobody worked still
   * prints 0 instead of leaving a hole in the row.
   * 'const [, y, mo] = k.split(':')' drops the first piece (the userId) on purpose: the
   * totals do not care who did the work.
   */
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
  // totalActual: days declared this year (approved or not - the screen shows the reality,
  //   the cost engine on the server is the one that only counts approved days).
  // ecart: actual minus planned. Positive = more days burnt than foreseen.
  // realizationPct: guarded by `totalPlanned() > 0`. Without that guard a project with no
  //   plan yet would compute 0/0 = NaN and the card would print "NaN %".
  readonly totalActual = computed(() => this.chargesInScope().reduce((s, c) => s + c.actualDays, 0));
  readonly totalPlanned = computed(() => this.planInScope().reduce((s, p) => s + p.plannedDays, 0));
  readonly ecart = computed(() => this.totalActual() - this.totalPlanned());
  readonly realizationPct = computed(() => this.totalPlanned() > 0 ? (this.totalActual() / this.totalPlanned()) * 100 : 0);

  /**
   * The busiest month of the chosen year, or null when there is nothing to show.
   *
   * "Busiest" is the LARGER of planned and actual, not their sum: a month planned at 20 days
   * and worked at 19 is a 20-day month, not a 39-day one.
   * bestVal starts at -1 so that a first month at 0 is still better than "nothing seen yet";
   * the final 'bestVal <= 0' then rejects a year where every month is empty, which is what
   * makes the card print a dash instead of naming a random empty month as the peak.
   * The key is returned as well as the label, because isPeak() compares keys to tint the
   * right column.
   */
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

  /**
   * First load: fetch the projects, then follow the '?p=<id>' parameter of the URL.
   *
   * The URL is the single source of truth for "which project am I on". Why: the page can be
   * bookmarked, refreshed, or reached by the browser Back button, and all three then land on
   * the same project. Keeping the choice only in a field would lose it on every refresh.
   * The queryParamMap subscription is nested inside the projects call because the id from
   * the URL has to be matched against a list that is already there; subscribing outside
   * would sometimes run before the list arrives and find nothing.
   */
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        // String(p.id) because a URL parameter is always text: 12 === "12" is false, so
        // comparing them raw would never find the project and the page would stay empty.
        const project = list.find(p => String(p.id) === pid);
        // The `!== project.id` test stops a reload when the URL changes but the project does
        // not (for example the same link clicked twice): without it the same two requests
        // would be sent again for nothing.
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

  /**
   * A project was picked in the picker: show it and write it into the URL.
   *
   * replaceUrl: false adds a history entry, so the browser Back button returns to the
   * picker. With true, Back would jump straight out of the screen.
   */
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.loadAll();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName, role: m.roleInTeam })))
    );
  }

  /**
   * Back to the picker. It only empties the URL and does NOT touch 'selected' itself: the
   * subscription in ngOnInit sees the parameter disappear and clears the selection. One
   * single path changes the state, so the screen and the URL cannot end up disagreeing.
   */
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  /**
   * Load the plan and the declared days of the current project.
   *
   * 'this.year.set(null)' first: the year of the previous project has no meaning for this
   * one, and initYear() needs that null to be allowed to choose a new default.
   * size 500 asks for one large page instead of paging: the matrix has to add up the WHOLE
   * project to show correct totals, and a first page of 20 rows would show a total that is
   * simply wrong.
   * The two calls are independent, so each one calls initYear() when it lands - whichever
   * answers first sets the year, the second finds it already set and leaves it alone.
   */
  private loadAll(): void {
    const p = this.selected();
    if (!p) return;
    this.year.set(null);
    this.workloadSvc.listPlanCharges(p.id, 0, 500).subscribe(res => { this.fullPlan.set(res.content); this.initYear(); });
    this.workloadSvc.listChargesReelles(p.id, 0, 500).subscribe(res => { this.fullCharges.set(res.content); this.initYear(); });
  }

  /** Default the scope to the current year if present, else the first year with data.
   *  The early return on a year already chosen is what protects the user's own click: once
   *  he has picked 2025, the second request landing must not throw him back to 2026. */
  private initYear(): void {
    if (this.year() !== null) return;
    const ys = this.years();
    if (!ys.length) return;
    const now = new Date().getFullYear();
    this.year.set(ys.includes(now) ? now : ys[0]);
  }

  // ── Cell accessors ─────────────────────────────────────────────
  /** One cell of the matrix, with { planned: 0, actual: 0 } when the person has nothing that
   *  month. Returning a real object instead of undefined is what lets planOf(), actualOf()
   *  and realClass() read '.planned' without a null test each time. */
  private cellOf(u: number, per: Period) {
    return this.cells().get(`${u}:${per.year}:${per.month}`) ?? { planned: 0, actual: 0 };
  }
  planOf(u: number, per: Period): number { return this.cellOf(u, per).planned; }
  actualOf(u: number, per: Period): number { return this.cellOf(u, per).actual; }
  /**
   * The colour of one actual figure: empty, ok (close to plan) or warn (drifting).
   *
   * The rule: nothing declared -> grey. Otherwise the gap with the plan is warned about when
   * it reaches 5 days OR a quarter of the plan.
   * Why two tests and not one: a percentage alone would scream at a 2-day plan worked in 3
   * days (50% off, half a week in real life), and 5 days alone would stay silent on a 40-day
   * plan worked in 44. Both together catch the drifts that matter at any size.
   * When nothing was planned, only the absolute test can apply - dividing by a plan of 0
   * would give Infinity.
   * It is a helper, not a stored flag: the colour always follows the numbers being shown.
   */
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
  /** Up to two initials for the round avatar ("Mohamed Ali Ben Salah" -> "MA").
   *  filter(Boolean) drops the empty pieces left by a double space; without it 'w[0]' would
   *  be undefined and the avatar would print "undefined". The final '|| '?'' covers a name
   *  that is empty altogether. */
  initials(name: string): string {
    return name.split(' ').filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase() || '?';
  }
  /**
   * A colour for the avatar, always the same one for the same name.
   *
   * The loop is a small hash: each letter is mixed into a number, and that number picks a
   * slot in the fixed palette. Why not a random colour: the reader recognises a person by
   * his colour while scrolling the matrix, and a colour that changes on every repaint would
   * be worse than no colour at all.
   * '>>> 0' keeps the number positive (it forces it back into 32 unsigned bits). Without it
   * the value overflows into negatives on long names, and a negative index would land
   * outside the palette and give undefined - an avatar with no background at all.
   */
  avatarColor(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return this.avatarPalette[h % this.avatarPalette.length];
  }
  /** Full month name for a column header, in the active language ("JUILLET").
   *  'm - 1' converts the API month (1..12) into the JavaScript one (0..11); without it
   *  July would be printed as August, and December would silently become January of the
   *  next year. The 1..12 guard keeps a bad value visible as a number instead of turning it
   *  into a wrong but believable month name. */
  monthName(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'long' })
      .format(new Date(2000, m - 1, 1)).toUpperCase();
  }
  /** Same as monthName() but short ("juil."), for the peak card and the CSV headers, where
   *  a full name would not fit. */
  monthShort(m: number): string {
    if (m < 1 || m > 12) return String(m);
    return new Intl.DateTimeFormat(this.tr.getActiveLang(), { month: 'short' })
      .format(new Date(2000, m - 1, 1));
  }
  /** Math is not reachable from an Angular template, so the progress bar needs this tiny
   *  bridge to cap its width at 100. */
  min(a: number, b: number): number { return Math.min(a, b); }

  // ── Export ─────────────────────────────────────────────────────
  /**
   * Download the matrix as a CSV file, built in the browser from what is already on screen.
   *
   * No server call: the numbers are already here, so the file is guaranteed to match the
   * table the user is looking at, and no extra endpoint has to be protected.
   * The file is meant to be opened in Excel, which is why the two Excel habits below
   * (semicolon separator and BOM) are respected.
   */
  exportCsv(): void {
    const per = this.periods();
    const plan = this.tr.translate('workload.csvPlan');
    const real = this.tr.translate('workload.csvActual');
    // flatMap gives TWO columns per month (planned, then actual) out of one month. A plain
    // map would produce an array inside the array and the header would come out as
    // "juil. 2026 (Plan),juil. 2026 (Réel)" inside one single cell.
    const header = [this.tr.translate('workload.resource'), this.tr.translate('workload.csvRole'), ...per.flatMap(p => [`${this.monthShort(p.month)} ${p.year} (${plan})`, `${this.monthShort(p.month)} ${p.year} (${real})`])];
    const rows = this.resources().map(r => [
      r.name, r.role || '',
      ...per.flatMap(p => [String(this.planOf(r.userId, p)), String(this.actualOf(r.userId, p))]),
    ]);
    const totals = [this.tr.translate('workload.csvMonthlyTotals'), '', ...per.flatMap(p => [String(this.monthTotalPlanned(p)), String(this.monthTotalActual(p))])];
    // Every value is wrapped in double quotes, and a double quote inside a value is doubled.
    // The regex /"/g simply means "every double quote character in the text".
    // Why: a name such as André "Dédé" Ben Ali would otherwise close the field in the middle
    // and shift the whole rest of the line one column to the left in Excel.
    // The separator is ';' and not ',' because a French/European Excel splits on ';'.
    const csv = [header, ...rows, totals]
      .map(line => line.map(v => `"${String(v).replace(/"/g, '""')}"`).join(';'))
      .join('\n');
    // The invisible character placed in front of the text is a BOM (byte order mark). It is
    // what tells Excel that the file is UTF-8. Without it Excel reads it as Windows-1252 and
    // "Réalisé" is shown as "RÃ©alisÃ©".
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    // A temporary link is created and clicked by code: this is the standard way to save a
    // file the browser built itself, with no server and no new tab.
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `matrice-occupation-${this.selected()?.code ?? 'projet'}.csv`;
    a.click();
    // Release the temporary address. Without it the file stays in the browser's memory until
    // the page is closed, and a user exporting twenty times keeps twenty copies alive.
    URL.revokeObjectURL(url);
  }

  // ── Modals ─────────────────────────────────────────────────────
  /** Open the "declare my days" window with a fresh form.
   *  The form object is REPLACED, not edited: whatever was typed and abandoned last time
   *  must not come back, and a leftover error message must not greet the user. A user who
   *  may only declare gets his own id pre-filled, since the field is locked on him. */
  openChargeModal(): void {
    const uid = this.isDevOnly() ? (this.auth.currentUserId ?? 0) : 0;
    this.chargeForm = { userId: uid, year: new Date().getFullYear(), month: new Date().getMonth() + 1, actualDays: 0 };
    this.chargeError.set('');
    this.showChargeModal.set(true);
  }

  /**
   * Send the declared days to POST /api/projects/{id}/charges-reelles.
   *
   * The row arrives NOT approved: it will be shown in the pending list and it costs nothing
   * until somebody with VALIDATE_WORKLOAD accepts it.
   * The check here is only politeness (it saves a round trip and gives an instant message);
   * the server checks the same things again, plus the permission and the project scope.
   * Note that '!actualDays' also rejects 0, which is wanted: a line of zero days is not a
   * declaration, it is an empty form.
   */
  submitCharge(): void {
    if (!this.chargeForm.userId || !this.chargeForm.actualDays) {
      this.chargeError.set(this.tr.translate('workload.errResourceDays'));
      return;
    }
    this.chargeSaving.set(true);
    this.chargeError.set('');
    // `selected()!` - the `!` tells TypeScript "this is not null here". It is true because
    // the button that opens this modal only exists inside the @if (selected()) block.
    // On success the whole data is reloaded rather than the new row being pushed into the
    // list by hand: the server may have adjusted or merged it, and the totals must reflect
    // what was really stored, not what we hoped to store.
    this.workloadSvc.submitCharge(this.selected()!.id, this.chargeForm).subscribe({
      next: () => { this.loadAll(); this.showChargeModal.set(false); this.chargeSaving.set(false); this.toast.success(this.tr.translate('workload.okActualSaved')); },
      // The error stays inside the modal (and not in a toast) so the user keeps what he
      // typed in front of him and can correct it.
      // Careful: Spring answers with an RFC 7807 ProblemDetail, whose text is in `detail`,
      // not in `message` - so this branch almost always falls back to the fixed sentence
      // below (see the same remark in validateCharge()).
      error: (e) => { this.chargeError.set(e.error?.message ?? 'Erreur lors de la soumission.'); this.chargeSaving.set(false); }
    });
  }

  /** Month name in the active language, for the pending-validation rows. */
  monthLabel(m: number): string {
    return this.months.find(x => x.v === m)?.l ?? String(m);
  }

  /**
   * Accept one declared month of work.
   *
   * This is the step that makes the money move: KpiService only reads charges
   * through findValidatedByProjectId, so an unvalidated row contributes nothing
   * to the consumed cost, the EAC or the margin. Reload afterwards so the
   * matrix and the indicators reflect the new state.
   */
  validateCharge(c: ChargeReelle): void {
    // Only one approval may be in flight. Why: approving is what turns days into money, and
    // a user clicking two rows quickly would otherwise fire two saves whose two reloads land
    // in an unknown order, leaving the list showing a row that is already approved.
    if (this.validatingId() !== null) return;          // one at a time
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
        // Spring returns RFC 7807 ProblemDetail, whose field is `detail`.
        // `message` is read first elsewhere in this app and is always
        // undefined, which is why those screens show a generic error.
        this.toast.error(e.error?.detail ?? e.error?.message
                         ?? this.tr.translate('workload.errValidate'));
      }
    });
  }

  /** Open the "plan a month" window with a fresh form. No pre-filled resource here: planning
   *  is always done FOR somebody else, so the field must be chosen on purpose. */
  openPlanModal(): void {
    this.planForm = { userId: 0, year: new Date().getFullYear(), month: new Date().getMonth() + 1, plannedDays: 0 };
    this.planError.set('');
    this.showPlanModal.set(true);
  }

  /**
   * Send a planned month to POST /api/projects/{id}/plan-charges.
   *
   * Same shape as submitCharge(), but this writes the forecast. A plan needs no approval:
   * it is a decision of the project manager, it never becomes a cost by itself, only the
   * approved actual days do.
   * The server requires VALIDATE_WORKLOAD here; the button is simply hidden for the others.
   */
  submitPlan(): void {
    if (!this.planForm.userId || !this.planForm.plannedDays) {
      this.planError.set(this.tr.translate('workload.errResourceDays'));
      return;
    }
    this.planSaving.set(true);
    this.planError.set('');
    this.workloadSvc.createPlanCharge(this.selected()!.id, this.planForm).subscribe({
      next: () => { this.loadAll(); this.showPlanModal.set(false); this.planSaving.set(false); this.toast.success(this.tr.translate('workload.okPlanSaved')); },
      // Same remark as in submitCharge(): Spring's ProblemDetail carries its text in
      // `detail`, so `message` is normally undefined and the fixed sentence is shown.
      error: (e) => { this.planError.set(e.error?.message ?? 'Erreur lors de la planification.'); this.planSaving.set(false); }
    });
  }
}
