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

interface TccAnnuel {
  annee: number;
  dailyRate: number;
  tccRate: number;
}

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
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
    tr.row-link { cursor:pointer; }
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
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'resources.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'resources.search.aria' | transloco">
          </div>
          @if (search()) {
            <button class="btn btn-ghost btn-sm" (click)="onSearch('')"><i class="bi bi-x-lg me-1"></i>{{ 'resources.search.reset' | transloco }}</button>
          }
          <span class="count">
            {{ (filtered().length === 1 ? 'resources.search.results.one' : 'resources.search.results.other')
               | transloco: { count: filtered().length } }}
          </span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
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
                <th class="d-none d-lg-table-cell">{{ 'resources.table.period' | transloco }}</th>
                <th class="text-end" style="width:150px">{{ 'resources.table.yearlyRates' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
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
                @for (r of pagedResources(); track r.id) {
                  <tr class="row-link" (click)="openTcc(r)">
                    <td class="fw-semibold">{{ r.userFullName }}</td>
                    <td class="text-end num">{{ r.dailyRate | number:'1.2-2' }}</td>
                    <td class="text-end num">{{ r.tccRate | number:'1.2-2' }}</td>
                    <td class="text-end num">{{ r.annualCost ? (r.annualCost | number:'1.0-0') : '—' }}</td>
                    <td class="d-none d-lg-table-cell text-muted small">
                      {{ r.staffingStart ?? '—' }}
                      @if (r.staffingEnd) { → {{ r.staffingEnd }} } @else { → {{ 'resources.table.ongoing' | transloco }} }
                    </td>
                    <td class="text-end">
                      <button class="btn btn-outline-secondary btn-sm" (click)="openTcc(r); $event.stopPropagation()"
                              [attr.aria-label]="'resources.actions.ratesAria' | transloco: {
                                action: (canManage ? ('resources.actions.manage' | transloco) : ('resources.actions.view' | transloco)),
                                name: r.userFullName }">
                        <i class="bi bi-calendar3 me-1"></i>{{ (canManage ? 'resources.actions.manage' : 'resources.actions.view') | transloco }}
                      </button>
                    </td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="6">
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
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="page.set($event)"
            (pageSizeChange)="pageSize.set($event); page.set(0)" />
        }
      </div>
    </div>

    <!-- TCC per-year modal -->
    @if (modalResource(); as r) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="closeTcc()" (keydown.escape)="closeTcc()">
        <div class="modal-dialog modal-dialog-centered modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content" style="max-height:calc(100vh - 3.5rem)">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-calendar3 me-2"></i>{{ 'resources.modal.title' | transloco: { name: r.userFullName } }}</h5>
              <button type="button" class="btn-close" (click)="closeTcc()" [attr.aria-label]="'resources.modal.close' | transloco"></button>
            </div>
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
                  @for (t of modalTcc(); track $index; let i = $index) {
                    <tr>
                      @if (canManage) {
                        <td><input type="number" class="form-control form-control-sm" [(ngModel)]="t.annee" min="2000" max="2100" style="width:110px" [attr.aria-label]="'resources.modal.yearAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.dailyRate" min="0" [attr.aria-label]="'resources.modal.dailyRateAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.tccRate" min="0" step="0.01" [attr.aria-label]="'resources.modal.tccRateAria' | transloco"></td>
                        <td class="text-end">
                          <button class="btn btn-sm btn-outline-danger" (click)="removeRow(i)" [title]="'resources.modal.removeTitle' | transloco" [attr.aria-label]="'resources.modal.removeAria' | transloco"><i class="bi bi-trash"></i></button>
                        </td>
                      } @else {
                        <td class="fw-semibold">{{ t.annee }}</td>
                        <td class="text-end num">{{ t.dailyRate | number:'1.2-2' }}</td>
                        <td class="text-end num">{{ t.tccRate | number:'1.2-2' }}</td>
                      }
                    </tr>
                  }
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
export class ResourcesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly transloco = inject(TranslocoService);

  resources = signal<Resource[]>([]);
  loading   = signal(true);
  page      = signal(0);
  pageSize  = signal(10);
  search    = signal('');
  sortCol   = signal<SortCol>('name');
  sortDir   = signal<SortDir>('asc');

  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const list = q ? this.resources().filter(r => r.userFullName.toLowerCase().includes(q)) : this.resources();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    const col = this.sortCol();
    return [...list].sort((a, b) => this.compare(a, b, col) * dir);
  });

  readonly pagedResources = computed(() => {
    const size = this.pageSize();
    const pages = Math.max(1, Math.ceil(this.filtered().length / size));
    const page = Math.min(this.page(), pages - 1);
    return this.filtered().slice(page * size, page * size + size);
  });

  // Modal state
  modalResource = signal<Resource | null>(null);
  modalTcc      = signal<TccAnnuel[]>([]);
  saving        = signal(false);
  msg           = signal('');
  isErr         = signal(false);
  private tccCache = new Map<number, TccAnnuel[]>();

  get canManage(): boolean { return this.auth.hasPermission('MANAGE_RESOURCES'); }

  ngOnInit(): void {
    this.http.get<Resource[]>(`${environment.apiUrl}/resources`).subscribe({
      next: list => { this.resources.set(list); this.loading.set(false); },
      error: () => { this.resources.set([]); this.loading.set(false); this.toast.error(this.transloco.translate('resources.msg.loadFailed')); }
    });
  }

  onSearch(v: string): void { this.search.set(v); this.page.set(0); }

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
  private compare(a: Resource, b: Resource, col: SortCol): number {
    switch (col) {
      case 'dailyRate':  return (a.dailyRate ?? 0) - (b.dailyRate ?? 0);
      case 'tccRate':    return (a.tccRate ?? 0) - (b.tccRate ?? 0);
      case 'annualCost': return (a.annualCost ?? 0) - (b.annualCost ?? 0);
      case 'name':
      default:           return a.userFullName.localeCompare(b.userFullName);
    }
  }

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

  addRow(): void {
    const rows = this.modalTcc();
    const next = rows.length > 0 ? Math.max(...rows.map(t => t.annee)) + 1 : new Date().getFullYear();
    this.modalTcc.set([...rows, { annee: next, dailyRate: 0, tccRate: 0 }]);
  }

  removeRow(i: number): void {
    const rows = [...this.modalTcc()];
    rows.splice(i, 1);
    this.modalTcc.set(rows);
  }

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
