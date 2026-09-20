import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { ProjectService } from '../../core/services/project.service';
import { Project } from '../../core/models/project.model';
import { KpiResponse } from '../../core/models/kpi.model';
import { PaginationComponent } from '../../shared/pagination/pagination.component';

// KPI screen: financial health of every project, as portfolio-wide charts or one card each.
// Calls ProjectService.listAll() then getLiveKpi(id) per project — "live" because the server
// computes amounts at read time, same rule as DI. No permission check here: server-only, a
// refused call just shows the card's error state.

// Union (not boolean): distinguishes "still loading" from "call failed" so a failed card can
// show Retry instead of spinning forever.
type LoadState = 'loading' | 'done' | 'error';

type ChartView = 'portfolio' | 'cards';

// Pre-computed row for the portfolio charts: percentages computed once in a computed()
// signal rather than recalculated by the template on every change-detection pass.
interface ChartRow {
  p: Project;             // the project itself (code, name, client, status)
  k: KpiResponse;         // the KPI payload returned by the backend for it
  budget: number;         // money sold to the client, in TND
  eac: number;            // expected total cost at completion, in TND
  consumed: number;       // cost already spent, in TND
  marge: number;          // budget - EAC, in TND (negative = the project loses money)
  marginPct: number;      // marge as a percentage of budget

  // The four fields below are widths in percent, NOT amounts. They are all
  // measured against the biggest value of the whole portfolio, so every bar of
  // the chart shares one single scale and bars can be compared by eye.
  budgetPct: number;            // width of the grey reference bar
  eacWithinBudgetPct: number;   // coloured part of the EAC bar that stays inside the budget
  eacOverBudgetPct: number;     // red part sticking out past the budget mark (0 if none)
  consumedInEacPct: number;     // dark fill inside the EAC bar, relative to the EAC bar itself

  // Health class of the project, derived from marginPct only. It drives both a
  // CSS class name ('kpi-bar--' + scenario) and a badge colour in the template.
  scenario: 'profit-high' | 'profit-mid' | 'break-even' | 'loss-mid' | 'loss-high';
}

