import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { Project, PROJECT_STATUS_LABELS } from '../../core/models/project.model';
import { KpiResponse } from '../../core/models/kpi.model';
import { PaginationComponent } from '../../shared/pagination/pagination.component';

type LoadState = 'loading' | 'done' | 'error';
type ChartView = 'portfolio' | 'cards';

interface ChartRow {
  p: Project;
  k: KpiResponse;
  budget: number;
  eac: number;
  consumed: number;
  marge: number;
  marginPct: number;
  budgetPct: number;
  eacWithinBudgetPct: number;
  eacOverBudgetPct: number;
  consumedInEacPct: number;
  scenario: 'profit-high' | 'profit-mid' | 'break-even' | 'loss-mid' | 'loss-high';
}

@Component({
  selector: 'app-kpi',
  standalone: true,
  imports: [CommonModule, RouterLink, PaginationComponent],
  styles: [`
    .chart-row:hover { background: var(--surface, var(--bg)); }
    .kpi-bar--profit-high  { background: #16a34a; }
    .kpi-bar--profit-mid   { background: #2563eb; }
    .kpi-bar--break-even   { background: #d97706; }
    .kpi-bar--loss-mid     { background: #ea580c; }
    .kpi-bar--loss-high    { background: #dc2626; }
    .badge-profit-high  { background:#dcfce7; color:#15803d; font-weight:600; }
    .badge-profit-mid   { background:#dbeafe; color:#1d4ed8; font-weight:600; }
    .badge-break-even   { background:#fef3c7; color:#92400e; font-weight:600; }
    .badge-loss-mid     { background:#ffedd5; color:#9a3412; font-weight:600; }
    .badge-loss-high    { background:#fee2e2; color:#991b1b; font-weight:600; }
    :host-context([data-theme="dark"]) .badge-profit-high { background:rgba(22,163,74,.18);  color:#86efac; }
    :host-context([data-theme="dark"]) .badge-profit-mid  { background:rgba(37,99,235,.18);  color:#93c5fd; }
    :host-context([data-theme="dark"]) .badge-break-even  { background:rgba(217,119,6,.18);  color:#fcd34d; }
    :host-context([data-theme="dark"]) .badge-loss-mid    { background:rgba(234,88,12,.18);  color:#fdba74; }
    :host-context([data-theme="dark"]) .badge-loss-high   { background:rgba(220,38,38,.18);  color:#fca5a5; }
    .legend-dot { width:10px; height:10px; border-radius:50%; display:inline-block; }
    .legend-swatch { width:10px; height:10px; border-radius:2px; display:inline-block; }
    .legend-inline { display:inline-flex; align-items:center; gap:4px; }
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

    /* Portfolio health bar */
    .pf-health { display:flex; height:12px; border-radius:7px; overflow:hidden; background:var(--surface-3); }
    .pf-seg { height:100%; transition:width .5s ease; }
    .pf-seg:first-child { border-radius:7px 0 0 7px; }
    .pf-seg:last-child { border-radius:0 7px 7px 0; }

    /* Skeleton */
    .skl { border-radius:8px; background:linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size:400% 100%; animation:skla 1.2s ease infinite; }
    @keyframes skla { 0%{background-position:100% 0} 100%{background-position:-100% 0} }
    @media (prefers-reduced-motion: reduce){ .skl{animation:none} .pf-seg{transition:none} .seg-btn{transition:none} }
  `],
  template: `
    <!-- TOPBAR -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-graph-up fs-13" style="color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">Tableau de bord KPI</span>
      </div>
      <div class="tb-right">
        <div class="seg">
          <button class="seg-btn" [class.seg-on]="view()==='portfolio'" (click)="view.set('portfolio')">
            <i class="bi bi-bar-chart-line me-1"></i>Portefeuille
          </button>
          <button class="seg-btn" [class.seg-on]="view()==='cards'" (click)="view.set('cards')">
            <i class="bi bi-grid me-1"></i>Projets
          </button>
        </div>
      </div>
    </div>

    <div class="page-body">

      <!-- Page header -->
      <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
        <div>
          <h1 class="page-title">Indicateurs de performance</h1>
          <p class="page-subtitle">Marge, coût estimé final (EAC) et rentabilité de votre portefeuille projets.</p>
        </div>
      </div>

      <!-- ── Initial load: skeleton ───────────────────────────────── -->
      @if (projects().length === 0) {
        <div class="row g-3 mb-4">
          @for (i of [1,2,3,4]; track i) {
            <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
          }
        </div>
        <div class="card"><div class="p-3">@for (i of [1,2,3,4,5]; track i) { <div class="skl mb-2" style="height:28px"></div> }</div></div>
      }

      <!-- ════════════════ VUE PORTEFEUILLE ═══════════════════════════ -->
      @if (view() === 'portfolio' && projects().length > 0) {

        <!-- Aggregate metric cards -->
        @if (aggregates(); as agg) {
          <div class="row g-3 mb-3">
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon metric-icon--brand"><i class="bi bi-briefcase-fill"></i></div>
                  <span class="text-caption">portefeuille</span>
                </div>
                <div class="metric-label">Budget total</div>
                <div class="metric-value">{{ agg.totalBudget | number:'1.0-0' }} <span class="kpi-unit">TND</span></div>
              </div>
            </div>
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon" [style.background]="agg.totalEac > agg.totalBudget ? 'var(--c-danger-dim,rgba(220,38,38,.12))' : 'var(--c-success-dim)'"
                       [style.color]="agg.totalEac > agg.totalBudget ? 'var(--c-danger,#dc2626)' : 'var(--c-success)'"><i class="bi bi-graph-up-arrow"></i></div>
                  <span class="fs-11" [class.text-danger]="agg.totalEac > agg.totalBudget" [class.text-success]="agg.totalEac <= agg.totalBudget">
                    {{ agg.totalEac <= agg.totalBudget ? 'dans le budget' : 'dépassement' }}
                  </span>
                </div>
                <div class="metric-label">EAC total</div>
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
                <div class="metric-label">Marge nette globale</div>
                <div class="metric-value" [class.text-success]="agg.totalMarge >= 0" [class.text-danger]="agg.totalMarge < 0">
                  {{ agg.totalMarge >= 0 ? '+' : '' }}{{ agg.totalMarge | number:'1.0-0' }} <span class="kpi-unit">TND</span>
                </div>
              </div>
            </div>
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon metric-icon--teal"><i class="bi bi-check2-circle"></i></div>
                  <span class="text-caption">sur {{ agg.total }}</span>
                </div>
                <div class="metric-label">Projets rentables</div>
                <div class="metric-value text-success">{{ agg.profitCount }}<span class="kpi-unit"> / {{ agg.total }}</span></div>
              </div>
            </div>
          </div>

          <!-- Portfolio health bar -->
          @if (health(); as h) {
            <div class="card p-3 mb-4">
              <div class="d-flex justify-content-between align-items-center mb-2">
                <span class="fs-13 fw-semibold"><i class="bi bi-heart-pulse me-1" style="color:var(--c-brand)"></i>Santé du portefeuille</span>
                <span class="text-caption">{{ h.total }} projets évalués</span>
              </div>
              <div class="pf-health" role="img"
                   [attr.aria-label]="'Santé du portefeuille : ' + h.profit + ' rentables, ' + h.breakEven + ' à l\\'équilibre, ' + h.deficit + ' déficitaires sur ' + h.total + ' projets.'">
                @if (h.profit)   { <div class="pf-seg kpi-bar--profit-high" [style.width.%]="h.profitPct"   [title]="h.profit + ' rentables'"></div> }
                @if (h.breakEven){ <div class="pf-seg kpi-bar--break-even" [style.width.%]="h.breakPct"    [title]="h.breakEven + ' à l\\'équilibre'"></div> }
                @if (h.deficit)  { <div class="pf-seg kpi-bar--loss-high" [style.width.%]="h.deficitPct"  [title]="h.deficit + ' déficitaires'"></div> }
              </div>
              <div class="d-flex gap-3 mt-2 flex-wrap fs-11" style="color:var(--text-2)">
                <span><span class="legend-dot me-1 kpi-bar--profit-high"></span>Rentables <b>{{ h.profit }}</b></span>
                <span><span class="legend-dot me-1 kpi-bar--break-even"></span>À l'équilibre <b>{{ h.breakEven }}</b></span>
                <span><span class="legend-dot me-1 kpi-bar--loss-high"></span>Déficitaires <b>{{ h.deficit }}</b></span>
              </div>
            </div>
          }
        } @else {
          <!-- KPIs still computing -->
          <div class="row g-3 mb-4">
            @for (i of [1,2,3,4]; track i) {
              <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
            }
          </div>
        }

        <!-- Portfolio bullet chart -->
        <div class="card mb-4">
          <div class="card-header justify-content-between">
            <div>
              <i class="bi bi-bar-chart-line me-2"></i>Budget vs EAC — comparaison portefeuille
            </div>
            <div class="d-flex gap-3 align-items-center fs-11">
              <span><span class="legend-dot kpi-bar--profit-high"></span> Rentable</span>
              <span><span class="legend-dot kpi-bar--break-even"></span> À l'équilibre</span>
              <span><span class="legend-dot kpi-bar--loss-high"></span> Déficitaire</span>
              <span style="color:var(--text-3)">| = Budget contractuel</span>
            </div>
          </div>

          @if (portfolioRows().length === 0) {
            <div class="card-body text-center text-muted py-4">
              <span class="spinner-border spinner-border-sm me-2"></span>Calcul des KPI en cours…
            </div>
          } @else {
            <div class="card-body p-0">
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
                    <div class="text-micro" style="margin-top:1px">
                      {{ statusLabel(row.p.status) }}
                      @if (row.p.client) { · {{ row.p.client }} }
                    </div>
                  </div>

                  <!-- Bullet chart track -->
                  <div style="position:relative; height:20px; display:flex; align-items:center">
                    <!-- Gray budget reference bar -->
                    <div [style.width.%]="row.budgetPct"
                         style="position:absolute; left:0; top:4px; height:12px; background:var(--surface-3); border-radius:6px; min-width:4px"></div>

                    <!-- EAC bar — within-budget portion -->
                    @if (row.eacWithinBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacWithinBudgetPct"
                           [class]="'kpi-bar--' + row.scenario"
                           style="position:absolute; left:0; top:4px; height:12px; border-radius:6px 0 0 6px; overflow:hidden; min-width:4px">
                        <!-- Consumed fill (darker alpha) -->
                        @if (row.consumedInEacPct > 0.5) {
                          <div [style.width.%]="row.consumedInEacPct"
                               style="height:100%; background:rgba(0,0,0,.22); border-radius:inherit"></div>
                        }
                      </div>
                    }

                    <!-- EAC over-budget extension (red) -->
                    @if (row.eacOverBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacOverBudgetPct"
                           [style.left.%]="row.budgetPct"
                           class="kpi-bar--loss-high"
                           style="position:absolute; top:4px; height:12px; border-radius:0 6px 6px 0; min-width:4px"></div>
                    }

                    <!-- Budget marker line -->
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
                        Consommé {{ row.consumed | number:'1.0-0' }}
                      </div>
                    }
                  </div>
                </div>
              }
            </div>

            <app-pagination
              [page]="portfolioPage()" [pageSize]="portfolioPageSize()" [total]="portfolioRows().length"
              (pageChange)="portfolioPage.set($event)"
              (pageSizeChange)="portfolioPageSize.set($event); portfolioPage.set(0)" />

            <!-- Chart legend footer -->
            <div class="card-footer text-caption d-flex flex-wrap" style="gap:1.5rem">
              <span><b>Barre grise</b> = budget contractuel</span>
              <span><b>Couleur</b> = EAC (coût estimé final)</span>
              <span><b>Partie sombre</b> = déjà consommé</span>
              <span><b>Rouge au-delà du trait</b> = dépassement budget</span>
            </div>
          }
        </div>

        <!-- Margin breakdown bar chart — diverging (zero centred) -->
        @if (portfolioRows().length > 0) {
          <div class="card mb-4">
            <div class="card-header">
              <i class="bi bi-currency-exchange me-2"></i>Marge par projet — vue comparative
            </div>
            <div class="card-body p-0">
              @for (row of pagedPortfolioRows(); track row.p.id; let last = $last) {
                <div class="chart-row-grid"
                     style="padding:.5rem 1.25rem"
                     [style.border-bottom]="!last ? '1px solid var(--border)' : 'none'">
                  <div class="monospace fs-11" style="color:var(--text-2)">{{ row.p.code }}</div>

                  <!-- Diverging bar: left half = loss, right half = profit, centre line = zero -->
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
                <span class="legend-swatch kpi-bar--loss-high"></span> Déficit
              </span>
              <span class="legend-inline" style="margin-left:1rem">
                <span class="legend-swatch kpi-bar--profit-high"></span> Bénéfice
              </span>
              <span style="margin-left:1rem">| = zéro (seuil de rentabilité)</span>
            </div>
          </div>
        }
      }

      <!-- ════════════════ VUE DÉTAIL CARTES ══════════════════════════ -->
      @if (view() === 'cards') {
        <div class="row g-3">
          @for (p of pagedProjects(); track p.id) {
            <div class="col-md-6 col-xl-4">
              <div class="card h-100" [style.border-left]="kpiMap()[p.id] ? '3px solid ' + scenarioColor(p) : ''">
                <div class="card-header justify-content-between">
                  <div class="me-2">
                    <div class="fw-bold monospace fs-12">{{ p.code }}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px">{{ p.name }}</div>
                  </div>
                  <span [class]="badge(p.status)">{{ statusLabel(p.status) }}</span>
                </div>

                @if (kpiMap()[p.id]; as k) {
                  <div class="card-body p-3">
                    <!-- Main metrics grid -->
                    <div class="row g-2 text-center mb-3">
                      <div class="col-6">
                        <div class="kpi-mini-label">Budget</div>
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
                        <div class="kpi-mini-label">Consommé</div>
                        <div class="fw-bold text-warning fs-13">
                          {{ k.budgetConsome | number:'1.0-0' }}
                        </div>
                        <div class="kpi-mini-unit">TND</div>
                      </div>
                      <div class="col-6">
                        <div class="kpi-mini-label">Marge prévue</div>
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

                    <!-- Budget vs EAC mini bar -->
                    <div class="mb-2">
                      <div class="d-flex justify-content-between mb-1 text-micro">
                        <span>Budget vs EAC</span>
                        <span>{{ cardEacPct(p, k) | number:'1.0-0' }}% du budget</span>
                      </div>
                      <div style="position:relative; height:8px; background:var(--surface-3); border-radius:4px; overflow:visible">
                        <!-- EAC bar -->
                        <div [style.width.%]="min(cardEacPct(p,k), 100)"
                             style="position:absolute; left:0; top:0; height:8px; border-radius:4px; overflow:hidden"
                             [class]="'kpi-bar--' + cardScenario(p,k)">
                          <!-- Consumed inner fill -->
                          @if (k.budgetConsome > 0 && k.eac > 0) {
                            <div [style.width.%]="(k.budgetConsome / k.eac) * 100"
                                 style="height:100%; background:rgba(0,0,0,.25)"></div>
                          }
                        </div>
                        <!-- Over-budget extension -->
                        @if (cardEacPct(p,k) > 100) {
                          <div [style.width.%]="cardEacPct(p,k) - 100"
                               [style.left.%]="100"
                               class="kpi-bar--loss-high"
                               style="position:absolute; top:0; height:8px; border-radius:0 4px 4px 0; max-width:30%"></div>
                        }
                      </div>
                    </div>

                    <!-- Taux de consommation bar -->
                    <div class="mb-3">
                      <div class="d-flex justify-content-between mb-1 text-micro">
                        <span>Taux consommation</span>
                        <span>{{ k.tauxConsommation * 100 | number:'1.0-1' }}%</span>
                      </div>
                      <div class="progress" style="height:4px">
                        <div class="progress-bar"
                             [class]="k.tauxConsommation > 0.9 ? 'bg-danger' : 'bg-primary'"
                             [style.width.%]="min(k.tauxConsommation * 100, 100)"></div>
                      </div>
                    </div>

                    <!-- EVM indicators (if available) -->
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
                            <div class="kpi-stat-label">Consommé JH</div>
                            <div class="fw-semibold">{{ k.consommeJh | number:'1.0-0' }}</div>
                          </div>
                        }
                        @if (k.rafJh != null) {
                          <div class="col-4 text-center p-1 rounded kpi-stat-box">
                            <div class="kpi-stat-label">RAF JH</div>
                            <div class="fw-semibold">{{ k.rafJh | number:'1.0-0' }}</div>
                          </div>
                        }
                      </div>
                    }

                    <!-- Warnings -->
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
                        Détail <i class="bi bi-arrow-right ms-1"></i>
                      </a>
                    </div>
                  </div>

                } @else if (state()[p.id] === 'error') {
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <i class="bi bi-exclamation-triangle text-warning fs-4"></i>
                    <span>Indicateurs indisponibles</span>
                    <button class="btn btn-sm btn-outline-secondary" (click)="load(p)">
                      <i class="bi bi-arrow-clockwise me-1"></i>Réessayer
                    </button>
                  </div>
                } @else {
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <span class="spinner-border spinner-border-sm text-primary"></span>
                    <span>Calcul en cours…</span>
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
export class KpiComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);

  projects  = signal<Project[]>([]);
  kpiMap    = signal<Record<number, KpiResponse>>({});
  state     = signal<Record<number, LoadState>>({});
  view      = signal<ChartView>('portfolio');

  // Pagination — portfolio charts and project cards (client-side)
  portfolioPage     = signal(0);
  portfolioPageSize = signal(10);
  cardsPage         = signal(0);
  cardsPageSize     = signal(9);

  readonly pagedPortfolioRows = computed(() => {
    const rows = this.portfolioRows();
    const size = this.portfolioPageSize();
    const pages = Math.max(1, Math.ceil(rows.length / size));
    const page = Math.min(this.portfolioPage(), pages - 1);
    return rows.slice(page * size, page * size + size);
  });

  readonly pagedProjects = computed(() => {
    const list = this.projects();
    const size = this.cardsPageSize();
    const pages = Math.max(1, Math.ceil(list.length / size));
    const page = Math.min(this.cardsPage(), pages - 1);
    return list.slice(page * size, page * size + size);
  });

  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      list.forEach(p => this.load(p));
    });
  }

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

  readonly portfolioRows = computed((): ChartRow[] => {
    const items = this.projects()
      .map(p => ({ p, k: this.kpiMap()[p.id] }))
      .filter(x => x.k != null
        && x.p.status !== 'DRAFT' && x.p.status !== 'CANCELLED'
        && (x.k.eac ?? 0) > 0);  // exclude projects with no workload data yet

    if (!items.length) return [];

    const maxVal = items.reduce((m, x) => {
      const budget = x.p.budgetTnd ?? 0;
      return Math.max(m, x.k.eac ?? 0, budget);
    }, 0);

    if (maxVal === 0) return [];

    return items
      .map(x => {
        const budget   = x.p.budgetTnd ?? ((x.k.eac ?? 0) + (x.k.marge ?? 0));
        const eac      = x.k.eac ?? 0;
        const consumed = x.k.budgetConsome ?? 0;
        const marge    = x.k.marge ?? 0;
        const marginPct = budget > 0 ? (marge / budget) * 100 : 0;

        const budgetPct           = (budget / maxVal) * 100;
        const eacPct              = (eac    / maxVal) * 100;
        const eacWithinBudgetPct  = Math.min(eacPct, budgetPct);
        const eacOverBudgetPct    = Math.max(0, eacPct - budgetPct);
        const consumedInEacPct    = eac > 0 ? Math.min((consumed / eac) * 100, 100) : 0;
        const scenario            = this.toScenario(marginPct);

        return { p: x.p, k: x.k, budget, eac, consumed, marge, marginPct,
                 budgetPct, eacWithinBudgetPct, eacOverBudgetPct, consumedInEacPct, scenario };
      })
      .sort((a, b) => b.marginPct - a.marginPct);
  });

  readonly aggregates = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;

    const totalBudget     = rows.reduce((s, r) => s + r.budget, 0);
    const totalEac        = rows.reduce((s, r) => s + r.eac,    0);
    const totalMarge      = rows.reduce((s, r) => s + r.marge,  0);
    const profitCount     = rows.filter(r => r.marge >= 0).length;
    const globalMarginPct = totalBudget > 0 ? (totalMarge / totalBudget) * 100 : 0;

    return { totalBudget, totalEac, totalMarge, profitCount, total: rows.length, globalMarginPct };
  });

  /** Portfolio health split by scenario for the at-a-glance segmented bar. */
  readonly health = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;
    const profit    = rows.filter(r => r.scenario === 'profit-high' || r.scenario === 'profit-mid').length;
    const breakEven = rows.filter(r => r.scenario === 'break-even').length;
    const deficit   = rows.filter(r => r.scenario === 'loss-mid' || r.scenario === 'loss-high').length;
    const total = rows.length;
    return {
      profit, breakEven, deficit, total,
      profitPct:  (profit / total) * 100,
      breakPct:   (breakEven / total) * 100,
      deficitPct: (deficit / total) * 100,
    };
  });

  // ── Margin breakdown chart helper ─────────────────────────────────

  absMarginBarPct(row: ChartRow): number {
    const rows = this.portfolioRows();
    const maxAbsMarge = rows.reduce((m, r) => Math.max(m, Math.abs(r.marge)), 1);
    return (Math.abs(row.marge) / maxAbsMarge) * 100;
  }

  // ── Card-level helpers ─────────────────────────────────────────────

  cardMarginPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.marge ?? 0) / budget) * 100 : 0;
  }

  cardEacPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.eac ?? 0) / budget) * 100 : 0;
  }

  cardScenario(p: Project, k: KpiResponse): string {
    return this.toScenario(this.cardMarginPct(p, k));
  }

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

  private toScenario(marginPct: number): ChartRow['scenario'] {
    if (marginPct >= 20)  return 'profit-high';
    if (marginPct >= 5)   return 'profit-mid';
    if (marginPct >= -5)  return 'break-even';
    if (marginPct >= -15) return 'loss-mid';
    return 'loss-high';
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

  min(a: number, b: number): number { return Math.min(a, b); }
}
