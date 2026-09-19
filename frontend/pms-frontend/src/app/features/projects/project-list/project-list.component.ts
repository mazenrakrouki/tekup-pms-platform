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

// =============================================================================
// FILE: project-list.component.ts   ("the Projects table screen")
// =============================================================================
// WHAT THIS FILE IS
//   The screen that shows all projects in one table, with a search box, a status
//   filter, sortable column headers, pages, and two tabs (Active / Archived).
//   It is one standalone Angular component: the HTML (template) and the CSS
//   (styles) are written inside this same file, so the whole screen is one file.
//
// WHERE IT SITS IN THE FLOW
//   WHO CALLS IT   the router. The route /projects points at this component.
//   WHAT IT CALLS
//     - ProjectService       -> listAll(), listArchived(), unarchive(id).
//                               That service is the only place that talks HTTP to
//                               the backend /api/projects.
//     - AuthService          -> hasPermission('CREATE_PROJECT' | 'EDIT_PROJECT' |
//                               'VIEW_KPI'), to decide which buttons and which
//                               columns are DRAWN.
//     - ProjectsListStateService -> set(...), so the detail page and the edit form
//                               can offer a "back to the list" link that keeps the
//                               user's search, filter, sort and page.
//     - PaginationComponent  -> the shared page bar at the bottom of the card.
//     - Router               -> to open a project, and to keep the URL in step with
//                               the filters.
//
// WHY IT EXISTS (what breaks if you delete it)
//   There would be no way to find a project. The detail page, the edit form and the
//   DI (Devis Interne) screens are all reached from a row of this table, so deleting
//   this file would cut the entrance to the whole Projects module.
//
// WHY THE LIST IS FILTERED CLIENT-SIDE, NOT SERVER-SIDE
//   load() fetches the WHOLE list once, then filter, sort and paging happen in the
//   browser (filtered -> sorted -> paged below). The company has tens, not millions,
//   of projects, so one small download is cheaper than one HTTP round trip on every
//   keystroke in the search box. With a server-side search, typing "BAD" would fire
//   three requests and the results would flicker as they come back out of order.
//   If the table ever grows to thousands of rows this choice has to be revisited.
//
// SECURITY NOTE (important for the jury)
//   Every hasPermission(...) in this file only hides or shows something on screen.
//   It is NOT the real protection: anybody can re-enable a hidden button with the
//   browser developer tools. The real refusal is on the server, where
//   @PreAuthorize("hasAuthority('X')") sits on the SERVICE methods, plus
//   ProjectScopeInterceptor for /api/projects/{id}/** URLs, which checks the
//   permission AND the project scope (ADR-021). Note also that this code never
//   tests a role NAME, only permission codes, so an administrator can create a new
//   role without touching this file.
// =============================================================================

// A "union of string literals" type: SortCol can hold ONLY one of these eight
//   words, nothing else.
//   WHY not plain 'string': toggleSort(), compare(), caret() and ariaSort() all
//   switch on this value. With 'string', a typo such as toggleSort('nmae') would
//   compile, the table would silently stop sorting, and nobody would see an error.
//   With this type the compiler refuses the typo straight away.
type SortCol = 'code' | 'name' | 'chef' | 'startDate' | 'endDate' | 'createdAt' | 'budget' | 'status';
// Same idea for the direction: only "ascending" or "descending" exist.
type SortDir = 'asc' | 'desc';

