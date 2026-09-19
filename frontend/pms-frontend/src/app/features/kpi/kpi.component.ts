import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { ProjectService } from '../../core/services/project.service';
import { Project } from '../../core/models/project.model';
import { KpiResponse } from '../../core/models/kpi.model';
import { PaginationComponent } from '../../shared/pagination/pagination.component';

/* ============================================================================
 * FILE: kpi.component.ts
 *
 * WHAT THIS FILE IS
 * The "KPI" screen of the application: one standalone Angular page that shows
 * the financial health of every project, either as portfolio-wide charts or as
 * one card per project.
 *
 * WHERE IT SITS IN THE FLOW
 * - The router opens this component when the user goes to the KPI page.
 * - It calls ProjectService.listAll() to get the list of projects, then, for
 *   each project, ProjectService.getLiveKpi(id). Those two calls go to the
 *   backend REST API (/api/projects and /api/projects/{id}/kpi).
 * - "Live" KPI means the backend computes the amounts at read time from the
 *   current data; nothing here is a stored total. That is the rule for DI
 *   (Devis Interne = internal quote) amounts: computed amounts are derived when
 *   read, never kept in a column. So this screen never caches a number on the
 *   server side; it just displays what the API returns right now.
 * - It renders with <app-pagination> (shared component) and translates every
 *   label through Transloco.
 *
 * WHY IT EXISTS
 * Without it, the numbers produced by the KPI engine (budget, EAC, consumed
 * budget, margin, EVM indicators) would only be visible project by project,
 * inside each project detail page. A manager could not answer "how is the whole
 * portfolio doing?" without opening every project one by one and adding the
 * figures by hand.
 *
 * VOCABULARY USED EVERYWHERE BELOW (all amounts are in TND, Tunisian dinar)
 * - budget    : the money sold to the client for the project (Project.budgetTnd).
 * - EAC       : "Estimate At Completion", the total cost the project is now
 *               expected to reach, consumed part included.
 * - consumed  : the part of the cost already spent (budgetConsome).
 * - marge     : margin = budget - EAC. Positive means the project earns money.
 * - EVM       : "Earned Value Management", a standard way to measure progress in
 *               money terms (EV %, consumed man-days, remaining man-days).
 *               These extra fields come from the F-AFF-13 specification.
 * - JH / MD   : "jour-homme" / man-day, one person working one day.
 *
 * SECURITY NOTE
 * There is no permission check in this file, and that is on purpose. In this
 * project authorization is dynamic and permission-based, and it is enforced on
 * the SERVER, on service methods (@PreAuthorize("hasAuthority('...')")), never
 * on a role name and never in the browser. The backend simply refuses the KPI
 * call if the user may not read it, and the card then shows its error state.
 * ========================================================================== */

// The three states a single project's KPI request can be in.
// WHY a union of strings and not a boolean: a boolean cannot tell "still
// loading" apart from "the call failed". Without the 'error' value the card of
// a project whose KPI call returned 500 would spin forever and the user would
// never get the "Retry" button.
type LoadState = 'loading' | 'done' | 'error';

// The two ways to look at the same data: aggregated charts, or one card per
// project. Kept as a string union so the template can compare with === and the
// compiler rejects a typo such as 'portefolio'.
type ChartView = 'portfolio' | 'cards';

/**
 * One line of the portfolio charts: a project plus its KPI plus every number
 * the template needs, already computed.
 *
 * WHY a pre-computed row object instead of doing the maths in the template:
 * an Angular template re-evaluates a method call on every change detection
 * pass. Putting percentages here means they are computed once inside a
 * computed() signal. Without it, the bullet chart would recompute several
 * divisions per bar on every mouse move over the page.
 */
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

