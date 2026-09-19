import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { Project, PROJECT_STATUS_LABELS, ProjectStatus } from '../../core/models/project.model';
import { PaginationComponent } from '../pagination/pagination.component';
import { TranslocoModule } from '@jsverse/transloco';

/*
 * ============================================================================
 * FILE: project-picker.component.ts
 * ============================================================================
 * WHAT THIS FILE IS
 * One shared, reusable Angular component: the standard way the whole app asks
 * the user "which project are you working on?".
 *
 * WHERE IT SITS IN THE FLOW
 *   - It is USED BY feature screens (DI / Devis Interne, team, planning...).
 *     A screen puts <app-project-picker [selected]="..." (projectSelected)="..." />
 *     at the top of its page. The screen keeps the chosen project and then loads
 *     its own data for that project id.
 *   - It CALLS:
 *       ProjectService.listAll()  -> the list of projects the backend lets this
 *                                    user see (the server already filters, the
 *                                    component never decides who may see what);
 *       TeamService.list(id)      -> how many people are on the chosen project,
 *                                    shown in the context card;
 *       AuthService.currentUserId -> to highlight "my" projects.
 *   - It also uses <app-pagination> (shared) and Transloco for translations.
 *
 * WHY IT EXISTS / WHAT BREAKS WITHOUT IT
 * Without it every screen would build its own project <select>. With hundreds of
 * projects a dropdown is unusable, and each screen would drift into a different
 * look and a different behaviour. This component gives one single behaviour:
 * search, "assigned to me" / "favourites" filters, recents, favourites and
 * pagination, in one place.
 *
 * SECURITY NOTE (important for the defence)
 * This component is pure user interface. It shows nothing that the backend did
 * not already agree to send. Authorization stays on the server: permissions are
 * checked with @PreAuthorize on service methods, and ADR-021 (ProjectScopeInterceptor)
 * also checks the project scope on /api/projects/{id}/** URLs. So even if a user
 * forged a project id here, the server would refuse the next call.
 * ============================================================================
 */

// Key used in the browser's localStorage to remember the projects this user
// starred. WHY a named constant: the same string is used to read and to write;
// a typo in one of the two would silently lose every favourite.
const FAV_KEY = 'pms.projectPicker.favorites';
// Same idea for the "recently opened" list.
const RECENT_KEY = 'pms.projectPicker.recents';
// How many recent projects we SHOW as shortcut chips. We store more than this
// (20, see addRecent) because the stored list is also used to sort the table:
// showing 20 chips would fill the screen with noise.
const RECENT_MAX = 6;

/**
 * Project selection built as "master -> detail" with progressive disclosure
 * (= show little first, show more only when the user asks).
 *
 * <p>The normal state of the page is NOT a project browser. It is either an
 * empty prompt (nothing selected yet) or a rich <b>Current Project</b> card
 * (code, status, manager, team size, budget, period). The full browser —
 * search, "Assigned" / "Favourites" filters, recents, paginated table — lives
 * inside a <b>modal</b> opened with the "Choose / Change project" button.
 *
 * <p>WHY this way rather than a plain dropdown: the obvious alternative is a
 * &lt;select&gt; with every project in it. With a few hundred projects that
 * list is impossible to scan, it cannot be searched, and it hides the useful
 * information (client, manager, dates). Keeping the browser in a modal also
 * means the host screen's real feature (the DI table, the planning...) stays
 * the main thing on the page.
 */