// @Component turns the class below into an Angular screen element.
@Component({
  // The tag name this component answers to, and the name the router logs use.
  selector: 'app-project-list',
  // standalone: true means this component declares its own dependencies in
  //   'imports' below and does not belong to any NgModule.
  //   WHY: the whole project uses standalone components. Without it, this file
  //   would have to be listed in a module file too, and forgetting that line gives
  //   the classic "app-project-list is not a known element" error at runtime.
  standalone: true,
  // Everything the TEMPLATE uses must be listed here, or the template silently
  //   renders nothing useful. CommonModule gives @if/@for helpers and ngClass,
  //   RouterLink gives [routerLink], FormsModule gives [ngModel] on the search box
  //   and the status <select>, DecimalPipe gives '| number', DatePipe gives
  //   '| date', PaginationComponent gives <app-pagination>, TranslocoModule gives
  //   the '| transloco' translation pipe.
  //   EXAMPLE of what goes wrong: remove FormsModule and the search input throws
  //   "Can't bind to 'ngModel'" and the page does not start at all.
  imports: [CommonModule, RouterLink, FormsModule, DecimalPipe, DatePipe, PaginationComponent, TranslocoModule],
  // Styles written here are scoped to THIS component only: Angular adds a hidden
  //   attribute to the elements so the rules cannot leak into other screens.
  //   Inside this block only /* */ comments are legal.
  styles: [`
    /* Filter bar above the table: search box, status select, reset, result count. */
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    /* margin-left:auto pushes the "N results" text to the far right of the bar. */
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    /* Sortable column headers */
    /* user-select:none stops the header text from being highlighted in blue when
       the user clicks it several times in a row to flip the sort direction. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* The little arrow is invisible by default and appears on hover, so the header
       row stays calm; it stays fully visible on the column actually sorted. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* :focus-visible draws the ring only for keyboard users, not on mouse click.
       The headers carry tabindex="0", so without this a keyboard user would move
       through the columns with no idea where he is. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    /* Rows */
    /* The whole row is clickable (see open(p) below), so show the hand cursor. */
    tr.row-link { cursor:pointer; }
    .code-cell { font-size:12px; font-weight:600; color:var(--c-brand); font-family:'JetBrains Mono','Fira Code',monospace; }
    .name-cell { font-weight:600; color:var(--text-1); }
    .sub-cell  { font-size:11px; color:var(--text-3); }
    .muted-cell { font-size:12px; color:var(--text-2); white-space:nowrap; }
    /* tabular-nums makes every digit the same width, so the budget column lines up
       on the right. Without it "1 111" and "9 000" have different widths and the
       column looks crooked. */
    .num-cell  { font-weight:600; color:var(--text-1); font-variant-numeric:tabular-nums; }

    /* Skeleton */
    /* Grey bars shown while the data is loading, instead of an empty white card. */
    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40 { width:40%; } .sk-w-60 { width:60%; } .sk-w-70 { width:70%; } .sk-w-30 { width:30%; }
  `],
  // The HTML of the screen, written inline between backticks.
  //   Inside this block only <!-- --> comments are legal: a // or /* */ would be
  //   printed on the page as plain text.
  template: `
    <!-- TOPBAR -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-folder2-open" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'projects.title' | transloco }}</span>
      </div>
      <div class="tb-right">
        <!-- Hide the "New project" button from a user who cannot create one.
             This is comfort, not security: the server checks CREATE_PROJECT again
             on the service method. Without this test the user would see the button,
             fill the whole form, and only then get a 403. -->
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
        <!-- Two tabs. They do NOT filter the table: each one reloads from a
             different endpoint (listAll vs listArchived), see setMode(). -->
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
            <!-- [ngModel] one-way + (ngModelChange) is used on purpose instead of
                 the usual two-way [(ngModel)]. The value lives in a signal, and the
                 handler must also reset the page and rewrite the URL. Two-way
                 binding would write the signal directly and skip that work, so the
                 user would stay on page 3 of a search that now has one result. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'projects.searchPlaceholder' | transloco"
                   [ngModel]="search()" (ngModelChange)="onSearch($event)"
                   [attr.aria-label]="'projects.searchAria' | transloco">
          </div>

          <!-- Status filter. The <option> values are the raw codes stored in the
               database (ACTIVE, DRAFT...), while the text shown is translated.
               Filtering on the code, not on the label, is what makes the filter
               keep working when the user switches the interface to English. -->
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

          <!-- The reset button only exists while at least one filter is set, so the
               toolbar is not cluttered with a button that would do nothing. -->
          @if (search() || statusFilter()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>{{ 'common.reset' | transloco }}
            </button>
          }

          <!-- Counts the rows AFTER filtering, not the rows on the current page.
               Singular / plural is picked with a ternary because the answer must be
               "1 result" and not "1 results". -->
          <span class="count">{{ filtered().length }}
            {{ (filtered().length === 1 ? 'common.result' : 'common.results') | transloco }}</span>
        </div>

        <!-- Table -->
        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <!-- Every sortable header repeats the same four things:
                   [class.is-sorted] paints the column currently used for sorting;
                   [attr.aria-sort] tells a screen reader "ascending/descending/none",
                     which a plain CSS colour cannot say to a blind user;
                   tabindex="0" puts the <th> in the keyboard tab order, because a
                     <th> is not focusable by itself;
                   (keydown.enter) and (keydown.space) give the keyboard the same
                     power as the mouse. $event.preventDefault() on space is needed
                     or the browser scrolls the page down at the same time. -->
              <tr>
                <th class="th-sort" [class.is-sorted]="sortCol()==='code'" [attr.aria-sort]="ariaSort('code')"
                    tabindex="0" (click)="toggleSort('code')" (keydown.enter)="toggleSort('code')" (keydown.space)="toggleSort('code'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colCode' | transloco }} <i class="bi caret" [ngClass]="caret('code')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='name'" [attr.aria-sort]="ariaSort('name')"
                    tabindex="0" (click)="toggleSort('name')" (keydown.enter)="toggleSort('name')" (keydown.space)="toggleSort('name'); $event.preventDefault()">
                  <span class="th-inner">{{ 'projects.colName' | transloco }} <i class="bi caret" [ngClass]="caret('name')"></i></span>
                </th>
                <!-- d-none d-lg-table-cell is Bootstrap: hide this column on small
                     screens, show it from the "lg" width up. The manager, the dates
                     and the creation date are the first things dropped on a phone so
                     that code, name and status stay readable without side scroll. -->
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
                <!-- The budget column exists only for a user holding VIEW_KPI.
                     Money figures are the sensitive part of this screen, so a user
                     without that permission must not even see the column heading. -->
                @if (auth.hasPermission('VIEW_KPI')) {
                  <th class="th-sort text-end" [class.is-sorted]="sortCol()==='budget'" [attr.aria-sort]="ariaSort('budget')"
                      tabindex="0" (click)="toggleSort('budget')" (keydown.enter)="toggleSort('budget')" (keydown.space)="toggleSort('budget'); $event.preventDefault()">
                    <span class="th-inner">{{ 'projects.colBudget' | transloco }} <i class="bi caret" [ngClass]="caret('budget')"></i></span>
                  </th>
                }
                <!-- Empty heading for the actions column (edit / restore). It has no
                     title on purpose, but the <th> must exist so that the body cells
                     below line up with the header. -->
                @if (auth.hasPermission('EDIT_PROJECT')) { <th></th> }
              </tr>
            </thead>
            <tbody>
              <!-- Loading skeleton -->
              <!-- While the HTTP call is running, draw fake grey rows with exactly
                   the same columns as the real ones. This keeps the card the same
                   height, so the page does not jump when the data lands. -->
              @if (loading()) {
                <!-- track i tells Angular how to tell the fake rows apart. It is
                     required by @for; here the number itself is the identity. -->
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
                <!-- paged() is the slice of rows for the current page only.
                     track p.id gives each row a stable identity: when the user sorts
                     the table, Angular MOVES the existing rows instead of rebuilding
                     them all, which keeps scrolling and focus steady. -->
                @for (p of paged(); track p.id) {
                  <!-- The whole row is clickable for convenience (open(p) below). -->
                  <tr class="row-link" (click)="open(p)">
                    <td>
                      <!-- A real <a routerLink>, not only the row click: it gives the
                           browser a real URL, so middle-click / "open in new tab" and
                           keyboard navigation work.
                           $event.stopPropagation() blocks the click from also
                           reaching the row handler; without it the same click would
                           navigate twice and push two entries into the history. -->
                      <a [routerLink]="['/projects', p.id]" class="code-cell text-decoration-none"
                         (click)="$event.stopPropagation()">{{ p.code }}</a>
                    </td>
                    <td>
                      <a [routerLink]="['/projects', p.id]" class="name-cell text-decoration-none"
                         (click)="$event.stopPropagation()">{{ p.name }}</a>
                      <!-- The client name is optional in the model, so it is shown as
                           a second small line only when it is there. -->
                      @if (p.client) { <div class="sub-cell">{{ p.client }}</div> }
                    </td>
                    <!-- ?? '—' is the "nullish" fallback: print a dash when the value
                         is null or undefined. It fires on null/undefined ONLY, unlike
                         || which would also replace an empty string or a 0.
                         Without it the cell would print the word "null". -->
                    <td class="d-none d-lg-table-cell muted-cell">{{ p.chefProjetName ?? '—' }}</td>
                    <td class="d-none d-md-table-cell muted-cell">{{ p.startDate ?? '—' }}</td>
                    <td class="d-none d-md-table-cell muted-cell">{{ p.endDate ?? '—' }}</td>
                    <!-- createdAt arrives as an ISO date-time text such as
                         "2026-07-14T08:24:06"; the date pipe turns it into 14/07/2026.
                         The ternary is needed because the pipe would crash on an
                         undefined value, and an undefined createdAt is possible. -->
                    <td class="d-none d-xl-table-cell muted-cell">{{ p.createdAt ? (p.createdAt | date:'dd/MM/yyyy') : '—' }}</td>
                    <!-- Two different things here: statusBadge() picks the COLOUR
                         class from the raw code, transloco prints the TEXT in the
                         user's language. Colour and wording are kept apart so a new
                         language never changes the colours. -->
                    <td><span [class]="statusBadge(p.status)">{{ 'status.' + p.status | transloco }}</span></td>
                    @if (auth.hasPermission('VIEW_KPI')) {
                      <!-- number:'1.0-0' = at least 1 digit before the decimal point
                           and ZERO decimals. Budgets are large amounts; showing
                           cents would make the column noisy and hard to compare. -->
                      <td class="text-end num-cell">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    }
                    @if (auth.hasPermission('EDIT_PROJECT')) {
                      <td class="text-end" style="white-space:nowrap">
                        <!-- The action depends on the tab: an active project is
                             edited, an archived one is restored. Offering "edit" on
                             an archived project would let a user change a project
                             that is supposed to be frozen. -->
                        @if (mode() === 'active') {
                          <a [routerLink]="['/projects', p.id, 'edit']" class="btn btn-ghost btn-icon btn-sm"
                             [title]="'projects.editTitle' | transloco" [attr.aria-label]="'projects.editTitle' | transloco"
                             (click)="$event.stopPropagation()">
                            <i class="bi bi-pencil"></i>
                          </a>
                        } @else {
                          <!-- stopPropagation() again: without it, restoring a
                               project would also open its detail page and the user
                               would never see the list refresh. -->
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
                <!-- Two different empty messages, and the difference matters: "no
                     project at all" needs a "create the first one" button, while "no
                     result for your filter" needs a "reset the filter" button.
                     A single generic message would leave a user staring at an empty
                     table without understanding that his own filter hides the rows. -->
                @if (filtered().length === 0) {
                  <tr>
                    <!-- colspan makes this one cell stretch across the whole table.
                         The number is computed, because the number of columns
                         depends on the user's permissions. -->
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
                          <!-- Offer "create the first project" only on the Active tab
                               and only to a user who may create one. -->
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
        <!-- Hidden while loading and when there is nothing to show, otherwise the
             user would see a page bar saying "page 1 of 0" under an empty table.
             [total] is filtered().length, NOT all().length: the page bar must count
             the rows that match the current search, or the last page would be empty. -->
        @if (!loading() && filtered().length > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="filtered().length"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>
  `
})
/**
 * The Projects list screen.
 *
 * <p>It keeps the whole list in memory and derives what is on screen from it in
 * three steps: filter, then sort, then cut the current page. Each step is a
 * 'computed' signal, so Angular recalculates only the steps that really depend on
 * what changed. Changing the page number, for example, does not re-run the filter.
 *
 * <p>implements OnInit is used rather than doing the work in the constructor,
 * because ngOnInit runs after Angular has finished setting the component up; the
 * route snapshot read there is reliable.
 */
