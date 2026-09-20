import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule, DecimalPipe, DatePipe } from '@angular/common';
import { RouterLink, Router, ActivatedRoute, Params } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';
import { ProjectService } from '../../../core/services/project.service';
import { AuthService } from '../../../core/services/auth.service';
import { Project, PROJECT_STATUS_LABELS } from '../../../core/models/project.model';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { ProjectsListStateService } from '../projects-list-state.service';

// Projects table: search, status filter, sortable columns, paging, Active/Archived tabs.
// Client-side filtering (load() fetches the whole list once) since the company has tens, not
// millions, of projects — revisit if that changes. Permission checks only hide UI; server enforces.

// Union of exact strings so a typo like toggleSort('nmae') fails to compile instead of
// silently breaking sorting.
type SortCol = 'code' | 'name' | 'chef' | 'startDate' | 'endDate' | 'createdAt' | 'budget' | 'status';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-project-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule, DecimalPipe, DatePipe, PaginationComponent, TranslocoModule],
  styles: [`
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    /* Sortable column headers */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* Arrow invisible at rest, faint on hover, full on the active sort column. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible (not :focus): ring only for keyboard users tabbing through headers. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    /* Rows */
    tr.row-link { cursor:pointer; }
    .code-cell { font-size:12px; font-weight:600; color:var(--c-brand); font-family:'JetBrains Mono','Fira Code',monospace; }
    .name-cell { font-weight:600; color:var(--text-1); }
    .sub-cell  { font-size:11px; color:var(--text-3); }
    .muted-cell { font-size:12px; color:var(--text-2); white-space:nowrap; }
    /* tabular-nums keeps digits aligned column-wise. */
    .num-cell  { font-weight:600; color:var(--text-1); font-variant-numeric:tabular-nums; }

    /* Skeleton */
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40 { width:40%; } .sk-w-60 { width:60%; } .sk-w-70 { width:70%; } .sk-w-30 { width:30%; }
  `],
  template: `
    <!-- TOPBAR -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-folder2-open" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'projects.title' | transloco }}</span>
      </div>
      <div class="tb-right">
        <!-- Comfort only: server re-checks CREATE_PROJECT on the service method. -->
        @if (auth.hasPermission('CREATE_PROJECT')) {
          <a routerLink="/projects/new" class="btn btn-primary btn-sm">
            <i class="bi bi-plus-lg"></i>{{ 'projects.new' | transloco }}
          </a>
        }
      </div>
    </div>

    <div class="page-body">
      <!-- Page header -->
      <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
        <div>
          <h1 class="page-title">{{ 'projects.title' | transloco }}</h1>
        </div>
        <!-- Not a filter: each tab reloads from a different endpoint, see setMode(). -->
        <div class="pms-tabs">
          <button class="tab-item" [class.active]="mode() === 'active'" (click)="setMode('active')">
            <i class="bi bi-folder2-open me-1"></i>{{ 'projects.tabActive' | transloco }}
          </button>
          <button class="tab-item" [class.active]="mode() === 'archived'" (click)="setMode('archived')">
            <i class="bi bi-archive me-1"></i>{{ 'projects.tabArchived' | transloco }}
          </button>
        </div>
      </div>

      <!-- Table card -->
      <div class="card">
        <!-- Toolbar -->
        <div class="toolbar">
          <div class="input-wrap" style="flex:1;min-width:200px;max-width:320px">
            <i class="bi bi-search input-icon"></i>
            <!-- Split ngModel/change (not [(ngModel)]): the handler must also reset the
                 page and rewrite the URL. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'projects.searchPlaceholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'projects.searchAria' | transloco">
          </div>

          <!-- Filters on the raw code, not the translated label, so it survives a language switch. -->
          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="statusFilter()" (ngModelChange)="onStatus($event)"
                  [attr.aria-label]="'projects.filterStatusAria' | transloco">
            <option value="">{{ 'projects.allStatuses' | transloco }}</option>
            <option value="ACTIVE">{{ 'status.ACTIVE' | transloco }}</option>
            <option value="DRAFT">{{ 'status.DRAFT' | transloco }}</option>
            <option value="ON_HOLD">{{ 'status.ON_HOLD' | transloco }}</option>
            <option value="COMPLETED">{{ 'status.COMPLETED' | transloco }}</option>
            <option value="CANCELLED">{{ 'status.CANCELLED' | transloco }}</option>
          </select>

          @if (search() || statusFilter()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>{{ 'common.reset' | transloco }}
            </button>
          }

          <!-- Count is of filtered rows, not the current page. -->
          <span class="count">{{ filtered().length }}
            {{ (filtered().length === 1 ? 'common.result' : 'common.results') | transloco }}</span>
        </div>

        <!-- Table -->
        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <!-- tabindex + keydown handlers make each <th> keyboard-sortable; Space also
                   preventDefault()s to stop page scroll. -->
              <tr>
                <th class="th-sort" [class.is-sorted]="sortCol()==='code'" [attr.aria-sort]="ariaSort('code')"
                    tabindex="0" (click)="toggleSort('code')" (keydown.enter)="toggleSort('code')" (keydown.space)="toggleSort('code'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colCode' | transloco }} <i class="bi caret" [ngClass]="caret('code')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='name'" [attr.aria-sort]="ariaSort('name')"
                    tabindex="0" (click)="toggleSort('name')" (keydown.enter)="toggleSort('name')" (keydown.space)="toggleSort('name'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colName' | transloco }} <i class="bi caret" [ngClass]="caret('name')"></i></span>
                </th>
                <!-- Manager/dates/created-at drop first on narrow screens so code/name/status
                     stay readable without side scroll. -->
                <th class="th-sort d-none d-lg-table-cell" [class.is-sorted]="sortCol()==='chef'" [attr.aria-sort]="ariaSort('chef')"
                    tabindex="0" (click)="toggleSort('chef')" (keydown.enter)="toggleSort('chef')" (keydown.space)="toggleSort('chef'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colManager' | transloco }} <i class="bi caret" [ngClass]="caret('chef')"></i></span>
                </th>
                <th class="th-sort d-none d-md-table-cell" [class.is-sorted]="sortCol()==='startDate'" [attr.aria-sort]="ariaSort('startDate')"
                    tabindex="0" (click)="toggleSort('startDate')" (keydown.enter)="toggleSort('startDate')" (keydown.space)="toggleSort('startDate'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colStart' | transloco }} <i class="bi caret" [ngClass]="caret('startDate')"></i></span>
                </th>
                <th class="th-sort d-none d-md-table-cell" [class.is-sorted]="sortCol()==='endDate'" [attr.aria-sort]="ariaSort('endDate')"
                    tabindex="0" (click)="toggleSort('endDate')" (keydown.enter)="toggleSort('endDate')" (keydown.space)="toggleSort('endDate'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colEnd' | transloco }} <i class="bi caret" [ngClass]="caret('endDate')"></i></span>
                </th>
                <th class="th-sort d-none d-xl-table-cell" [class.is-sorted]="sortCol()==='createdAt'" [attr.aria-sort]="ariaSort('createdAt')"
                    tabindex="0" (click)="toggleSort('createdAt')" (keydown.enter)="toggleSort('createdAt')" (keydown.space)="toggleSort('createdAt'); $event.preventDefault()">
                  <span class="th-inner">{{ 'common.createdAt' | transloco }} <i class="bi caret" [ngClass]="caret('createdAt')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='status'" [attr.aria-sort]="ariaSort('status')"
                    tabindex="0" (click)="toggleSort('status')" (keydown.enter)="toggleSort('status')" (keydown.space)="toggleSort('status'); $event.preventDefault()">
                  <span class="th-inner">{{ 'common.status' | transloco }} <i class="bi caret" [ngClass]="caret('status')"></i></span>
                </th>
                <!-- Budget: not even the column heading is shown without VIEW_KPI. -->
                @if (auth.hasPermission('VIEW_KPI')) {
                  <th class="th-sort text-end" [class.is-sorted]="sortCol()==='budget'" [attr.aria-sort]="ariaSort('budget')"
                      tabindex="0" (click)="toggleSort('budget')" (keydown.enter)="toggleSort('budget')" (keydown.space)="toggleSort('budget'); $event.preventDefault()">
                    <span class="th-inner">{{ 'projects.colBudget' | transloco }} <i class="bi caret" [ngClass]="caret('budget')"></i></span>
                  </th>
                }
                <!-- Titleless <th> so body actions cells still line up. -->
                @if (auth.hasPermission('EDIT_PROJECT')) { <th></th> }
              </tr>
            </thead>
            <tbody>
              <!-- Fake rows matching the real columns, so the card height doesn't jump. -->
              @if (loading()) {
                @for (i of skeletonRows; track i) {
                  <tr>
                    <td><div class="skeleton sk-line sk-w-60"></div></td>
                    <td><div class="skeleton sk-line sk-w-70"></div></td>
                    <td class="d-none d-lg-table-cell"><div class="skeleton sk-line sk-w-60"></div></td>
                    <td class="d-none d-md-table-cell"><div class="skeleton sk-line sk-w-70"></div></td>
                    <td class="d-none d-md-table-cell"><div class="skeleton sk-line sk-w-70"></div></td>
                    <td class="d-none d-xl-table-cell"><div class="skeleton sk-line sk-w-70"></div></td>
                    <td><div class="skeleton sk-line sk-w-40"></div></td>
                    @if (auth.hasPermission('VIEW_KPI')) { <td><div class="skeleton sk-line sk-w-60 ms-auto"></div></td> }
                    @if (auth.hasPermission('EDIT_PROJECT')) { <td><div class="skeleton sk-line sk-w-30 ms-auto"></div></td> }
                  </tr>
                }
              } @else {
                <!-- track p.id: rows move (not rebuild) on re-sort, keeping scroll/focus steady. -->
                @for (p of paged(); track p.id) {
                  <tr class="row-link" (click)="open(p)">
                    <td>
                      <!-- Real routerLink (not just the row click): supports middle-click and
                           keyboard nav. stopPropagation avoids a double navigate. -->
                      <a [routerLink]="['/projects', p.id]" class="code-cell text-decoration-none"
                         (click)="$event.stopPropagation()">{{ p.code }}</a>
                    </td>
                    <td>
                      <a [routerLink]="['/projects', p.id]" class="name-cell text-decoration-none"
                         (click)="$event.stopPropagation()">{{ p.name }}</a>
                      @if (p.client) { <div class="sub-cell">{{ p.client }}</div> }
                    </td>
                    <td class="d-none d-lg-table-cell muted-cell">{{ p.chefProjetName ?? '—' }}</td>
                    <td class="d-none d-md-table-cell muted-cell">{{ p.startDate ?? '—' }}</td>
                    <td class="d-none d-md-table-cell muted-cell">{{ p.endDate ?? '—' }}</td>
                    <!-- Ternary needed: the date pipe would crash on an undefined createdAt. -->
                    <td class="d-none d-xl-table-cell muted-cell">{{ p.createdAt ? (p.createdAt | date:'dd/MM/yyyy') : '—' }}</td>
                    <!-- Colour and wording kept separate so a language switch never recolors. -->
                    <td><span [class]="statusBadge(p.status)">{{ 'status.' + p.status | transloco }}</span></td>
                    @if (auth.hasPermission('VIEW_KPI')) {
                      <td class="text-end num-cell">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    }
                    @if (auth.hasPermission('EDIT_PROJECT')) {
                      <td class="text-end" style="white-space:nowrap">
                        <!-- Active -> edit, archived -> restore: an archived project must stay frozen. -->
                        @if (mode() === 'active') {
                          <a [routerLink]="['/projects', p.id, 'edit']" class="btn btn-ghost btn-icon btn-sm"
                             [title]="'projects.editTitle' | transloco" [attr.aria-label]="'projects.editTitle' | transloco"
                             (click)="$event.stopPropagation()">
                            <i class="bi bi-pencil"></i>
                          </a>
                        } @else {
                          <button class="btn btn-ghost btn-sm" (click)="unarchive(p); $event.stopPropagation()"
                                  [title]="'projects.restoreTitle' | transloco" [attr.aria-label]="'projects.restoreTitle' | transloco">
                            <i class="bi bi-arrow-counterclockwise me-1"></i>{{ 'common.restore' | transloco }}
                          </button>
                        }
                      </td>
                    }
                  </tr>
                }
                <!-- Two distinct empty messages: "no project at all" vs "filter matched nothing". -->
                @if (filtered().length === 0) {
                  <tr>
                    <!-- colspan is computed: column count depends on the user's permissions. -->
                    <td [attr.colspan]="colCount()">
                      @if (hasFilters()) {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-search"></i></div>
                          <div class="es-title">{{ 'common.noResults' | transloco }}</div>
                          <div class="es-desc">{{ 'projects.emptyFilteredDesc' | transloco }}</div>
                          <button class="btn btn-outline-secondary btn-sm mt-3" (click)="clearFilters()">
                            <i class="bi bi-x-lg me-1"></i>{{ 'projects.resetFilters' | transloco }}
                          </button>
                        </div>
                      } @else {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi" [ngClass]="mode()==='archived' ? 'bi-archive' : 'bi-folder2-open'"></i></div>
                          <div class="es-title">{{ (mode() === 'archived' ? 'projects.emptyArchived' : 'projects.empty') | transloco }}</div>
                          <div class="es-desc">{{ (mode() === 'archived' ? 'projects.emptyArchivedDesc' : 'projects.emptyDesc') | transloco }}</div>
                          @if (auth.hasPermission('CREATE_PROJECT') && mode() === 'active') {
                            <a routerLink="/projects/new" class="btn btn-primary btn-sm mt-3">
                              <i class="bi bi-plus-lg me-1"></i>{{ 'projects.createFirst' | transloco }}
                            </a>
                          }
                        </div>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        <!-- [total] is filtered().length, not all().length, so the last page isn't empty. -->
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>
  `
})
// Keeps the whole list in memory, derives on-screen rows in three computed() steps
// (filter -> sort -> page), so changing the page number doesn't re-run the filter.
// implements OnInit: route snapshot is only reliable after Angular finishes setup.
export class ProjectListComponent implements OnInit {
  private readonly svc = inject(ProjectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  // Feeds the detail/edit page's "back to list" link (search/filter/sort/page).
  private readonly listState = inject(ProjectsListStateService);
  // Public: the template calls auth.hasPermission(...) directly.
  readonly auth = inject(AuthService);

  // Private: the template always reads filtered()/sorted()/paged(), never this directly.
  private readonly all = signal<Project[]>([]);
  // Starts true so the first paint shows skeleton rows, not a "no project" flash.
  loading = signal(true);

  mode         = signal<'active' | 'archived'>('active');
  search       = signal('');
  statusFilter = signal('');
  sortCol      = signal<SortCol>('code');
  sortDir      = signal<SortDir>('asc');
  page         = signal(0);      // zero-based
  pageSize     = signal(20);

  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  // ── Derived list pipeline: filter → sort → paginate ──────────────────

  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const status = this.statusFilter();
    return this.all().filter(p => {
      // Searches four fields; ?? '' guards optional client/chefProjetName from throwing
      // on .toLowerCase().
      const matchText = !q
        || p.code.toLowerCase().includes(q)
        || p.name.toLowerCase().includes(q)
        || (p.client ?? '').toLowerCase().includes(q)
        || (p.chefProjetName ?? '').toLowerCase().includes(q);
      // Against the raw code, not the translated label, so this survives a language switch.
      const matchStatus = !status || p.status === status;
      return matchText && matchStatus;
    });
  });

  readonly sorted = computed(() => {
    const col = this.sortCol();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    // Copies before sort() (which mutates in place), so filtered()'s own array stays untouched.
    return [...this.filtered()].sort((a, b) => this.compare(a, b, col) * dir);
  });

  readonly paged = computed(() => {
    const start = this.page() * this.pageSize();
    return this.sorted().slice(start, start + this.pageSize());
  });

  readonly hasFilters = computed(() => !!this.search() || !!this.statusFilter());

  // URL is the source of truth, so the page is shareable/refresh-proof (and feeds the
  // detail page's back link).
  ngOnInit(): void {
    // snapshot is enough: syncUrl() uses replaceUrl, which doesn't recreate the component.
    const p = this.route.snapshot.queryParamMap;
    // Each cast has a fallback, so a hand-typed bad query param still renders a working table.
    this.mode.set((p.get('mode') as 'active' | 'archived') || 'active');
    this.search.set(p.get('search') || '');
    this.statusFilter.set(p.get('status') || '');
    this.sortCol.set((p.get('sort') as SortCol) || 'code');
    this.sortDir.set((p.get('dir') as SortDir) || 'asc');
    this.page.set(Number(p.get('page') || 0));
    this.pageSize.set(Number(p.get('size') || 20));
    this.listState.set(this.buildCompact());   // seed context for breadcrumbs on deep-link
    this.load();
  }

  // Omits values equal to their default, so the URL/back-link stays short (and an empty
  // 'search=' can't be confused with no search at all).
  private buildCompact(): Params {
    const c: Params = {};
    if (this.mode() !== 'active') c['mode'] = this.mode();
    if (this.search()) c['search'] = this.search();
    if (this.statusFilter()) c['status'] = this.statusFilter();
    if (this.sortCol() !== 'code') c['sort'] = this.sortCol();
    if (this.sortDir() !== 'asc') c['dir'] = this.sortDir();
    if (this.page() !== 0) c['page'] = this.page();
    if (this.pageSize() !== 20) c['size'] = this.pageSize();
    return c;
  }

  // Called at start-up, on tab change, and after a restore. Two endpoints (not one with a
  // flag): the archived list is a separate, rarely used dataset.
  private load(): void {
    this.loading.set(true);
    const obs = this.mode() === 'archived' ? this.svc.listArchived() : this.svc.listAll();
    obs.subscribe({
      // clampPage() runs here: page count is only known once the rows arrive.
      next: list => { this.all.set(list); this.loading.set(false); this.clampPage(); },
      error: () => { this.all.set([]); this.loading.set(false); }
    });
  }

  // ── URL + context sync ───────────────────────────────────────────────

  // Called by every handler below, so refresh/bookmark/back-from-detail all land on the
  // same table.
  private syncUrl(): void {
    this.listState.set(this.buildCompact());
    this.router.navigate([], {
      queryParams: {
        // null removes the param, keeping default state out of the URL.
        mode: this.mode() === 'active' ? null : this.mode(),
        search: this.search() || null,
        status: this.statusFilter() || null,
        sort: this.sortCol() === 'code' ? null : this.sortCol(),
        dir: this.sortDir() === 'asc' ? null : this.sortDir(),
        page: this.page() || null,
        size: this.pageSize() === 20 ? null : this.pageSize(),
      },
      // replaceUrl: typing "BAD" shouldn't push 4 history entries the user has to Back through.
      replaceUrl: true,
    });
  }

  // Nothing stops a URL from naming a page past the end of a shorter filtered result.
  private clampPage(): void {
    const maxPage = Math.max(0, Math.ceil(this.filtered().length / this.pageSize()) - 1);
    if (this.page() > maxPage) this.page.set(maxPage);
  }

  // ── Handlers ─────────────────────────────────────────────────────────

  onSearch(v: string): void { this.search.set(v); this.page.set(0); this.syncUrl(); }
  onStatus(v: string): void { this.statusFilter.set(v); this.page.set(0); this.syncUrl(); }
  onPage(n: number): void { this.page.set(n); this.syncUrl(); }
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.syncUrl(); }

  // Sort order deliberately kept: the user asked to see everything, not to undo their sort.
  clearFilters(): void {
    this.search.set(''); this.statusFilter.set(''); this.page.set(0); this.syncUrl();
  }

  setMode(m: 'active' | 'archived'): void {
    if (this.mode() === m) return;
    // Reloads (not a filter): the two tabs are two different endpoints.
    this.mode.set(m); this.page.set(0); this.syncUrl(); this.load();
  }

  // Same column flips direction; a new column starts ascending (matches user expectation).
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortCol.set(col); this.sortDir.set('asc');
    }
    this.page.set(0);
    this.syncUrl();
  }

  open(p: Project): void { this.router.navigate(['/projects', p.id]); }

  // Reloads inside the subscribe callback (not immediately after the call), so the refresh
  // waits for server confirmation instead of racing the request.
  unarchive(p: Project): void {
    this.svc.unarchive(p.id).subscribe(() => this.load());
  }

  // ── Sorting helpers ──────────────────────────────────────────────────

  // Direction handled by sorted() (multiplies by -1), so each column is written once.
  private compare(a: Project, b: Project, col: SortCol): number {
    switch (col) {
      // ?? 0 avoids a NaN from subtracting a missing budget.
      case 'budget': return (a.effectiveBudget ?? 0) - (b.effectiveBudget ?? 0);
      // localeCompare (not a < b) handles accents correctly.
      case 'chef':   return (a.chefProjetName ?? '').localeCompare(b.chefProjetName ?? '');
      // By readable label, not the raw code, for a sensible alphabetical order.
      case 'status': return this.statusLabel(a.status).localeCompare(this.statusLabel(b.status));
      // ISO text compares in the same order as the real dates; ?? '' puts no-date rows first.
      case 'startDate': return (a.startDate ?? '').localeCompare(b.startDate ?? '');
      case 'endDate':   return (a.endDate ?? '').localeCompare(b.endDate ?? '');
      case 'createdAt': return (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
      case 'name':   return a.name.localeCompare(b.name);
      case 'code':
      default:       return a.code.localeCompare(b.code);
    }
  }

  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  // Computed: budget/actions columns only exist for VIEW_KPI/EDIT_PROJECT holders.
  colCount(): number {
    return 6
      + (this.auth.hasPermission('VIEW_KPI') ? 1 : 0)
      + (this.auth.hasPermission('EDIT_PROJECT') ? 1 : 0);
  }

  // Used only for sorting; the displayed text comes from Transloco instead.
  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  // Lookup map (not if/else) so a new status is one line.
  statusBadge(s: string): string {
    const map: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled',
    };
    return map[s] ?? 'badge-draft';
  }
}
