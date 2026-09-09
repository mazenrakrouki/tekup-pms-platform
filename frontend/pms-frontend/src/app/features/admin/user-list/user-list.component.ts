import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { User, UserCreateResult } from '../../../core/models/user.model';
import { PagedResponse } from '../../../core/models/pagination.model';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { environment } from '../../../../environments/environment';

interface Role { id: number; name: string; }
type SortCol = 'lastName' | 'email';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-user-list',
  standalone: true,
  providers: [provideTranslocoScope('admin')],
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  styles: [`
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    .u-name { font-weight:600; color:var(--text-1); }
    .u-mail { color:var(--text-2); }
    .act-info { color:var(--c-brand); }
    .act-warn { color:var(--c-warning); }
    .act-ok   { color:var(--c-success); }
    .act-danger { color:var(--c-danger); }

    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-55{width:55%} .sk-w-70{width:70%} .sk-w-30{width:30%}
    .pw-field { }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-person-lines-fill" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'admin.breadcrumb.users' | transloco }}</span>
      </div>
      <div class="tb-right">
        <button class="btn btn-primary btn-sm" (click)="openCreate()">
          <i class="bi bi-plus-lg"></i>{{ 'admin.users.new' | transloco }}
        </button>
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'admin.users.title' | transloco }}</h1>
      </div>

      <div class="card">
        <!-- Toolbar -->
        <div class="toolbar">
          <div class="input-wrap" style="flex:1;min-width:200px;max-width:320px">
            <i class="bi bi-search input-icon"></i>
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.users.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearchChange($event)"
                   [attr.aria-label]="'admin.users.search.aria' | transloco">
          </div>

          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="roleFilter()" (ngModelChange)="onRoleChange($event)" [attr.aria-label]="'admin.users.filter.roleAria' | transloco">
            <option value="">{{ 'admin.users.filter.allRoles' | transloco }}</option>
            @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
          </select>

          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="statusFilter()" (ngModelChange)="onStatusChange($event)" [attr.aria-label]="'admin.users.filterStatusAria' | transloco">
            <option value="">{{ 'admin.users.filter.allStatuses' | transloco }}</option>
            <option value="true">{{ 'admin.users.state.active' | transloco }}</option>
            <option value="false">{{ 'admin.users.state.inactive' | transloco }}</option>
          </select>

          @if (hasFilters()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>{{ 'admin.users.search.reset' | transloco }}
            </button>
          }

          <span class="count">{{ (totalElements() === 1 ? 'admin.users.results.one' : 'admin.users.results.other') | transloco: { count: totalElements() } }}</span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <th class="th-sort" [class.is-sorted]="sortCol()==='lastName'" [attr.aria-sort]="ariaSort('lastName')"
                    tabindex="0" (click)="toggleSort('lastName')" (keydown.enter)="toggleSort('lastName')" (keydown.space)="toggleSort('lastName'); $event.preventDefault()">
                  <span class="th-inner">{{ 'admin.users.table.name' | transloco }} <i class="bi caret" [ngClass]="caret('lastName')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='email'" [attr.aria-sort]="ariaSort('email')"
                    tabindex="0" (click)="toggleSort('email')" (keydown.enter)="toggleSort('email')" (keydown.space)="toggleSort('email'); $event.preventDefault()">
                  <span class="th-inner">Email <i class="bi caret" [ngClass]="caret('email')"></i></span>
                </th>
                <th>{{ 'admin.users.table.role' | transloco }}</th>
                <th>{{ 'admin.users.table.status' | transloco }}</th>
                <th class="text-end">{{ 'admin.users.table.actions' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              @if (loading()) {
                @for (i of skeletonRows; track i) {
                  <tr>
                    <td><div class="skeleton sk-line sk-w-55"></div></td>
                    <td><div class="skeleton sk-line sk-w-70"></div></td>
                    <td><div class="skeleton sk-line sk-w-40"></div></td>
                    <td><div class="skeleton sk-line sk-w-40"></div></td>
                    <td><div class="skeleton sk-line sk-w-30 ms-auto"></div></td>
                  </tr>
                }
              } @else {
                @for (u of users(); track u.id) {
                  <tr>
                    <td class="u-name">{{ u.firstName }} {{ u.lastName }}</td>
                    <td class="u-mail">{{ u.email }}</td>
                    <td><span class="role-badge-light">{{ u.roleName }}</span></td>
                    <td>
                      <span [class]="u.active ? 'badge-active' : 'badge-cancelled'">{{ (u.active ? 'admin.users.state.active' : 'admin.users.state.inactive') | transloco }}</span>
                    </td>
                    <td class="text-end" style="white-space:nowrap">
                      <button class="btn btn-ghost btn-icon btn-sm" (click)="openEdit(u)"
                              [title]="'admin.users.actions.edit' | transloco" [attr.aria-label]="'admin.users.actions.editAria' | transloco">
                        <i class="bi bi-pencil"></i>
                      </button>
                      @if (u.active) {
                        <button class="btn btn-ghost btn-icon btn-sm act-info" (click)="resetAccount(u)"
                                [title]="'admin.users.actions.resetPassword' | transloco" [attr.aria-label]="'admin.users.actions.resetPassword' | transloco">
                          <i class="bi bi-key"></i>
                        </button>
                        <button class="btn btn-ghost btn-icon btn-sm act-warn" (click)="deactivate(u)"
                                [title]="'admin.users.actions.deactivate' | transloco" [attr.aria-label]="'admin.users.actions.deactivateAria' | transloco">
                          <i class="bi bi-person-dash"></i>
                        </button>
                      } @else {
                        <button class="btn btn-ghost btn-icon btn-sm act-ok" (click)="reactivate(u)"
                                [title]="'admin.users.actions.reactivate' | transloco" [attr.aria-label]="'admin.users.actions.reactivateAria' | transloco">
                          <i class="bi bi-person-check"></i>
                        </button>
                      }
                    </td>
                  </tr>
                }
                @empty {
                  <tr>
                    <td colspan="5">
                      @if (hasFilters()) {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-search"></i></div>
                          <div class="es-title">{{ 'admin.users.empty.noMatchTitle' | transloco }}</div>
                          <div class="es-desc">{{ 'admin.users.empty.noMatchDesc' | transloco }}</div>
                          <button class="btn btn-outline-secondary btn-sm mt-3" (click)="clearFilters()">
                            <i class="bi bi-x-lg me-1"></i>{{ 'admin.users.empty.resetFilters' | transloco }}
                          </button>
                        </div>
                      } @else {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-people"></i></div>
                          <div class="es-title">{{ 'admin.users.empty.noneTitle' | transloco }}</div>
                          <div class="es-desc">{{ 'admin.users.empty.noneDesc' | transloco }}</div>
                          <button class="btn btn-primary btn-sm mt-3" (click)="openCreate()">
                            <i class="bi bi-plus-lg me-1"></i>{{ 'admin.users.new' | transloco }}
                          </button>
                        </div>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        @if (!loading() && totalElements() > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="totalElements()"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>

    <!-- Modale réinitialisation mot de passe -->
    @if (showResetModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showResetModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-key me-2 act-info"></i>{{ 'admin.users.reset.title' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showResetModal.set(false)"></button>
            </div>
            <div class="modal-body">
              @if (resetResult()) {
                <p class="mb-3" style="font-size:13px">
                  {{ 'admin.users.reset.done' | transloco: { name: resetResult()!.userName } }}
                  {{ 'admin.users.reset.mustChange' | transloco }}
                </p>
                <div class="alert alert-warning py-2" style="font-size:12px">
                  <i class="bi bi-exclamation-triangle me-1"></i>
                  {{ 'admin.users.reset.communicate' | transloco }}
                </div>
                <div class="input-group mt-2">
                  <input type="text" class="form-control monospace" [value]="resetResult()!.pwd" readonly [attr.aria-label]="'admin.users.tempPassword' | transloco">
                  <button class="btn btn-outline-secondary" type="button" (click)="copyResetPassword()" [title]="'common.copy' | transloco" [attr.aria-label]="'admin.users.copyPassword' | transloco">
                    <i class="bi bi-clipboard"></i>
                  </button>
                </div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-primary" (click)="showResetModal.set(false)">{{ 'common.close' | transloco }}</button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modale création / édition -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ (editingId() ? 'admin.users.form.editTitle' : 'admin.users.form.newTitle') | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'admin.users.form.firstName' | transloco }} <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.firstName" [placeholder]="'admin.users.form.firstName' | transloco" autocomplete="given-name">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'admin.users.form.lastName' | transloco }} <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.lastName" [placeholder]="'admin.users.form.lastName' | transloco" autocomplete="family-name">
                </div>
                <div class="col-12">
                  <label class="form-label">Email <span class="text-danger">*</span></label>
                  <input type="email" class="form-control" [(ngModel)]="form.email" placeholder="email@example.com" autocomplete="email">
                </div>
                <div class="col-12">
                  <label class="form-label">{{ 'admin.users.form.role' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="form.roleId">
                    <option [value]="0" disabled>{{ 'admin.users.form.selectRole' | transloco }}</option>
                    @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
                  </select>
                </div>
              </div>
              @if (errorMsg()) { <div class="alert alert-danger py-2 mt-3">{{ errorMsg() }}</div> }
              @if (initialPassword()) {
                <div class="alert alert-success mt-3">
                  <strong>{{ 'admin.users.form.created' | transloco }}</strong> {{ 'admin.users.form.communicate' | transloco }}
                  <div class="input-group mt-2">
                    <input type="text" class="form-control monospace" [value]="initialPassword()" readonly [attr.aria-label]="'admin.users.form.created' | transloco">
                    <button class="btn btn-outline-secondary" type="button" (click)="copyPassword()" [title]="'common.copy' | transloco" [attr.aria-label]="'admin.users.copyPassword' | transloco">
                      <i class="bi bi-clipboard"></i>
                    </button>
                  </div>
                </div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ (initialPassword() ? 'common.close' : 'common.cancel') | transloco }}</button>
              @if (!initialPassword()) {
                <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                  @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                  Enregistrer
                </button>
              }
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class UserListComponent implements OnInit {
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly t     = inject(TranslocoService);
  constructor(private http: HttpClient) {}

  users = signal<User[]>([]);
  roles = signal<Role[]>([]);
  loading = signal(true);
  page = signal(0);
  pageSize = signal(20);
  totalElements = signal(0);
  sortCol = signal<SortCol>('lastName');
  sortDir = signal<SortDir>('asc');

  search = signal('');
  roleFilter = signal('');
  statusFilter = signal('');
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  showModal = signal(false);
  editingId = signal<number | null>(null);
  saving = signal(false);
  errorMsg = signal('');
  initialPassword = signal<string | null>(null);
  showResetModal = signal(false);
  resetResult = signal<{ userName: string; pwd: string } | null>(null);

  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  form: { firstName: string; lastName: string; email: string; roleId: number } = {
    firstName: '', lastName: '', email: '', roleId: 0
  };

  hasFilters = () => !!this.search() || !!this.roleFilter() || !!this.statusFilter();

  ngOnInit(): void {
    this.load();
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe(r => this.roles.set(r));
  }

  load(): void {
    this.loading.set(true);
    let params = new HttpParams()
      .set('page', this.page())
      .set('size', this.pageSize())
      .set('sort', `${this.sortCol()},${this.sortDir()}`);
    const s = this.search().trim();
    if (s) params = params.set('search', s);
    if (this.roleFilter()) params = params.set('roleId', this.roleFilter());
    if (this.statusFilter()) params = params.set('active', this.statusFilter());
    this.http.get<PagedResponse<User>>(`${environment.apiUrl}/users`, { params }).subscribe({
      next: res => {
        this.users.set(res.content);
        this.totalElements.set(res.totalElements);
        const maxPage = Math.max(0, Math.ceil(res.totalElements / this.pageSize()) - 1);
        if (this.page() > maxPage) { this.page.set(maxPage); }
        this.loading.set(false);
      },
      error: () => { this.users.set([]); this.totalElements.set(0); this.loading.set(false); this.toast.error('Impossible de charger les utilisateurs.'); }
    });
  }

  onPage(n: number): void { this.page.set(n); this.load(); }
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.load(); }

  onSearchChange(value: string): void {
    this.search.set(value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => { this.page.set(0); this.load(); }, 300);
  }
  onRoleChange(value: string): void { this.roleFilter.set(value); this.page.set(0); this.load(); }
  onStatusChange(value: string): void { this.statusFilter.set(value); this.page.set(0); this.load(); }
  clearFilters(): void {
    this.search.set(''); this.roleFilter.set(''); this.statusFilter.set('');
    this.page.set(0); this.load();
  }

  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    this.page.set(0);
    this.load();
  }
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  openCreate(): void {
    this.editingId.set(null);
    this.form = { firstName: '', lastName: '', email: '', roleId: 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  openEdit(u: User): void {
    this.editingId.set(u.id);
    const role = this.roles().find(r => r.name === u.roleName);
    this.form = { firstName: u.firstName, lastName: u.lastName, email: u.email, roleId: role?.id ?? 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  save(): void {
    if (!this.form.firstName || !this.form.lastName || !this.form.email || !this.form.roleId) {
      this.errorMsg.set(this.t.translate('admin.users.msg.allRequired'));
      return;
    }
    this.saving.set(true);
    this.errorMsg.set('');
    const id = this.editingId();
    if (id) {
      this.http.put<User>(`${environment.apiUrl}/users/${id}`, this.form).subscribe({
        next: () => { this.load(); this.showModal.set(false); this.saving.set(false); this.toast.success(this.t.translate('admin.users.msg.updated')); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    } else {
      this.http.post<UserCreateResult>(`${environment.apiUrl}/users`, this.form).subscribe({
        next: (res) => { this.load(); this.saving.set(false); this.initialPassword.set(res.initialPassword); this.toast.success(this.t.translate('admin.users.msg.created')); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    }
  }

  copyPassword(): void {
    const pwd = this.initialPassword();
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  async resetAccount(u: User): Promise<void> {
    const confirmed = await this.confirm.ask(
      this.t.translate('admin.users.msg.resetConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.resetConfirmTitle')
    );
    if (!confirmed) return;
    this.http.patch<UserCreateResult>(`${environment.apiUrl}/users/${u.id}/reset-account`, {}).subscribe({
      next: (res) => {
        this.resetResult.set({ userName: `${u.firstName} ${u.lastName}`, pwd: res.initialPassword });
        this.showResetModal.set(true);
        this.load();
      },
      error: () => this.toast.error(this.t.translate('admin.users.msg.resetError'))
    });
  }

  copyResetPassword(): void {
    const pwd = this.resetResult()?.pwd;
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  async deactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.deactivateConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.deactivateTitle'))) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/deactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.deactivated')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.deactivateError'))
    });
  }

  async reactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.reactivateConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.reactivateTitle'))) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/reactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.reactivated')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.reactivateError'))
    });
  }
}