// @Component turns this class into an Angular component: Angular will create it,
// render the template below, and keep the screen in sync with the signals.
// Without it the class would just be an ordinary class and <app-project-picker>
// in a page would stay an unknown, empty tag.
@Component({
  // The tag name screens write in their HTML: <app-project-picker ... />.
  selector: 'app-project-picker',
  // standalone = this component declares its own dependencies below and needs no
  // NgModule. Without it we would have to register it in a module, and every new
  // screen that wants the picker would have to import that module too.
  standalone: true,
  // The building blocks used inside the template. In a standalone component
  // nothing is available unless it is listed here: remove TranslocoModule and the
  // "| transloco" pipe in the template stops compiling; remove FormsModule and
  // [ngModel] on the search box stops working.
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  // Inline template (the HTML lives here, not in a separate .html file).
  // NOTE for anyone editing: inside these backticks only <!-- --> comments are legal.
  template: `
    <!-- ── No selection: on-topic feature landing ──
         @if is Angular's built-in condition block. selected() reads the input
         signal, so the moment the parent screen sets a project this whole block
         is removed from the page and the card below takes its place.
         Without this branch a screen with no project yet would show an empty
         page with no hint of what to do. -->
    @if (!selected()) {
      <div class="pp-landing">
        <!-- No title here: the host page already has its own title. This block
             says WHAT TO DO, it does not repeat where the user is. -->
        <div class="pp-hero">
          <div class="pp-hero-icon"><i class="bi {{ featureIcon() }}"></i></div>
          <div class="pp-hero-text">
            <p class="pp-hero-lead">{{ 'picker.chooseProject' | transloco }}</p>
          </div>
          <button class="btn btn-primary pp-hero-btn" (click)="openModal()">
            <i class="bi bi-search me-1"></i>{{ 'picker.browseAll' | transloco }}
            @if (projects().length) { <span class="pp-hero-count">{{ projects().length }}</span> }
          </button>
        </div>

        <!-- Recent projects stay directly clickable: this is a real shortcut to
             work already in progress, not a decorative section. Clicking a card
             calls confirm(p), which is exactly what picking a row in the modal does.
             Without it the user would have to open the modal every single time,
             even to come back to the project opened one minute ago. -->
        @if (quickAccess().length) {
          <div class="pp-qa-grid">
            <!-- @for is Angular's loop. "track p.id" tells Angular which DOM card
                 belongs to which project. WHY it matters: without a stable track
                 key Angular destroys and rebuilds every card when the list changes,
                 which loses focus and makes the screen flicker. -->
            @for (p of quickAccess(); track p.id) {
              <button type="button" class="pp-qa-card" (click)="confirm(p)" [title]="p.code">
                <div class="pp-qa-top">
                  <span class="pp-qa-name">{{ p.name }}</span>
                  <span [class]="badgeClass(p.status)">{{ 'status.' + p.status | transloco }}</span>
                </div>
                <div class="pp-qa-meta">
                  <span class="pp-qa-metaitem"><i class="bi bi-building"></i>{{ p.client || '—' }}</span>
                  <span class="pp-qa-metaitem"><i class="bi bi-person-badge"></i>{{ p.chefProjetName || '—' }}</span>
                </div>
                <!-- Badge on the card. This is DISPLAY ONLY: it says "you are the
                     manager of this project", it grants nothing. Rights are decided
                     by the backend (permission check + ADR-021 project scope), never
                     by this comparison. -->
                @if (p.chefProjetId === myId) {
                  <span class="pp-qa-mine"><i class="bi bi-person-check"></i> {{ 'picker.mine' | transloco }}</span>
                } @else if (isFav(p.id)) {
                  <span class="pp-qa-mine" style="color:var(--c-amber)"><i class="bi bi-star-fill"></i> {{ 'picker.favorite' | transloco }}</span>
                }
                <i class="bi bi-arrow-right pp-qa-go"></i>
              </button>
            }
          </div>
        }
      </div>
    }

    <!-- ── Selection: Current Project context card ──
         Shown once a project is chosen. It replaces the landing block above, so
         the user always sees exactly one of the two. It also answers the question
         "am I really on the right project?" before he edits sensitive data such
         as a DI (Devis Interne = internal quote). -->
    @else {
      <div class="pp-context">
        <div class="pp-ctx-body">
          <!-- The "!" after selected() is TypeScript's "it is not null here".
               It is safe because this whole block is inside the @else of
               "@if (!selected())", so a project always exists at this point.
               Without it TypeScript would refuse to compile "selected().code". -->
          <div class="pp-ctx-head">
            <i class="bi bi-folder-check pp-ctx-icon"></i>
            <span class="pp-ctx-code">{{ selected()!.code }}</span>
            <span [class]="badgeClass(selected()!.status)">{{ 'status.' + selected()!.status | transloco }}</span>
          </div>
          <div class="pp-ctx-name">{{ selected()!.name }}</div>
          <div class="pp-ctx-stats">
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-person-badge"></i> {{ 'picker.manager' | transloco }}</span>
              <span class="pp-stat-val">{{ selected()!.chefProjetName || '—' }}</span>
            </div>
            <!-- teamCount is null while the team request is still running, so we
                 show "…". WHY not 0: showing 0 would be a lie for one second and
                 the user could think the project has nobody on it. -->
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-people"></i> {{ 'picker.team' | transloco }}</span>
              <span class="pp-stat-val">{{ teamCount() === null ? '…' : teamCount() }}</span>
            </div>
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-wallet2"></i> {{ 'picker.budget' | transloco }}</span>
              <span class="pp-stat-val">{{ budget() }}</span>
            </div>
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-calendar3"></i> {{ 'picker.period' | transloco }}</span>
              <span class="pp-stat-val">{{ period() }}</span>
            </div>
            @if (selected()!.client) {
              <div class="pp-stat">
                <span class="pp-stat-label"><i class="bi bi-building"></i> {{ 'picker.client' | transloco }}</span>
                <span class="pp-stat-val">{{ selected()!.client }}</span>
              </div>
            }
          </div>
        </div>
        <button class="btn btn-outline-secondary pp-ctx-change" (click)="openModal()">
          <i class="bi bi-arrow-left-right me-1"></i>{{ 'picker.change' | transloco }}
        </button>
      </div>
    }

    <!-- ── Modal browser ──
         The full project browser. It only exists in the page while open() is true,
         so the big table is not built (and not kept in memory) when it is closed. -->
    @if (open()) {
      <!-- Grey layer behind the dialog (Bootstrap 5 class). It exists so the page
           behind clearly looks disabled. -->
      <div class="modal-backdrop fade show"></div>
      <!-- Click anywhere on this outer layer = close. tabindex="-1" makes the
           dialog focusable so the Escape key is received here.
           Without (keydown.escape) the user would be forced to reach the mouse to
           leave the dialog, which fails basic keyboard accessibility. -->
      <div class="modal d-block" tabindex="-1" (click)="close()" (keydown.escape)="close()">
        <!-- stopPropagation keeps a click INSIDE the dialog from bubbling up to the
             outer div. Without it, clicking a table row or the search box would
             also trigger close() and the modal would shut on every click. -->
        <div class="modal-dialog modal-xl modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content pp-modal">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-folder2-open me-2"></i>{{ 'picker.modalTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="close()" [attr.aria-label]="'common.close' | transloco"></button>
            </div>

            <div class="pp-search-row">
              <div class="input-wrap pp-search">
                <i class="bi bi-search input-icon"></i>
                <!-- [ngModel] one-way + (ngModelChange) instead of the usual
                     two-way [(ngModel)]: the value always comes from the search()
                     signal, and every keystroke goes through onSearch(), which
                     also resets the page number. Without that reset, typing while
                     on page 4 would show an empty table, because the filtered list
                     may now have only one page. -->
                <input type="search" class="form-control"
                       [placeholder]="'picker.searchPlaceholder' | transloco"
                       [ngModel]="search()" (ngModelChange)="onSearch($event)">
              </div>
              <!-- Two toggle "chips". Each one flips its own signal and resets the
                   page to 0, for the same reason as the search box above.
                   [class.pp-chip-on] adds the "on" style only while the filter is
                   active, so the user can see which filters are applied. -->
              <div class="pp-filters">
                <button type="button" class="pp-chip" [class.pp-chip-on]="onlyMine()"
                        (click)="onlyMine.set(!onlyMine()); page.set(0)" [title]="'picker.assigned' | transloco">
                  <i class="bi bi-person-check"></i> {{ 'picker.assigned' | transloco }}
                </button>
                <button type="button" class="pp-chip" [class.pp-chip-on]="onlyFav()"
                        (click)="onlyFav.set(!onlyFav()); page.set(0)" [title]="'picker.favorites' | transloco">
                  <i class="bi bi-star-fill"></i> {{ 'picker.favorites' | transloco }}
                </button>
              </div>
            </div>

            <!-- The recents strip is hidden as soon as the user searches or turns a
                 filter on. WHY: those chips ignore the filters, so keeping them
                 visible would offer projects that contradict what the table shows
                 (example: filter "Favourites" on, but a non-favourite chip still
                 clickable right above the table). -->
            @if (recentProjects().length && !search() && !onlyMine() && !onlyFav()) {
              <div class="pp-recents">
                <span class="pp-recents-label"><i class="bi bi-clock-history"></i> {{ 'picker.recents' | transloco }}</span>
                @for (p of recentProjects(); track p.id) {
                  <button type="button" class="pp-recent-chip" (click)="confirm(p)" [title]="p.name">
                    <span class="pp-recent-code">{{ p.code }}</span>
                    <span class="pp-recent-name">{{ p.name }}</span>
                  </button>
                }
              </div>
            }

            <div class="pp-table-wrap">
              <table class="table table-hover align-middle pp-table mb-0">
                <thead>
                  <tr>
                    <th style="width:36px"></th>
                    <th style="width:130px">{{ 'picker.code' | transloco }}</th>
                    <th>{{ 'picker.project' | transloco }}</th>
                    <th class="d-none d-lg-table-cell">{{ 'picker.client' | transloco }}</th>
                    <th style="width:96px">{{ 'common.status' | transloco }}</th>
                    <th class="d-none d-xl-table-cell">{{ 'picker.manager' | transloco }}</th>
                    <th class="d-none d-xl-table-cell" style="width:100px">{{ 'picker.start' | transloco }}</th>
                    <th class="d-none d-xl-table-cell" style="width:100px">{{ 'picker.end' | transloco }}</th>
                  </tr>
                </thead>
                <tbody>
                  <!-- paged() is only the rows of the current page, never the whole
                       list. With several hundred projects, rendering everything
                       would create thousands of DOM cells and make the modal slow
                       to open and slow to scroll. -->
                  <!-- Two different highlights:
                       pp-row-active  = the row the user just clicked (pending, not
                                        confirmed yet, the "Select" button is armed);
                       pp-row-current = the project the host screen is already on.
                       Without the two, the user could not tell what he is about to
                       choose from what he is already using. -->
                  <!-- On each row: one click = pre-select (safe, reversible),
                       double click = choose straight away for users who already know
                       the row. Choosing on a single click would change the host
                       screen's whole context by accident while the user is only
                       reading the table. -->
                  @for (p of paged(); track p.id) {
                    <tr class="pp-row" [class.pp-row-active]="pending()?.id === p.id"
                        [class.pp-row-current]="selected()?.id === p.id"
                        (click)="pick(p)" (dblclick)="confirm(p)">
                      <!-- The star lives inside a clickable row, so toggleFav() calls
                           stopPropagation(). Without it, starring a project would
                           also pre-select that row, which the user never asked for.
                           aria-label changes with the state so a screen reader says
                           "add to favourites" or "remove from favourites" and not
                           just "button". -->
                      <td class="text-center">
                        <button type="button" class="pp-star" [class.pp-star-on]="isFav(p.id)"
                                (click)="toggleFav(p, $event)"
                                [attr.aria-label]="(isFav(p.id) ? 'picker.removeFavorite' : 'picker.addFavorite') | transloco">
                          <i class="bi" [class.bi-star-fill]="isFav(p.id)" [class.bi-star]="!isFav(p.id)"></i>
                        </button>
                      </td>
                      <td><span class="pp-code">{{ p.code }}</span></td>
                      <td>
                        <div class="pp-name">{{ p.name }}</div>
                        @if (p.chefProjetId === myId) {
                          <span class="pp-mine"><i class="bi bi-person-check"></i> {{ 'picker.assigned' | transloco }}</span>
                        }
                      </td>
                      <td class="d-none d-lg-table-cell pp-muted">{{ p.client || '—' }}</td>
                      <td><span [class]="badgeClass(p.status)">{{ 'status.' + p.status | transloco }}</span></td>
                      <td class="d-none d-xl-table-cell pp-muted">{{ p.chefProjetName || '—' }}</td>
                      <td class="d-none d-xl-table-cell pp-muted pp-num">{{ p.startDate || '—' }}</td>
                      <td class="d-none d-xl-table-cell pp-muted pp-num">{{ p.endDate || '—' }}</td>
                    </tr>
                  }
                  <!-- @empty runs when the loop produced no row at all. Without it a
                       search with no match would show an empty white box and the
                       user could think the screen is broken. -->
                  @empty {
                    <tr><td colspan="8">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-search"></i></div>
                        <div class="es-title">{{ 'picker.noMatch' | transloco }}</div>
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>

            <!-- Shared pagination component. "total" is ordered().length, that is the
                 size AFTER search and filters, not the size of the whole list.
                 If we passed projects().length the bar would offer page 9 while the
                 filtered result only has one page. -->
            <app-pagination
              [page]="page()" [pageSize]="pageSize()" [total]="ordered().length"
              (pageChange)="page.set($event)"
              (pageSizeChange)="pageSize.set($event); page.set(0)" />

            <div class="modal-footer">
              <span class="pp-hint">{{ 'picker.hint' | transloco }}</span>
              <button class="btn btn-secondary" (click)="close()">{{ 'common.cancel' | transloco }}</button>
              <!-- The confirm button stays disabled while nothing is pre-selected.
                   That is what makes "pending()!" safe: the click cannot happen when
                   pending() is null, so the non-null "!" is not a guess. -->
              <button class="btn btn-primary" [disabled]="!pending()" (click)="confirm(pending()!)">
                <i class="bi bi-check-lg me-1"></i>{{ 'picker.select' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  // Component-scoped styles. Angular rewrites these rules so they apply only
  // inside this component: a class like .pp-row cannot leak out and repaint rows
  // in another table of the app.
  // NOTE for anyone editing: inside this block only /* */ comments are legal.
  styles: [`
    /* ── Feature landing (no selection) ── */
    .pp-landing { display: flex; flex-direction: column; gap: 1.25rem; }
    .pp-hero { display: flex; align-items: center; gap: 1.25rem; padding: 1.5rem 1.75rem;
      border: 1px solid var(--border); border-radius: 16px; flex-wrap: wrap;
      background:
        radial-gradient(120% 140% at 0% 0%, var(--c-brand-dim) 0%, transparent 55%),
        var(--surface-1, var(--surface)); }
    .pp-hero-icon { width: 56px; height: 56px; border-radius: 14px; display: grid; place-items: center;
      background: var(--c-brand); color: #fff; font-size: 26px; flex-shrink: 0;
      box-shadow: 0 6px 16px var(--c-brand-dim); }
    .pp-hero-text { flex: 1; min-width: 220px; }
    .pp-hero-title { font-size: 22px; font-weight: 800; color: var(--text-1); margin: 0; }
    .pp-hero-desc { font-size: 14px; color: var(--text-2); margin: .25rem 0 0; max-width: 60ch; }
    .pp-hero-btn { flex-shrink: 0; display: inline-flex; align-items: center; }
    .pp-hero-count { margin-left: .5rem; background: rgba(255,255,255,.22); border-radius: 20px;
      padding: 0 .5rem; font-size: 11px; font-weight: 700; }

    .pp-qa-label { font-size: 12px; font-weight: 700; letter-spacing: .03em; color: var(--text-2);
      display: inline-flex; align-items: center; gap: .4rem; }
    .pp-qa-label > .bi { color: var(--c-amber, #f59e0b); }
    .pp-qa-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 1rem; }
    .pp-qa-card { position: relative; text-align: left; padding: 1.15rem 1.25rem; min-height: 132px;
                 display: flex; flex-direction: column; gap: .55rem; border: 1px solid var(--border);
      border-radius: 14px; background: var(--surface-1, var(--surface)); cursor: pointer;
      transition: transform .12s, border-color .12s, box-shadow .12s; display: flex; flex-direction: column; gap: .4rem; }
    .pp-qa-card:hover { transform: translateY(-2px); border-color: var(--c-brand);
      box-shadow: 0 8px 22px rgba(0,0,0,.10); }
    .pp-qa-top { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
    .pp-qa-code { font-family: var(--font-mono, monospace); font-weight: 800; font-size: 12px; color: var(--c-brand); }
    .pp-qa-name { font-size: 15px; font-weight: 700; color: var(--text-1); line-height: 1.3;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .pp-qa-meta { display: flex; flex-direction: column; gap: .2rem; margin-top: .1rem; }
    .pp-qa-metaitem { font-size: 12px; color: var(--text-2); display: inline-flex; align-items: center; gap: .4rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pp-qa-metaitem > .bi { color: var(--text-3); font-size: 12px; flex-shrink: 0; }
    .pp-qa-mine { font-size: 10px; font-weight: 700; color: var(--c-success); display: inline-flex;
      align-items: center; gap: .25rem; }
    .pp-qa-go { position: absolute; right: 1rem; bottom: 1rem; color: var(--text-3); font-size: 14px;
      opacity: 0; transition: opacity .12s; }
    .pp-qa-card:hover .pp-qa-go { opacity: 1; color: var(--c-brand); }
    @media (prefers-reduced-motion: reduce) { .pp-qa-card { transition: none; } .pp-qa-card:hover { transform: none; } }

    /* ── Current Project card ── */
    .pp-context { display: flex; align-items: center; gap: 1.5rem; padding: 1.125rem 1.5rem;
      border: 1px solid var(--border); border-left: 4px solid var(--c-brand); border-radius: 14px;
      background: var(--surface-1, var(--surface)); flex-wrap: wrap; }
    .pp-ctx-body { flex: 1; min-width: 260px; }
    .pp-ctx-head { display: flex; align-items: center; gap: .625rem; }
    .pp-ctx-icon { color: var(--c-success); font-size: 17px; }
    .pp-ctx-code { font-family: var(--font-mono, monospace); font-weight: 800; font-size: 14px; color: var(--c-brand); }
    .pp-ctx-name { font-size: 18px; font-weight: 700; color: var(--text-1); margin: .25rem 0 .75rem; }
    .pp-ctx-stats { display: flex; flex-wrap: wrap; gap: 1.75rem; }
    .pp-stat { display: flex; flex-direction: column; gap: .1rem; }
    .pp-stat-label { font-size: 10px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase;
      color: var(--text-3); display: inline-flex; align-items: center; gap: .3rem; }
    .pp-stat-val { font-size: 14px; font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; }
    .pp-ctx-change { flex-shrink: 0; align-self: flex-start; }

    /* ── Modal shell ── */
    .pp-modal { max-height: calc(100vh - 3.5rem); }
    .pp-search-row { display: flex; align-items: center; gap: .75rem; padding: .875rem 1rem;
      border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .pp-search { flex: 1; min-width: 220px; }
    .pp-filters { display: flex; gap: .5rem; }
    .pp-chip { display: inline-flex; align-items: center; gap: .375rem; padding: .375rem .75rem;
      border: 1px solid var(--border); border-radius: 20px; background: transparent; color: var(--text-2);
      font-size: 12px; font-weight: 600; cursor: pointer; transition: all .15s; }
    .pp-chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
    .pp-chip-on { background: var(--c-brand-dim); border-color: var(--c-brand); color: var(--c-brand); }

    .pp-recents { display: flex; align-items: center; gap: .5rem; flex-wrap: wrap;
      padding: .625rem 1rem; border-bottom: 1px solid var(--border); }
    .pp-recents-label { font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase;
      color: var(--text-3); display: inline-flex; align-items: center; gap: .35rem; }
    .pp-recent-chip { display: inline-flex; align-items: baseline; gap: .4rem; max-width: 260px;
      padding: .3rem .625rem; border: 1px solid var(--border); border-radius: 8px; background: transparent;
      cursor: pointer; transition: all .15s; }
    .pp-recent-chip:hover { border-color: var(--c-brand); background: var(--c-brand-dim); }
    .pp-recent-code { font-family: var(--font-mono, monospace); font-size: 11px; font-weight: 700; color: var(--c-brand); }
    .pp-recent-name { font-size: 12px; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .pp-table-wrap { overflow-y: auto; overflow-x: auto; flex: 1; }
    .pp-table thead th { position: sticky; top: 0; z-index: 1; background: var(--surface-2, var(--surface));
      font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-3); }
    .pp-row { cursor: pointer; }
    .pp-row:hover { background: var(--c-brand-dim); }
    .pp-row-current { background: var(--surface-2, rgba(0,0,0,.03)); }
    .pp-row-active { background: var(--c-brand-dim) !important; box-shadow: inset 3px 0 0 var(--c-brand); }
    .pp-code { font-family: var(--font-mono, monospace); font-weight: 700; font-size: 12px; color: var(--c-brand); }
    .pp-name { font-size: 13px; font-weight: 600; color: var(--text-1); }
    .pp-muted { color: var(--text-2); font-size: 12px; }
    .pp-num { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .pp-mine { font-size: 10px; color: var(--c-success); font-weight: 600; display: inline-flex; align-items: center; gap: .25rem; }
    .pp-star { background: none; border: 0; padding: 0; cursor: pointer; color: var(--text-3); font-size: 14px; line-height: 1; }
    .pp-star:hover { color: var(--c-amber, #f59e0b); }
    .pp-star-on { color: var(--c-amber, #f59e0b); }
    .pp-hint { font-size: 11px; color: var(--text-3); margin-right: auto; }
    @media (prefers-reduced-motion: reduce) { .pp-chip, .pp-recent-chip, .pp-row { transition: none; } }
  `]
})
/**
 * The component class: all the state and the logic behind the template above.
 *
 * <p>Design choice: everything is held in SIGNALS. A signal is a value that
 * remembers who reads it, so when it changes Angular redraws only the parts of
 * the screen that used it. The alternative (plain fields + manual change
 * detection) would redraw the whole modal on every keystroke in the search box.
 */
export class ProjectPickerComponent {
  // inject() is the modern way to get a service instead of a constructor
  // parameter. These three are the component's only doors to the backend.
  private readonly projectSvc = inject(ProjectService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);

  // input() = a value the PARENT screen passes down, as a signal.
  // The project currently chosen by the host screen. The component does not own
  // it: the parent stays the single source of truth, which is why confirm() only
  // emits an event and never writes this itself.
  selected = input<Project | null>(null);
  // Bootstrap icon name for the landing block, so the same picker can look like
  // the feature that hosts it (a wallet for DI, a calendar for planning...).
  featureIcon = input<string>('bi-folder2-open');
  // output() = the event the parent listens to: (projectSelected)="...".
  // This is the ONLY way a choice leaves this component.
  projectSelected = output<Project>();

  // Id of the logged-in user, read once when the component is created, used only
  // to mark and sort "my" projects. It is display information, never a right.
  readonly myId = this.auth.currentUserId;

  // The projects the backend agreed to return for this user (filled in the
  // constructor). Empty until the HTTP answer arrives.
  projects = signal<Project[]>([]);
  // Is the browser modal open?
  open = signal(false);
  // Current text typed in the search box.
  search = signal('');
  // Filter: keep only the projects where I am the manager.
  onlyMine = signal(false);
  // Filter: keep only the starred projects.
  onlyFav = signal(false);
  // Current page number in the modal table (0-based).
  page = signal(0);
  // Rows per page.
  pageSize = signal(12);
  // The row clicked once but not confirmed yet. Separate from selected() so a
  // single click never changes the host screen.
  pending = signal<Project | null>(null);
  // Number of people on the selected project; null means "still loading".
  teamCount = signal<number | null>(null);
  // Starred project ids. A Set is used, not an array, because isFav() runs for
  // every visible row: Set.has() is immediate while array.includes() would walk
  // the whole list again and again.
  favorites = signal<Set<number>>(this.loadFavorites());
  // Recently opened project ids, newest first.
  recents = signal<number[]>(this.loadRecents());

  /**
   * Loads the project list once, and keeps the team counter in sync with the
   * selected project.
   */
  constructor() {
    // One single call for the whole list. WHY load everything instead of asking
    // the server page by page: search, favourites, "mine" and the sort order all
    // work on the complete list, and the number of projects of one company stays
    // small enough for that. The server still decides WHICH projects are in the
    // answer, so this is not a way to see more than allowed.
    this.projectSvc.listAll().subscribe(list => this.projects.set(list));
    // effect() re-runs on its own every time a signal it reads changes. Here it
    // reads selected(), so each new selection triggers a fresh team request.
    // Without it the context card would keep showing the team size of the
    // previous project after the user changed project.
    effect(() => {
      const p = this.selected();
      // Reset to "loading" first, so the card never shows the old number while
      // the new one is on its way.
      this.teamCount.set(null);
      if (p) {
        this.teamSvc.list(p.id).subscribe({
          // Guard against answers arriving in the wrong order: if the user changed
          // project again while this request was travelling, the reply is ignored.
          // Without this test, a slow answer for project A could overwrite the
          // correct count of project B.
          next: (m: unknown[]) => { if (this.selected()?.id === p.id) this.teamCount.set(m.length); },
          // A failure here is not important: the card simply keeps showing "…".
          // We swallow it on purpose rather than throw a red banner at the user
          // for a secondary piece of information.
          error: () => {}
        });
      }
    });
  }

  // ── Context card presentation ──────────────────────────────────
  /**
   * Budget of the selected project, already formatted for display
   * (French number grouping + " TND"), or "—" when there is none.
   *
   * <p>computed() caches the result and recalculates only when selected()
   * changes; calling toLocaleString straight in the template would redo the
   * formatting on every single redraw of the page.
   *
   * <p>effectiveBudget comes first, budgetTnd is the fallback: the effective
   * budget is the value the backend derived, and derived amounts always win
   * over the raw stored one.
   */
  readonly budget = computed(() => {
    const p = this.selected();
    // "?." stops if p is null, "??" takes budgetTnd only when effectiveBudget is
    // null or undefined. Using "||" here would be a bug: a real budget of 0 would
    // be treated as missing and replaced by the other field.
    const v = p?.effectiveBudget ?? p?.budgetTnd;
    // "!= null" is deliberate: it covers null AND undefined, but still lets 0
    // through, so a project with a zero budget displays "0 TND" and not "—".
    return v != null ? v.toLocaleString('fr-FR', { maximumFractionDigits: 0 }) + ' TND' : '—';
  });
  /**
   * Start and end dates of the selected project as one short string.
   *
   * <p>A missing END date is shown as "…" and not as "—": an open end usually
   * means the project is still running, which is different from "no date known".
   */
  readonly period = computed(() => {
    const p = this.selected();
    if (!p) return '—';
    return `${p.startDate ?? '—'} → ${p.endDate ?? '…'}`;
  });

  // ── Modal derived lists ────────────────────────────────────────
  /**
   * The projects that match the search text AND the two filter chips.
   *
   * <p>First step of a chain of three: filtered -> ordered -> paged. Splitting
   * it in three keeps each step easy to read and lets Angular recompute only the
   * step that really changed (changing the page does not redo the filtering).
   */
  readonly filtered = computed(() => {
    // Lowercase + trim once, outside the loop. Doing it inside would repeat the
    // same work for every project on every keystroke.
    const s = this.search().toLowerCase().trim();
    const fav = this.favorites();
    return this.projects().filter(p => {
      // Filters are applied before the text search because they are cheaper and
      // they cut the list down first.
      if (this.onlyMine() && p.chefProjetId !== this.myId) return false;
      if (this.onlyFav() && !fav.has(p.id)) return false;
      // Empty box = keep everything.
      if (!s) return true;
      // The search looks in four fields at once, so the user can type a code, a
      // project name, a client or a manager without choosing a column first.
      // "?? ''" protects the optional fields: client may be undefined, and
      // undefined.toLowerCase() would crash the whole modal.
      return p.code.toLowerCase().includes(s)
          || p.name.toLowerCase().includes(s)
          || (p.client ?? '').toLowerCase().includes(s)
          || (p.chefProjetName ?? '').toLowerCase().includes(s);
    });
  });

  /**
   * The filtered list, sorted so that the most likely project comes first.
   *
   * <p>Order of the rules: favourites, then projects I manage, then recently
   * opened, then project code. WHY this order rather than a plain alphabetical
   * sort: a user typically works on a handful of projects, and a pure A→Z list
   * would bury them behind dozens of projects he never touches.
   */
  readonly ordered = computed(() => {
    const fav = this.favorites();
    const recents = this.recents();
    // Position in the recents list; a project that is not in it gets Infinity so
    // it always sorts after every recent one.
    const recentRank = (id: number) => { const i = recents.indexOf(id); return i === -1 ? Infinity : i; };
    // [...] copies the array first. sort() changes the array in place, and
    // sorting filtered() directly would mutate a value Angular has cached, which
    // can make the screen show a stale or half-sorted list.
    return [...this.filtered()].sort((a, b) => {
      // Rule 1 - favourites first (0 sorts before 1).
      const fa = fav.has(a.id) ? 0 : 1, fb = fav.has(b.id) ? 0 : 1;
      if (fa !== fb) return fa - fb;
      // Rule 2 - projects where I am the manager.
      const ma = a.chefProjetId === this.myId ? 0 : 1, mb = b.chefProjetId === this.myId ? 0 : 1;
      if (ma !== mb) return ma - mb;
      // Rule 3 - most recently opened.
      const ra = recentRank(a.id), rb = recentRank(b.id);
      if (ra !== rb) return ra - rb;
      // Rule 4 - last resort, alphabetical on the code. This final tie-break
      // matters: without it two equal projects could swap places between two
      // redraws and the table would look unstable.
      return a.code.localeCompare(b.code);
    });
  });

  /**
   * Only the rows of the current page. Third and last step of the chain.
   *
   * <p>It clamps the page number instead of trusting it. Without the clamp, a
   * user sitting on page 5 who then types a search would land past the end of
   * the shorter result and see an empty table.
   */
  readonly paged = computed(() => {
    const size = this.pageSize();
    // Math.max(1, ...) keeps at least one page, so an empty result gives page 0
    // and not page -1.
    const pages = Math.max(1, Math.ceil(this.ordered().length / size));
    const page = Math.min(this.page(), pages - 1);
    return this.ordered().slice(page * size, page * size + size);
  });

  /**
   * The recent project ids turned into real Project objects, for the chips at
   * the top of the modal.
   *
   * <p>A Map is built first so each lookup is immediate instead of scanning the
   * whole project list once per recent id.
   */
  readonly recentProjects = computed(() => {
    const byId = new Map(this.projects().map(p => [p.id, p]));
    // Ids stored in the browser may point at projects that were deleted, or that
    // this user is no longer allowed to see, so byId.get() can return undefined.
    // "(p): p is Project => !!p" drops those holes AND tells TypeScript the
    // result contains no undefined. Without it a deleted project would crash the
    // chip row on p.code.
    return this.recents().map(id => byId.get(id)).filter((p): p is Project => !!p).slice(0, RECENT_MAX);
  });

  /**
   * Landing shortcut cards: assigned → recent → favourites → active → any,
   * with duplicates removed, 12 cards at most.
   *
   * <p>WHY the cascade: a brand-new user has no recents and no favourites. If we
   * only showed those, his landing page would be empty and he would have no idea
   * what to do. Each source fills the gaps left by the previous one, so the block
   * is never empty as long as he can see at least one project.
   */
  readonly quickAccess = computed(() => {
    const all = this.projects();
    // Nothing loaded yet (or nothing visible to this user): show no cards rather
    // than an empty grid frame.
    if (!all.length) return [];
    const byId = new Map(all.map(p => [p.id, p]));
    const fav = this.favorites();
    // "seen" remembers the ids already placed. Without it a project that is both
    // mine and a favourite would appear twice in the grid.
    const seen = new Set<number>();
    const out: Project[] = [];
    // Helper: append the projects of one source, skipping duplicates and stopping
    // at 12. It accepts undefined entries because the recents source may contain
    // ids of projects that no longer exist.
    const add = (arr: (Project | undefined)[]) => {
      for (const p of arr) {
        if (p && !seen.has(p.id)) { seen.add(p.id); out.push(p); if (out.length >= 12) return; }
      }
    };
    // The order of these five calls IS the priority of the cards on screen.
    add(all.filter(p => p.chefProjetId === this.myId));  // 1. projects I manage
    add(this.recents().map(id => byId.get(id)));         // 2. what I opened lately
    add(all.filter(p => fav.has(p.id)));                 // 3. what I starred
    add(all.filter(p => p.status === 'ACTIVE'));         // 4. running projects
    add(all);                                            // 5. anything else, as a last resort
    // Second safety cut: add() stops at 12 inside one source, this guarantees the
    // final total too.
    return out.slice(0, 12);
  });

  // ── Actions ────────────────────────────────────────────────────
  /**
   * Opens the browser modal from a clean state: no search text, no filter,
   * first page, and the current project already pre-selected.
   *
   * <p>WHY reset everything: the filters from the previous visit would otherwise
   * still be on, and the user would think half his projects have disappeared.
   * Pre-selecting selected() means "Cancel then Select" cannot silently switch
   * him to another project.
   */
  openModal(): void {
    this.open.set(true);
    this.search.set('');
    this.onlyMine.set(false);
    this.onlyFav.set(false);
    this.page.set(0);
    this.pending.set(this.selected());
    // setTimeout(..., 0) waits for Angular to actually put the modal in the page.
    // The input does not exist yet at this instant, so focusing it right away
    // would do nothing and the user would have to click the box before typing.
    // "?." keeps it harmless if the modal was closed again in the meantime.
    setTimeout(() => document.querySelector<HTMLInputElement>('.pp-search input')?.focus(), 0);
  }

  /** Closes the modal without changing anything the host screen uses. */
  close(): void { this.open.set(false); }

  /**
   * Stores the typed text and goes back to the first page.
   * The page reset is the whole point of this method: without it a search made
   * while on page 3 would look like "no result".
   */
  onSearch(v: string): void { this.search.set(v); this.page.set(0); }

  /** Pre-selects a row (one click). Nothing leaves the component yet. */
  pick(p: Project): void { this.pending.set(p); }

  /**
   * Confirms the choice: remembers the project as recent, closes the modal, and
   * tells the parent screen through the projectSelected event.
   *
   * <p>The component never writes selected() itself. The parent owns that value,
   * so only one place in the app decides which project a screen is on.
   */
  confirm(p: Project): void {
    this.addRecent(p.id);
    this.open.set(false);
    this.projectSelected.emit(p);
  }

  // ── Favourites & recents (browser localStorage) ────────────────
  // These preferences are kept in the BROWSER, not on the server. They are pure
  // comfort (which projects to show first) and contain only ids the user is
  // already allowed to see, so nothing confidential is written to the disk.

  /** True when the project is starred. Called once per visible row. */
  isFav(id: number): boolean { return this.favorites().has(id); }

  /**
   * Adds or removes a star, then saves the new list in the browser.
   *
   * @param e the click event, needed to stop it from reaching the row.
   */
  toggleFav(p: Project, e: Event): void {
    // The star sits inside a clickable row. Without this line, starring a project
    // would also pre-select that row.
    e.stopPropagation();
    this.favorites.update(s => {
      // A NEW Set is built instead of changing the old one. Signals only notify
      // when the value itself changes; mutating the existing Set would leave the
      // stars on screen unchanged until something else refreshed the page.
      const n = new Set(s);
      n.has(p.id) ? n.delete(p.id) : n.add(p.id);
      // Sets cannot be turned into JSON directly, so we spread it into an array.
      this.persist(FAV_KEY, [...n]);
      return n;
    });
  }

  /**
   * Pushes a project to the front of the "recently opened" list.
   *
   * <p>The id is first removed from the rest of the list, so re-opening a project
   * moves it to the front instead of adding a second copy of it.
   */
  private addRecent(id: number): void {
    this.recents.update(list => {
      // Keep 20, although only RECENT_MAX (6) are shown as chips: the extra ones
      // still influence the sort order of the table.
      const next = [id, ...list.filter(x => x !== id)].slice(0, 20);
      this.persist(RECENT_KEY, next);
      return next;
    });
  }

  /**
   * Reads the starred ids from the browser.
   * The try/catch is not decoration: the stored text can be corrupted, or
   * localStorage can be blocked entirely (private browsing, strict settings).
   * Without it the component would fail to build and the whole screen would stay
   * blank, just because of an old preference.
   */
  private loadFavorites(): Set<number> {
    try { return new Set(JSON.parse(localStorage.getItem(FAV_KEY) ?? '[]')); } catch { return new Set(); }
  }

  /** Same as loadFavorites, for the recents list. Falls back to an empty list. */
  private loadRecents(): number[] {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]'); } catch { return []; }
  }

  /**
   * Saves one preference. Failures are ignored on purpose: localStorage can be
   * full or forbidden, and losing a favourite must never block the user from
   * picking his project.
   */
  private persist(key: string, value: unknown): void {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* ignore quota errors */ }
  }

  // ── Presentation ───────────────────────────────────────────────
  /**
   * Human label of a status, from the shared PROJECT_STATUS_LABELS map.
   * "?? s" returns the raw value when the backend sends a status this frontend
   * does not know yet, so a new status shows as "ARCHIVED" instead of "undefined".
   */
  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as ProjectStatus] ?? s;
  }

  /**
   * Maps a project status to the CSS class of its coloured badge.
   *
   * <p>A lookup object is used rather than a chain of if/else: adding a status
   * later is one line, and every caller keeps the exact same colours.
   * Unknown statuses fall back to the neutral "draft" badge, so a value this
   * frontend does not know still renders as a normal badge and not as unstyled
   * text.
   */
  badgeClass(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }
}
