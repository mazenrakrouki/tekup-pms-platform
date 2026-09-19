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

/*
 * FILE: user-list.component.ts
 *
 * WHAT THIS FILE IS
 * The whole "Gestion des utilisateurs" screen of the admin area, in one standalone Angular
 * component: the toolbar (search, role filter, status filter), the sortable and paged table
 * of accounts, the create/edit modal, and the modal that shows the one-time password
 * produced by a password reset.
 *
 * WHERE IT SITS IN THE FLOW
 *   app.routes.ts, path 'admin/users' -> permissionGuard with data.permission =
 *   'MANAGE_USERS' -> this component is lazy-loaded (loadComponent) -> it talks to the
 *   backend directly through HttpClient:
 *       GET   /api/roles                     -> fills the two role drop-downs
 *       GET   /api/users?page&size&sort&...  -> one page of accounts (UserController.list)
 *       POST  /api/users                     -> create, answers a UserCreateResult
 *       PUT   /api/users/{id}                -> update
 *       PATCH /api/users/{id}/reset-account  -> new one-time password
 *       PATCH /api/users/{id}/deactivate     -> switch the account off
 *       PATCH /api/users/{id}/reactivate     -> switch it back on
 *   Every one of those requests passes through core/interceptors/auth.interceptor.ts, which
 *   attaches the short-lived JWT access token. The real authorization check is NOT here: it
 *   lives on the SERVICE methods of the backend as
 *   @PreAuthorize("hasAuthority('MANAGE_USERS')"). The route guard in front of this screen
 *   only hides a page the server would refuse anyway.
 *   It also uses three shared pieces: ConfirmService (the application's "are you sure?"),
 *   ToastService (the small transient messages), and <app-pagination> (the shared pager).
 *
 * WHY IT EXISTS
 * Without it there is no way to create an account, change somebody's role, hand out a new
 * password, or switch an account off. Accounts could then only be inserted by hand in
 * PostgreSQL, and nobody could be locked out when they leave the company.
 *
 * WHY IT CALLS HttpClient DIRECTLY INSTEAD OF A UserService
 * Every call here is used by this screen and by no other screen, and the payloads are the
 * plain DTOs of UserController (a DTO, Data Transfer Object, is the flat object used to
 * carry data between the server and the browser). A dedicated service would be one extra
 * file that only forwards calls. The shapes are still typed (User, UserCreateResult,
 * PagedResponse<User>), so a wrong field name is caught by the compiler, which is the part
 * that actually matters.
 *
 * WHY THE TABLE IS PAGED BY THE SERVER AND NOT LOADED WHOLE
 * The account table grows with the company. Asking the server for 20 rows at a time keeps
 * the first paint fast and keeps the filtering and the sorting in SQL, where an index can
 * help. Sorting a 2000-row array in the browser on every keystroke would freeze the page.
 */

