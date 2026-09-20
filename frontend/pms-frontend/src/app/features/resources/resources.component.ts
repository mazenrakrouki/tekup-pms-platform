// "Ressources & TCC" screen: table of costed people (rate, TCC, annual cost), with a per-year
// rates modal. Resource (cost) is split from User (account) per ADR-022. TCC is a fraction,
// not a percent. No dedicated service: calls HttpClient directly against /api/resources.
import { Component, OnInit, computed, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Resource } from '../../core/models/user.model';
import { AuthService } from '../../core/services/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { PaginationComponent } from '../../shared/pagination/pagination.component';
import { environment } from '../../../environments/environment';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';

// Front-end mirror of the Java record TccAnnuelDto: one year's rates for one person.
// Declared here (not core/models) since this screen is its only user.
interface TccAnnuel {
  annee: number;
  dailyRate: number;
  tccRate: number;
}

// Exact-string union rather than 'string', so a typo'd sort column fails to compile.
type SortCol = 'name' | 'dailyRate' | 'tccRate' | 'annualCost';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-resources',
  standalone: true,
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  providers: [provideTranslocoScope('resources')],
  styles: [`
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* Right-aligned columns need the arrow on the left of the title, not the right. */
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    /* Arrow hidden at rest so only the active sort column's arrow stands out. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible (not :focus): ring only for keyboard users tabbing through headers. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
    tr.row-link { cursor:pointer; }
    /* tabular-nums keeps digits aligned column-wise. */
    .num { font-variant-numeric:tabular-nums; }
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-60{width:60%} .sk-w-70{width:70%}
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-people" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'resources.breadcrumb' | transloco }}</span>
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'resources.title' | transloco }}</h1>
      </div>

      <div class="card">
        <div class="toolbar">
          <div class="input-wrap" style="flex:1;min-width:200px;max-width:320px">
            <i class="bi bi-search input-icon"></i>
            <!-- Split ngModel/change, not [(ngModel)]: onSearch() must also reset to page 1. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'resources.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'resources.search.aria' | transloco">
          </div>
          @if (search()) {
            <button class="btn btn-ghost btn-sm" (click)="onSearch('')"><i class="bi bi-x-lg me-1"></i>{{ 'resources.search.reset' | transloco }}</button>
          }
          <!-- Key chosen by count, not a bare "(s)": FR/EN don't agree on plural boundaries. -->
          <span class="count">
            {{ (filtered().length === 1 ? 'resources.search.results.one' : 'resources.search.results.other')
               | transloco: { count: filtered().length } }}
          </span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <!-- tabindex+keydown handlers: a <th> isn't a button, needs these to be
                     keyboard-sortable. Space also preventDefault()s to stop page scroll. -->
                <th class="th-sort" [class.is-sorted]="sortCol()==='name'" [attr.aria-sort]="ariaSort('name')"
                    tabindex="0" (click)="toggleSort('name')" (keydown.enter)="toggleSort('name')" (keydown.space)="toggleSort('name'); $event.preventDefault()">
                  <span class="th-inner">{{ 'resources.table.person' | transloco }} <i class="bi caret" [ngClass]="caret('name')"></i></span>
                </th>
                <th class="th-sort text-end" [class.is-sorted]="sortCol()==='dailyRate'" [attr.aria-sort]="ariaSort('dailyRate')"
                    tabindex="0" (click)="toggleSort('dailyRate')" (keydown.enter)="toggleSort('dailyRate')" (keydown.space)="toggleSort('dailyRate'); $event.preventDefault()">
                  <span class="th-inner">{{ 'resources.table.dailyRate' | transloco }} <i class="bi caret" [ngClass]="caret('dailyRate')"></i></span>
                </th>
                <th class="th-sort text-end" [class.is-sorted]="sortCol()==='tccRate'" [attr.aria-sort]="ariaSort('tccRate')"
                    tabindex="0" (click)="toggleSort('tccRate')" (keydown.enter)="toggleSort('tccRate')" (keydown.space)="toggleSort('tccRate'); $event.preventDefault()">
                  <span class="th-inner">{{ 'resources.table.tccRate' | transloco }} <i class="bi caret" [ngClass]="caret('tccRate')"></i></span>
                </th>
                <th class="th-sort text-end" [class.is-sorted]="sortCol()==='annualCost'" [attr.aria-sort]="ariaSort('annualCost')"
                    tabindex="0" (click)="toggleSort('annualCost')" (keydown.enter)="toggleSort('annualCost')" (keydown.space)="toggleSort('annualCost'); $event.preventDefault()">
                  <span class="th-inner">{{ 'resources.table.annualCost' | transloco }} <i class="bi caret" [ngClass]="caret('annualCost')"></i></span>
                </th>
                <!-- Staffing window is least useful; dropped first on narrow screens. -->
                <th class="d-none d-lg-table-cell">{{ 'resources.table.period' | transloco }}</th>
                <th class="text-end" style="width:150px">{{ 'resources.table.yearlyRates' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              <!-- Six skeleton rows while loading, to hold the table's height roughly steady. -->
              @if (loading()) {
                @for (i of [1,2,3,4,5,6]; track i) {
                  <tr>
                    <td><div class="skeleton sk-line sk-w-60"></div></td>
                    <td><div class="skeleton sk-line sk-w-40 ms-auto"></div></td>
                    <td><div class="skeleton sk-line sk-w-40 ms-auto"></div></td>
                    <td><div class="skeleton sk-line sk-w-40 ms-auto"></div></td>
                    <td class="d-none d-lg-table-cell"><div class="skeleton sk-line sk-w-70"></div></td>
                    <td><div class="skeleton sk-line sk-w-60 ms-auto"></div></td>
                  </tr>
                }
              } @else {
                <!-- track r.id (not $index): keeps rows stable across sort/page instead of
                     rebuilding, which would lose focus and be slower. -->
                @for (r of pagedResources(); track r.id) {
                  <tr class="row-link" (click)="openTcc(r)">
                    <td class="fw-semibold">{{ r.userFullName }}</td>
                    <!-- TCC shown to 4 decimals: it's a fraction, rounding to 2 shifts cost >1%. -->
                    <td class="text-end num">{{ r.dailyRate | number:'1.2-2' }}</td>
                    <td class="text-end num">{{ r.tccRate | number:'1.2-4' }}</td>
                    <!-- annualCost falsy (missing or 0) both read as "nothing to show yet". -->
                    <td class="text-end num">{{ r.annualCost ? (r.annualCost | number:'1.0-0') : '—' }}</td>
                    <td class="d-none d-lg-table-cell text-muted small">
                      {{ r.staffingStart ?? '—' }}
                      @if (r.staffingEnd) { → {{ r.staffingEnd }} } @else { → {{ 'resources.table.ongoing' | transloco }} }
                    </td>
                    <td class="text-end">
                      <!-- stopPropagation: avoids double-firing openTcc via the row's own click. -->
                      <button class="btn btn-outline-secondary btn-sm" (click)="openTcc(r); $event.stopPropagation()"
                              [attr.aria-label]="'resources.actions.ratesAria' | transloco: {
                                action: (canManage ? ('resources.actions.manage' | transloco) : ('resources.actions.view' | transloco)),
                                name: r.userFullName }">
                        <!-- Wording follows the permission: never promises "manage" if the
                             server would refuse it. -->
                        <i class="bi bi-calendar3 me-1"></i>{{ (canManage ? 'resources.actions.manage' : 'resources.actions.view') | transloco }}
                      </button>
                    </td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="6">
                    <!-- Distinct empty states: "no match" (with clear button) vs "none yet". -->
                    @if (search()) {
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-search"></i></div>
                        <div class="es-title">{{ 'resources.empty.noMatchTitle' | transloco }}</div>
                        <div class="es-desc">{{ 'resources.empty.noMatchDesc' | transloco: { query: search() } }}</div>
                        <button class="btn btn-outline-secondary btn-sm mt-3" (click)="onSearch('')"><i class="bi bi-x-lg me-1"></i>{{ 'resources.search.reset' | transloco }}</button>
                      </div>
                    } @else {
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-people"></i></div>
                        <div class="es-title">{{ 'resources.empty.noneTitle' | transloco }}</div>
                        <div class="es-desc">{{ 'resources.empty.noneDesc' | transloco }}</div>
                      </div>
                    }
                  </td></tr>
                }
              }
            </tbody>
          </table>
        </div>
        <!-- Page-size change also resets to page 0, else a shrink could land past the end. -->
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="page.set($event)"
            (pageSizeChange)="pageSize.set($event); page.set(0)" />
        }
      </div>
    </div>

    <!-- TCC per-year modal; @if (...; as r) both gates and names the non-null resource. -->
    @if (modalResource(); as r) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="closeTcc()" (keydown.escape)="closeTcc()">
        <!-- stopPropagation: a click inside the dialog must not bubble up and close it. -->
        <div class="modal-dialog modal-dialog-centered modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content" style="max-height:calc(100vh - 3.5rem)">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-calendar3 me-2"></i>{{ 'resources.modal.title' | transloco: { name: r.userFullName } }}</h5>
              <button type="button" class="btn-close" (click)="closeTcc()" [attr.aria-label]="'resources.modal.close' | transloco"></button>
            </div>
            <!-- overflow-y + max-height above: rows scroll while header/footer stay visible. -->
            <div class="modal-body" style="overflow-y:auto">
              <table class="table table-sm align-middle mb-3">
                <thead>
                  <tr>
                    <th>{{ 'resources.modal.year' | transloco }}</th>
                    <th class="text-end">{{ 'resources.modal.dailyRate' | transloco }}</th>
                    <th class="text-end">{{ 'resources.modal.tccRate' | transloco }}</th>
                    @if (canManage) { <th style="width:56px"></th> }
                  </tr>
                </thead>
                <tbody>
                  <!-- track $index (not t.annee): a mid-edit row can briefly duplicate a year,
                       which would throw with a value-based key; rows are just a local buffer. -->
                  @for (t of modalTcc(); track $index; let i = $index) {
                    <tr>
                      <!-- Hides controls only; the server still 403s a forced edit without
                           MANAGE_RESOURCES. -->
                      @if (canManage) {
                        <!-- [(ngModel)] writes into the local copy made in openTcc(), never the
                             cached server data. step 0.0001 matches the DB's scale-4 column. -->
                        <td><input type="number" class="form-control form-control-sm" [(ngModel)]="t.annee" min="2000" max="2100" style="width:110px" [attr.aria-label]="'resources.modal.yearAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.dailyRate" min="0" [attr.aria-label]="'resources.modal.dailyRateAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.tccRate" min="0" max="9.9999" step="0.0001" [attr.aria-label]="'resources.modal.tccRateAria' | transloco"></td>
                        <td class="text-end">
                          <!-- Local removal only; the PUT on Save is what soft-deletes server-side. -->
                          <button class="btn btn-sm btn-outline-danger" (click)="removeRow(i)" [title]="'resources.modal.removeTitle' | transloco" [attr.aria-label]="'resources.modal.removeAria' | transloco"><i class="bi bi-trash"></i></button>
                        </td>
                      } @else {
                        <td class="fw-semibold">{{ t.annee }}</td>
                        <td class="text-end num">{{ t.dailyRate | number:'1.2-2' }}</td>
                        <td class="text-end num">{{ t.tccRate | number:'1.2-4' }}</td>
                      }
                    </tr>
                  }
                  <!-- colspan follows canManage: the delete column only exists for an editor. -->
                  @if (modalTcc().length === 0) {
                    <tr><td [attr.colspan]="canManage ? 4 : 3">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-calendar-range"></i></div>
                        <div class="es-title">{{ 'resources.modal.emptyTitle' | transloco }}</div>
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
              @if (canManage) {
                <button class="btn btn-sm btn-outline-primary" (click)="addRow()">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'resources.modal.addYear' | transloco }}
                </button>
              }
              <!-- Not a toast on purpose: an error must stay next to the grid to fix. -->
              @if (msg()) {
                <div class="mt-3 small" [class.text-success]="!isErr()" [class.text-danger]="isErr()" role="status">
                  <i class="bi" [class.bi-check-circle]="!isErr()" [class.bi-exclamation-triangle]="isErr()"></i>
                  {{ msg() }}
                </div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="closeTcc()">{{ 'resources.modal.close' | transloco }}</button>
              @if (canManage) {
                <!-- Disabled while saving: blocks a double-click sending the PUT twice. -->
                <button class="btn btn-primary" (click)="saveTcc()" [disabled]="saving()">
                  @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                  {{ 'resources.modal.save' | transloco }}
                </button>
              }
            </div>
          </div>
        </div>
      </div>
    }
  `
})
// implements OnInit: a typo'd ngOninit would otherwise silently compile and never load data.
export class ResourcesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly transloco = inject(TranslocoService);

  /** Raw server list, never sorted/filtered in place; filtered() derives from it. */
  resources = signal<Resource[]>([]);
  /** Starts true: starting false would flash the empty state before data arrives. */
  loading   = signal(true);
  page      = signal(0);
  pageSize  = signal(10);
  search    = signal('');
  sortCol   = signal<SortCol>('name');
  sortDir   = signal<SortDir>('asc');

  // [...list] copies before sort() (which mutates in place), so resources() stays untouched.
  // localeCompare handles accented names correctly; a < b would sort accents after Z.
  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const list = q ? this.resources().filter(r => r.userFullName.toLowerCase().includes(q)) : this.resources();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    const col = this.sortCol();
    return [...list].sort((a, b) => this.compare(a, b, col) * dir);
  });

  // Clamps the page number to the last existing page, so a search that shrinks the result
  // set can't leave the slice starting past the end (a blank table with no explanation).
  readonly pagedResources = computed(() => {
    const size = this.pageSize();
    const pages = Math.max(1, Math.ceil(this.filtered().length / size));
    const page = Math.min(this.page(), pages - 1);
    return this.filtered().slice(page * size, page * size + size);
  });

  // Modal state
  /** Person whose year window is open (null = closed); the single source of truth for it. */
  modalResource = signal<Resource | null>(null);
  /** Always a COPY of the server rows (see openTcc); cancelling just discards it. */
  modalTcc      = signal<TccAnnuel[]>([]);
  saving        = signal(false);
  msg           = signal('');
  isErr         = signal(false);
  // Cache of fetched year rows by resource id; plain Map since no template reads it.
  private tccCache = new Map<number, TccAnnuel[]>();

  // Display-only: hides controls. The server enforces via @PreAuthorize on ResourceService.
  get canManage(): boolean { return this.auth.hasPermission('MANAGE_RESOURCES'); }

  // Server scopes the list itself (full company vs. own projects), so this renders differently
  // per user with no extra logic here.
  ngOnInit(): void {
    this.http.get<Resource[]>(`${environment.apiUrl}/resources`).subscribe({
      next: list => { this.resources.set(list); this.loading.set(false); },
      error: () => { this.resources.set([]); this.loading.set(false); this.toast.error(this.transloco.translate('resources.msg.loadFailed')); }
    });
  }

  // Exists instead of a two-way binding solely to also reset the page on each keystroke.
  onSearch(v: string): void { this.search.set(v); this.page.set(0); }

  // Resets to page 0 on sort: rows on the current page number would otherwise no longer match.
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    this.page.set(0);
  }
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }
  // Always ascending; filtered() flips the sign for descending. (value ?? 0) avoids NaN from
  // undefined arithmetic, which would leave sort() unable to order the rows.
  private compare(a: Resource, b: Resource, col: SortCol): number {
    switch (col) {
      case 'dailyRate':  return (a.dailyRate ?? 0) - (b.dailyRate ?? 0);
      case 'tccRate':    return (a.tccRate ?? 0) - (b.tccRate ?? 0);
      case 'annualCost': return (a.annualCost ?? 0) - (b.annualCost ?? 0);
      case 'name':
      default:           return a.userFullName.localeCompare(b.userFullName);
    }
  }

  // Copies rows (spread) so ngModel edits a local copy, not the cached server answer — Close
  // must not corrupt the cache. Checks modalResource() still matches r before applying a
  // slow response, since the user may have switched to another person meanwhile.
  openTcc(r: Resource): void {
    this.modalResource.set(r);
    this.msg.set('');
    this.isErr.set(false);
    const cached = this.tccCache.get(r.id);
    if (cached) {
      this.modalTcc.set(cached.map(x => ({ ...x })));
    } else {
      this.modalTcc.set([]);
      this.http.get<TccAnnuel[]>(`${environment.apiUrl}/resources/${r.id}/tcc`)
        .subscribe(rows => {
          this.tccCache.set(r.id, rows);
          if (this.modalResource()?.id === r.id) this.modalTcc.set(rows.map(x => ({ ...x })));
        });
    }
  }

  closeTcc(): void { this.modalResource.set(null); }

  // Proposes highest existing year + 1 (avoids the server's 409 duplicate-year conflict).
  // Builds a new array (not push()): a signal only notifies on a new reference.
  addRow(): void {
    const rows = this.modalTcc();
    const next = rows.length > 0 ? Math.max(...rows.map(t => t.annee)) + 1 : new Date().getFullYear();
    this.modalTcc.set([...rows, { annee: next, dailyRate: 0, tccRate: 0 }]);
  }

  // Local removal only; splice() applied to a copy so the signal sees a new reference.
  removeRow(i: number): void {
    const rows = [...this.modalTcc()];
    rows.splice(i, 1);
    this.modalTcc.set(rows);
  }

  // PUT replaces the whole history, so retrying after a network error is safe. The duplicate-
  // year check here is a courtesy (avoids a round trip for a 409 the server would enforce
  // anyway). On success, the cache/grid are refreshed from the server's saved response, not
  // from what was typed, since the server owns ids and rounding.
  saveTcc(): void {
    const r = this.modalResource();
    if (!r) return;
    const rows = this.modalTcc();

    const years = rows.map(x => x.annee);
    if (new Set(years).size !== years.length) {
      this.msg.set(this.transloco.translate('resources.msg.duplicateYear'));
      this.isErr.set(true);
      return;
    }

    this.saving.set(true);
    this.isErr.set(false);
    this.msg.set('');

    this.http.put<TccAnnuel[]>(`${environment.apiUrl}/resources/${r.id}/tcc`, rows)
      .subscribe({
        next: saved => {
          this.tccCache.set(r.id, saved);
          this.modalTcc.set(saved.map(x => ({ ...x })));
          this.saving.set(false);
          this.msg.set(this.transloco.translate('resources.msg.saved'));
          this.toast.success(this.transloco.translate('resources.msg.savedToast'));
        },
        error: (e: { error?: { detail?: string } }) => {
          this.saving.set(false);
          this.isErr.set(true);
          this.msg.set(e.error?.detail ?? this.transloco.translate('resources.msg.saveFailed'));
        }
      });
  }
}