// standalone: true means the component declares its own dependencies in
// `imports` and needs no NgModule. WHY: the whole front end is built this way,
// so the page can be lazy-loaded straight from the router. Without it, this
// component would have to be declared in some module, and forgetting to declare
// it there would fail at runtime with "app-kpi is not a known element".
// `imports` must list every directive/pipe used in the template below:
// CommonModule for the number pipe, RouterLink for the "Detail" link,
// PaginationComponent for <app-pagination>, TranslocoModule for the | transloco
// pipe. Remove one and the template stops compiling.
@Component({
  selector: 'app-kpi',
  standalone: true,
  imports: [CommonModule, RouterLink, PaginationComponent, TranslocoModule],
  /* Styles are scoped to this component only: Angular adds a unique attribute to
     every element and rewrites these selectors. WHY that matters here: class
     names such as .seg or .skl are very generic, and without this scoping they
     would leak and restyle unrelated screens.
     Colours come from CSS variables of the global design system (--text-1,
     --border, --surface...) so light and dark themes both work. The few raw hex
     values below are the fixed KPI health colours, deliberately the same in both
     themes so a green bar never turns into something else in dark mode. */
  styles: [`
    .chart-row:hover { background: var(--surface, var(--bg)); }
    /* The five health colours, from "earns a lot" to "loses a lot". A class name
       is built in the template as 'kpi-bar--' + row.scenario, so these five names
       must stay exactly in step with the scenario union of ChartRow. Rename one
       here and the matching bars silently lose their colour. */
    .kpi-bar--profit-high  { background: #16a34a; }
    .kpi-bar--profit-mid   { background: #2563eb; }
    .kpi-bar--break-even   { background: #d97706; }
    .kpi-bar--loss-mid     { background: #ea580c; }
    .kpi-bar--loss-high    { background: #dc2626; }
    /* Same five health levels, but as a soft pill badge: pale background, dark
       text. Built the same way in the template ('badge-' + row.scenario). */
    .badge-profit-high  { background:#dcfce7; color:#15803d; font-weight:600; }
    .badge-profit-mid   { background:#dbeafe; color:#1d4ed8; font-weight:600; }
    .badge-break-even   { background:#fef3c7; color:#92400e; font-weight:600; }
    .badge-loss-mid     { background:#ffedd5; color:#9a3412; font-weight:600; }
    .badge-loss-high    { background:#fee2e2; color:#991b1b; font-weight:600; }
    /* Dark theme overrides. :host-context() looks at an ANCESTOR of this
       component (here the <html> element carrying data-theme="dark") and still
       applies the rule inside the component. WHY: the theme flag lives outside
       this component, so a plain selector could never reach it. The pale badge
       backgrounds above are almost white; without these four lines a badge in
       dark mode would be a bright white block with dark text, unreadable next to
       the dark page. rgba(...,.18) keeps just a tint of the colour instead. */
    :host-context([data-theme="dark"]) .badge-profit-high { background:rgba(22,163,74,.18);  color:#86efac; }
    :host-context([data-theme="dark"]) .badge-profit-mid  { background:rgba(37,99,235,.18);  color:#93c5fd; }
    :host-context([data-theme="dark"]) .badge-break-even  { background:rgba(217,119,6,.18);  color:#fcd34d; }
    :host-context([data-theme="dark"]) .badge-loss-mid    { background:rgba(234,88,12,.18);  color:#fdba74; }
    :host-context([data-theme="dark"]) .badge-loss-high   { background:rgba(220,38,38,.18);  color:#fca5a5; }
    .legend-dot { width:10px; height:10px; border-radius:50%; display:inline-block; }
    .legend-swatch { width:10px; height:10px; border-radius:2px; display:inline-block; }
    .legend-inline { display:inline-flex; align-items:center; gap:4px; }
    /* The three columns of one chart line: fixed 170px label, elastic middle for
       the bar, fixed 120px for the figures. WHY fixed side columns: every line
       then starts its bar at the same x position, so bars of different projects
       line up and can be compared. With auto columns a long project name would
       push its own bar to the right and the chart would look crooked. */
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
    /* One single bar cut into coloured segments (profitable / break-even / in
       deficit). overflow:hidden on the container is what clips the square
       corners of the segments to the rounded shape of the bar. */
    .pf-health { display:flex; height:12px; border-radius:7px; overflow:hidden; background:var(--surface-3); }
    .pf-seg { height:100%; transition:width .5s ease; }
    /* Only the first and last segment get a rounded outer edge. WHY :first-child
       / :last-child instead of fixed classes: which segment comes first changes,
       because a segment with a count of zero is not rendered at all. If a project
       group disappears, the next one must take over the rounded end. */
    .pf-seg:first-child { border-radius:7px 0 0 7px; }
    .pf-seg:last-child { border-radius:0 7px 7px 0; }

    /* Skeleton: grey placeholder blocks shown while the data is still loading.
       The moving gradient gives the usual "shimmer" effect. WHY a skeleton and
       not a spinner: the boxes already have the size of the real cards, so the
       page does not jump when the numbers arrive. */
    .skl { border-radius:8px; background:linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size:400% 100%; animation:skla 1.2s ease infinite; }
    @keyframes skla { 0%{background-position:100% 0} 100%{background-position:-100% 0} }
    /* Accessibility: some users ask their operating system to reduce motion,
       often because animation makes them dizzy or triggers a migraine. This
       media query listens to that setting and switches every animation off.
       Without it, the shimmering skeleton would keep moving for those users. */
    @media (prefers-reduced-motion: reduce){ .skl{animation:none} .pf-seg{transition:none} .seg-btn{transition:none} }
  `],
  /* The template is written inline (in backticks) rather than in a separate .html
     file. That is the convention of this front end: the page and its markup stay
     in one file. Inside the backticks, ONLY HTML comments work. */
  template: `
    <!-- TOPBAR: breadcrumb on the left, view switch on the right. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-graph-up fs-13" style="color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'kpi.breadcrumb' | transloco }}</span>
      </div>
      <!--
        View switch. view is a signal, so view() reads it and view.set() writes
        it; writing it marks the template dirty and Angular redraws only what
        depends on that signal. [class.seg-on] adds the "selected" class when the
        expression is true. Both views are simply two different renderings of the
        same already-loaded data, so switching costs no extra HTTP call.
      -->
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

      <!--
        Initial load: skeleton.
        Shown while the project list has not arrived yet. @if is the built-in
        control flow of modern Angular (it replaces *ngIf): the block is really
        removed from the DOM, not just hidden.
        Careful: the test is only on the list being empty, so a user who really
        owns no project keeps seeing this placeholder.
      -->
      @if (projects().length === 0) {
        <div class="row g-3 mb-4">
          <!--
            @for needs a track expression: it tells Angular how to recognise an
            item it has already drawn. Here the items are just the numbers 1..4,
            so the number itself is the identity. Without track, Angular would
            not know which placeholder is which and would destroy and rebuild all
            of them on every redraw, restarting the shimmer animation each time.
          -->
          @for (i of [1,2,3,4]; track i) {
            <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
          }
        </div>
        <!-- Five grey lines standing for the rows of the chart that is coming. -->
        <div class="card"><div class="p-3">@for (i of [1,2,3,4,5]; track i) { <div class="skl mb-2" style="height:28px"></div> }</div></div>
      }

      <!-- ════════════════ PORTFOLIO VIEW ═════════════════════════════ -->
      @if (view() === 'portfolio' && projects().length > 0) {

        <!--
          Four totals for the whole portfolio.
          "@if (aggregates(); as agg)" reads the computed signal ONCE and names
          the result agg for the whole block. Why this form: aggregates() returns
          null while no project has usable KPI data yet, so the same line acts as
          both the null check and the local variable. Without the "as agg" alias
          the template would call aggregates() a dozen times below, and each call
          would have to be re-checked for null.
        -->
        @if (aggregates(); as agg) {
          <div class="row g-3 mb-3">
            <div class="col-6 col-xl-3">
              <div class="metric-card kpi-metric">
                <div class="d-flex align-items-start justify-content-between mb-2">
                  <div class="metric-icon metric-icon--brand"><i class="bi bi-briefcase-fill"></i></div>
                  <span class="text-caption">{{ 'kpi.portfolioCaption' | transloco }}</span>
                </div>
                <!--
                  Two pipes are used all over this template:
                  - "| transloco" replaces a translation key by the text of the
                    current language (French or English). Never write a comment
                    inside such a key.
                  - "| number:'1.0-0'" formats a number with at least one digit
                    before the dot and zero decimals, so 128499.6 shows as
                    128 500. Why no decimals: these are portfolio totals in
                    dinars; showing cents would add noise and make the cards
                    overflow on a small screen.
                -->
                <div class="metric-label">{{ 'kpi.totalBudget' | transloco }}</div>
                <div class="metric-value">{{ agg.totalBudget | number:'1.0-0' }} <span class="kpi-unit">TND</span></div>
              </div>
            </div>
            <!--
              Total EAC card. The icon colour and the caption both switch on one
              test: is the expected total cost above the money sold? Written with
              [style.background] and a ternary so the colour follows the data.
              The second value inside var(...) is a fallback colour, used when
              that CSS variable is not defined in the active theme; without the
              fallback the icon background would simply be missing there.
            -->
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
                <!-- The "+" sign is added by hand for positive margins. The
                     number pipe prints "-" for negatives but never "+", and a
                     margin shown as plain "12 000" reads like an amount, not
                     like a gain. -->
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

          <!--
            Portfolio health bar: one bar split into three coloured parts
            (profitable / break-even / in deficit) so the reader sees the shape
            of the whole portfolio in one glance, without reading any figure.
          -->
          @if (health(); as h) {
            <div class="card p-3 mb-4">
              <div class="d-flex justify-content-between align-items-center mb-2">
                <span class="fs-13 fw-semibold"><i class="bi bi-heart-pulse me-1" style="color:var(--c-brand)"></i>{{ 'kpi.portfolioHealth' | transloco }}</span>
                <span class="text-caption">{{ 'kpi.projectsAssessed' | transloco: { count: h.total } }}</span>
              </div>
              <!--
                Accessibility: the bar carries no text, so role="img" plus an
                aria-label give a screen reader one sentence describing it
                ("4 profitable, 1 break-even, 2 in deficit out of 7").
                [attr.aria-label] is used, not [aria-label], because aria-label
                is a plain HTML attribute and not a DOM property; binding it as a
                property would throw "Can't bind to 'aria-label'".
                Without this, a blind user would hear nothing at all here.
                Each segment is drawn only if its count is not zero: a zero-width
                coloured block would still show as a thin line because of the
                rounded corners.
              -->
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
          <!-- aggregates() is still null: the project list is here but no KPI
               answer has arrived yet, so the same skeleton cards are shown
               again rather than four zeros, which would look like real data. -->
          <div class="row g-3 mb-4">
            @for (i of [1,2,3,4]; track i) {
              <div class="col-6 col-xl-3"><div class="metric-card kpi-metric"><div class="skl mb-3" style="width:32px;height:32px;border-radius:9px"></div><div class="skl mb-2" style="width:60%;height:11px"></div><div class="skl" style="width:45%;height:22px"></div></div></div>
            }
          </div>
        }

        <!--
          Portfolio bullet chart, one line per project.
          A "bullet chart" shows a measured value against a reference on the same
          line: here a grey bar for the budget sold, a coloured bar for the EAC
          on top of it, a darker fill inside for what is already spent, and a
          vertical mark at the budget position. Why this shape rather than two
          separate bars: the eye compares "cost versus budget" on one single
          line, and going past the vertical mark instantly means overspending.
        -->
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
              <!--
                track row.p.id uses the database id of the project as identity.
                Why it matters here: the rows are sorted by margin, so a KPI
                arriving late can move a project from the bottom to the top. With
                a stable id Angular moves the existing DOM node; tracking the
                index instead would rewrite the content of every line below.
                "let last = $last" exposes the built-in flag for the final item,
                used right after to drop the separator under the last row.
              -->
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
                    <!-- The translation key is built from the status value
                         ('status.' + 'ACTIVE' = 'status.ACTIVE'), so adding a
                         new status only needs a new entry in the translation
                         files. The client name is printed only when there is
                         one; without the @if, projects with no client would show
                         a dangling middle dot. -->
                    <div class="text-micro" style="margin-top:1px">
                      {{ 'status.' + row.p.status | transloco }}
                      @if (row.p.client) { · {{ row.p.client }} }
                    </div>
                  </div>

                  <!--
                    Bullet chart track. Every bar inside is position:absolute and
                    anchored to this box (position:relative), so they all start
                    at the same left edge and can be stacked on top of each
                    other. Without the relative parent they would be placed
                    against the whole page and land somewhere else entirely.
                  -->
                  <div style="position:relative; height:20px; display:flex; align-items:center">
                    <!-- Grey reference bar = the budget sold, drawn first so the
                         coloured EAC bar sits on top of it. min-width:4px keeps a
                         very small budget still visible instead of invisible. -->
                    <div [style.width.%]="row.budgetPct"
                         style="position:absolute; left:0; top:4px; height:12px; background:var(--surface-3); border-radius:6px; min-width:4px"></div>

                    <!--
                      Coloured EAC bar, only the part that stays inside the
                      budget. The > 0.1 test skips bars thinner than a tenth of a
                      percent: because of min-width:4px such a bar would still be
                      painted and would look like a stray coloured dot at the
                      left of the line.
                      [class] builds the colour class name from the scenario,
                      which is why the five health rules in the styles block must
                      keep those exact names.
                    -->
                    @if (row.eacWithinBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacWithinBudgetPct"
                           [class]="'kpi-bar--' + row.scenario"
                           style="position:absolute; left:0; top:4px; height:12px; border-radius:6px 0 0 6px; overflow:hidden; min-width:4px">
                        <!--
                          Darker fill inside the coloured bar = the share of the
                          EAC already spent. It is a semi-transparent black
                          overlay, not a fixed colour, so it darkens whichever of
                          the five health colours is underneath. With five fixed
                          "darker" colours instead, every new health colour would
                          need a sixth hex value.
                        -->
                        @if (row.consumedInEacPct > 0.5) {
                          <div [style.width.%]="row.consumedInEacPct"
                               style="height:100%; background:rgba(0,0,0,.22); border-radius:inherit"></div>
                        }
                      </div>
                    }

                    <!--
                      Red extension: the part of the EAC that goes beyond the
                      budget. It starts exactly where the grey bar ends
                      ([style.left.%]="row.budgetPct"), so the two pieces meet
                      without a gap. It is always red whatever the scenario,
                      because overspending must be readable even on a project
                      whose overall margin is still positive.
                    -->
                    @if (row.eacOverBudgetPct > 0.1) {
                      <div [style.width.%]="row.eacOverBudgetPct"
                           [style.left.%]="row.budgetPct"
                           class="kpi-bar--loss-high"
                           style="position:absolute; top:4px; height:12px; border-radius:0 6px 6px 0; min-width:4px"></div>
                    }

                    <!--
                      Vertical mark at the budget position: the reference of the
                      bullet chart. z-index:2 puts it above the bars, and
                      translateX(-1px) centres its 2px width on the exact value
                      instead of starting 2px after it. Without this line the
                      reader could not tell where "the budget" is when the
                      coloured bar covers the grey one completely.
                    -->
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

            <!--
              Shared pagination component. It only reports what the user clicked;
              the slicing itself is done here in pagedPortfolioRows(). [total]
              must be the FULL row count, not the count of the visible page,
              otherwise the widget would always believe there is a single page.
              On a page-size change the page index is reset to 0: staying on
              page 7 after switching from 10 to 50 rows per page would land the
              user past the end of the list on an empty screen.
            -->
            <app-pagination
              [page]="portfolioPage()" [pageSize]="portfolioPageSize()" [total]="portfolioRows().length"
              (pageChange)="portfolioPage.set($event)"
              (pageSizeChange)="portfolioPageSize.set($event); portfolioPage.set(0)" />

            <!-- Legend of the four visual elements above (grey bar, coloured
                 bar, dark fill, red extension). A bullet chart is not a common
                 shape, so it is explained in words right under it. -->
            <div class="card-footer text-caption d-flex flex-wrap" style="gap:1.5rem">
              <span><b>{{ 'kpi.legendGrey' | transloco }}</b> {{ 'kpi.legendGreyDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendColour' | transloco }}</b> {{ 'kpi.legendColourDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendDark' | transloco }}</b> {{ 'kpi.legendDarkDesc' | transloco }}</span>
              <span><b>{{ 'kpi.legendRed' | transloco }}</b> {{ 'kpi.legendRedDesc' | transloco }}</span>
            </div>
          }
        </div>

        <!--
          Second chart: margin per project, "diverging" around zero. Losses grow
          to the left of a centre line, gains to the right. Why not one ordinary
          bar chart: with a single direction a loss of 10 000 and a gain of
          10 000 would look identical, and only the minus sign in the figure
          would tell them apart. It shows the same page of rows as the chart
          above, so both charts always talk about the same projects.
        -->
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

                  <!-- Diverging bar: left half = loss, right half = profit,
                       centre line = zero. Each half is a 50% wide box; the loss
                       box aligns its content to the right (justify-content:
                       flex-end) so a loss bar grows away from the centre line
                       towards the left. Without that alignment the red bar would
                       start at the far left and float away from zero. -->
                  <div style="position:relative; height:14px; display:flex; align-items:center">
                    <!-- Left half (loss side) -->
                    <div style="width:50%; height:12px; position:relative; display:flex; justify-content:flex-end">
                      <!-- absMarginBarPct() returns a width relative to the
                           largest margin of the portfolio, in absolute value, so
                           gains and losses share one scale. max-width:100% is a
                           safety net: it keeps the bar inside its half even if
                           the percentage ever came out above 100. -->
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

      <!-- ════════════════ DETAILED CARDS VIEW ════════════════════════ -->
      <!--
        One card per project, with every figure the KPI engine returned,
        including the EVM indicators. This view loops over ALL projects, not only
        the ones kept by portfolioRows(): a draft project or a project with no
        workload yet is meaningless in an aggregated chart, but the user still
        wants to open its card and see why it has no numbers.
      -->
      @if (view() === 'cards') {
        <div class="row g-3">
          @for (p of pagedProjects(); track p.id) {
            <div class="col-md-6 col-xl-4">
              <!-- Coloured left border = health of the project at a glance. It
                   is set to '' (no border) while the KPI has not arrived, so an
                   unloaded card does not show a colour that means nothing. -->
              <div class="card h-100" [style.border-left]="kpiMap()[p.id] ? '3px solid ' + scenarioColor(p) : ''">
                <div class="card-header justify-content-between">
                  <div class="me-2">
                    <div class="fw-bold monospace fs-12">{{ p.code }}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px">{{ p.name }}</div>
                  </div>
                  <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
                </div>

                <!--
                  Three possible bodies for one card, in this order:
                  1. the KPI is here  -> the figures;
                  2. the call failed  -> a message plus a Retry button;
                  3. anything else    -> a spinner (still loading).
                  "as k" names the KPI object for the whole block, so the
                  template does not look it up in the map at every line.
                -->
                @if (kpiMap()[p.id]; as k) {
                  <div class="card-body p-3">
                    <!-- Main metrics grid -->
                    <div class="row g-2 text-center mb-3">
                      <div class="col-6">
                        <!--
                          ?? is the "null coalescing" operator: it takes the left
                          value unless it is null or undefined. budgetTnd is
                          optional on a project, so when it is missing the budget
                          is rebuilt from the KPI itself: EAC + margin = budget,
                          since margin is defined as budget - EAC.
                          Careful: ?? only falls back on null/undefined, not on
                          0. A budget really set to 0 stays 0, which is correct;
                          with "||" instead, a real zero budget would be silently
                          replaced by EAC + margin.
                        -->
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
                        <!-- "!= null" (two equal signs) is deliberate: it is
                             true for both null and undefined, and false for 0.
                             A margin of exactly 0 is a real result and must be
                             printed; only a missing margin shows the dash. -->
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

                    <!--
                      Small "budget versus EAC" bar, the card version of the
                      bullet chart above. Here the scale is the budget of THIS
                      project (100% = its own budget), not the portfolio maximum,
                      because a card is read alone and not compared with its
                      neighbours.
                    -->
                    <div class="mb-2">
                      <div class="d-flex justify-content-between mb-1 text-micro">
                        <span>{{ 'kpi.budgetVsEac' | transloco }}</span>
                        <span>{{ cardEacPct(p, k) | number:'1.0-0' }}{{ 'kpi.pctOfBudget' | transloco }}</span>
                      </div>
                      <div style="position:relative; height:8px; background:var(--surface-3); border-radius:4px; overflow:visible">
                        <!-- EAC bar -->
                        <!-- min() is a small helper of this class, because a
                             template cannot call Math.min directly (the global
                             Math object is not visible from a template). The cap
                             at 100 stops a project at 180% of its budget from
                             drawing a bar almost twice as wide as its card. The
                             extra part is drawn separately just below. -->
                        <div [style.width.%]="min(cardEacPct(p,k), 100)"
                             style="position:absolute; left:0; top:0; height:8px; border-radius:4px; overflow:hidden"
                             [class]="'kpi-bar--' + cardScenario(p,k)">
                          <!-- Consumed part, as a share of the EAC. The
                               "k.eac > 0" test is a guard against a division by
                               zero: in JavaScript 0/0 gives NaN, and a width of
                               NaN% is an invalid style that the browser drops,
                               leaving a bar that never fills. -->
                          @if (k.budgetConsome > 0 && k.eac > 0) {
                            <div [style.width.%]="(k.budgetConsome / k.eac) * 100"
                                 style="height:100%; background:rgba(0,0,0,.25)"></div>
                          }
                        </div>
                        <!-- Red overflow beyond the budget, starting at 100% of
                             the track. max-width:30% keeps a catastrophic
                             project (say 400% of its budget) from pushing far
                             outside the card and breaking the grid layout. -->
                        @if (cardEacPct(p,k) > 100) {
                          <div [style.width.%]="cardEacPct(p,k) - 100"
                               [style.left.%]="100"
                               class="kpi-bar--loss-high"
                               style="position:absolute; top:0; height:8px; border-radius:0 4px 4px 0; max-width:30%"></div>
                        }
                      </div>
                    </div>

                    <!--
                      Consumption rate bar ("taux de consommation"): how much of
                      the budget is already spent. The backend sends it as a
                      ratio between 0 and 1, so it is multiplied by 100 here for
                      display. Above 90% the bar turns red as an early warning,
                      before the project actually goes over.
                    -->
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

                    <!--
                      EVM indicators (F-AFF-13 §5): EV % (earned value, the share
                      of the work really done, entered by the project manager at
                      the monthly review), consumed man-days, and remaining
                      man-days. They are optional in KpiResponse because they
                      only exist once a monthly snapshot has been taken. The
                      whole block is hidden when none of them is there; without
                      this guard the card would show three empty boxes on every
                      project that has never been reviewed.
                    -->
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

                    <!--
                      Warnings raised by the backend while it computed the KPI
                      (for example missing workload data). "?." is the optional
                      chaining operator: if warnings is undefined it yields
                      undefined instead of throwing. Without it, reading .length
                      on an absent array would crash the rendering of the card.
                      These messages come from the server already worded, so they
                      are printed as they are rather than through Transloco.
                    -->
                    @if (k.warnings?.length) {
                      <div class="alert alert-warning py-2 small mt-2 mb-2 d-flex align-items-start gap-2" role="alert">
                        <i class="bi bi-exclamation-triangle-fill flex-shrink-0 mt-1"></i>
                        <ul class="mb-0 ps-2">
                          @for (w of k.warnings; track w) { <li>{{ w }}</li> }
                        </ul>
                      </div>
                    }

                    <!-- routerLink navigates inside the application without
                         reloading the page. The array form ['/projects', p.id]
                         lets Angular build the URL and escape the id itself. -->
                    <div class="text-end">
                      <a [routerLink]="['/projects', p.id]" class="btn btn-sm btn-outline-primary">
                        {{ 'kpi.detail' | transloco }} <i class="bi bi-arrow-right ms-1"></i>
                      </a>
                    </div>
                  </div>

                } @else if (state()[p.id] === 'error') {
                  <!--
                    Failure state. The Retry button calls load(p) again for this
                    single project. Why retry per project and not for the whole
                    page: the KPI calls are independent, so one failure must not
                    force the user to reload the twenty others. This branch is
                    also what the user sees if the backend refused the call for
                    lack of permission, since authorization is checked on the
                    server and never in the browser.
                  -->
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <i class="bi bi-exclamation-triangle text-warning fs-4"></i>
                    <span>{{ 'kpi.unavailable' | transloco }}</span>
                    <button class="btn btn-sm btn-outline-secondary" (click)="load(p)">
                      <i class="bi bi-arrow-clockwise me-1"></i>{{ 'kpi.retry' | transloco }}
                    </button>
                  </div>
                } @else {
                  <!-- Still loading. min-height:140px keeps the card the same
                       height as a filled one, so the grid does not jump when
                       the answers arrive one after the other. -->
                  <div class="card-body d-flex flex-column align-items-center justify-content-center text-muted small gap-2"
                       style="min-height:140px">
                    <span class="spinner-border spinner-border-sm text-primary"></span>
                    <span>{{ 'kpi.computingShort' | transloco }}</span>
                  </div>
                }
              </div>
            </div>
          }
          <!-- @empty is the block @for falls back to when the list has no item.
               It saves writing a second @if on the same list, which could drift
               out of step with the loop condition. -->
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
/**
 * The KPI page.
 *
 * Holds the raw data in signals (project list, one KPI per project, one load
 * state per project) and exposes everything the template needs through
 * computed() signals. WHY signals and not plain fields: a signal remembers who
 * reads it, so when one late KPI answer arrives Angular redraws only the parts
 * that depend on it, instead of re-checking the whole page.
 *
 * implements OnInit because the data must be fetched after Angular has built
 * the component, not in the constructor: a constructor that starts HTTP calls
 * is much harder to test and can fire before the injected services are ready.
 */
export class KpiComponent implements OnInit {
  // inject() is the modern form of constructor injection. readonly says the
  // reference never changes after construction.
  private readonly projectSvc = inject(ProjectService);

  // All projects returned by the API, in code order.
  projects  = signal<Project[]>([]);

  // KPI of each project, indexed by project id.
  // WHY a map keyed by id and not an array parallel to `projects`: the answers
  // come back in any order, and the list can be re-sorted for display. With two
  // parallel arrays, one late answer could be written at the wrong index and a
  // project would show the figures of another one - the worst possible bug on
  // a financial screen.
  kpiMap    = signal<Record<number, KpiResponse>>({});

  // Where each project stands: loading, done, or error. Same id key as kpiMap.
  state     = signal<Record<number, LoadState>>({});

  // Which of the two views is on screen. 'portfolio' is the default because the
  // aggregated charts answer the first question a manager asks.
  view      = signal<ChartView>('portfolio');

  // Pagination state for the two views. It is CLIENT-side: everything is already
  // in memory (listAll() asks the API for one page of up to 1000 projects), so
  // paging only slices an array and needs no extra HTTP call. The two views keep
  // separate counters so that moving to page 3 of the cards does not silently
  // move the charts too.
  portfolioPage     = signal(0);
  portfolioPageSize = signal(10);
  cardsPage         = signal(0);
  cardsPageSize     = signal(9);   // 9 = three full rows of three cards

  /**
   * The rows of the current page of the portfolio charts.
   *
   * computed() builds a signal from other signals: it recalculates only when one
   * of the signals it reads really changed, and caches the result otherwise.
   * WHY the Math.min on the page index: the row count shrinks and grows while
   * the KPI answers arrive. Without the clamp, a user sitting on page 5 when the
   * list drops to 2 pages would get an empty screen with no way back.
   */
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

  /**
   * Entry point of the page: fetch the project list, then fire one KPI request
   * per project.
   *
   * WHY one call per project instead of a single bulk endpoint: the KPI is
   * computed live on the server for one project at a time, and the backend
   * checks the permission (and, for URLs under /api/projects/{id}/**, the
   * project scope) on each of these calls. The visible benefit is that each
   * card fills in as soon as its own answer arrives, and one project the user
   * may not read only makes its own card fail, not the whole page.
   *
   * .subscribe() is what actually sends the request: an Angular HttpClient
   * Observable is "cold", so without subscribing nothing would ever leave the
   * browser.
   */
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      list.forEach(p => this.load(p));
    });
  }

  /**
   * Load (or reload) the KPI of one project and record its state.
   * Also used as the click handler of the "Retry" button on a failed card.
   *
   * Every write uses .update() with a fresh object ({ ...s, [p.id]: ... })
   * instead of changing the existing one. WHY: a signal compares the old and the
   * new value by reference. Mutating the map in place would keep the same
   * reference, the signal would believe nothing changed, and the card would stay
   * on its spinner although the data had arrived.
   *
   * The error branch stores only the state and leaves kpiMap untouched, so an
   * earlier successful result is not erased by a failed refresh.
   */
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

  /**
   * Turns projects + KPIs into the ready-to-draw rows of the portfolio charts,
   * sorted from the best margin to the worst.
   *
   * It is the single place where the chart maths lives, so both charts and the
   * aggregate cards always agree on the same set of projects and the same
   * numbers. WHY do it here and not in the template: a template cannot filter,
   * scale and sort without calling methods on every redraw.
   */
  readonly portfolioRows = computed((): ChartRow[] => {
    // Keep only what makes sense in an aggregated view:
    // - k != null : the KPI answer really arrived (a project still loading or in
    //   error must not silently count as zero in the totals);
    // - not DRAFT and not CANCELLED : a quote never signed or a project dropped
    //   would drag the portfolio margin down although no money is at stake;
    // - EAC > 0 : nothing has been planned yet, so there is no bar to draw.
    //   Without this last test the chart would show flat empty lines.
    const items = this.projects()
      .map(p => ({ p, k: this.kpiMap()[p.id] }))
      .filter(x => x.k != null
        && x.p.status !== 'DRAFT' && x.p.status !== 'CANCELLED'
        && (x.k.eac ?? 0) > 0);  // exclude projects with no workload data yet

    if (!items.length) return [];

    // Biggest amount of the whole selection (largest EAC or largest budget).
    // It becomes the 100% of the chart, so every bar of every line is drawn to
    // the same scale. Without a shared scale, a 50 000 TND project and a
    // 5 000 000 TND one would show bars of the same length.
    const maxVal = items.reduce((m, x) => {
      const budget = x.p.budgetTnd ?? 0;
      return Math.max(m, x.k.eac ?? 0, budget);
    }, 0);

    // Guard: if the largest amount is 0, every width below would be a division
    // by zero and every bar would get a NaN width, which the browser ignores.
    if (maxVal === 0) return [];

    return items
      .map(x => {
        // Fallback budget when the project carries no sold amount: margin is
        // defined as budget - EAC, so budget = EAC + margin.
        const budget   = x.p.budgetTnd ?? ((x.k.eac ?? 0) + (x.k.marge ?? 0));
        const eac      = x.k.eac ?? 0;
        const consumed = x.k.budgetConsome ?? 0;
        const marge    = x.k.marge ?? 0;
        const marginPct = budget > 0 ? (marge / budget) * 100 : 0;

        // Widths, all against the shared portfolio scale (maxVal = 100%).
        const budgetPct           = (budget / maxVal) * 100;
        const eacPct              = (eac    / maxVal) * 100;
        // The EAC bar is cut in two pieces at the budget mark: the part inside
        // the budget keeps the health colour, the part beyond it is drawn red.
        // Math.min / Math.max keep each piece positive, so a project under
        // budget simply gets an over-budget piece of 0 and nothing red is drawn.
        const eacWithinBudgetPct  = Math.min(eacPct, budgetPct);
        const eacOverBudgetPct    = Math.max(0, eacPct - budgetPct);
        // The dark "already spent" fill is measured inside the EAC bar, not
        // against the portfolio scale, because it is nested in that bar and a
        // child width in CSS is relative to its parent. Capped at 100 so that
        // spending more than the estimate cannot overflow the bar; guarded
        // against eac = 0 to avoid a division by zero.
        const consumedInEacPct    = eac > 0 ? Math.min((consumed / eac) * 100, 100) : 0;
        const scenario            = this.toScenario(marginPct);

        return { p: x.p, k: x.k, budget, eac, consumed, marge, marginPct,
                 budgetPct, eacWithinBudgetPct, eacOverBudgetPct, consumedInEacPct, scenario };
      })
      // Best margin first. Sorting on the PERCENTAGE and not on the amount is
      // deliberate: a small project earning 30% is healthier than a huge one
      // earning 2%, and sorting by amount would always put the big contracts on
      // top whatever their health.
      // .sort() would mutate its array, which is why it is applied to the fresh
      // array produced by .map() just above and never to this.projects().
      .sort((a, b) => b.marginPct - a.marginPct);
  });

  /**
   * Portfolio totals for the four cards at the top.
   * Returns null (not an object full of zeros) when there is nothing to add up,
   * so the template can tell "no data yet" from "a real total of zero" and show
   * the skeleton instead of four convincing but meaningless 0 TND.
   * It reads portfolioRows(), so it always sums exactly the projects drawn in
   * the charts below; summing this.projects() instead would produce totals that
   * no chart line explains.
   */
  readonly aggregates = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;

    // reduce() walks the rows and accumulates one number, starting at 0.
    const totalBudget     = rows.reduce((s, r) => s + r.budget, 0);
    const totalEac        = rows.reduce((s, r) => s + r.eac,    0);
    const totalMarge      = rows.reduce((s, r) => s + r.marge,  0);
    const profitCount     = rows.filter(r => r.marge >= 0).length;
    // The global rate is computed from the TOTALS, not as the average of the
    // per-project rates. Why it matters: an average would give the same weight
    // to a 10 000 TND project and to a 2 000 000 TND one, so a tiny project at
    // +40% could hide a huge contract at -5%.
    const globalMarginPct = totalBudget > 0 ? (totalMarge / totalBudget) * 100 : 0;

    return { totalBudget, totalEac, totalMarge, profitCount, total: rows.length, globalMarginPct };
  });

  /**
   * Portfolio health split by scenario, for the segmented bar seen at a glance.
   *
   * The five scenarios are folded into three groups: the two profit levels
   * count as "profitable", the two loss levels as "deficit", break-even stays
   * alone. WHY three and not five: the bar must be readable in one second; five
   * segments on a 12px bar would be a row of thin stripes.
   * Returns null when there is nothing to show, same reason as aggregates().
   */
  readonly health = computed(() => {
    const rows = this.portfolioRows();
    if (!rows.length) return null;
    const profit  = rows.filter(r => r.scenario === 'profit-high' || r.scenario === 'profit-mid').length;
    const breakEven = rows.filter(r => r.scenario === 'break-even').length;
    const deficit   = rows.filter(r => r.scenario === 'loss-mid' || r.scenario === 'loss-high').length;
    const total = rows.length;
    // The three shares are given in percent of the project COUNT, so together
    // they always make 100 and fill the whole bar. total cannot be 0 here,
    // because the empty case returned null above.
    return {
      profit, breakEven, deficit, total,
      profitPct: (profit / total) * 100,
      breakPct:   (breakEven / total) * 100,
      deficitPct: (deficit / total) * 100,
    };
  });

  // ── Margin breakdown chart helper ─────────────────────────────────

  /**
   * Width, in percent of its half of the track, of one bar of the diverging
   * margin chart.
   *
   * The scale is the largest margin of the portfolio taken in absolute value,
   * so a gain of 40 000 and a loss of 40 000 draw bars of the same length on
   * opposite sides. Math.abs() is what makes a negative margin usable as a
   * width; a width of -30% would simply not be drawn.
   *
   * The seed of the reduce is 1, not 0. That is the division-by-zero guard: if
   * every project had a margin of exactly 0, dividing by 0 would give NaN and no
   * bar would be drawn at all. With 1, every bar comes out at 0% instead, which
   * is the correct picture.
   */
  absMarginBarPct(row: ChartRow): number {
    const rows = this.portfolioRows();
    const maxAbsMarge = rows.reduce((m, r) => Math.max(m, Math.abs(r.marge)), 1);
    return (Math.abs(row.marge) / maxAbsMarge) * 100;
  }

  // ── Card-level helpers ─────────────────────────────────────────────

  /**
   * Margin of one project as a percentage of its own budget, for the cards view.
   *
   * It is a plain method and not a computed signal because it takes arguments:
   * a computed() has no parameters. The cost stays low since the cards view only
   * draws one page of projects at a time.
   * Same budget fallback as elsewhere (EAC + margin), so a card and its line in
   * the portfolio chart can never disagree.
   */
  cardMarginPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.marge ?? 0) / budget) * 100 : 0;
  }

  /**
   * How much of the budget the expected total cost represents: 100 means the
   * EAC exactly eats the budget, above 100 means the project is heading past it.
   * The template uses it both for the bar width and for the figure printed next
   * to it, so the two can never tell different stories.
   */
  cardEacPct(p: Project, k: KpiResponse): number {
    const budget = p.budgetTnd ?? ((k.eac ?? 0) + (k.marge ?? 0));
    return budget > 0 ? ((k.eac ?? 0) / budget) * 100 : 0;
  }

  /**
   * Health class of one card, as a string the template glues after
   * 'kpi-bar--'. It goes through the same toScenario() thresholds as the
   * portfolio chart, so the same project cannot be green in one view and orange
   * in the other.
   */
  cardScenario(p: Project, k: KpiResponse): string {
    return this.toScenario(this.cardMarginPct(p, k));
  }

  /**
   * Colour of the left border of a card, as a raw hex value.
   *
   * A hex value is returned rather than a CSS class because the template sets it
   * through [style.border-left], which needs a real colour and not a class name.
   * Returns 'transparent' when the KPI has not arrived: an unknown project must
   * not be painted as if it were healthy.
   * Note: the thresholds below repeat those of toScenario(); the five values
   * are the same hex codes as the .kpi-bar--* rules in the styles block.
   */
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

  /**
   * The single rule that turns a margin percentage into a health level.
   * Thresholds: 20% and above = comfortable gain, 5% to 20% = normal gain,
   * -5% to 5% = break-even, -5% to -15% = moderate loss, below = heavy loss.
   * The band around zero exists because a margin of +0.4% is not really a
   * profit; without it, a project would flip from green to red on a rounding.
   *
   * The return type is ChartRow['scenario'], an "indexed access type": it reuses
   * the union declared in the interface instead of copying it. If a sixth level
   * is ever added there, this method stops compiling until it handles it, which
   * is exactly what we want.
   * Private because every caller is in this file; the template goes through
   * cardScenario().
   */
  private toScenario(marginPct: number): ChartRow['scenario'] {
    if (marginPct >= 20)  return 'profit-high';
    if (marginPct >= 5)   return 'profit-mid';
    if (marginPct >= -5)  return 'break-even';
    if (marginPct >= -15) return 'loss-mid';
    return 'loss-high';
  }


  /**
   * CSS class of the small status pill of a card (ACTIVE, COMPLETED...).
   * The "?? 'badge-draft'" at the end is the safety net: if the backend ever
   * sends a status this map does not know, the pill still gets a neutral style
   * instead of no class at all, which would render as unstyled black text.
   */
  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }

  /**
   * Math.min made reachable from the template. An Angular template can only read
   * members of the component, never the global Math object, so writing
   * Math.min(...) in the template would fail to compile. Used to cap bar widths
   * at 100%.
   */
  min(a: number, b: number): number { return Math.min(a, b); }
}
