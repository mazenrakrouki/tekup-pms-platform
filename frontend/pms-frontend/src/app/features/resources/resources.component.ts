/**
 * FILE: resources.component.ts - the "Ressources & TCC" screen.
 *
 * WHAT THIS SCREEN IS
 * One table with every costed person the current user is allowed to see: full name, daily
 * rate, TCC rate, computed annual cost and staffing window. Clicking a row opens a small
 * window (a "modal") that shows, and for some users lets you edit, the rates YEAR BY YEAR.
 *
 * TWO WORDS THE JURY WILL ASK ABOUT
 *   - Resource: ADR-022 splits a person in two. The User is the ACCOUNT (who can log in).
 *     The Resource is what that same person COSTS. Two shapes, because a rate is salary
 *     information and an account is not.
 *   - TCC ("taux de coût chargé"): the overhead the company adds on top of the daily rate.
 *     It is a FRACTION, not a percentage: 0.42 means 42%, so one loaded day costs
 *     dailyRate x (1 + tccRate).
 *
 * WHO CAN REACH IT
 * The route 'resources' in app.routes.ts is protected by permissionGuard with
 * data: { permission: 'VIEW_RESOURCES' }. So the menu entry and the URL need that
 * permission. A second, stronger permission, MANAGE_RESOURCES, decides whether the year
 * grid is editable (see the canManage getter below).
 * Say it plainly in front of the jury: the guard and canManage only decide what is DRAWN.
 * Anybody can change them with the browser developer tools. The real refusal is on the
 * server, with @PreAuthorize('hasAuthority(...)') on the SERVICE methods of
 * ResourceService, never on the controller. The code never tests a role NAME, only
 * permission codes, which is why an administrator can invent a new role without touching
 * this file.
 *
 * WHERE IT SITS IN THE FLOW
 * This screen has NO service of its own in core/services. It is the only caller of these
 * URLs, so it uses HttpClient directly (the Resource shape lives in core/models/user.model.ts):
 *   ngOnInit  -> GET /api/resources            -> ResourceController.list()
 *   openTcc   -> GET /api/resources/{id}/tcc   -> ResourceController.tccAnnuels()
 *   saveTcc   -> PUT /api/resources/{id}/tcc   -> ResourceController.replaceTccAnnuels()
 * It also uses AuthService (permission check), ToastService (small pop-up messages) and
 * TranslocoService (translated text from TypeScript code).
 *
 * WHAT THE SERVER DOES THAT THIS SCREEN DOES NOT
 *   - GET /api/resources does not return the same list to everybody. A caller with
 *     MANAGE_RESOURCES gets the whole company; a project manager with only
 *     VIEW_RESOURCES gets himself plus the people of the projects he manages. The filter
 *     is in ResourceService, not here.
 *   - These URLs are NOT under /api/projects/{id}/**, so ProjectScopeInterceptor of
 *     ADR-021 does not apply to them. The same "is this row in my scope" idea is written
 *     by hand in ResourceService.assertVisible(), which answers 403.
 *   - PUT /api/resources/{id}/tcc REPLACES the whole year history: a year present in the
 *     body is created or updated, a year missing from the body is soft-deleted.
 *   - annualCost is never stored. The server computes it at read time, so correcting a
 *     daily rate immediately corrects every total.
 *
 * WHAT WOULD BE MISSING WITHOUT THIS FILE
 * No way to enter or check a rate. Every figure downstream - the Devis Interne, every
 * margin, every EVM indicator of the KPI screen - reads these numbers, and would quietly
 * compute on an empty referential and show zero.
 */
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

/**
 * One line of the year grid: the rates that apply for ONE year for ONE person.
 * This is the front-end mirror of the Java record TccAnnuelDto ("DTO" = Data Transfer
 * Object: a plain shape used only to carry data between the server and the browser, with
 * no behaviour of its own).
 * It is declared here and not in core/models because this screen is its only user.
 */
interface TccAnnuel {
  annee: number;
  dailyRate: number;
  tccRate: number;
}

/**
 * The four columns the table can be sorted on, and the two directions.
 * Writing them as a union of exact strings instead of plain 'string' lets the compiler
 * refuse a typo: toggleSort('dailyrate') does not compile, so a sort click can never
 * silently do nothing at runtime.
 */
type SortCol = 'name' | 'dailyRate' | 'tccRate' | 'annualCost';
type SortDir = 'asc' | 'desc';

