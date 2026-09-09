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
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    /* Rows */
    tr.row-link { cursor:pointer; }
    .code-cell { font-size:12px; font-weight:600; color:var(--c-brand); font-family:'JetBrains Mono','Fira Code',monospace; }
    .name-cell { font-weight:600; color:var(--text-1); }
    .sub-cell  { font-size:11px; color:var(--text-3); }
    .muted-cell { font-size:12px; color:var(--text-2); white-space:nowrap; }
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
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'projects.searchPlaceholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'projects.searchAria' | transloco">
          </div>

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

          <span class="count">{{ filtered().length }}
            {{ (filtered().length === 1 ? 'common.result' : 'common.results') | transloco }}</span>
        </div>

        <!-- Table -->
        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <th class="th-sort" [class.is-sorted]="sortCol()==='code'" [attr.aria-sort]="ariaSort('code')"
                    tabindex="0" (click)="toggleSort('code')" (keydown.enter)="toggleSort('code')" (keydown.space)="toggleSort('code'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colCode' | transloco }} <i class="bi caret" [ngClass]="caret('code')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='name'" [attr.aria-sort]="ariaSort('name')"
                    tabindex="0" (click)="toggleSort('name')" (keydown.enter)="toggleSort('name')" (keydown.space)="toggleSort('name'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colName' | transloco }} <i class="bi caret" [ngClass]="caret('name')"></i></span>
                </th>
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
                @if (auth.hasPermission('VIEW_KPI')) {
                  <th class="th-sort text-end" [class.is-sorted]="sortCol()==='budget'" [attr.aria-sort]="ariaSort('budget')"
                      tabindex="0" (click)="toggleSort('budget')" (keydown.enter)="toggleSort('budget')" (keydown.space)="toggleSort('budget'); $event.preventDefault()">
                    <span class="th-inner">{{ 'projects.colBudget' | transloco }} <i class="bi caret" [ngClass]="caret('budget')"></i></span>
                  </th>
                }
                @if (auth.hasPermission('EDIT_PROJECT')) { <th></th> }
              </tr>
            </thead>
            <tbody>
              <!-- Loading skeleton -->
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
                @for (p of paged(); track p.id) {
                  <tr class="row-link" (click)="open(p)">
                    <td>
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
                    <td class="d-none d-xl-table-cell muted-cell">{{ p.createdAt ? (p.createdAt | date:'dd/MM/yyyy') : '—' }}</td>
                    <td><span [class]="statusBadge(p.status)">{{ 'status.' + p.status | transloco }}</span></td>
                    @if (auth.hasPermission('VIEW_KPI')) {
                      <td class="text-end num-cell">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    }
                    @if (auth.hasPermission('EDIT_PROJECT')) {
                      <td class="text-end" style="white-space:nowrap">
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
                <!-- Empty states -->
                @if (filtered().length === 0) {
                  <tr>
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

        <!-- Pagination (client-side) -->
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>
  `
})
export class ProjectListComponent implements OnInit {
  private readonly svc = inject(ProjectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly listState = inject(ProjectsListStateService);
  readonly auth = inject(AuthService);

  /** Full dataset for the current mode (loaded once, then filtered/sorted/paged client-side). */
  private readonly all = signal<Project[]>([]);
  loading = signal(true);

  mode         = signal<'active' | 'archived'>('active');
  search       = signal('');
  statusFilter = signal('');
  sortCol      = signal<SortCol>('code');
  sortDir      = signal<SortDir>('asc');
  page         = signal(0);
  pageSize     = signal(20);

  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  // ── Derived list pipeline: filter → sort → paginate ──────────────────
  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const status = this.statusFilter();
    return this.all().filter(p => {
      const matchText = !q
        || p.code.toLowerCase().includes(q)
        || p.name.toLowerCase().includes(q)
        || (p.client ?? '').toLowerCase().includes(q)
        || (p.chefProjetName ?? '').toLowerCase().includes(q);
      const matchStatus = !status || p.status === status;
      return matchText && matchStatus;
    });
  });

  readonly sorted = computed(() => {
    const col = this.sortCol();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    return [...this.filtered()].sort((a, b) => this.compare(a, b, col) * dir);
  });

  readonly paged = computed(() => {
    const start = this.page() * this.pageSize();
    return this.sorted().slice(start, start + this.pageSize());
  });

  readonly hasFilters = computed(() => !!this.search() || !!this.statusFilter());

  ngOnInit(): void {
    const p = this.route.snapshot.queryParamMap;
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

  /** Compact (non-default) query params mirroring the current list state. */
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

  private load(): void {
    this.loading.set(true);
    const obs = this.mode() === 'archived' ? this.svc.listArchived() : this.svc.listAll();
    obs.subscribe({
      next: list => { this.all.set(list); this.loading.set(false); this.clampPage(); },
      error: () => { this.all.set([]); this.loading.set(false); }
    });
  }

  // ── URL + context sync ───────────────────────────────────────────────
  private syncUrl(): void {
    this.listState.set(this.buildCompact());
    this.router.navigate([], {
      queryParams: {
        mode: this.mode() === 'active' ? null : this.mode(),
        search: this.search() || null,
        status: this.statusFilter() || null,
        sort: this.sortCol() === 'code' ? null : this.sortCol(),
        dir: this.sortDir() === 'asc' ? null : this.sortDir(),
        page: this.page() || null,
        size: this.pageSize() === 20 ? null : this.pageSize(),
      },
      replaceUrl: true,
    });
  }

  private clampPage(): void {
    const maxPage = Math.max(0, Math.ceil(this.filtered().length / this.pageSize()) - 1);
    if (this.page() > maxPage) this.page.set(maxPage);
  }

  // ── Handlers ─────────────────────────────────────────────────────────
  onSearch(v: string): void { this.search.set(v); this.page.set(0); this.syncUrl(); }
  onStatus(v: string): void { this.statusFilter.set(v); this.page.set(0); this.syncUrl(); }
  onPage(n: number): void { this.page.set(n); this.syncUrl(); }
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.syncUrl(); }

  clearFilters(): void {
    this.search.set(''); this.statusFilter.set(''); this.page.set(0); this.syncUrl();
  }

  setMode(m: 'active' | 'archived'): void {
    if (this.mode() === m) return;
    this.mode.set(m); this.page.set(0); this.syncUrl(); this.load();
  }

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

  unarchive(p: Project): void {
    this.svc.unarchive(p.id).subscribe(() => this.load());
  }

  // ── Sorting helpers ──────────────────────────────────────────────────
  private compare(a: Project, b: Project, col: SortCol): number {
    switch (col) {
      case 'budget': return (a.effectiveBudget ?? 0) - (b.effectiveBudget ?? 0);
      case 'chef':   return (a.chefProjetName ?? '').localeCompare(b.chefProjetName ?? '');
      case 'status': return this.statusLabel(a.status).localeCompare(this.statusLabel(b.status));
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

  colCount(): number {
    return 6
      + (this.auth.hasPermission('VIEW_KPI') ? 1 : 0)
      + (this.auth.hasPermission('EDIT_PROJECT') ? 1 : 0);
  }

  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  statusBadge(s: string): string {
    const map: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled',
    };
    return map[s] ?? 'badge-draft';
  }
}