@Component({
  selector: 'app-kpi',
  standalone: true,
  imports: [CommonModule, RouterLink, PaginationComponent, TranslocoModule],
  styles: [`
    .chart-row:hover { background: var(--surface, var(--bg)); }
    /* These five names must stay in step with ChartRow's scenario union (built as
       'kpi-bar--' + row.scenario in the template). */
    .kpi-bar--profit-high  { background: #16a34a; }
    .kpi-bar--profit-mid   { background: #2563eb; }
    .kpi-bar--break-even   { background: #d97706; }
    .kpi-bar--loss-mid     { background: #ea580c; }
    .kpi-bar--loss-high    { background: #dc2626; }
    /* Same five levels as soft pill badges ('badge-' + row.scenario). */
    .badge-profit-high  { background:#dcfce7; color:#15803d; font-weight:600; }
    .badge-profit-mid   { background:#dbeafe; color:#1d4ed8; font-weight:600; }
    .badge-break-even   { background:#fef3c7; color:#92400e; font-weight:600; }
    .badge-loss-mid     { background:#ffedd5; color:#9a3412; font-weight:600; }
    .badge-loss-high    { background:#fee2e2; color:#991b1b; font-weight:600; }
    /* Dark-theme overrides via :host-context (theme flag lives on <html>, outside this
       component). rgba(...,.18) tints the near-white badges instead of leaving them glaring. */
    :host-context([data-theme="dark"]) .badge-profit-high { background:rgba(22,163,74,.18);  color:#86efac; }
    :host-context([data-theme="dark"]) .badge-profit-mid  { background:rgba(37,99,235,.18);  color:#93c5fd; }
    :host-context([data-theme="dark"]) .badge-break-even  { background:rgba(217,119,6,.18);  color:#fcd34d; }
    :host-context([data-theme="dark"]) .badge-loss-mid    { background:rgba(234,88,12,.18);  color:#fdba74; }
    :host-context([data-theme="dark"]) .badge-loss-high   { background:rgba(220,38,38,.18);  color:#fca5a5; }
    .legend-dot { width:10px; height:10px; border-radius:50%; display:inline-block; }
    .legend-swatch { width:10px; height:10px; border-radius:2px; display:inline-block; }
    .legend-inline { display:inline-flex; align-items:center; gap:4px; }
    /* Fixed side columns so every line's bar starts at the same x position. */
    .chart-row-grid { display:grid; grid-template-columns:170px 1fr 120px; align-items:center; gap:1rem; }
    .kpi-mini-label { font-size:10px; color:var(--text-3); text-transform:uppercase; letter-spacing:.5px; }
    .kpi-mini-unit  { font-size:10px; color:var(--text-3); }
    .kpi-stat-box   { background: var(--surface, var(--bg)); }
    .kpi-stat-label { color:var(--text-3); font-size:9px; }
    .kpi-mini-bar { height:6px; border-radius:3px; background:var(--surface-3); overflow:hidden; margin-top:4px; }
    .kpi-mini-fill { height:100%; border-radius:3px; transition:width .4s; }

    /* Segmented view toggle */
    .seg { display:inline-flex; background:var(--surface-2,rgba(0,0,0,.04)); border:1px solid var(--border);
      border-radius:10px; padding:2px; gap:2px; }
    .seg-btn { border:0; background:transparent; color:var(--text-2); font-size:12px; font-weight:600;
      padding:.35rem .75rem; border-radius:8px; cursor:pointer; display:inline-flex; align-items:center; transition:all .15s; }
    .seg-btn:hover { color:var(--text-1); }
    .seg-on { background:var(--surface-1,var(--surface)); color:var(--c-brand); box-shadow:0 1px 3px rgba(0,0,0,.12); }

    /* KPI metric card */
    .kpi-metric { padding:1.1rem 1.15rem; }
    .kpi-metric .metric-value { font-size:1.5rem; }
    .kpi-metric .kpi-unit { font-size:.7rem; color:var(--text-3); font-weight:600; }

    /* Portfolio health bar: one bar cut into coloured segments. */
    .pf-health { display:flex; height:12px; border-radius:7px; overflow:hidden; background:var(--surface-3); }
    .pf-seg { height:100%; transition:width .5s ease; }
    /* :first/:last-child (not fixed classes): a zero-count segment isn't rendered, so
       whichever segment ends up first/last must still get the rounded edge. */
    .pf-seg:first-child { border-radius:7px 0 0 7px; }
    .pf-seg:last-child { border-radius:0 7px 7px 0; }

    /* Skeleton (not a spinner): boxes match real card size, so nothing jumps on load. */
    .skl { border-radius:8px; background:linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size:400% 100%; animation:skla 1.2s ease infinite; }
    @keyframes skla { 0%{background-position:100% 0} 100%{background-position:-100% 0} }
    /* Respects reduced-motion preference (animation can trigger dizziness/migraines). */
    @media (prefers-reduced-motion: reduce){ .skl{animation:none} .pf-seg{transition:none} .seg-btn{transition:none} }
  `],
  template: `
    <!-- TOPBAR: breadcrumb on the left, view switch on the right. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-graph-up fs-13" style="color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'kpi.breadcrumb' | transloco }}</span>
      </div>
      <!-- Both views render the same already-loaded data; switching costs no HTTP call. -->
      <div class="tb-right">
        <div class="seg">
          <button class="seg-btn" [class.seg-on]="view()==='portfolio'" (click)="view.set('portfolio')">
            <i class="bi bi-bar-chart-line me-1"></i>{{ 'kpi.tabPortfolio' | transloco }}
          </button>
          <button class="seg-btn" [class.seg-on]="view()==='cards'" (click)="view.set('cards')">
            <i class="bi bi-grid me-1"></i>{{ 'kpi.tabProjects' | transloco }}
          </button>
        </div>
      </div>
    </div>

    <div class="page-body">

      <!-- Page header -->
      <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
        <div>
          <h1 class="page-title">{{ 'kpi.title' | transloco }}</h1>
        </div>
      </div>

      <!-- Note: tests list-empty only, so a user who genuinely owns no project sees this too. -->
      @if (projects().length === 0) {
        <div class="row g-3 mb-4">
          @for (i of [1,2,3,4]; track i) {
            <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
          }
        </div>
        <div class="card"><div class="p-3">@for (i of [1,2,3,4,5]; track i) { <div class="skl mb-2" style="height:28px"></div> }</div></div>
      }

      <!-- PORTFOLIO VIEW -->
      @if (view() === 'portfolio' && projects().length > 0) {

        <!-- "as agg" reads the computed signal once for the block; aggregates() is null
             until there's usable KPI data, so this doubles as the null guard. -->
        @if (aggregates(); as agg) {
          <div class="row g-3 mb-3">
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon metric-icon--brand"><i class="bi bi-briefcase-fill"></i></div>
                  <span class="text-caption">{{ 'kpi.portfolioCaption' | transloco }}</span>
                </div>
                <!-- No decimals: these are portfolio totals in dinars, cents would add noise. -->
                <div class="metric-label">{{ 'kpi.totalBudget' | transloco }}</div>
                <div class="metric-value">{{ agg.totalBudget | number:'1.0-0' }} <span class="kpi-unit">TND</span></div>
              </div>
            </div>
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon" [style.background]="agg.totalEac > agg.totalBudget ? 'var(--c-danger-dim,rgba(220,38,38,.12))' : 'var(--c-success-dim)'"
                       [style.color]="agg.totalEac > agg.totalBudget ? 'var(--c-danger,#dc2626)' : 'var(--c-success)'"><i class="bi bi-graph-up-arrow"></i></div>
                  <span class="fs-11" [class.text-danger]="agg.totalEac > agg.totalBudget" [class.text-success]="agg.totalEac <= agg.totalBudget">
                    {{ (agg.totalEac <= agg.totalBudget ? 'kpi.withinBudget' : 'kpi.overBudget') | transloco }}
                  </span>
                </div>
                <div class="metric-label">{{ 'kpi.totalEac' | transloco }}</div>
                <div class="metric-value" [class.text-danger]="agg.totalEac > agg.totalBudget">{{ agg.totalEac | number:'1.0-0' }} <span class="kpi-unit">TND</span></div>
              </div>
            </div>
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon" [style.background]="agg.totalMarge >= 0 ? 'var(--c-success-dim)' : 'var(--c-danger-dim,rgba(220,38,38,.12))'"
                       [style.color]="agg.totalMarge >= 0 ? 'var(--c-success)' : 'var(--c-danger,#dc2626)'"><i class="bi bi-cash-coin"></i></div>
                  <span class="fs-11 fw-semibold" [class.text-success]="agg.globalMarginPct>=0" [class.text-danger]="agg.globalMarginPct<0">
                    {{ agg.globalMarginPct >= 0 ? '+' : '' }}{{ agg.globalMarginPct | number:'1.1-1' }}%
                  </span>
                </div>
                <!-- "+" added by hand: the number pipe prints "-" but never "+". -->
                <div class="metric-label">{{ 'kpi.netMargin' | transloco }}</div>
                <div class="metric-value" [class.text-success]="agg.totalMarge >= 0" [class.text-danger]="agg.totalMarge < 0">
                  {{ agg.totalMarge >= 0 ? '+' : '' }}{{ agg.totalMarge | number:'1.0-0' }} <span class="kpi-unit">TND</span>
                </div>
              </div>
            </div>
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon metric-icon--teal"><i class="bi bi-check2-circle"></i></div>
                  <span class="text-caption">{{ 'kpi.outOf' | transloco: { total: agg.total } }}</span>
                </div>
                <div class="metric-label">{{ 'kpi.profitableProjects' | transloco }}</div>
                <div class="metric-value text-success">{{ agg.profitCount }}<span class="kpi-unit"> / {{ agg.total }}</span></div>
              </div>
            </div>
          </div>

          <!-- Portfolio health: shape of the whole portfolio at a glance, no reading required. -->
          @if (health(); as h) {
            <div class="card p-3 mb-4">
              <div class="d-flex justify-content-between align-items-center mb-2">
                <span class="fs-13 fw-semibold"><i class="bi bi-heart-pulse me-1" style="color:var(--c-brand)"></i>{{ 'kpi.portfolioHealth' | transloco }}</span>
                <span class="text-caption">{{ 'kpi.projectsAssessed' | transloco: { count: h.total } }}</span>
              </div>
              <!-- role="img" + aria-label: the bar carries no text of its own for a screen
                   reader. Each segment only renders if its count is nonzero (else a
                   zero-width block would still show as a thin line from the rounding). -->
              <div class="pf-health" role="img"
                   [attr.aria-label]="'kpi.healthAria' | transloco: { profit: h.profit, breakEven: h.breakEven, deficit: h.deficit, total: h.total }">
                @if (h.profit)   { <div class="pf-seg kpi-bar--profit-high" [style.width.%]="h.profitPct"   [title]="'kpi.profitableCount' | transloco: { count: h.profit }"></div> }
                @if (h.breakEven){ <div class="pf-seg kpi-bar--break-even" [style.width.%]="h.breakPct"    [title]="'kpi.breakEvenCount' | transloco: { count: h.breakEven }"></div> }
                @if (h.deficit)  { <div class="pf-seg kpi-bar--loss-high" [style.width.%]="h.deficitPct"  [title]="'kpi.deficitCount' | transloco: { count: h.deficit }"></div> }
              </div>
              <div class="d-flex gap-3 mt-2 flex-wrap fs-11" style="color:var(--text-2)">
                <span><span class="legend-dot me-1 kpi-bar--profit-high"></span>{{ 'kpi.profitable' | transloco }} <b>{{ h.profit }}</b></span>
                <span><span class="legend-dot me-1 kpi-bar--break-even"></span>{{ 'kpi.breakEven' | transloco }} <b>{{ h.breakEven }}</b></span>
                <span><span class="legend-dot me-1 kpi-bar--loss-high"></span>{{ 'kpi.deficit' | transloco }} <b>{{ h.deficit }}</b></span>
              </div>
            </div>
          }
        } @else {
          <!-- aggregates() still null: skeleton again rather than four zeros that would
               look like real data. -->
          <div class="row g-3 mb-4">
            @for (i of [1,2,3,4]; track i) {
              <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
            }
          </div>
        }

        <!-- Bullet chart, one line/project: grey bar = budget, coloured bar = EAC, dark fill
             = spent, vertical mark = budget position. One line reads "cost vs budget" at a glance. -->
        <div class="card mb-4">
          <div class="card-header justify-content-between">
            <div>
              <i class="bi bi-bar-chart-line me-2"></i>{{ 'kpi.budgetVsEacTitle' | transloco }}
            </div>
            <div class="d-flex gap-3 align-items-center fs-11">
              <span><span class="legend-dot kpi-bar--profit-high"></span> {{ 'kpi.profitableOne' | transloco }}</span>
              <span><span class="legend-dot kpi-bar--break-even"></span> {{ 'kpi.breakEven' | transloco }}</span>
              <span><span class="legend-dot kpi-bar--loss-high"></span> {{ 'kpi.deficitOne' | transloco }}</span>
              <span style="color:var(--text-3)">{{ 'kpi.contractBudgetMark' | transloco }}</span>
            </div>
          </div>

          @if (portfolioRows().length === 0) {
            <div class="card-body text-center text-muted py-4">
              <span class="spinner-border spinner-border-sm me-2"></span>{{ 'kpi.computing' | transloco }}
            </div>
          } @else {
            <div class="card-body p-0">
              <!-- track row.p.id: rows are sorted by margin, so a late KPI can reorder them;
                   a stable id moves the DOM node instead of rewriting every row. -->
              @for (row of pagedPortfolioRows(); track row.p.id; let last = $last) {
                <div class="chart-row chart-row-grid"
                     style="padding:.625rem 1.25rem"
                     [style.border-bottom]="!last ? '1px solid var(--border)' : 'none'">

                  <!-- Project label -->
                  <div>
                    <div class="fw-semibold monospace fs-12" style="color:var(--c-brand)">
                      {{ row.p.code }}
                    </div>
                    <div class="text-truncate fs-11" style="color:var(--text-2)">
                      {{ row.p.name }}
                    </div>
                    <!-- @if on client: without it, a project with none would show a stray dot. -->
                    <div class="text-micro" style="margin-top:1px">
                      {{ 'status.' + row.p.status | transloco }}
                      @if (row.p.client) { · {{ row.p.client }} }
                    </div>
                  </div>

                  <!-- position:relative anchors the absolutely-positioned bars so they stack
                       on the same left edge. -->
                  <div style="position:relative; height:20px; display:flex; align-items:center">
                    <!-- Grey budget bar, drawn first; min-width:4px keeps tiny budgets visible. -->
                    <div [style.width.%]="row.budgetPct"
                         style="position:absolute; left:0; top:4px; height:12px; background:var(--surface-3); border-radius:6px; min-width:4px"></div>

                    <!-- > 0.1 skips bars thin enough that min-width:4px would paint a stray dot. -->
                    @if (row.eacWithinBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacWithinBudgetPct"
                           [class]="'kpi-bar--' + row.scenario"
                           style="position:absolute; left:0; top:4px; height:12px; border-radius:6px 0 0 6px; overflow:hidden; min-width:4px">
                        <!-- Semi-transparent overlay (not a fixed color) so it darkens whichever
                             health color is underneath. -->
                        @if (row.consumedInEacPct > 0.5) {
                          <div [style.width.%]="row.consumedInEacPct"
                               style="height:100%; background:rgba(0,0,0,.22); border-radius:inherit"></div>
                        }
                      </div>
                    }

                    <!-- Always red regardless of scenario: overspending must stay readable
                         even on an overall-positive project. Starts exactly where grey ends. -->
                    @if (row.eacOverBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacOverBudgetPct"
                           [style.left.%]="row.budgetPct"
                           class="kpi-bar--loss-high"
                           style="position:absolute; top:4px; height:12px; border-radius:0 6px 6px 0; min-width:4px"></div>
                    }

                    <!-- Vertical budget mark; translateX(-1px) centers it on the exact value. -->
                    <div [style.left.%]="row.budgetPct"
                         style="position:absolute; top:0; width:2px; height:20px; background:var(--text-1); border-radius:1px; transform:translateX(-1px); z-index:2"></div>
                  </div>

                  <!-- Values & margin badge -->
                  <div class="text-end flex-shrink-0">
                    <span class="badge rounded-pill px-2 fs-12"
                          [class]="'badge-' + row.scenario">
                      {{ row.marginPct >= 0 ? '+' : '' }}{{ row.marginPct | number:'1.0-1' }}%
                    </span>
                    <div class="text-micro" style="margin-top:3px">
                      EAC {{ row.eac | number:'1.0-0' }} TND
                    </div>
                    @if (row.consumed > 0) {
                      <div class="text-micro">
                        {{ 'kpi.consumed' | transloco }} {{ row.consumed | number:'1.0-0' }}
                      </div>
                    }
                  </div>
                </div>
              }
            </div>

            <!-- [total] must be the FULL row count, not the visible page's, or the widget
                 thinks there's only one page. Page reset to 0 on page-size change. -->
            <app-pagination
              [page]="portfolioPage()" [pageSize]="portfolioPageSize()" [total]="portfolioRows().length"
              (pageChange)="portfolioPage.set($event)"
              (pageSizeChange)="portfolioPageSize.set($event); portfolioPage.set(0)" />

            <!-- A bullet chart isn't a common shape, so it's explained in words underneath. -->
            <div class="card-footer text-caption d-flex flex-wrap" style="gap:1.5rem">
              <span><b>{{ 'kpi.legendGrey' | transloco }}</b> {{ 'kpi.legendGreyDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendColour' | transloco }}</b> {{ 'kpi.legendColourDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendDark' | transloco }}</b> {{ 'kpi.legendDarkDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendRed' | transloco }}</b> {{ 'kpi.legendRedDesc' | transloco }}</span>
            </div>
          }
        </div>

        <!-- Diverging chart: losses grow left of a center line, gains right — a single-
             direction bar chart would make a 10k loss and a 10k gain look identical. -->
        @if (portfolioRows().length > 0) {
          <div class="card mb-4">
            <div class="card-header">
              <i class="bi bi-currency-exchange me-2"></i>{{ 'kpi.marginByProject' | transloco }}
            </div>
            <div class="card-body p-0">
              @for (row of pagedPortfolioRows(); track row.p.id; let last = $last) {
                <div class="chart-row-grid"
                     style="padding:.5rem 1.25rem"
                     [style.border-bottom]="!last ? '1px solid var(--border)' : 'none'">
                  <div class="monospace fs-11" style="color:var(--text-2)">{{ row.p.code }}</div>

                  <!-- Left half justifies content right, so a loss bar grows toward the
                       center line instead of floating at the far left. -->
                  <div style="position:relative; height:14px; display:flex; align-items:center">
                    <!-- Left half (loss side) -->
                    <div style="width:50%; height:12px; position:relative; display:flex; justify-content:flex-end">
                      @if (row.marge < 0) {
                        <div [style.width.%]="absMarginBarPct(row)"
                             class="kpi-bar--loss-high"
                             style="height:100%; border-radius:6px 0 0 6px; opacity:.85; max-width:100%"></div>
                      }
                    </div>
                    <!-- Centre zero line -->
                    <div class="flex-shrink-0" style="width:2px; height:14px; background:var(--text-3); z-index:1"></div>
                    <!-- Right half (profit side) -->
                    <div style="width:50%; height:12px; position:relative; display:flex; justify-content:flex-start">
                      @if (row.marge >= 0) {
                        <div [style.width.%]="absMarginBarPct(row)"
                             class="kpi-bar--profit-high"
                             style="height:100%; border-radius:0 6px 6px 0; opacity:.85; max-width:100%"></div>
                      }
                    </div>
                  </div>

                  <div class="text-end fw-semibold"
                       [class.text-success]="row.marge >= 0"
                       [class.text-danger]="row.marge < 0"
                       style="font-size:12px">
                    {{ row.marge >= 0 ? '+' : '' }}{{ row.marge | number:'1.0-0' }} TND
                  </div>
                </div>
              }
            </div>
            <div class="card-footer text-caption">
              <span class="legend-inline">
                <span class="legend-swatch kpi-bar--loss-high"></span> {{ 'kpi.loss' | transloco }}
              </span>
              <span class="legend-inline" style="margin-left:1rem">
                <span class="legend-swatch kpi-bar--profit-high"></span> {{ 'kpi.profit' | transloco }}
              </span>
              <span style="margin-left:1rem">{{ 'kpi.breakEvenAxis' | transloco }}</span>
            </div>
          </div>
        }
      }

      <!-- DETAILED CARDS VIEW: loops over ALL projects (not just portfolioRows()) since a
           draft/no-workload project is meaningless in an aggregate but the user still
           wants to open its card. -->
      @if (view() === 'cards') {
        <div class="row g-3">
          @for (p of pagedProjects(); track p.id) {
            <div class="col-md-6 col-xl-4">
              <!-- No border while unloaded, so an empty card doesn't imply a meaningless health color. -->
              <div class="card h-100" [style.border-left]="kpiMap()[p.id] ? '3px solid ' + scenarioColor(p) : ''">
                <div class="card-header justify-content-between">
                  <div class="me-2">
                    <div class="fw-bold monospace fs-12">{{ p.code }}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px">{{ p.name }}</div>
                  </div>
                  <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
                </div>

                <!-- Three bodies: KPI present -> figures, call failed -> Retry, else -> spinner.
                     "as k" avoids re-looking up the map on every line. -->
                @if (kpiMap()[p.id]; as k) {
                  <div class="card-body p-3">
                    <!-- Main metrics grid -->
                    <div class="row g-2 text-center mb-3">
                      <div class="col-6">
                        <!-- ?? (not ||): a real budget of 0 must stay 0, not fall back to EAC+margin. -->
                        <div class="kpi-mini-label">{{ 'kpi.budget' | transloco }}</div>
                        <div class="fw-bold fs-13" style="color:var(--text-1)">
                          {{ (p.budgetTnd ?? k.eac + k.marge) | number:'1.0-0' }}
                        </div>
                        <div class="kpi-mini-unit">TND</div>
                      </div>
                      <div class="col-6">
                        <div class="kpi-mini-label">EAC</div>
                        <div class="fw-bold fs-13"
                             [class.text-success]="k.marge != null && k.marge >= 0"
                             [class.text-danger]="k.marge != null && k.marge < 0">
                          {{ k.eac | number:'1.0-0' }}
                        </div>
                        <div class="kpi-mini-unit">TND</div>
                      </div>
                      <div class="col-6">
                        <div class="kpi-mini-label">{{ 'kpi.consumed' | transloco }}</div>
                        <div class="fw-bold text-warning fs-13">
                          {{ k.budgetConsome | number:'1.0-0' }}
                        </div>
                        <div class="kpi-mini-unit">TND</div>
                      </div>
                      <div class="col-6">
                        <!-- != null: a margin of exactly 0 is a real result, only a missing one shows a dash. -->
                        <div class="kpi-mini-label">{{ 'kpi.plannedMargin' | transloco }}</div>
                        <div class="fw-bold fs-13"
                             [class.text-success]="k.marge != null && k.marge >= 0"
                             [class.text-danger]="k.marge != null && k.marge < 0">
                          {{ k.marge != null ? ((k.marge >= 0 ? '+' : '') + (k.marge | number:'1.0-0')) : '—' }}
                        </div>
                        @if (k.marge != null) {
                          <div class="fs-10"
                               [class.text-success]="k.marge >= 0" [class.text-danger]="k.marge < 0">
                            {{ cardMarginPct(p, k) | number:'1.1-1' }}%
                          </div>
                        }
                      </div>
                    </div>

                    <!-- Card version of the bullet chart; scale is THIS project's own budget
                         (not the portfolio max) since a card is read alone. -->
                    <div class="mb-2">
                      <div class="d-flex justify-content-between mb-1 text-micro">
                        <span>{{ 'kpi.budgetVsEac' | transloco }}</span>
                        <span>{{ cardEacPct(p, k) | number:'1.0-0' }}{{ 'kpi.pctOfBudget' | transloco }}</span>
                      </div>
                      <div style="position:relative; height:8px; background:var(--surface-3); border-radius:4px; overflow:visible">
                        <!-- EAC bar; min() since templates can't call Math.min directly.
                             Capped at 100, overflow drawn separately below. -->
                        <div [style.width.%]="min(cardEacPct(p,k), 100)"
                             style="position:absolute; left:0; top:0; height:8px; border-radius:4px; overflow:hidden"
                             [class]="'kpi-bar--' + cardScenario(p,k)">
                          <!-- k.eac > 0 guards a 0/0 NaN width, which the browser would drop. -->
                          @if (k.budgetConsome > 0 && k.eac > 0) {
                            <div [style.width.%]="(k.budgetConsome / k.eac) * 100"
                                 style="height:100%; background:rgba(0,0,0,.25)"></div>
                          }
                        </div>
                        <!-- max-width:30% keeps a catastrophic overrun from breaking the grid. -->
                        @if (cardEacPct(p,k) > 100) {
                          <div [style.width.%]="cardEacPct(p,k) - 100"
                               [style.left.%]="100"
                               class="kpi-bar--loss-high"
                               style="position:absolute; top:0; height:8px; border-radius:0 4px 4px 0; max-width:30%"></div>
                        }
                      </div>
                    </div>

                    <!-- Backend sends a 0-1 ratio, *100 here for display; red above 90% as an
                         early warning before the project actually goes over. -->
                    <div class="mb-3">
                      <div class="d-flex justify-content-between mb-1 text-micro">
                        <span>{{ 'kpi.consumptionRate' | transloco }}</span>
                        <span>{{ k.tauxConsommation * 100 | number:'1.0-1' }}%</span>
                      </div>
                      <div class="progress" style="height:4px">
                        <div class="progress-bar"
                             [class]="k.tauxConsommation > 0.9 ? 'bg-danger' : 'bg-primary'"
                             [style.width.%]="min(k.tauxConsommation * 100, 100)"></div>
                      </div>
                    </div>

                    <!-- EVM indicators (F-AFF-13 §5), optional until a monthly snapshot exists;
                         whole block hidden rather than showing three empty boxes. -->
                    @if (k.evPct != null || k.consommeJh != null) {
                      <div class="row g-1 mb-3 fs-11">
                        @if (k.evPct != null) {
                          <div class="col-4 text-center p-1 rounded kpi-stat-box">
                            <div class="kpi-stat-label">EV%</div>
                            <div class="fw-semibold">{{ k.evPct | number:'1.0-0' }}%</div>
                          </div>
                        }
                        @if (k.consommeJh != null) {
                          <div class="col-4 text-center p-1 rounded kpi-stat-box">
                            <div class="kpi-stat-label">{{ 'kpi.consumedMd' | transloco }}</div>
                            <div class="fw-semibold">{{ k.consommeJh | number:'1.0-0' }}</div>
                          </div>
                        }
                        @if (k.rafJh != null) {
                          <div class="col-4 text-center p-1 rounded kpi-stat-box">
                            <div class="kpi-stat-label">{{ 'kpi.remainingMd' | transloco }}</div>
                            <div class="fw-semibold">{{ k.rafJh | number:'1.0-0' }}</div>
                          </div>
                        }
                      </div>
                    }

                    <!-- Server-worded messages, printed as-is (not through Transloco). -->
                    @if (k.warnings?.length) {
                      <div class="alert alert-warning py-2 small mt-2 mb-2 d-flex align-items-start gap-2" role="alert">
                        <i class="bi bi-exclamation-triangle-fill flex-shrink-0 mt-1"></i>
                        <ul class="mb-0 ps-2">
                          @for (w of k.warnings; track w) { <li>{{ w }}</li> }
                        </ul>
                      </div>
                    }

                    <div class="text-end">
                      <a [routerLink]="['/projects', p.id]" class="btn btn-sm btn-outline-primary">
                        {{ 'kpi.detail' | transloco }} <i class="bi bi-arrow-right ms-1"></i>
                      </a>
                    </div>
                  </div>

                } @else if (state()[p.id] === 'error') {
                  <!-- Per-project retry, not page-wide: the calls are independent. Also what
                       shows if the server refused the call for lack of permission. -->
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <i class="bi bi-exclamation-triangle text-warning fs-4"></i>
                    <span>{{ 'kpi.unavailable' | transloco }}</span>
                    <button class="btn btn-sm btn-outline-secondary" (click)="load(p)">
                      <i class="bi bi-arrow-clockwise me-1"></i>{{ 'kpi.retry' | transloco }}
                    </button>
                  </div>
                } @else {
                  <!-- Still loading; min-height matches a filled card so the grid doesn't jump. -->
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <span class="spinner-border spinner-border-sm text-primary"></span>
                    <span>{{ 'kpi.computingShort' | transloco }}</span>
                  </div>
                }
              </div>
            </div>
          }
          @empty {
            <div class="col-12 text-center py-5 text-muted">
              <i class="bi bi-graph-up fs-1 d-block mb-3 opacity-25"></i>
              Aucun projet disponible
            </div>
          }
        </div>
        @if (projects().length > 0) {
          <div class="card mt-3">
            <app-pagination
              [page]="cardsPage()" [pageSize]="cardsPageSize()" [total]="projects().length"
              [pageSizeOptions]="[9, 18, 36]"
              (pageChange)="cardsPage.set($event)"
              (pageSizeChange)="cardsPageSize.set($event); cardsPage.set(0)" />
          </div>
        }
      }

    </div>
  `
})
// Signals (not plain fields) so a late KPI answer redraws only what depends on it.
// implements OnInit: HTTP-in-constructor is harder to test and can race injected services.
export class KpiComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);

  projects  = signal<Project[]>([]);

  // Keyed by id (not an array parallel to 'projects'): answers arrive out of order, and a
  // parallel-array write could land at the wrong index — the worst bug on a financial screen.
  kpiMap    = signal<Record<number, KpiResponse>>({});

  state     = signal<Record<number, LoadState>>({});

  // 'portfolio' default: the aggregated charts answer the first question a manager asks.
  view      = signal<ChartView>('portfolio');

  // Client-side pagination: listAll() loads everything up front, so paging just slices an
  // array. Separate counters per view so cards paging doesn't move the charts too.
  portfolioPage     = signal(0);
  portfolioPageSize = signal(10);
  cardsPage         = signal(0);
  cardsPageSize     = signal(9);   // 9 = three full rows of three cards

  // Math.min clamps the page index as the row count shrinks/grows while KPI answers arrive.
  readonly pagedPortfolioRows = computed(() => {
    const rows = this.portfolioRows();
    const size = this.portfolioPageSize();
    const pages = Math.max(1, Math.ceil(rows.length / size));
    const page = Math.min(this.portfolioPage(), pages - 1);
    return rows.slice(page * size, page * size + size);
  });

  /** Same slicing, for the cards view, on the full project list. */
  readonly pagedProjects = computed(() => {
    const list = this.projects();
    const size = this.cardsPageSize();
    const pages = Math.max(1, Math.ceil(list.length / size));
    const page = Math.min(this.cardsPage(), pages - 1);
    return list.slice(page * size, page * size + size);
  });

  // One call per project (not a bulk endpoint): the server checks permission and project
  // scope per call, so each card fills independently and one refusal doesn't fail the page.
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      list.forEach(p => this.load(p));
    });
  }

  // Also the Retry button handler. Fresh object on every write ({ ...s, ... }): a signal
  // compares by reference, so mutating the map in place wouldn't trigger a redraw. On error,
  // only state changes — kpiMap keeps an earlier successful result.
  load(p: Project): void {
    this.state.update(s => ({ ...s, [p.id]: 'loading' }));
    this.projectSvc.getLiveKpi(p.id).subscribe({
      next: k => {
        this.kpiMap.update(m => ({ ...m, [p.id]: k }));
        this.state.update(s => ({ ...s, [p.id]: 'done' }));
      },
      error: () => this.state.update(s => ({ ...s, [p.id]: 'error' }))
    });
  }

  // ── Portfolio chart computation ────────────────────────────────────

  // Single place where the chart maths lives, so all charts and cards agree on the same
  // numbers; a template can't filter/scale/sort without recomputing on every redraw.
  readonly portfolioRows = computed((): ChartRow[] => {
    // k != null: skip projects still loading/errored (must not count as zero).
    // Skip DRAFT/CANCELLED: no money at stake, would drag the portfolio margin down.
    // EAC > 0: nothing planned yet, no bar to draw.
    const items = this.projects()
      .map(p => ({ p, k: this.kpiMap()[p.id] }))
      .filter(x => x.k != null
        && x.p.status !== 'DRAFT' && x.p.status !== 'CANCELLED'
        && (x.k.eac ?? 0) > 0);  // exclude projects with no workload data yet

    if (!items.length) return [];

    // Largest EAC or budget of the selection becomes 100% of the chart, so all bars share
    // one scale.
    const maxVal = items.reduce((m, x) => {
      const budget = x.p.budgetTnd ?? 0;
      return Math.max(m, x.k.eac ?? 0, budget);
    }, 0);

    if (maxVal === 0) return [];

    return items
      .map(x => {
        // Fallback budget: margin = budget - EAC, so budget = EAC + margin.
        const budget   = x.p.budgetTnd ?? ((x.k.eac ?? 0) + (x.k.marge ?? 0));
        const eac      = x.k.eac ?? 0;
        const consumed = x.k.budgetConsome ?? 0;
        const marge    = x.k.marge ?? 0;
        const marginPct = budget > 0 ? (marge / budget) * 100 : 0;

        // Widths, all against the shared portfolio scale (maxVal = 100%).
        const budgetPct           = (budget / maxVal) * 100;
        const eacPct              = (eac    / maxVal) * 100;
        // EAC bar cut at the budget mark: colored inside, red beyond it.
        const eacWithinBudgetPct  = Math.min(eacPct, budgetPct);
        const eacOverBudgetPct    = Math.max(0, eacPct - budgetPct);
        // Relative to the EAC bar itself (CSS child width), not the portfolio scale.
        const consumedInEacPct    = eac > 0 ? Math.min((consumed / eac) * 100, 100) : 0;
        const scenario            = this.toScenario(marginPct);

        return { p: x.p, k: x.k, budget, eac, consumed, marge, marginPct,
                 budgetPct, eacWithinBudgetPct, eacOverBudgetPct, consumedInEacPct, scenario };
      })
      // By percentage, not amount: a small project at 30% is healthier than a huge one at 2%.
      .sort((a, b) => b.marginPct - a.marginPct);
  });

  // Null (not zeros) when nothing to add up, so the template shows the skeleton instead of
  // a misleadingly confident "0 TND". Sums portfolioRows() so totals always match the charts.
  readonly aggregates = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;

    const totalBudget     = rows.reduce((s, r) => s + r.budget, 0);
    const totalEac        = rows.reduce((s, r) => s + r.eac,    0);
    const totalMarge      = rows.reduce((s, r) => s + r.marge,  0);
    const profitCount     = rows.filter(r => r.marge >= 0).length;
    // From TOTALS, not an average of per-project rates: an average would let a tiny +40%
    // project hide a huge -5% one.
    const globalMarginPct = totalBudget > 0 ? (totalMarge / totalBudget) * 100 : 0;

    return { totalBudget, totalEac, totalMarge, profitCount, total: rows.length, globalMarginPct };
  });

  // Five scenarios folded into three (profit/break-even/deficit): five segments on a 12px
  // bar would be unreadable stripes.
  readonly health = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;
    const profit  = rows.filter(r => r.scenario === 'profit-high' || r.scenario === 'profit-mid').length;
    const breakEven = rows.filter(r => r.scenario === 'break-even').length;
    const deficit   = rows.filter(r => r.scenario === 'loss-mid' || r.scenario === 'loss-high').length;
    const total = rows.length;
    return {
      profit, breakEven, deficit, total,
      profitPct: (profit / total) * 100,
      breakPct:   (breakEven / total) * 100,
      deficitPct: (deficit / total) * 100,
    };
  });

  // ── Margin breakdown chart helper ─────────────────────────────────

  // Scale is the portfolio's largest |margin|, so a 40k gain and a 40k loss draw equal-length
  // bars on opposite sides. Seed 1 (not 0) avoids a 0/0 NaN when every margin is exactly 0.
  absMarginBarPct(row: ChartRow): number {
    const rows = this.portfolioRows();
    const maxAbsMarge = rows.reduce((m, r) => Math.max(m, Math.abs(r.marge)), 1);
    return (Math.abs(row.marge) / maxAbsMarge) * 100;
  }

  // ── Card-level helpers ─────────────────────────────────────────────

  // Plain method, not computed(): needs arguments. Same budget fallback as elsewhere, so a
  // card and its portfolio-chart line never disagree.
  cardMarginPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.marge ?? 0) / budget) * 100 : 0;
  }

  // 100 = EAC exactly eats the budget; used for both bar width and the printed figure.
  cardEacPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.eac ?? 0) / budget) * 100 : 0;
  }

  // Same toScenario() thresholds as the portfolio chart, so a project can't be green in
  // one view and orange in the other.
  cardScenario(p: Project, k: KpiResponse): string {
    return this.toScenario(this.cardMarginPct(p, k));
  }

  // Raw hex (not a CSS class): [style.border-left] needs a real color. 'transparent' while
  // unloaded, so an unknown project isn't painted as healthy. Mirrors .kpi-bar--* hex values.
  scenarioColor(p: Project): string {
    const k = this.kpiMap()[p.id];
    if (!k) return 'transparent';
    const pct = this.cardMarginPct(p, k);
    if (pct >= 20)  return '#16a34a';
    if (pct >= 5)   return '#2563eb';
    if (pct >= -5)  return '#d97706';
    if (pct >= -15) return '#ea580c';
    return '#dc2626';
  }

  // ── Utility ───────────────────────────────────────────────────────

  // Thresholds: >=20% comfortable gain, 5-20% normal gain, -5% to 5% break-even (a band, so
  // +0.4% doesn't flip green/red on rounding), -5% to -15% moderate loss, below = heavy loss.
  private toScenario(marginPct: number): ChartRow['scenario'] {
    if (marginPct >= 20)  return 'profit-high';
    if (marginPct >= 5)   return 'profit-mid';
    if (marginPct >= -5)  return 'break-even';
    if (marginPct >= -15) return 'loss-mid';
    return 'loss-high';
  }


  // ?? 'badge-draft': neutral fallback for a status this map doesn't know.
  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }

  // Templates can't reach the global Math object directly.
  min(a: number, b: number): number { return Math.min(a, b); }
}