// The shape of one row of GET /api/roles: the backend answers a list with exactly these two
// keys. It is declared here, and not in core/models, because no other file needs it - the
// two <select> lists on this screen are its only readers.
// WHY type it at all: without it roles() would be any[], and a typo such as r.label would
// compile and quietly render an empty drop-down, with no error anywhere.
interface Role { id: number; name: string; }
// A union type listing the only two columns the table can be sorted by. These two strings
// are not free text: they are ENTITY FIELD names that travel to Spring Data as
// ?sort=lastName,asc and are turned into an ORDER BY on the SQL side.
// WHY a union and not plain string: a mistyped column such as 'lastname' would be refused by
// Spring at request time with a property error, and the table would simply fail to load. The
// union makes that mistake impossible to compile.
type SortCol = 'lastName' | 'email';
// The two sort directions, again spelled exactly the way Spring Data expects them.
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-user-list',
  // standalone means the component declares its own dependencies in `imports` below and
  // needs no NgModule. That is what lets app.routes.ts lazy-load it on its own: the
  // JavaScript of this screen is only downloaded when an administrator opens it.
  standalone: true,
  // provideTranslocoScope('admin') tells Transloco to also load the translation file of the
  // admin area for this component and its children.
  // WHY: the admin wording is only needed by three screens, so it is kept out of the main
  // translation bundle that every user downloads. Without this line every key used below,
  // such as 'admin.users.title', would be printed on screen as the raw key text.
  providers: [provideTranslocoScope('admin')],
  // The dependencies used by the inline template: CommonModule for ngClass, FormsModule for
  // [(ngModel)], the shared pager, and the transloco pipe. A missing entry here is not a
  // silent bug - the template fails to compile.
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  // Component styles are scoped to this component only: Angular adds a unique attribute to
  // these elements and to these rules. A plain `.toolbar` written here can therefore never
  // repaint a .toolbar on another screen.
  // Inside this block only /* */ comments are legal.
  styles: [`
    /* The filter bar above the table. flex-wrap lets the filters drop onto a second line on
       a narrow window instead of overflowing sideways. */
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    /* margin-left:auto pushes the "N results" counter to the far right of the bar, whatever
       number of filters sit on its left. white-space:nowrap stops that short sentence from
       being cut in two when the bar gets tight. */
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    /* The two clickable column headers. user-select:none stops a double click on a header
       from selecting the text instead of just sorting. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* The small arrow is invisible at rest and only fades in on hover, so an untouched table
       stays quiet; the column actually sorted keeps it at full opacity. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible, not :focus - the ring is drawn for keyboard users only and does not
       appear after a mouse click. Without it a keyboard user tabbing through the headers
       would have no idea where he is. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    .u-name { font-weight:600; color:var(--text-1); }
    .u-mail { color:var(--text-2); }
    /* One colour per action button, taken from the design-system variables so that both the
       light and the dark theme stay correct. */
    .act-info { color:var(--c-brand); }
    .act-warn { color:var(--c-warning); }
    .act-ok   { color:var(--c-success); }
    .act-danger { color:var(--c-danger); }

    /* The grey bars shown while the page is loading. Four widths are used so the fake rows
       look like real, uneven data instead of a block of identical rectangles. */
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-55{width:55%} .sk-w-70{width:70%} .sk-w-30{width:30%}
    .pw-field { }
  `],
  // The template is written inline, inside backticks, as in every component of this project.
  // Inside this HTML only <!-- --> comments are legal: // or /* */ would be printed on the
  // page as text or break the parsing.
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
            <!-- [ngModel] one-way plus (ngModelChange), NOT [(ngModel)]. The value is read
                 from the signal, and every keystroke goes through onSearchChange(), which
                 waits 300 ms before calling the server. With a plain [(ngModel)] the signal
                 would be written directly and there would be no place left to hold that
                 delay, so typing "martin" would fire six /api/users requests. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.users.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearchChange($event)"
                   [attr.aria-label]="'admin.users.search.aria' | transloco">
          </div>

          <!-- Role filter. The empty value means "no filter", which is why load() can simply
               test the string before adding the query parameter. -->
          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="roleFilter()" (ngModelChange)="onRoleChange($event)" [attr.aria-label]="'admin.users.filter.roleAria' | transloco">
            <option value="">{{ 'admin.users.filter.allRoles' | transloco }}</option>
            <!-- track r.id lets Angular match the existing <option> elements with the new
                 list when roles() changes, instead of destroying and rebuilding them.
                 Without a stable track key the option chosen by the user could be thrown
                 away and the filter would reset itself. -->
            @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
          </select>

          <!-- Status filter. The values are the strings "true" and "false" on purpose: they
               are sent as ?active=true / ?active=false and Spring converts them into the
               Boolean parameter of UserController.list. -->
          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="statusFilter()" (ngModelChange)="onStatusChange($event)" [attr.aria-label]="'admin.users.filterStatusAria' | transloco">
            <option value="">{{ 'admin.users.filter.allStatuses' | transloco }}</option>
            <option value="true">{{ 'admin.users.state.active' | transloco }}</option>
            <option value="false">{{ 'admin.users.state.inactive' | transloco }}</option>
          </select>

          <!-- The reset button only exists while at least one filter is set, so the bar
               stays clean on a fresh page. -->
          @if (hasFilters()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>{{ 'admin.users.search.reset' | transloco }}
            </button>
          }

          <!-- Singular and plural are two different translation keys, because the rule is not
               the same in every language. The number shown is totalElements (all pages),
               never users().length, which would say "20 results" on every page. -->
          <span class="count">{{ (totalElements() === 1 ? 'admin.users.results.one' : 'admin.users.results.other') | transloco: { count: totalElements() } }}</span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <!-- Sortable header. Three things happen here:
                     [class.is-sorted] paints the column that is currently sorted;
                     [attr.aria-sort] tells a screen reader "ascending"/"descending"/"none";
                     tabindex="0" plus the two keydown handlers make a <th> reachable and
                     usable with the keyboard, which a <th> is not by default.
                     $event.preventDefault() on space stops the browser from scrolling the
                     page down while the user only meant to sort. -->
                <th class="th-sort" [class.is-sorted]="sortCol()==='lastName'" [attr.aria-sort]="ariaSort('lastName')"
                    tabindex="0" (click)="toggleSort('lastName')" (keydown.enter)="toggleSort('lastName')" (keydown.space)="toggleSort('lastName'); $event.preventDefault()">
                  <span class="th-inner">{{ 'admin.users.table.name' | transloco }} <i class="bi caret" [ngClass]="caret('lastName')"></i></span>
                </th>
                <!-- "Email" is written as plain text and not as a translation key because the
                     word is the same in French and in English. -->
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
              <!-- While the request is in flight the table shows eight grey rows of the same
                   height as real rows. Why not a spinner: the table keeps its size, so the
                   page does not jump when the data arrives. -->
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
                <!-- track u.id: the row is identified by its database id, so after a reload
                     Angular reuses the same <tr> for the same person instead of rebuilding
                     the whole table. -->
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
                      <!-- The action buttons depend on the state of the account: an active
                           account can be reset or switched off, a switched-off account can
                           only be switched back on. Showing "deactivate" on an account that
                           is already inactive would send a request the server answers with
                           an error the user cannot understand. -->
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
                <!-- @empty runs when users() is an empty array. -->
                @empty {
                  <tr>
                    <!-- colspan="5" makes the empty message span the five columns; without it
                         the message would be squeezed inside the first column. -->
                    <td colspan="5">
                      <!-- Two different empty screens on purpose. "Nothing matches your
                           filters", with a reset button, is a completely different problem
                           from "there is no user yet", with a create button. One single
                           message would send the administrator looking for a bug when he has
                           only typed a search term that matches nobody. -->
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

        <!-- The shared pager, reused instead of rewritten (project rule: the same pager on
             every long list). It is hidden while loading and when there is nothing to page,
             so an empty table does not show "page 1 of 1".
             page/pageSize/total go down as inputs, and the two outputs come back up into
             onPage()/onPageSize(), which ask the server for the new page. -->
        @if (!loading() && totalElements() > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="totalElements()"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>

    <!-- Password reset modal. It is shown only after the server has answered, and its only
         job is to display the one-time password once - the server keeps only a BCrypt hash
         of it (BCrypt is a one-way hashing function, so the clear value cannot be read back
         from the database). If the administrator closes this window without copying the
         password, the only way out is another reset. -->
    @if (showResetModal()) {
      <!-- The grey overlay behind the modal. It is a separate element because Bootstrap
           styles the backdrop and the dialog separately. -->
      <div class="modal-backdrop fade show"></div>
      <!-- Clicking anywhere outside the dialog closes it... -->
      <div class="modal d-block" tabindex="-1" (click)="showResetModal.set(false)">
        <!-- ...and $event.stopPropagation() keeps a click INSIDE the dialog from bubbling up
             to that handler. Without this line, selecting the password with the mouse would
             close the window and lose the password. -->
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-key me-2 act-info"></i>{{ 'admin.users.reset.title' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showResetModal.set(false)"></button>
            </div>
            <div class="modal-body">
              @if (resetResult()) {
                <p class="mb-3" style="font-size:13px">
                  <!-- The name is passed as a parameter of the translation, not glued to it,
                       so each language can put it where its grammar needs it. -->
                  {{ 'admin.users.reset.done' | transloco: { name: resetResult()!.userName } }}
                  {{ 'admin.users.reset.mustChange' | transloco }}
                </p>
                <div class="alert alert-warning py-2" style="font-size:12px">
                  <i class="bi bi-exclamation-triangle me-1"></i>
                  {{ 'admin.users.reset.communicate' | transloco }}
                </div>
                <div class="input-group mt-2">
                  <!-- type="text" and not type="password": the whole point of this field is
                       to be read and copied. readonly stops the administrator from editing
                       what he believes is the real password. The "!" after resetResult() is
                       the non-null assertion; it is safe here because the whole block sits
                       inside the @if above. -->
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

    <!-- Create / edit modal. One single modal for both jobs: the fields are identical, and
         editingId() decides whether save() sends a POST or a PUT. Two separate modals would
         be the same HTML written twice. -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
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
                  <!-- [(ngModel)] here, two-way, because 'form' is a plain object and not a
                       signal: there is nothing to debounce and no request to send while the
                       user types. autocomplete="given-name" lets the browser offer the right
                       stored value instead of a random one. -->
                  <input type="text" class="form-control" [(ngModel)]="form.firstName" [placeholder]="'admin.users.form.firstName' | transloco" autocomplete="given-name">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'admin.users.form.lastName' | transloco }} <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.lastName" [placeholder]="'admin.users.form.lastName' | transloco" autocomplete="family-name">
                </div>
                <div class="col-12">
                  <label class="form-label">Email <span class="text-danger">*</span></label>
                  <!-- type="email" gives a keyboard with @ on a phone and a first, rough
                       browser check. The real check is the backend one: only the server knows
                       whether the address is already taken. -->
                  <input type="email" class="form-control" [(ngModel)]="form.email" placeholder="email@example.com" autocomplete="email">
                </div>
                <div class="col-12">
                  <label class="form-label">{{ 'admin.users.form.role' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="form.roleId">
                    <!-- The 0 option is disabled: it is the "nothing chosen yet" placeholder
                         and must not be selectable, because save() refuses roleId 0. -->
                    <option [value]="0" disabled>{{ 'admin.users.form.selectRole' | transloco }}</option>
                    <!-- The role list comes from the database, never from a hard-coded list:
                         authorization is dynamic in this project, so a role created by an
                         administrator in the Roles screen appears here with no code change. -->
                    @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
                  </select>
                </div>
              </div>
              <!-- Server-side error, shown inside the modal and not as a toast: the
                   administrator must still see the form he has to correct. -->
              @if (errorMsg()) { <div class="alert alert-danger py-2 mt-3">{{ errorMsg() }}</div> }
              <!-- After a successful creation the same modal changes job: it stops being a
                   form and becomes the one and only place where the initial password is
                   shown. -->
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
              <!-- Once the account exists there is nothing left to cancel, so the same button
                   changes its wording from "Cancel" to "Close". -->
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ (initialPassword() ? 'common.close' : 'common.cancel') | transloco }}</button>
              <!-- The save button disappears after a successful creation. Why: clicking it
                   again would POST the same form a second time and try to create a duplicate
                   account. -->
              @if (!initialPassword()) {
                <!-- [disabled]="saving()" is the guard against the impatient double click:
                     without it, two clicks in a row would send two POST requests, and the
                     second one would fail on the unique e-mail constraint. -->
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
 * The controller of the users screen: it holds the state of the table (page, sort, filters),
 * the state of the two modals, and the seven HTTP calls listed in the file header.
 *
 * WHY THE STATE IS HELD IN SIGNALS
 * A signal is a value Angular watches: writing into it redraws exactly the parts of the
 * template that read it, without a change-detection pass over the whole page. With plain
 * fields the template would only be refreshed by chance, at the next global check, and a row
 * switched off inside a callback could stay drawn as active.
 *
 * WHY THERE IS NO ROLE TEST ANYWHERE IN THIS CLASS
 * Authorization is dynamic and permission-based, and the decision is taken on the backend
 * service methods (MANAGE_USERS). Anything hidden here is comfort, not security: hiding a
 * button in the browser stops nobody from sending the request by hand.
 */
export class UserListComponent implements OnInit {
  // inject() is the modern form of dependency injection. It is used for the three services
  // that are only called from methods.
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  // TranslocoService is the programmatic half of the transloco pipe: the pipe works inside
  // the template, this one translates strings inside TypeScript code, which is exactly what
  // the toasts and the confirmation questions below need.
  private readonly t     = inject(TranslocoService);
  // HttpClient is taken the classic way, through the constructor. Both styles are
  // equivalent; the mix here simply reflects how the file grew.
  constructor(private http: HttpClient) {}

  // The rows of the CURRENT page only - never the whole table.
  users = signal<User[]>([]);
  // The role list, loaded once in ngOnInit and reused by the filter and by the form.
  roles = signal<Role[]>([]);
  // Starts at true, not false: the very first paint already shows the grey skeleton rows.
  // Starting at false would show "no user yet" for a split second before the data lands,
  // which looks like a broken screen.
  loading = signal(true);
  // 0-indexed page, exactly like Spring Data. Sending 1 for the first page would silently
  // skip the first 20 accounts.
  page = signal(0);
  pageSize = signal(20);
  // How many accounts match the filters across ALL pages. It feeds the counter and the pager.
  totalElements = signal(0);
  // Default sort: by family name, ascending. It matches the @PageableDefault of
  // UserController.list, so the first page looks the same whether the parameter travels or
  // not.
  sortCol = signal<SortCol>('lastName');
  sortDir = signal<SortDir>('asc');

  // The three filters. They are kept as strings because that is what the form controls and
  // the query string use; the empty string means "filter not set".
  search = signal('');
  roleFilter = signal('');
  statusFilter = signal('');
  // The handle of the pending debounce timer. ReturnType<typeof setTimeout> is used instead
  // of `number` because setTimeout returns a number in the browser but an object in Node,
  // and the project is compiled with both type sets in scope; writing `number` would break
  // the build depending on which one wins.
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  // State of the create/edit modal.
  showModal = signal(false);
  // null = creating, a number = editing that account. This single value is what makes one
  // modal serve both jobs, and what save() reads to choose between POST and PUT.
  editingId = signal<number | null>(null);
  saving = signal(false);
  errorMsg = signal('');
  // The one-time password returned by a creation. It only ever lives in memory, and it is
  // dropped the moment the modal is opened again.
  initialPassword = signal<string | null>(null);
  // State of the password-reset modal: the flag, plus the name and password to display.
  showResetModal = signal(false);
  resetResult = signal<{ userName: string; pwd: string } | null>(null);

  // A fixed array used only to repeat the loading placeholder eight times. @for needs
  // something to iterate over, and this costs nothing because it is created once.
  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  // The edit form. A plain object, not a signal: it is bound with [(ngModel)] and nothing in
  // the template has to react to a single keystroke, so a signal would only add noise.
  // roleId starts at 0, the value of the disabled placeholder option, which save() then
  // treats as "no role chosen".
  form: { firstName: string; lastName: string; email: string; roleId: number } = {
    firstName: '', lastName: '', email: '', roleId: 0
  };

  // True as soon as one of the three filters is set. It drives the reset button and decides
  // which of the two empty screens is shown. The double "!!" turns a string into a plain
  // true/false. It is written as an arrow-function field so the template can call it.
  hasFilters = () => !!this.search() || !!this.roleFilter() || !!this.statusFilter();

  /**
   * Runs once, right after Angular has created the component.
   *
   * Why the work is here and not in the constructor: the constructor should only wire
   * dependencies. Firing HTTP requests from it makes the component much harder to test,
   * because the request leaves before the test can prepare anything.
   */
  ngOnInit(): void {
    // First page of accounts.
    this.load();
    // The role list is fetched once and never again: roles change very rarely, and both the
    // filter drop-down and the form drop-down read this same signal.
    // GET /api/roles is guarded on the backend by hasAuthority('MANAGE_USERS'), the same
    // permission that lets the route guard open this screen.
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe(r => this.roles.set(r));
  }

  /**
   * Asks the server for one page of accounts, using the current page, size, sort and
   * filters, and puts the answer into the signals the table reads.
   *
   * Why every filter change calls this again instead of filtering users() in the browser:
   * users() only ever holds the 20 rows of the current page, so a browser-side filter would
   * search inside one page and pretend the rest of the table does not exist.
   */
  load(): void {
    this.loading.set(true);
    // HttpParams is immutable: .set() does NOT change the object, it returns a new one. That
    // is why the result is assigned back to `params` on each line below. Forgetting that
    // assignment is the classic bug here - the filter would simply never reach the URL, and
    // the table would ignore it with no error at all.
    let params = new HttpParams()
      .set('page', this.page())
      .set('size', this.pageSize())
      // Spring Data reads the sort parameter in this exact "field,direction" form, for
      // example "lastName,asc". Without a sort the database is free to return the rows in
      // any order, and the same person could then appear on page 1 and again on page 2 while
      // somebody else never appears at all.
      .set('sort', `${this.sortCol()},${this.sortDir()}`);
    const s = this.search().trim();
    // The three filters are only added when they hold something. Sending an empty search=
    // would make the backend run a "contains nothing" condition instead of no condition.
    if (s) params = params.set('search', s);
    if (this.roleFilter()) params = params.set('roleId', this.roleFilter());
    if (this.statusFilter()) params = params.set('active', this.statusFilter());
    // PagedResponse<User> is the typed shape of the Spring Data Page envelope: content holds
    // the rows of this page, totalElements the count over all pages.
    this.http.get<PagedResponse<User>>(`${environment.apiUrl}/users`, { params }).subscribe({
      next: res => {
        this.users.set(res.content);
        this.totalElements.set(res.totalElements);
        // Safety net for the page that no longer exists. Example: the administrator is on
        // page 3, then types a search that leaves only 5 results - page 3 is now past the end
        // and the server answers an empty content. Clamping the page number keeps the pager
        // honest instead of showing "page 3 of 1".
        const maxPage = Math.max(0, Math.ceil(res.totalElements / this.pageSize()) - 1);
        if (this.page() > maxPage) { this.page.set(maxPage); }
        this.loading.set(false);
      },
      // The error branch empties the table instead of leaving the previous page on screen.
      // Why: stale rows next to an error message look like real data, and the administrator
      // could act on an account that no longer exists. loading is turned off here too -
      // without that line a failed request would leave the grey skeleton for ever.
      error: () => { this.users.set([]); this.totalElements.set(0); this.loading.set(false); this.toast.error('Impossible de charger les utilisateurs.'); }
    });
  }

  // Called by the shared pager when the user moves to another page.
  onPage(n: number): void { this.page.set(n); this.load(); }
  // Changing the number of rows per page also jumps back to the first page: staying on page 4
  // while switching from 10 to 50 rows per page would land far past the end of the result.
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.load(); }

  /**
   * Handles every keystroke in the search box, but only queries the server once the user has
   * stopped typing for 300 ms. This wait is called debouncing.
   *
   * Why: without it, typing "martin" sends six requests, and the answers can come back in the
   * wrong order, so the table could end up showing the results for "mart" while the box
   * reads "martin".
   */
  onSearchChange(value: string): void {
    // The box itself must stay instant, so the signal is written straight away.
    this.search.set(value);
    // Cancels the timer armed by the previous keystroke. This single line is what turns a
    // series of delayed calls into exactly one. clearTimeout on undefined is harmless, so the
    // first keystroke needs no special case.
    clearTimeout(this.searchTimer);
    // Back to the first page: the result of a new search has nothing to do with the page the
    // user happened to be on.
    this.searchTimer = setTimeout(() => { this.page.set(0); this.load(); }, 300);
  }
  // The two drop-down filters need no delay: one click is one intention, so the request
  // leaves at once. Both reset the page for the same reason as the search.
  onRoleChange(value: string): void { this.roleFilter.set(value); this.page.set(0); this.load(); }
  onStatusChange(value: string): void { this.statusFilter.set(value); this.page.set(0); this.load(); }
  // Clears the three filters in one go and reloads. The sort is deliberately kept: the user
  // asked to drop his filters, not to change the order of the columns.
  clearFilters(): void {
    this.search.set(''); this.roleFilter.set(''); this.statusFilter.set('');
    this.page.set(0); this.load();
  }

  /**
   * Handles a click on a sortable column header: same column = flip the direction, different
   * column = sort by it, ascending.
   *
   * Why that rule rather than always going back to ascending: it is the behaviour of every
   * table the user already knows, so the second click on "Name" means "reverse it".
   */
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    // Back to the first page: after a new sort, "page 3" holds completely different people.
    this.page.set(0);
    this.load();
  }
  /**
   * Gives the value of the aria-sort attribute for one column.
   *
   * Why it exists: the coloured arrow tells a sighted user which column is sorted, and a
   * screen reader cannot see a colour. Without aria-sort, a blind administrator hears the
   * column name and never learns that the table is ordered by it.
   */
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  /**
   * Gives the Bootstrap-icon class of the little arrow of one column: a double arrow when the
   * column is not the sorted one, an up or a down arrow when it is.
   *
   * Why a method and not the three classes written in the template: the same three-way choice
   * is needed for both columns, and writing it twice in the HTML is where the two copies
   * start to drift apart.
   */
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  /**
   * Opens the modal in "create" mode.
   *
   * Every field is reset by hand, including the previous error and the previous one-time
   * password. Why that matters: the same component state is reused, so without this reset the
   * password of the account created a minute ago would still be displayed, and the save
   * button would still be hidden.
   */
  openCreate(): void {
    // null is what tells save() to send a POST.
    this.editingId.set(null);
    this.form = { firstName: '', lastName: '', email: '', roleId: 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  /**
   * Opens the same modal in "edit" mode, pre-filled with one account.
   */
  openEdit(u: User): void {
    // The id is what makes save() send a PUT on this account.
    this.editingId.set(u.id);
    // The table row carries the role NAME, not its id, because the name is what is displayed.
    // The id needed by the form is found back in the role list loaded in ngOnInit.
    const role = this.roles().find(r => r.name === u.roleName);
    // ?? 0 is the fallback used when no role matches, for example if the role list has not
    // arrived yet. It puts the drop-down back on its disabled placeholder, which save()
    // refuses, so the worst case is "choose the role again" - never a silent change of role.
    this.form = { firstName: u.firstName, lastName: u.lastName, email: u.email, roleId: role?.id ?? 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  /**
   * Sends the form: POST /api/users when creating, PUT /api/users/{id} when editing.
   *
   * Why one method does both: the payload is identical, and only the verb and the URL change.
   * Two methods would duplicate the validation and the error handling.
   */
  save(): void {
    // A first, cheap check in the browser, so an obviously incomplete form never leaves. It
    // does NOT replace the backend validation (@Valid on UserRequest): this code runs in the
    // user's browser and anyone can skip it, so the server checks again.
    if (!this.form.firstName || !this.form.lastName || !this.form.email || !this.form.roleId) {
      // translate() is the TypeScript twin of the transloco pipe used in the template.
      this.errorMsg.set(this.t.translate('admin.users.msg.allRequired'));
      return;
    }
    // Locks the save button until the answer comes back (see [disabled] in the template).
    this.saving.set(true);
    // Clears the previous error, so the user does not read the message of the last attempt
    // while the new one is still travelling.
    this.errorMsg.set('');
    const id = this.editingId();
    if (id) {
      this.http.put<User>(`${environment.apiUrl}/users/${id}`, this.form).subscribe({
        // load() is called again rather than patching the row in memory: the server may have
        // changed more than what was sent (a role change also revokes that person's sessions,
        // ADR-017), and re-reading is the only way to be sure the table shows what the
        // database really holds.
        next: () => { this.load(); this.showModal.set(false); this.saving.set(false); this.toast.success(this.t.translate('admin.users.msg.updated')); },
        // e.error?.message is the message the backend sent, for example "this e-mail is
        // already used". The ?. and the ?? fallback cover the case of a network failure with
        // no JSON body at all - without them the user would read "undefined" in a red box.
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    } else {
      // UserCreateResult = the saved account PLUS the initial password in clear text. The
      // server draws that password, stores only its BCrypt hash, and returns the clear value
      // exactly once, in this answer.
      this.http.post<UserCreateResult>(`${environment.apiUrl}/users`, this.form).subscribe({
        // The modal is deliberately NOT closed here, unlike in the edit branch: it has to stay
        // open to show the password, which can never be read again afterwards.
        next: (res) => { this.load(); this.saving.set(false); this.initialPassword.set(res.initialPassword); this.toast.success(this.t.translate('admin.users.msg.created')); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? this.t.translate('common.saveFailed')); this.saving.set(false); }
      });
    }
  }

  /**
   * Copies the initial password of a freshly created account into the system clipboard.
   *
   * The empty .catch() is on purpose: the clipboard API is refused by the browser when the
   * page is not served over HTTPS, or when the user denied the permission. Swallowing that
   * rejection keeps a red exception out of the console, and the password stays visible on
   * screen, so the administrator can still select it by hand.
   */
  copyPassword(): void {
    const pwd = this.initialPassword();
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  /**
   * Resets one account: asks for confirmation, then calls
   * PATCH /api/users/{id}/reset-account and shows the new one-time password.
   *
   * What the backend does with that call (UserCrudService.resetAccount): it draws a new
   * password, stores only its BCrypt hash, sets firstLogin = true so the person is forced
   * through the change-password screen, and increases tokenVersion to revoke every session
   * that person still had (ADR-017). This is the administrator's replacement for a "forgot my
   * password" e-mail link, because the application sends no e-mail.
   *
   * Why async/await here: ConfirmService.ask() hands back a Promise<boolean>, and awaiting it
   * keeps the code flat - one line to ask, one line to stop if the answer is no.
   */
  async resetAccount(u: User): Promise<void> {
    const confirmed = await this.confirm.ask(
      // The name is passed as a translation parameter so the question names the person:
      // resetting the wrong account logs a colleague out of everything.
      this.t.translate('admin.users.msg.resetConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.resetConfirmTitle')
    );
    // The early return is what makes the confirmation real. Without it the dialog would be
    // decoration and the account would be reset whatever the user answered.
    if (!confirmed) return;
    // PATCH with an empty body: the request changes part of the account and carries no data,
    // because the server draws the new password itself. The empty object is still passed
    // because HttpClient.patch requires a body argument.
    this.http.patch<UserCreateResult>(`${environment.apiUrl}/users/${u.id}/reset-account`, {}).subscribe({
      next: (res) => {
        // The name is stored next to the password so the modal can say whose password it is.
        this.resetResult.set({ userName: `${u.firstName} ${u.lastName}`, pwd: res.initialPassword });
        this.showResetModal.set(true);
        // Reloads the table, because the reset also changes the account on the server side.
        this.load();
      },
      error: () => this.toast.error(this.t.translate('admin.users.msg.resetError'))
    });
  }

  // The same clipboard copy as copyPassword(), for the reset modal. The ?. is needed because
  // resetResult() is null until a reset has actually answered.
  copyResetPassword(): void {
    const pwd = this.resetResult()?.pwd;
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info(this.t.translate('admin.users.msg.passwordCopied'))).catch(() => {});
  }

  /**
   * Switches an account off after confirmation: PATCH /api/users/{id}/deactivate.
   *
   * Why deactivate and not delete: the person is referenced by projects, tasks and time
   * entries. Removing the row would break all of those links. Deactivating blocks the login
   * while every past record keeps pointing at a real name.
   */
  async deactivate(u: User): Promise<void> {
    // The await sits inside the if: the method simply stops when the answer is false.
    if (!await this.confirm.ask(
      this.t.translate('admin.users.msg.deactivateConfirm', { name: `${u.firstName} ${u.lastName}` }),
      this.t.translate('admin.users.msg.deactivateTitle'))) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/deactivate`, {}).subscribe({
      // Reloading is what swaps the row's badge and its action buttons, because the template
      // draws both of them from u.active.
      next: () => { this.load(); this.toast.success(this.t.translate('admin.users.msg.deactivated')); },
      error: () => this.toast.error(this.t.translate('admin.users.msg.deactivateError'))
    });
  }

  /**
   * The opposite move: PATCH /api/users/{id}/reactivate lets the person sign in again. It is
   * confirmed too, because giving access back is as sensitive as taking it away.
   */
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