export class ProjectListComponent implements OnInit {
  // inject() is the modern form of constructor injection. It is used here so the
  //   class has no constructor at all and the fields can be readonly one by one.
  private readonly svc = inject(ProjectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  // Shared memory that lets the detail page build a "back to the list" link
  //   carrying the user's current search, filter, sort and page.
  private readonly listState = inject(ProjectsListStateService);
  // PUBLIC on purpose (no `private`): the template calls auth.hasPermission(...),
  //   and a private field cannot be read from the template.
  readonly auth = inject(AuthService);

  /** Full dataset for the current mode (loaded once, then filtered/sorted/paged client-side). */
  // private: nothing outside may replace the dataset. The template never reads it
  //   directly, it always reads filtered() / sorted() / paged().
  private readonly all = signal<Project[]>([]);
  // Starts at true so the very first paint already shows the grey skeleton rows
  //   instead of a flash of "no project found".
  loading = signal(true);

  // Each piece of list state is its own signal. Any computed value that reads one
  //   of them recalculates by itself when it changes, and the template redraws.
  //   Plain fields would need manual change detection calls to do the same.
  mode         = signal<'active' | 'archived'>('active');
  search       = signal('');
  statusFilter = signal('');
  sortCol      = signal<SortCol>('code');
  sortDir      = signal<SortDir>('asc');
  page         = signal(0);      // zero-based: page 0 is the first page
  pageSize     = signal(20);

  // Eight fixed numbers, used only to repeat the grey skeleton row eight times.
  //   It is a plain array and not a signal because it never changes.
  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  // ── Derived list pipeline: filter → sort → paginate ──────────────────

  /**
   * The rows that match the search text and the status filter.
   *
   * <p>A 'computed' signal: Angular runs this function again only when one of the
   * signals it reads (all, search, statusFilter) changes, and caches the result in
   * between. Written as a normal method it would run on every single change
   * detection pass, so scrolling the page would re-filter the whole list.
   */
  readonly filtered = computed(() => {
    // trim() drops the spaces a user leaves when he pastes text, and toLowerCase()
    //   makes the search case-insensitive: typing "bad" must find "BAD-2026".
    const q = this.search().trim().toLowerCase();
    const status = this.statusFilter();
    return this.all().filter(p => {
      // An empty search matches everything (`!q` short-circuits to true), otherwise
      //   the text is looked for in four fields at once, so the user does not have
      //   to know whether "Ooredoo" is the client or part of the project name.
      const matchText = !q
        || p.code.toLowerCase().includes(q)
        || p.name.toLowerCase().includes(q)
        // ?? '' guards the optional fields: client and chefProjetName may be
        //   undefined, and calling .toLowerCase() on undefined throws and blanks
        //   the whole table.
        || (p.client ?? '').toLowerCase().includes(q)
        || (p.chefProjetName ?? '').toLowerCase().includes(q);
      // Compared against the raw code stored in the database, never against the
      //   translated label, so the filter survives a language change.
      const matchStatus = !status || p.status === status;
      return matchText && matchStatus;
    });
  });

  /**
   * The filtered rows put in order by the column the user clicked.
   */
  readonly sorted = computed(() => {
    const col = this.sortCol();
    // Turning the direction into +1 / -1 lets one single compare() serve both
    //   directions: multiply its answer by -1 and the order is reversed.
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    // [...this.filtered()] copies the array BEFORE sorting. Array.sort() rearranges
    //   the array in place, so sorting filtered() directly would modify the value
    //   held inside a computed signal. Angular would then believe nothing changed
    //   while the data underneath has moved, and the table could show a stale order.
    return [...this.filtered()].sort((a, b) => this.compare(a, b, col) * dir);
  });

  /**
   * Only the rows of the page currently displayed.
   */
  readonly paged = computed(() => {
    const start = this.page() * this.pageSize();
    // slice() returns a new array and never fails when the end index is past the
    //   last row: on the final page it simply returns the few rows that are left.
    return this.sorted().slice(start, start + this.pageSize());
  });

  /** True when at least one filter is active; drives which empty message is shown. */
  readonly hasFilters = computed(() => !!this.search() || !!this.statusFilter());

  /**
   * Restores the list exactly as the URL describes it, then loads the data.
   *
   * <p>Reading the state from the URL is what makes the screen shareable and
   * refresh-proof: a user can copy /projects?search=BAD&sort=name&page=2, send it
   * to a colleague, and the colleague sees the same table. It is also what makes
   * the "back to the list" link of the detail page work.
   */
  ngOnInit(): void {
    // snapshot = the query parameters as they are at this instant. A snapshot is
    //   enough here because syncUrl() navigates with replaceUrl, which does not
    //   re-create the component, and every later change goes through the signals.
    const p = this.route.snapshot.queryParamMap;
    // `as` casts are needed because a URL parameter is always plain text. Each one
    //   has a fallback for the case where the parameter is missing or nonsense,
    //   so a hand-typed /projects?sort=xyz still shows a working table.
    this.mode.set((p.get('mode') as 'active' | 'archived') || 'active');
    this.search.set(p.get('search') || '');
    this.statusFilter.set(p.get('status') || '');
    this.sortCol.set((p.get('sort') as SortCol) || 'code');
    this.sortDir.set((p.get('dir') as SortDir) || 'asc');
    // Number(...) converts the text "2" into the number 2. Without it, page() would
    //   hold a string and page() * pageSize() would give "2" * 20, then slice()
    //   would misbehave.
    this.page.set(Number(p.get('page') || 0));
    this.pageSize.set(Number(p.get('size') || 20));
    this.listState.set(this.buildCompact());   // seed context for breadcrumbs on deep-link
    this.load();
  }

  /**
   * Builds the smallest set of query parameters that still describes the current
   * list state: any value equal to its default is left out.
   *
   * <p>WHY not simply send everything: the result is used both for the URL and for
   * the "back to the list" link. Sending every value would give a long noisy URL
   * such as /projects?mode=active&search=&status=&sort=code&dir=asc&page=0&size=20,
   * and an empty 'search=' is not the same thing as no search at all for the code
   * that reads it back.
   */
  private buildCompact(): Params {
    // Params is the Angular type for a bag of URL query parameters.
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

  /**
   * Downloads the whole list for the current tab and puts it in 'all'.
   *
   * <p>Called once at start-up and again whenever the tab changes or a project is
   * restored. Two different endpoints are used rather than one endpoint with a
   * flag, because the archived list is a separate, rarely used dataset.
   */
  private load(): void {
    this.loading.set(true);
    // Choosing the observable first, then subscribing once, avoids writing the same
    //   subscribe block twice.
    const obs = this.mode() === 'archived' ? this.svc.listArchived() : this.svc.listAll();
    // An Observable does nothing until somebody subscribes: without this call the
    //   HTTP request would never even be sent.
    obs.subscribe({
      // clampPage() runs here and not earlier, because the number of pages is only
      //   known once the rows have arrived.
      next: list => { this.all.set(list); this.loading.set(false); this.clampPage(); },
      // The error branch must also turn loading off, or a failed request would
      //   leave the grey skeleton rows spinning on screen for ever. The list is
      //   emptied so the user sees the normal empty state. The message itself is
      //   handled by the global HTTP error handling, not here.
      error: () => { this.all.set([]); this.loading.set(false); }
    });
  }

  // ── URL + context sync ───────────────────────────────────────────────

  /**
   * Writes the current list state into the URL and into the shared list state.
   *
   * <p>Called by every handler below. This is what lets the user refresh the page,
   * bookmark it, or come back from a project detail and find the same table.
   */
  private syncUrl(): void {
    this.listState.set(this.buildCompact());
    // navigate([]) with an empty command array means "stay on the same route, only
    //   change the query parameters".
    this.router.navigate([], {
      queryParams: {
        // null REMOVES a parameter from the URL. So each default value is sent as
        //   null and disappears, which keeps the URL short and readable.
        //   EXAMPLE: on the first page the URL is /projects, not /projects?page=0.
        mode: this.mode() === 'active' ? null : this.mode(),
        search: this.search() || null,
        status: this.statusFilter() || null,
        sort: this.sortCol() === 'code' ? null : this.sortCol(),
        dir: this.sortDir() === 'asc' ? null : this.sortDir(),
        page: this.page() || null,
        size: this.pageSize() === 20 ? null : this.pageSize(),
      },
      // replaceUrl replaces the current history entry instead of adding one.
      //   Without it, typing "BAD" in the search box would push four history
      //   entries (B, BA, BAD...) and the browser Back button would walk the user
      //   backwards letter by letter instead of leaving the screen.
      replaceUrl: true,
    });
  }

  /**
   * Pulls the page number back inside the valid range.
   *
   * <p>Needed because the page number can come from the URL, where nothing stops a
   * user from asking for page 12 of a list that has 2 pages. Without this the table
   * body would be empty while the data is clearly there.
   */
  private clampPage(): void {
    // Math.ceil turns 41 rows / 20 per page into 3 pages; minus 1 because the page
    //   number is zero-based. Math.max(0, ...) protects the empty-list case, where
    //   the computation would give -1.
    const maxPage = Math.max(0, Math.ceil(this.filtered().length / this.pageSize()) - 1);
    if (this.page() > maxPage) this.page.set(maxPage);
  }

  // ── Handlers ─────────────────────────────────────────────────────────

  /** New search text. Also jumps back to page 1: the old page number means nothing
   *  once the number of matching rows has changed. */
  onSearch(v: string): void { this.search.set(v); this.page.set(0); this.syncUrl(); }
  /** New status filter. Same reason for resetting the page to 0. */
  onStatus(v: string): void { this.statusFilter.set(v); this.page.set(0); this.syncUrl(); }
  /** The user picked another page in <app-pagination>. */
  onPage(n: number): void { this.page.set(n); this.syncUrl(); }
  /** The user changed how many rows fit on a page; page 1 is the only safe landing
   *  spot, because "page 5 of 20 rows" is not "page 5 of 50 rows". */
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.syncUrl(); }

  /** Clears search and status in one go. Sort order is deliberately kept: the user
   *  asked to see everything, not to undo the way he ordered the columns. */
  clearFilters(): void {
    this.search.set(''); this.statusFilter.set(''); this.page.set(0); this.syncUrl();
  }

  /**
   * Switches between the Active and the Archived tab.
   */
  setMode(m: 'active' | 'archived'): void {
    // Clicking the tab already selected does nothing. Without this guard the same
    //   tab would fire a fresh HTTP call and blank the table for no reason.
    if (this.mode() === m) return;
    // load() is called because the two tabs use two different endpoints; this is
    //   not a filter over data already in memory.
    this.mode.set(m); this.page.set(0); this.syncUrl(); this.load();
  }

  /**
   * Handles a click on a column header.
   *
   * <p>Clicking the column already sorted flips the direction; clicking another
   * column moves the sort there and starts ascending. Always starting ascending on
   * a new column is what users expect: jumping straight to descending looks like
   * the table sorted itself the wrong way round.
   */
  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortCol.set(col); this.sortDir.set('asc');
    }
    // Back to page 1: after a re-sort the rows on page 3 are completely different
    //   ones, so staying there would feel random to the user.
    this.page.set(0);
    this.syncUrl();
  }

  /** Opens a project when the user clicks anywhere on its row. */
  open(p: Project): void { this.router.navigate(['/projects', p.id]); }

  /**
   * Restores an archived project.
   *
   * <p>load() is called inside the subscribe callback, so the refresh happens only
   * after the server has confirmed. Reloading immediately after the call instead
   * would race the request and could redraw the list before the change is saved,
   * showing the project still archived.
   *
   * <p>The permission is checked again on the server side; hiding the button is
   * only for comfort.
   */
  unarchive(p: Project): void {
    this.svc.unarchive(p.id).subscribe(() => this.load());
  }

  // ── Sorting helpers ──────────────────────────────────────────────────

  /**
   * Compares two projects on one column. Returns a negative number when 'a' comes
   * first, a positive number when 'b' does, 0 when they are equal: that is the
   * contract Array.sort() expects.
   *
   * <p>The direction is NOT handled here. sorted() multiplies the answer by -1 for
   * a descending sort, so this function stays simple and each column is written
   * once instead of twice.
   */
  private compare(a: Project, b: Project, col: SortCol): number {
    switch (col) {
      // A budget is a number, so a plain subtraction gives the right sign.
      //   ?? 0 treats a missing budget as zero, otherwise the subtraction would
      //   produce NaN and sort() would leave the rows in a random order.
      case 'budget': return (a.effectiveBudget ?? 0) - (b.effectiveBudget ?? 0);
      // localeCompare, not a < b: it knows about accents, so "Élise" is placed next
      //   to "Elise" and not pushed to the very end of the list as a raw code-point
      //   comparison would do. That matters a lot with French names.
      case 'chef':   return (a.chefProjetName ?? '').localeCompare(b.chefProjetName ?? '');
      // Statuses are ordered by their readable label, so the user gets an
      //   alphabetical order he can see, not the arbitrary order of the raw codes.
      case 'status': return this.statusLabel(a.status).localeCompare(this.statusLabel(b.status));
      // The dates arrive as ISO text ("2026-07-14"). In that format, comparing the
      //   text gives exactly the same order as comparing the real dates, so there is
      //   no need to build Date objects. ?? '' sends the rows with no date first.
      case 'startDate': return (a.startDate ?? '').localeCompare(b.startDate ?? '');
      case 'endDate':   return (a.endDate ?? '').localeCompare(b.endDate ?? '');
      case 'createdAt': return (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
      case 'name':   return a.name.localeCompare(b.name);
      case 'code':
      // The `default` branch is what makes the function total: even if the value
      //   coming from the URL is nonsense, the table is still sorted by code
      //   instead of throwing.
      default:       return a.code.localeCompare(b.code);
    }
  }

  /**
   * The value for the aria-sort attribute of a column header.
   *
   * <p>Colour alone tells a sighted user which column is sorted. A screen reader
   * cannot see colour; aria-sort is the standard way to say "this column is sorted,
   * ascending". Without it the table is not usable by a blind user.
   */
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  /**
   * The Bootstrap Icons class of the little arrow in a column header: a double
   * arrow on the columns that COULD be sorted, an up or down arrow on the one that
   * IS sorted. Showing the same icon everywhere would leave the user guessing which
   * column is driving the order.
   */
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  /**
   * How many columns the table has right now.
   *
   * <p>Used for the colspan of the empty-state row. It has to be computed because
   * the budget column and the actions column appear only for users holding VIEW_KPI
   * and EDIT_PROJECT, so the same hard-coded number cannot fit every user.
   */
  colCount(): number {
    return 6
      + (this.auth.hasPermission('VIEW_KPI') ? 1 : 0)
      + (this.auth.hasPermission('EDIT_PROJECT') ? 1 : 0);
  }

  /**
   * The readable label of a status code, taken from the shared map in the model.
   *
   * <p>Used only for SORTING (see compare). The text actually displayed in the
   * table comes from Transloco, so it follows the user's language.
   */
  statusLabel(s: string): string {
    // `as keyof typeof ...` tells the compiler to treat this free text as one of
    //   the keys of the map. It is needed because the parameter is a plain string.
    //   ?? s is the safety net: if the database ever holds a status the front end
    //   does not know, the raw code is shown instead of `undefined`.
    return PROJECT_STATUS_LABELS[s as keyof typeof PROJECT_STATUS_LABELS] ?? s;
  }

  /**
   * The CSS class that gives a status its colour badge.
   *
   * <p>A lookup map is used rather than a chain of if/else: adding a status later
   * is one line here instead of a new branch, and the map reads like a table.
   */
  statusBadge(s: string): string {
    // Record<string, string> is the TypeScript type for "an object used as a
    //   dictionary whose keys and values are both text".
    const map: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled',
    };
    // ?? 'badge-draft' keeps the badge looking normal if an unknown status shows
    //   up; without it the class would be `undefined` and the badge would be an
    //   unstyled bare word in the middle of the table.
    return map[s] ?? 'badge-draft';
  }
}
