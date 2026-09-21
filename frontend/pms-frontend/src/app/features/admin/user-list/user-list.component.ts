import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { User, UserCreateResult } from '../../../core/models/user.model';
import { PagedResponse } from '../../../core/models/pagination.model';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { AuthService } from '../../../core/services/auth.service';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { environment } from '../../../../environments/environment';

// The "Gestion des utilisateurs" admin screen: toolbar (search/role/status filters), sortable
// paged accounts table, create/edit modal, and the one-time-password modal from a reset.
// Calls HttpClient directly (no UserService) since these DTOs are only used here; the real
// authorization check lives on the backend (@PreAuthorize), not in this component.

// Shape of one row of GET /api/roles. Declared here (not core/models) since only this screen reads it.
interface Role { id: number; name: string; }
// The only two sortable columns; these are Spring Data entity field names (?sort=lastName,asc),
// not free text. A union catches a typo'd column at compile time instead of a failed request.
type SortCol = 'lastName' | 'email';
// The two sort directions, again spelled exactly the way Spring Data expects them.
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-user-list',
  // Lets app.routes.ts lazy-load this screen on its own (loadComponent).
  standalone: true,
  // Loads the admin translation bundle, kept out of the main bundle since only a few screens need it.
  providers: [provideTranslocoScope('admin')],
  // Dependencies used by the inline template below; a missing entry fails the template build.
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  // Styles are view-encapsulated to this component only.
  styles: [`
    /* flex-wrap lets filters drop to a second line instead of overflowing on narrow windows. */
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    /* user-select:none stops a double click on a header from selecting text instead of sorting. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* Caret is invisible at rest, fades in on hover; the sorted column keeps it fully visible. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible, not :focus - ring shows for keyboard nav only, not after a mouse click. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    .u-name { font-weight:600; color:var(--text-1); }
    .u-mail { color:var(--text-2); }
    /* Design-system variables so colors stay correct in both light and dark theme. */
    .act-info { color:var(--c-brand); }
    .act-warn { color:var(--c-warning); }
    .act-ok   { color:var(--c-success); }
    .act-danger { color:var(--c-danger); }

    /* Four widths so skeleton rows look like real, uneven data rather than identical blocks. */
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-55{width:55%} .sk-w-70{width:70%} .sk-w-30{width:30%}
    .pw-field { }
  `],
  template: `
    <!-- Top bar of the page: breadcrumb on the left, main action on the right. -->
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
        <!-- Toolbar: free-text search, role filter, status filter, reset, result count. -->
        <div class="toolbar">
          <div class="input-wrap" style="flex:1;min-width:200px;max-width:320px">
            <i class="bi bi-search input-icon"></i>
            <!-- One-way [ngModel] + (ngModelChange), not [(ngModel)]: lets onSearchChange() debounce before calling the server. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.users.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearchChange($event)"
                   [attr.aria-label]="'admin.users.search.aria' | transloco">
          </div>

          <!-- Empty value means "no filter"; load() only adds the query param when it's set. -->
          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="roleFilter()" (ngModelChange)="onRoleChange($event)" [attr.aria-label]="'admin.users.filter.roleAria' | transloco">
            <option value="">{{ 'admin.users.filter.allRoles' | transloco }}</option>
            <!-- track r.id: keeps <option> elements stable across roles() updates instead of rebuilding them. -->
            @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
          </select>

          <!-- Values are the strings "true"/"false" on purpose: sent as ?active=true/false for Spring's Boolean param. -->
          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="statusFilter()" (ngModelChange)="onStatusChange($event)" [attr.aria-label]="'admin.users.filterStatusAria' | transloco">
            <option value="">{{ 'admin.users.filter.allStatuses' | transloco }}</option>
            <option value="true">{{ 'admin.users.state.active' | transloco }}</option>
            <option value="false">{{ 'admin.users.state.inactive' | transloco }}</option>
          </select>

          <!-- Reset button only appears once a filter is set, so the bar stays clean on a fresh page. -->
          @if (hasFilters()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>{{ 'admin.users.search.reset' | transloco }}
            </button>
          }

          <!-- Singular/plural are separate keys (pluralization rules differ by language); count is totalElements, not users().length. -->
          <span class="count">{{ (totalElements() === 1 ? 'admin.users.results.one' : 'admin.users.results.other') | transloco: { count: totalElements() } }}</span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <!-- tabindex + keydown handlers make this <th> keyboard-operable; preventDefault stops space from scrolling the page. -->
                <th class="th-sort" [class.is-sorted]="sortCol()==='lastName'" [attr.aria-sort]="ariaSort('lastName')"
                    tabindex="0" (click)="toggleSort('lastName')" (keydown.enter)="toggleSort('lastName')" (keydown.space)="toggleSort('lastName'); $event.preventDefault()">
                  <span class="th-inner">{{ 'admin.users.table.name' | transloco }} <i class="bi caret" [ngClass]="caret('lastName')"></i></span>
                </th>
                <!-- "Email" is plain text, not a translation key: the word is identical in French and English. -->
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
              <!-- Skeleton rows (not a spinner) keep the table's size, so the page doesn't jump when data arrives. -->
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
                <!-- track u.id: reuses the same <tr> for the same person after a reload instead of rebuilding the table. -->
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
                      <!-- Buttons depend on account state: an inactive account can only be reactivated, never deactivated again. -->
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
                      <!-- Disabled on your own row: deleting yourself would revoke this very session's token. -->
                      <button class="btn btn-ghost btn-icon btn-sm"
                              [disabled]="isSelf(u)"
                              (click)="remove(u)"
                              [title]="(isSelf(u) ? 'admin.users.msg.cannotDeleteSelf' : 'admin.users.actions.delete') | transloco"
                              [attr.aria-label]="(isSelf(u) ? 'admin.users.msg.cannotDeleteSelf' : 'admin.users.actions.deleteAria') | transloco"
                              style="color:var(--c-danger,#dc3545)">
                        <i class="bi bi-trash"></i>
                      </button>
                    </td>
                  </tr>
                }
                @empty {
                  <tr>
                    <!-- colspan="5" spans the empty message across all columns. -->
                    <td colspan="5">
                      <!-- Two different empty states: "no match" (with reset) vs "no user yet" (with create). -->
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

        <!-- Shared pager (project rule: reuse it everywhere); hidden while loading or empty. -->
        @if (!loading() && totalElements() > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="totalElements()"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>

    <!-- Password reset modal: shows the one-time password once, since only its BCrypt hash is stored server-side. -->
    @if (showResetModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Clicking outside the dialog closes it; stopPropagation keeps a click inside from bubbling up to that handler. -->
      <!-- No backdrop-click dismiss here on purpose: a stray click just outside the dialog must not silently discard a half-filled form. Cancel, the X and Escape still close it. -->
      <div class="modal d-block" tabindex="-1">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-key me-2 act-info"></i>{{ 'admin.users.reset.title' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showResetModal.set(false)"></button>
            </div>
            <div class="modal-body">
              @if (resetResult()) {
                <p class="mb-3" style="font-size:13px">
                  <!-- Name is a translation param, not concatenated, so each language can place it per its grammar. -->
                  {{ 'admin.users.reset.done' | transloco: { name: resetResult()!.userName } }}
                  {{ 'admin.users.reset.mustChange' | transloco }}
                </p>
                <div class="alert alert-warning py-2" style="font-size:12px">
                  <i class="bi bi-exclamation-triangle me-1"></i>
                  {{ 'admin.users.reset.communicate' | transloco }}
                </div>
                <div class="input-group mt-2">
                  <!-- type="text", not "password": the field exists to be read and copied. readonly prevents edits. -->
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

    <!-- Create/edit modal: editingId() decides whether save() sends a POST or a PUT. -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- No backdrop-click dismiss here on purpose: a stray click just outside the dialog must not silently discard a half-filled form. Cancel, the X and Escape still close it. -->
      <div class="modal d-block" tabindex="-1">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <!-- The title alone tells the two modes apart. -->
              <h5 class="modal-title">{{ (editingId() ? 'admin.users.form.editTitle' : 'admin.users.form.newTitle') | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'admin.users.form.firstName' | transloco }} <span class="text-danger">*</span></label>
                  <!-- Two-way [(ngModel)] here: 'form' is a plain object, not a signal, so nothing needs debouncing. -->
                  <input type="text" class="form-control" [(ngModel)]="form.firstName" [placeholder]="'admin.users.form.firstName' | transloco" autocomplete="given-name">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'admin.users.form.lastName' | transloco }} <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.lastName" [placeholder]="'admin.users.form.lastName' | transloco" autocomplete="family-name">
                </div>
                <div class="col-12">
                  <label class="form-label">Email <span class="text-danger">*</span></label>
                  <!-- type="email" gives a rough browser check; only the server knows if the address is already taken. -->
                  <input type="email" class="form-control" [(ngModel)]="form.email" placeholder="email@example.com" autocomplete="email">
                </div>
                <div class="col-12">
                  <label class="form-label">{{ 'admin.users.form.role' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="form.roleId">
                    <!-- 0 is the disabled "nothing chosen yet" placeholder; save() refuses roleId 0. -->
                    <option [value]="0" disabled>{{ 'admin.users.form.selectRole' | transloco }}</option>
                    <!-- Role list comes from the database (dynamic authorization), never a hard-coded list. -->
                    @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
                  </select>
                </div>
              </div>
              <!-- Server-side error shown inside the modal, not as a toast, so the form stays visible to correct. -->
              @if (errorMsg()) { <div class="alert alert-danger py-2 mt-3">{{ errorMsg() }}</div> }
              <!-- After a successful creation, the modal switches from a form to showing the initial password. -->
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
              <!-- Once the account exists there's nothing to cancel, so wording switches from "Cancel" to "Close". -->
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ (initialPassword() ? 'common.close' : 'common.cancel') | transloco }}</button>
              <!-- Save button disappears after creation, to avoid a duplicate POST of the same form. -->
              @if (!initialPassword()) {
                <!-- [disabled]="saving()" guards against a double click sending two POSTs. -->
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
/**
 * Controller of the users screen: table state (page/sort/filters), the two modals, and the HTTP calls.
 * State is kept in signals so the template redraws reactively. No role test here - authorization
 * is permission-based and enforced on the backend; hiding UI is comfort, not security.
 */
export class UserListComponent implements OnInit {
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  // Used only to block deleting your own account (auth.currentUserId) — the real permission
  // check for the delete call itself is the backend's @PreAuthorize, not this.
  private readonly auth = inject(AuthService);
  // Programmatic twin of the transloco pipe, for translating strings in TypeScript (toasts, confirm dialogs).
  private readonly t     = inject(TranslocoService);
  // HttpClient via constructor injection; mixed with inject() above simply reflects how the file grew.
  constructor(private http: HttpClient) {}

  // Rows of the CURRENT page only - never the whole table.
  users = signal<User[]>([]);
  // Loaded once in ngOnInit, reused by both the filter and the form.
  roles = signal<Role[]>([]);
  // Starts true so the first paint shows skeleton rows instead of a flash of "no user yet".
  loading = signal(true);
  // 0-indexed, like Spring Data - sending 1 for the first page would skip the first 20 accounts.
  page = signal(0);
  pageSize = signal(20);
  // Count across ALL pages (feeds the counter and pager), not just the current page.
  totalElements = signal(0);
  // Matches UserController's @PageableDefault so the first page looks the same either way.
  sortCol = signal<SortCol>('lastName');
  sortDir = signal<SortDir>('asc');

  // Kept as strings since that's what the form controls and query string use; empty means "not set".
  search = signal('');
  roleFilter = signal('');
  statusFilter = signal('');
  // ReturnType<typeof setTimeout>, not `number`: setTimeout's return type differs between browser and Node typings.
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  // State of the create/edit modal.
  showModal = signal(false);
  // null = creating, a number = editing that account id; save() reads this to choose POST vs PUT.
  editingId = signal<number | null>(null);
  saving = signal(false);
  errorMsg = signal('');
  // Lives only in memory; dropped as soon as the modal is reopened.
  initialPassword = signal<string | null>(null);
  // State of the password-reset modal: the flag, plus the name and password to display.
  showResetModal = signal(false);
  resetResult = signal<{ userName: string; pwd: string } | null>(null);

  // Dummy array only to repeat the skeleton placeholder eight times in @for.
  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  // Plain object, not a signal: bound via [(ngModel)], nothing needs reactive updates per keystroke.
  // roleId starts at 0, the disabled placeholder value, which save() treats as "no role chosen".
  form: { firstName: string; lastName: string; email: string; roleId: number } = {
    firstName: '', lastName: '', email: '', roleId: 0
  };

  // Drives the reset button and which empty state is shown. Arrow-function field so the template can call it.
  hasFilters = () => !!this.search() || !!this.roleFilter() || !!this.statusFilter();

  // HTTP calls belong here, not in the constructor, which should only wire dependencies.
  ngOnInit(): void {
    this.load();
    // Fetched once: roles change rarely, and both the filter and form dropdowns read this signal.
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe(r => this.roles.set(r));
  }

  /**
   * Asks the server for one page of accounts (page/size/sort/filters) into the signals the table reads.
   * Filtering happens server-side because users() only ever holds the current page's 20 rows.
   */
  load(): void {
    this.loading.set(true);
    // HttpParams is immutable: .set() returns a new object, so the result must be reassigned each time.
    let params = new HttpParams()
      .set('page', this.page())
      .set('size', this.pageSize())
      // Spring Data expects "field,direction"; without a sort the row order is undefined.
      .set('sort', `${this.sortCol()},${this.sortDir()}`);
    const s = this.search().trim();
    // Only added when non-empty: an empty search= would filter on "contains nothing" instead of no filter.
    if (s) params = params.set('search', s);
    if (this.roleFilter()) params = params.set('roleId', this.roleFilter());
    if (this.statusFilter()) params = params.set('active', this.statusFilter());
    this.http.get<PagedResponse<User>>(`${environment.apiUrl}/users`, { params }).subscribe({
      next: res => {
        this.users.set(res.content);
        this.totalElements.set(res.totalElements);
        // Clamp for the case where a new filter leaves the current page past the end of the results.
        const maxPage = Math.max(0, Math.ceil(res.totalElements / this.pageSize()) - 1);
        if (this.page() > maxPage) { this.page.set(maxPage); }
        this.loading.set(false);
      },
      // Empties the table rather than leaving stale rows next to the error message.
      error: () => { this.users.set([]); this.totalElements.set(0); this.loading.set(false); this.toast.error('Impossible de charger les utilisateurs.'); }
    });
  }

  // Called by the shared pager when the user moves to another page.
  onPage(n: number): void { this.page.set(n); this.load(); }
  // Also resets to page 0: staying on page 4 after growing the page size could land past the end.
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.load(); }

  /**
   * Debounces search-box keystrokes 300ms before querying, so fast typing doesn't fire a request
   * per character (and risk out-of-order responses overwriting the latest one).
   */
  onSearchChange(value: string): void {
    // The box itself must stay instant, so the signal is written straight away.
    this.search.set(value);
    // Cancels the previous keystroke's timer; clearTimeout on undefined is harmless.
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => { this.page.set(0); this.load(); }, 300);
  }
  // No debounce needed for dropdowns: one click is one intention. Both reset the page, like search.
  onRoleChange(value: string): void { this.roleFilter.set(value); this.page.set(0); this.load(); }
  onStatusChange(value: string): void { this.statusFilter.set(value); this.page.set(0); this.load(); }
  // Sort is deliberately kept: the user asked to drop filters, not change column order.
  clearFilters(): void {
    this.search.set(''); this.roleFilter.set(''); this.statusFilter.set('');
    this.page.set(0); this.load();
  }

  /**
   * Same column = flip direction, different column = sort by it ascending - the usual table convention.
   */
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    this.page.set(0);
    this.load();
  }
  // aria-sort for screen readers, since the colored arrow only conveys sort state visually.
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  // Shared by both sortable columns so the three-way icon choice isn't duplicated in the template.
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  // Opens the modal in "create" mode; resets fields by hand since the same component state is reused.
  openCreate(): void {
    // null is what tells save() to send a POST.
    this.editingId.set(null);
    this.form = { firstName: '', lastName: '', email: '', roleId: 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  // Opens the same modal in "edit" mode, pre-filled with one account.
  openEdit(u: User): void {
    // The id is what makes save() send a PUT on this account.
    this.editingId.set(u.id);
    // The row carries the role NAME (for display); look up its id from the role list loaded in ngOnInit.
    const role = this.roles().find(r => r.name === u.roleName);
    // ?? 0 falls back to the disabled placeholder if no role matches (e.g. roles not loaded yet).
    this.form = { firstName: u.firstName, lastName: u.lastName, email: u.email, roleId: role?.id ?? 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  // Sends the form: POST when creating, PUT when editing. One method for both since only the verb/URL differ.
  save(): void {
    // Cheap browser-side check; does NOT replace backend @Valid validation, which the server still runs.
    if (!this.form.firstName || !this.form.lastName || !this.form.email || !this.form.roleId) {
      this.errorMsg.set(this.t.translate('admin.users.msg.allRequired'));
      return;
    }
    // Locks the save button until the answer comes back (see [disabled] in the template).
    this.saving.set(true);
    this.errorMsg.set('');
    const id = this.editingId();
    if (id) {
      this.http.put<User>(`${environment.apiUrl}/users/${id}`, this.form).subscribe({
        // Reloads rather than patching in memory: the server may have changed more than what was sent
        // (a role change also revokes sessions, ADR-017), so re-reading is the only reliable source.
        next: () => { this.load(); this.showModal.set(false); this.saving.set(false); this.toast.success(this.t.translate('admin.users.msg.updated')); },
        // ?./?? cover a network failure with no JSON body, so the user never sees "undefined".
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    } else {
      // UserCreateResult = the saved account plus the initial clear-text password, returned exactly once.
      this.http.post<UserCreateResult>(`${environment.apiUrl}/users`, this.form).subscribe({
        // Modal stays open (unlike the edit branch) to show the password, which can't be read again later.
        next: (res) => { this.load(); this.saving.set(false); this.initialPassword.set(res.initialPassword); this.toast.success(this.t.translate('admin.users.msg.created')); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    }
  }

  // Copies the initial password to the clipboard. Empty .catch(): clipboard API can be refused
  // (no HTTPS, denied permission) and the password stays visible on screen either way.
  copyPassword(): void {
    const pwd = this.initialPassword();
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  /**
   * Resets one account after confirmation: PATCH /api/users/{id}/reset-account. The backend
   * (UserCrudService.resetAccount) draws a new password, forces a change on next login, and
   * revokes existing sessions (ADR-017) - the admin's substitute for a "forgot password" email,
   * since the app sends none.
   */
  async resetAccount(u: User): Promise<void> {
    const confirmed = await this.confirm.ask(
      this.t.translate('admin.users.msg.resetConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.resetConfirmTitle')
    );
    if (!confirmed) return;
    // Empty body: the server draws the new password itself; HttpClient.patch still requires a body arg.
    this.http.patch<UserCreateResult>(`${environment.apiUrl}/users/${u.id}/reset-account`, {}).subscribe({
      next: (res) => {
        this.resetResult.set({ userName: `${u.firstName} ${u.lastName}`, pwd: res.initialPassword });
        this.showResetModal.set(true);
        this.load();
      },
      error: () => this.toast.error(this.t.translate('admin.users.msg.resetError'))
    });
  }

  // Same clipboard copy as copyPassword(), for the reset modal; ?. since resetResult() is null until answered.
  copyResetPassword(): void {
    const pwd = this.resetResult()?.pwd;
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  // Deactivate rather than delete: the account is referenced by projects/tasks/time entries that must keep pointing at a real name.
  async deactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.deactivateConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.deactivateTitle'))) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/deactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.deactivated')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.deactivateError'))
    });
  }

  // Reverses deactivate(); also confirmed, since restoring access is as sensitive as removing it.
  async reactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.reactivateConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.reactivateTitle'))) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/reactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.reactivated')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.reactivateError'))
    });
  }

  /** True while u is the signed-in admin's own account — the delete button stays disabled for
   *  it so an admin can't lock themselves out (deleting revokes every token, this session's too). */
  isSelf(u: User): boolean {
    return u.id === this.auth.currentUserId;
  }

  // Soft delete (UserCrudService.delete): the row stays for projects/tasks/time entries that
  // reference it, only "deleted" flips and every token is revoked. Unlike deactivate(), the
  // account then disappears from every admin list for good, so the confirm text says so.
  async remove(u: User): Promise<void> {
    if (this.isSelf(u)) return;
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.deleteConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.deleteTitle'))) return;
    this.http.delete(`${environment.apiUrl}/users/${u.id}`).subscribe({
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.deleted')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.deleteError'))
    });
  }
}