/**
 * COMPONENT DECORATOR - the part Angular reads to know how to build this screen.
 *   selector    : the tag name. Here it is loaded by the router, not written by hand.
 *   standalone  : no NgModule. The component declares its own dependencies in 'imports'.
 *   imports     : only what the template really uses. CommonModule brings the 'number'
 *                 pipe, FormsModule brings ngModel, PaginationComponent is the shared
 *                 <app-pagination>, TranslocoModule brings the 'transloco' pipe. Forget
 *                 one and the template silently stops working (an unknown pipe is a
 *                 build error, an unknown attribute is simply ignored).
 *   providers   : provideTranslocoScope('resources') loads the translation file
 *                 i18n/resources/fr.json + en.json only when this screen is opened, and
 *                 makes every key start with 'resources.'. Without the scope, all the
 *                 texts of the whole application would have to sit in one giant file.
 *   styles      : CSS written inline, automatically limited to this component.
 *   template    : the HTML, also inline - the project convention for every component.
 */
@Component({
  selector: 'app-resources',
  standalone: true,
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  providers: [provideTranslocoScope('resources')],
  styles: [`
    /* The grey bar above the table holding the search box and the result count.
       flex-wrap lets the pieces fall onto a second line on a narrow screen instead of
       overflowing outside the card. */
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    /* margin-left:auto eats all the free space, so the count is pushed to the far right
       whatever the width of the search box. white-space:nowrap keeps the sentence
       "12 results" on one line. */
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }
    /* A sortable header. user-select:none stops the browser from selecting the title text
       when the user clicks the header several times in a row to flip the order. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* Number columns are right-aligned, so the little arrow must sit on the LEFT of the
       title, otherwise it would be pushed away from the edge of the column. */
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    /* The sort arrow is invisible at rest: shown faintly on hover, fully when the column
       is the active sort. Showing all four arrows at once would make it impossible to see
       which column is really sorted. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible, not :focus - the ring is drawn for keyboard users only, not on a
       mouse click. The headers are reachable with Tab, so without this a keyboard user
       would have no idea where he is. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
    tr.row-link { cursor:pointer; }
    /* tabular-nums forces every digit to take the same width, so the figures of a column
       line up under each other. Without it 1 111 looks narrower than 8 888 and the column
       looks ragged. */
    .num { font-variant-numeric:tabular-nums; }
    /* Grey bars shown while the data is loading (the "skeleton"). Different widths make
       the fake rows look like real text instead of a block. */
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-60{width:60%} .sk-w-70{width:70%}
  `],
  template: `
    <!-- Top bar with the breadcrumb. 'resources.breadcrumb' | transloco reads the text
         from the scoped translation file, so the screen exists in French and in English
         without a second template. -->
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
            <!-- [ngModel] alone (one-way) plus (ngModelChange), NOT [(ngModel)].
                 The value shown always comes from the search() signal, and every keystroke
                 goes through onSearch(), which also sends the list back to page 1.
                 With the two-way [(ngModel)] the signal would be written directly and that
                 page reset would be skipped: type a letter while on page 5 and you would
                 be looking at page 5 of a two-row result, that is, an empty table. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'resources.search.placeholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'resources.search.aria' | transloco">
          </div>
          <!-- @if is Angular's built-in condition block. The clear button only exists while
               something is typed, so it never sits there doing nothing. -->
          @if (search()) {
            <button class="btn btn-ghost btn-sm" (click)="onSearch('')"><i class="bi bi-x-lg me-1"></i>{{ 'resources.search.reset' | transloco }}</button>
          }
          <!-- Singular / plural handled by choosing the KEY, then passing count as a
               parameter to the sentence. French and English do not agree on when the
               plural starts, so the choice has to be per language, not a bare "(s)". -->
          <span class="count">
            {{ (filtered().length === 1 ? 'resources.search.results.one' : 'resources.search.results.other')
               | transloco: { count: filtered().length } }}
          </span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <!-- Each sortable header repeats the same three things:
                     [class.is-sorted] paints it when it is the active column;
                     [attr.aria-sort] tells a screen reader the table is sorted on this
                       column and in which direction (ascending / descending / none);
                     tabindex="0" plus the two keydown handlers make it usable without a
                       mouse. A <th> is not a button, so without tabindex it cannot be
                       reached with Tab and a keyboard user could never sort at all.
                     On Space we also call $event.preventDefault(), because the browser's
                     default action for Space is to scroll the page down. -->
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
                <!-- d-none d-lg-table-cell: Bootstrap hides this column below the large
                     breakpoint. The staffing window is the least useful column, so it is
                     the first one dropped on a laptop or a tablet rather than letting the
                     table scroll sideways. -->
                <th class="d-none d-lg-table-cell">{{ 'resources.table.period' | transloco }}</th>
                <th class="text-end" style="width:150px">{{ 'resources.table.yearlyRates' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              <!-- While the HTTP call is on its way, six grey fake rows are drawn.
                   They keep the table at roughly its final height, so the page does not
                   jump when the real rows arrive. The array [1,2,3,4,5,6] is only a way
                   to repeat the row six times; track i just numbers them. -->
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
                <!-- track r.id is the important part here. It tells Angular which row in
                     the DOM matches which object, so after a sort or a page change it
                     MOVES the existing rows instead of destroying and rebuilding them.
                     With track $index, sorting would keep row number 1 in place and only
                     swap its text, which loses the focus and is slower on every change. -->
                @for (r of pagedResources(); track r.id) {
                  <!-- The whole row opens the year window, which is why the cursor becomes
                       a hand (.row-link). -->
                  <tr class="row-link" (click)="openTcc(r)">
                    <td class="fw-semibold">{{ r.userFullName }}</td>
                    <!-- The 'number' pipe formats the figure for the active language:
                         '1.2-2' = at least 1 digit before the dot, exactly 2 after.
                         The TCC rate is shown with up to 4 decimals because it is a
                         fraction: 0.4235 must not be rounded to 0.42, that is a
                         difference of more than 1% on every cost. -->
                    <td class="text-end num">{{ r.dailyRate | number:'1.2-2' }}</td>
                    <td class="text-end num">{{ r.tccRate | number:'1.2-4' }}</td>
                    <!-- annualCost is optional in the Resource shape: the server only
                         computes it when the rates are known. The plain truthiness test
                         prints a dash for a missing value - and, because 0 is falsy, for a
                         zero cost as well. That is accepted here: a resource with a real
                         annual cost of 0 has no rate entered either, so the dash still
                         reads as "nothing to show yet". -->
                    <td class="text-end num">{{ r.annualCost ? (r.annualCost | number:'1.0-0') : '—' }}</td>
                    <td class="d-none d-lg-table-cell text-muted small">
                      <!-- ?? is the "if null or undefined, use this instead" operator. -->
                      {{ r.staffingStart ?? '—' }}
                      <!-- No end date means the person is still staffed, so we write
                           "ongoing" rather than leaving the arrow pointing at nothing. -->
                      @if (r.staffingEnd) { → {{ r.staffingEnd }} } @else { → {{ 'resources.table.ongoing' | transloco }} }
                    </td>
                    <td class="text-end">
                      <!-- $event.stopPropagation() stops the click from also reaching the
                           row handler above. Without it openTcc would run twice for one
                           click on this button.
                           aria-label: the icon plus a one-word label is not enough for a
                           screen reader, which reads the buttons of a table out of
                           context. It builds "Manage the rates of X".
                           (A comment can never be written between the attributes of a
                           tag, only before or after the tag.) -->
                      <button class="btn btn-outline-secondary btn-sm" (click)="openTcc(r); $event.stopPropagation()"
                              [attr.aria-label]="'resources.actions.ratesAria' | transloco: {
                                action: (canManage ? ('resources.actions.manage' | transloco) : ('resources.actions.view' | transloco)),
                                name: r.userFullName }">
                        <!-- The wording follows the permission: a user without
                             MANAGE_RESOURCES is promised "view", not "manage", so the
                             button never announces something the server would refuse. -->
                        <i class="bi bi-calendar3 me-1"></i>{{ (canManage ? 'resources.actions.manage' : 'resources.actions.view') | transloco }}
                      </button>
                    </td>
                  </tr>
                }
                <!-- @empty runs when the @for above produced no row at all. -->
                @empty {
                  <tr><td colspan="6">
                    <!-- Two different empty states on purpose. "Your search matched
                         nothing" and "there is no resource yet" call for two different
                         reactions, and only the first one deserves a clear button. A
                         single generic message would make a user believe the referential
                         is empty when he simply mistyped a name. -->
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
        <!-- The shared <app-pagination>, reused instead of being rewritten here. The
             component only receives the current page, the page size and the TOTAL number
             of FILTERED rows, and sends back events; it owns no data.
             Note the second handler: changing the page size also sends the user back to
             page 0. Moving from 50 to 10 rows per page while on page 4 would otherwise
             land on a page that no longer exists.
             The bar is hidden while loading and when the list is empty, so it never shows
             "page 1 of 0" under a skeleton. -->
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="page.set($event)"
            (pageSizeChange)="pageSize.set($event); page.set(0)" />
        }
      </div>
    </div>

    <!-- TCC per-year modal -->
    <!-- @if (...; as r) does two things at once: it draws the window only when a resource
         is selected, and it gives the non-null value the local name r, so the markup below
         does not have to repeat modalResource()! everywhere.
         The window is written by hand rather than with the Bootstrap JavaScript, so its
         visible state is a signal like everything else: one source of truth. -->
    @if (modalResource(); as r) {
      <div class="modal-backdrop fade show"></div>
      <!-- Clicking the dark background, or pressing Escape, closes the window. Both are
           what a user expects; without the Escape handler the only way out would be the
           mouse. -->
      <div class="modal d-block" tabindex="-1" (click)="closeTcc()" (keydown.escape)="closeTcc()">
        <!-- stopPropagation on the dialog itself: without it, any click INSIDE the window -
             on an input, on the save button - would bubble up to the handler above and
             close the window while the user is typing. -->
        <div class="modal-dialog modal-dialog-centered modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content" style="max-height:calc(100vh - 3.5rem)">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-calendar3 me-2"></i>{{ 'resources.modal.title' | transloco: { name: r.userFullName } }}</h5>
              <button type="button" class="btn-close" (click)="closeTcc()" [attr.aria-label]="'resources.modal.close' | transloco"></button>
            </div>
            <!-- max-height above plus overflow-y here: with fifteen years entered, the
                 rows scroll inside the window and the header and the save button stay
                 visible instead of being pushed off the screen. -->
            <div class="modal-body" style="overflow-y:auto">
              <table class="table table-sm align-middle mb-3">
                <thead>
                  <tr>
                    <th>{{ 'resources.modal.year' | transloco }}</th>
                    <th class="text-end">{{ 'resources.modal.dailyRate' | transloco }}</th>
                    <th class="text-end">{{ 'resources.modal.tccRate' | transloco }}</th>
                    <!-- The delete column only exists for a user who may edit, otherwise
                         the header would sit above nothing. -->
                    @if (canManage) { <th style="width:56px"></th> }
                  </tr>
                </thead>
                <tbody>
                  <!-- track $index here, NOT track t.annee, and this one is deliberate: a
                       brand new row starts on a year the user is about to retype, and two
                       rows can hold the same year for a moment while he edits. A key that
                       is duplicated makes Angular throw. The rows are only a local edit
                       buffer, so their position is a safe key. -->
                  @for (t of modalTcc(); track $index; let i = $index) {
                    <tr>
                      <!-- The same permission again, this time deciding between inputs and
                           plain text. Remember this only HIDES the controls: a user
                           without MANAGE_RESOURCES who forces the inputs open still gets a
                           403 from ResourceService on the PUT. -->
                      @if (canManage) {
                        <!-- Here [(ngModel)] IS the two-way form, on purpose: it writes
                             straight into the object of the local list, which is a copy
                             made in openTcc(), never the cached server answer. Nothing has
                             to be recomputed while typing, so no handler is needed.
                             min / max / step are a first, friendly filter in the browser;
                             the real rules are Bean Validation on TccAnnuelDto
                             (@Min(2000), @Max(2100), @Positive, @DecimalMax('9.9999')).
                             step 0.0001 matches the column precision(5, scale 4): without
                             it the browser would refuse 0.4235 as an invalid step. -->
                        <td><input type="number" class="form-control form-control-sm" [(ngModel)]="t.annee" min="2000" max="2100" style="width:110px" [attr.aria-label]="'resources.modal.yearAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.dailyRate" min="0" [attr.aria-label]="'resources.modal.dailyRateAria' | transloco"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="t.tccRate" min="0" max="9.9999" step="0.0001" [attr.aria-label]="'resources.modal.tccRateAria' | transloco"></td>
                        <td class="text-end">
                          <!-- Removing a row only changes the local list. Nothing is
                               deleted on the server until Save is pressed, and it is the
                               PUT that soft-deletes the years absent from the body. -->
                          <button class="btn btn-sm btn-outline-danger" (click)="removeRow(i)" [title]="'resources.modal.removeTitle' | transloco" [attr.aria-label]="'resources.modal.removeAria' | transloco"><i class="bi bi-trash"></i></button>
                        </td>
                      } @else {
                        <td class="fw-semibold">{{ t.annee }}</td>
                        <td class="text-end num">{{ t.dailyRate | number:'1.2-2' }}</td>
                        <td class="text-end num">{{ t.tccRate | number:'1.2-4' }}</td>
                      }
                    </tr>
                  }
                  <!-- Shown for a person who has no year entered yet. The colspan follows
                       the permission because the delete column above only exists for an
                       editor; a fixed 4 would leave an empty cell for a read-only user. -->
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
              <!-- The message line inside the window: saved, or the reason the save was
                   refused. isErr() only changes the colour and the icon.
                   role="status" makes a screen reader announce the text when it appears,
                   which a plain <div> would not do.
                   This is deliberately NOT a toast: an error must stay next to the grid
                   the user has to correct, while a toast disappears after a few seconds. -->
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
                <!-- [disabled]="saving()" plus the spinner: while the PUT is travelling the
                     button cannot be pressed again. Without it an impatient double click
                     would send the same replacement twice. -->
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
/**
 * THE CLASS - all the state of the screen and the three HTTP calls.
 *
 * Everything that can change is a SIGNAL. A signal is a small box holding a value: you
 * read it by calling it, sig(), and you write it with sig.set(value). Angular remembers
 * every place in the template that read the box, and redraws exactly those places when the
 * value changes. That is why this component needs no manual refresh anywhere.
 *
 * implements OnInit only forces the compiler to check the name of ngOnInit. A typo such as
 * ngOninit would otherwise compile and the screen would simply never load its data.
 */
export class ResourcesComponent implements OnInit {
  // inject() is the modern form of constructor injection: Angular hands over the shared
  // instance of each service. readonly private, so no other part of the code can swap them.
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly transloco = inject(TranslocoService);

  /** The full list exactly as the server sent it. It is never sorted or filtered in
   *  place; filtered() derives from it. Keeping the raw answer untouched is what makes
   *  clearing the search box instant - nothing has to be fetched again. */
  resources = signal<Resource[]>([]);
  /** True until the first answer arrives, which is what draws the grey skeleton rows.
   *  It starts at true on purpose: starting at false would flash the "no resource yet"
   *  empty state for a moment before the data arrives. */
  loading   = signal(true);
  /** Current page, counted from 0. */
  page      = signal(0);
  /** Rows per page, also owned here so that <app-pagination> stays a dumb display. */
  pageSize  = signal(10);
  /** What is typed in the search box. */
  search    = signal('');
  /** Which column the table is sorted on, and in which direction. */
  sortCol   = signal<SortCol>('name');
  sortDir   = signal<SortDir>('asc');

  /**
   * The list after the search and the sort. A computed() is a signal whose value is
   * CALCULATED from other signals: Angular re-runs it only when resources(), search(),
   * sortCol() or sortDir() really change, and caches the result in between. Writing this
   * as a plain method would re-sort the whole list on every single change detection pass.
   *
   * Two details worth defending:
   *   - the search is trimmed and lower-cased, so "  Ben ali" finds "Ben Ali";
   *   - [...list] makes a copy BEFORE sort(), because sort() rearranges the array it is
   *     given. Sorting in place would quietly reorder resources() itself, and the
   *     "original" order would be lost for good.
   * localeCompare is used for the name so that accented letters (é, à) land where a French
   * reader expects them; a plain a < b comparison sorts on character codes and puts every
   * accent after Z.
   */
  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const list = q ? this.resources().filter(r => r.userFullName.toLowerCase().includes(q)) : this.resources();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    const col = this.sortCol();
    return [...list].sort((a, b) => this.compare(a, b, col) * dir);
  });

  /**
   * The slice of filtered() that is actually drawn: one page.
   * Note the clamp on the middle line. The page number is limited to the last existing
   * page before the slice is cut. Concrete case it saves: you are on page 4, you type a
   * letter, only three rows match - without the clamp the slice would start past the end
   * of the array and the user would face a blank table with no way to understand why.
   * The paging is done in the browser and not on the server because the referential is
   * short (one row per staffed employee); the project rule against giant lists is honoured
   * by reusing <app-pagination> rather than dropping everything in one scroll.
   */
  readonly pagedResources = computed(() => {
    const size = this.pageSize();
    const pages = Math.max(1, Math.ceil(this.filtered().length / size));
    const page = Math.min(this.page(), pages - 1);
    return this.filtered().slice(page * size, page * size + size);
  });

  // Modal state
  /** The person whose year window is open, or null when no window is open. It is this one
   *  signal that both shows the window and tells the rest of the code which resource is
   *  being edited - one source of truth instead of a boolean plus an id that can drift. */
  modalResource = signal<Resource | null>(null);
  /** The year rows being edited. Always a COPY of the server rows (see openTcc), so
   *  cancelling simply throws them away and leaves the cache intact. */
  modalTcc      = signal<TccAnnuel[]>([]);
  /** True while the PUT is travelling; it disables the save button. */
  saving        = signal(false);
  /** The message shown under the grid, and whether it is an error. Two signals rather than
   *  one, because the text itself is already translated and carries no level. */
  msg           = signal('');
  isErr         = signal(false);
  /**
   * Remembers the year rows already fetched, keyed by resource id, so reopening the same
   * person does not call the server again. It is a plain Map and not a signal because
   * nothing in the template reads it; only openTcc and saveTcc do.
   * It lives as long as the screen: leaving and coming back rebuilds the component and
   * empties it, which is what keeps it from ever showing stale rates.
   */
  private tccCache = new Map<number, TccAnnuel[]>();

  /**
   * Whether the current user may EDIT the year grid (MANAGE_RESOURCES), as opposed to
   * merely reading it (VIEW_RESOURCES, which the route guard already required).
   * Say it to the jury before being asked: this only hides controls. The refusal that
   * counts is @PreAuthorize('hasAuthority(...)') on ResourceService. No role name is
   * tested anywhere, only this permission code.
   */
  get canManage(): boolean { return this.auth.hasPermission('MANAGE_RESOURCES'); }

  /**
   * Runs once when the screen is created: loads the whole referential.
   * GET /api/resources -> ResourceController.list(). The server decides what is in that
   * list: everything for MANAGE_RESOURCES, only the people of the projects he manages for
   * a project manager. So the same code here shows a different table to two users.
   * subscribe() is what actually SENDS the request - an Angular HTTP call does nothing
   * until somebody subscribes to it.
   * On failure the list is emptied, loading is turned off and a toast explains why.
   * Without that error branch the screen would stay on its grey skeleton for ever and the
   * user would believe it is still loading.
   */
  ngOnInit(): void {
    this.http.get<Resource[]>(`${environment.apiUrl}/resources`).subscribe({
      next: list => { this.resources.set(list); this.loading.set(false); },
      error: () => { this.resources.set([]); this.loading.set(false); this.toast.error(this.transloco.translate('resources.msg.loadFailed')); }
    });
  }

  /** Stores what was typed AND sends the list back to the first page. The page reset is
   *  the whole reason this method exists instead of a two-way binding. */
  onSearch(v: string): void { this.search.set(v); this.page.set(0); }

  /**
   * Handles a click on a column header. Clicking the column already sorted flips the
   * direction; clicking another column switches to it, ascending first.
   * It also returns to page 1: after a sort the rows on page 4 are not the same rows at
   * all, so staying there would look like the table jumped at random.
   * Everything happens in the browser, on data already loaded - no request is sent.
   */
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    this.page.set(0);
  }
  /**
   * The value for the aria-sort attribute of a header. It is what a screen reader reads
   * out: without it, a blind user hears the column titles but never learns that the table
   * is sorted, nor on which column.
   */
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  /**
   * The Bootstrap-icon class of the little arrow: a double chevron on the columns that
   * are not sorted (meaning "you can sort here"), an up or down chevron on the active one.
   */
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }
  /**
   * Compares two resources on one column, always ASCENDING; filtered() multiplies the
   * result by -1 to get the descending order. Keeping the direction out of here means the
   * rule for each column is written once.
   * The three numeric columns use (value ?? 0): these fields may be missing, and
   * undefined - undefined gives NaN, which makes sort() leave the rows in an arbitrary
   * order instead of sorting them.
   */
  private compare(a: Resource, b: Resource, col: SortCol): number {
    switch (col) {
      case 'dailyRate':  return (a.dailyRate ?? 0) - (b.dailyRate ?? 0);
      case 'tccRate':    return (a.tccRate ?? 0) - (b.tccRate ?? 0);
      case 'annualCost': return (a.annualCost ?? 0) - (b.annualCost ?? 0);
      case 'name':
      default:           return a.userFullName.localeCompare(b.userFullName);
    }
  }

  /**
   * Opens the year window for one resource.
   * It first clears any message left by a previous save, so the user never sees the
   * success or the error of somebody else's row.
   * If the years are already in the cache they are used straight away; otherwise
   * GET /api/resources/{id}/tcc -> ResourceController.tccAnnuels(). That endpoint can
   * answer 403 even to a holder of VIEW_RESOURCES, when the resource is outside the
   * projects he manages (ResourceService.assertVisible()).
   *
   * Two points worth defending:
   *   - .map(x => ({ ...x })) copies every row. The spread syntax { ...x } builds a new
   *     object with the same fields. The grid is edited with ngModel, which writes into
   *     these objects; without the copy the user would be typing directly into the cached
   *     server answer, and pressing Close instead of Save would leave the modified values
   *     behind for the next opening.
   *   - the test 'if (this.modalResource()?.id === r.id)' before using the answer. The
   *     request is asynchronous: the user can close the window, or click another person,
   *     while it is still travelling. Without this test a slow answer for Ali would land
   *     in a window now showing Sonia, and she would appear to have his rates.
   */
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

  /** Closes the window. Setting the resource back to null is enough: the @if in the
   *  template removes the whole markup, and the edited copies are simply dropped. */
  closeTcc(): void { this.modalResource.set(null); }

  /**
   * Adds one empty year line to the local grid. Nothing is sent to the server.
   * The proposed year is the highest one already present plus one, or the current year
   * when the grid is empty. Rates are entered year after year, so this is almost always
   * the value the user wanted, and it also avoids proposing a year that is already there,
   * which the server would reject with 409 (duplicate year).
   * [...rows, newRow] builds a NEW array instead of using push(). A signal only notifies
   * when it receives a different value, so push() would change the content without the
   * table ever redrawing.
   */
  addRow(): void {
    const rows = this.modalTcc();
    const next = rows.length > 0 ? Math.max(...rows.map(t => t.annee)) + 1 : new Date().getFullYear();
    this.modalTcc.set([...rows, { annee: next, dailyRate: 0, tccRate: 0 }]);
  }

  /**
   * Removes the line at position i from the local grid. Same reason for the copy as in
   * addRow: splice() changes the array in place, so it is applied to a copy and the copy
   * is then set on the signal, which is what makes the table redraw.
   * The year is only really deleted on the server when Save is pressed, because the PUT
   * replaces the whole history: a year absent from the body is soft-deleted.
   */
  removeRow(i: number): void {
    const rows = [...this.modalTcc()];
    rows.splice(i, 1);
    this.modalTcc.set(rows);
  }

  /**
   * Saves the year grid: PUT /api/resources/{id}/tcc ->
   * ResourceController.replaceTccAnnuels(). The body is the complete list, and the server
   * makes the history match it exactly. Sending it twice gives the same result, which is
   * what makes the button safe to press again after a network error.
   *
   * The duplicate-year test before sending (a Set keeps each value once, so a smaller size
   * means a repeat) is a courtesy, NOT the guard. The server refuses a duplicated year
   * with a 409 and a unique index protects the table underneath. Checking here only turns
   * a confusing 409 into a sentence in the user's language, without a round trip.
   *
   * On success the cache and the grid are refreshed from what the server actually saved,
   * not from what was typed: the server is the one that decides ids and rounding.
   * Two messages on purpose - the line under the grid, which stays, and a toast, which is
   * the short confirmation the user catches out of the corner of his eye.
   * On failure it shows e.error?.detail, the 'detail' field of the ProblemDetail answer
   * (for instance the duplicate-year conflict), and falls back to a generic translated
   * sentence when the server sent nothing usable - a network cut has no body at all, and
   * printing undefined would look like a crash.
   */
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
