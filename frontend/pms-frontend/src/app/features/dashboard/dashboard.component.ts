import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../core/services/auth.service';
import { ProjectService } from '../../core/services/project.service';
import { Project } from '../../core/models/project.model';
import { LanguageService } from '../../core/i18n/language.service';
import { environment } from '../../../environments/environment';

/**
 * ============================================================================
 * DASHBOARD - the home screen the user lands on right after signing in.
 * ============================================================================
 *
 * WHAT THIS FILE IS
 * One standalone Angular component that draws four different home pages in a
 * single file: one for the administrator, one for the director, one for the
 * project manager (chef de projet), and one fallback page for everybody else
 * (typically the developer). It shows counters (KPI = Key Performance
 * Indicator, a single headline number) and short project tables.
 *
 * WHERE IT SITS IN THE FLOW
 *   router ("/" route, behind the auth guard)
 *        -> DashboardComponent (this file)
 *             -> AuthService      : who is logged in, and what may he see
 *             -> ProjectService   : GET /api/projects  (the project list)
 *             -> HttpClient       : the five small admin counter calls
 *             -> LanguageService  : the active language, for the date
 *        -> the user clicks a row -> Router -> /projects/{id}
 *
 * WHY IT EXISTS
 * Without it the user would sign in and land on nothing. He would have to type
 * URLs by hand to reach the projects, the users page or the TCC resources. It
 * is also the only screen that gives an at-a-glance count of the portfolio.
 *
 * IMPORTANT - THIS FILE DECIDES NOTHING ABOUT SECURITY
 * The screen only decides what is DRAWN. Every real check happens on the
 * server: the service methods carry @PreAuthorize("hasAuthority('...')"), and
 * for every URL of the form /api/projects/{id}/** the ProjectScopeInterceptor
 * (ADR-021) checks BOTH the permission AND that this user is attached to that
 * project. So hiding a button here is comfort, not protection: if somebody
 * calls the API by hand, the back end still answers 403.
 */
@Component({
  // The tag used in the router outlet / in HTML: <app-dashboard>.
  selector: 'app-dashboard',
  // Standalone = this component declares its own dependencies below and needs
  // no NgModule. Why: the whole front end is built this way (Angular 21), so
  // there is no shared module that could forget to declare it.
  standalone: true,
  // Everything used inside the inline template must be listed here, or the
  // template does not compile. CommonModule brings the lowercase pipe,
  // RouterLink the [routerLink] links, DecimalPipe the "| number" formatting,
  // FormsModule the [ngModel] search box, TranslocoModule the "| transloco"
  // translations. Example of what breaks: remove FormsModule and the director
  // search input fails at build time with "ngModel is not a known property".
  imports: [CommonModule, RouterLink, DecimalPipe, FormsModule, TranslocoModule],
  // Loads the "dashboard" translation file (i18n/dashboard/fr.json, en.json)
  // only for this component, instead of putting every key in one global file.
  // Why: the keys below are written "dashboard.xxx" and are resolved in that
  // scope. Without this line every "dashboard.*" label would render as the raw
  // key text on screen.
  providers: [provideTranslocoScope('dashboard')],
  // Component styles. Angular scopes them to this component only, so these
  // class names cannot leak into another screen. Inside this block only the
  // /* */ comment form is legal - // would be printed as broken CSS.
  styles: [`
    /* The whole table row reacts to a click (see open()), so show the hand
       cursor. Without it the row looks like plain text and the user never
       guesses he can click anywhere on it. */
    tr.row-link { cursor: pointer; }
    /* Grey placeholder bars shown while the data is still loading. */
    .sk-line { height: 12px; border-radius: var(--r-xs); }
    /* Three widths, so the placeholder bars are not all the same length and
       look like real text instead of a machine pattern. */
    .sk-w-40 { width: 40%; } .sk-w-55 { width: 55%; } .sk-w-70 { width: 70%; }
    .dash-module-title { font-size: 14px; font-weight: 600; color: var(--text-1); }
    .dash-module-sub   { font-size: 12px; color: var(--text-2); }
    .dash-module-cta   { font-size: 11px; font-weight: 600; color: var(--c-brand); margin-top: .875rem; display: flex; align-items: center; gap: .25rem; }
    .dash-link         { color: var(--c-brand); font-size: 12px; }
  `],
  // The template is written inline, in backticks, like every screen of this
  // project. Inside it, ONLY the <!-- --> comment form is legal: // or /* */
  // would simply be printed on the page as text.
  template: `
    <!-- ═══ TOPBAR ════════════════════════════════════════════════════════════ -->
    <!-- Fixed bar at the top of the page: breadcrumb on the left, the name of
         the signed-in user and the "new project" button on the right. -->

    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-house" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'dashboard.breadcrumb' | transloco }}</span>
      </div>
      <div class="tb-right">
        <div class="d-none d-md-flex align-items-center gap-2 me-2">
          <!-- context() is a signal, so it is read as a function call. The "?."
               guards the case where the context is not loaded yet (page opened
               straight after a browser refresh): without it Angular would throw
               "cannot read fullName of null" and the whole page would stay
               blank. -->
          <span style="font-size:13px;color:var(--text-2);font-weight:500">{{ context()?.fullName }}</span>
          <!-- Shows the role of the user as a small badge. roleKey() gives a
               translation key such as "roles.DIRECTEUR"; if the user has no
               role we print nothing instead of translating an empty key, which
               would display the literal text "roles." on screen. -->
          <span class="role-badge-light">{{ roleKey() ? (roleKey() | transloco) : '' }}</span>
        </div>
        <!-- Permission-based display: the button is drawn only if the user owns
             the CREATE_PROJECT permission. Note it is a PERMISSION, never a role
             name - that is the rule of the whole application, so an admin can
             give that right to a new role without touching this file.
             This only hides the button; the server still refuses the POST. -->
        @if (auth.hasPermission('CREATE_PROJECT')) {
          <a routerLink="/projects/new" class="btn btn-primary btn-sm">
            <i class="bi bi-plus-lg"></i>{{ 'nav.newProject' | transloco }}
          </a>
        }
      </div>
    </div>

    <!-- ═══ PAGE BODY ═════════════════════════════════════════════════════════ -->
    <div class="page-body">

      <!-- Warning banner shown when the user was pushed back here by a guard
           because he tried to open a page he is not allowed to see. The guard
           redirects to "/?forbidden=1" and ngOnInit() turns that into this
           flag. Without it the user would be bounced to the dashboard with no
           explanation and would think the application is broken. -->
      @if (forbidden()) {
        <div class="alert alert-warning d-flex align-items-center gap-2 mb-4">
          <i class="bi bi-shield-exclamation flex-shrink-0"></i>
          <span>{{ 'dashboard.accessDenied' | transloco }}</span>
          <button class="btn-close ms-auto" (click)="forbidden.set(false)"></button>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- LOADING SKELETON                                                   -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- This @if / @else if / @else chain is the heart of the screen: only
           ONE of the four dashboards below is built. Careful, the role test
           here chooses a LAYOUT, it is not a security check - the data itself
           is filtered and authorised by the back end. -->
      <!-- While the HTTP calls are still running we draw grey placeholder
           blocks that have the same shape as the real content. Why not a
           spinner: the blocks keep the page height stable, so nothing jumps
           when the data arrives. -->
      @if (loading()) {
        <div class="page-header"><div class="skeleton sk-line" style="width:240px;height:22px"></div></div>
        <div class="row g-3 mb-4">
          <!-- Repeats the placeholder card four times. "track i" tells Angular
               how to tell two items apart when the list changes; here the
               number itself is unique, so it is enough. Without a track
               expression the @for block does not compile. -->
          @for (i of [1,2,3,4]; track i) {
            <div class="col-sm-6 col-xl-3">
              <div class="metric-card">
                <div class="skeleton mb-3" style="width:36px;height:36px;border-radius:9px"></div>
                <div class="skeleton sk-line sk-w-55 mb-2"></div>
                <div class="skeleton sk-line sk-w-40" style="height:22px"></div>
              </div>
            </div>
          }
        </div>
        <div class="card"><div class="p-3">
          @for (i of [1,2,3,4,5]; track i) { <div class="skeleton sk-line mb-2" style="height:28px"></div> }
        </div></div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- ADMIN DASHBOARD                                                    -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- The administrator does not manage projects, he manages the system.
           So his page counts users, roles, permissions and TCC resources, and
           offers shortcut cards to the admin modules. Each card is still
           wrapped in a permission test below, because two administrators can
           have different rights. -->
      @else if (role() === 'ADMIN') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.admin.title' | transloco }}</h1>
          </div>
          <span style="font-size:12px;color:var(--text-3);align-self:flex-start;padding-top:.375rem">{{ today() }}</span>
        </div>

        <!-- KPI row -->
        <div class="row g-3 mb-4">
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-people-fill"></i>
                </div>
                <span class="text-caption">{{ 'dashboard.admin.metrics.total' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.users' | transloco }}</div>
              <!-- "|| '—'" shows a dash instead of 0. Why: the counter starts
                   at 0 and is filled by an HTTP answer. Without the dash the
                   admin would read a confident "0 users" for a moment, or for
                   ever if the call failed. The same trick is used on the four
                   counters below. -->
              <div class="metric-value">{{ userCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-person-check-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-success)">{{ 'dashboard.admin.metrics.active' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.activeAccounts' | transloco }}</div>
              <div class="metric-value">{{ activeUserCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--purple">
                  <i class="bi bi-shield-fill-check"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.roles' | transloco }}</div>
              <div class="metric-value">{{ roleCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-key-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.permissions' | transloco }}</div>
              <div class="metric-value">{{ permissionCount() || '—' }}</div>
            </div>
          </div>
          <div class="col-6 col-xl">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--amber">
                  <i class="bi bi-currency-exchange"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.admin.metrics.tccResources' | transloco }}</div>
              <div class="metric-value">{{ resourceCount() || '—' }}</div>
            </div>
          </div>
        </div>

        <!-- Modules section -->
        <div style="font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.07em;color:var(--text-2);margin-bottom:.75rem">
          {{ 'dashboard.admin.modulesTitle' | transloco }}
        </div>
        <div class="row g-3">
          <!-- One shortcut card per admin module. Each one is gated by the
               permission that the target page really requires, so a user who
               cannot open /admin/users does not even see the card. Example of
               what goes wrong without the test: the card is visible, the user
               clicks, the route guard sends him back here with ?forbidden=1 -
               a dead end that looks like a bug. -->
          @if (auth.hasPermission('MANAGE_USERS')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/users" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--brand">
                    <i class="bi bi-people-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.users.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.users.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
          @if (auth.hasPermission('VIEW_RESOURCES')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/resources" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--amber">
                    <i class="bi bi-currency-exchange"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.tcc.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.tcc.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
          <!-- MANAGE_ROLES opens TWO cards: the roles page and the permissions
               page. They are one single administration job (a role is a bag of
               permissions), so the project gives them one single permission
               rather than two rights that would always be granted together. -->
          @if (auth.hasPermission('MANAGE_ROLES')) {
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/roles" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--purple">
                    <i class="bi bi-shield-lock-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.roles.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.roles.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
            <div class="col-md-6 col-lg-4">
              <a routerLink="/admin/permissions" class="card p-3 d-block text-decoration-none module-card">
                <div class="d-flex align-items-center gap-3 mb-3">
                  <div class="metric-icon metric-icon--teal">
                    <i class="bi bi-key-fill"></i>
                  </div>
                  <div>
                    <div class="dash-module-title">{{ 'dashboard.admin.modules.permissions.title' | transloco }}</div>
                    <div class="dash-module-sub">{{ 'dashboard.admin.modules.permissions.sub' | transloco }}</div>
                  </div>
                </div>
                <div class="dash-module-cta">
                  {{ 'dashboard.admin.access' | transloco }} <i class="bi bi-arrow-right"></i>
                </div>
              </a>
            </div>
          }
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- DIRECTOR DASHBOARD                                                 -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- The director looks at the whole portfolio: four counters, a
           searchable table of projects, and a bar chart of the statuses. The
           list he sees is the one the back end agreed to send him; this screen
           does not widen it. -->
      @else if (role() === 'DIRECTEUR') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.director.title' | transloco }}</h1>
          </div>
          <a routerLink="/projects" class="btn btn-outline-secondary btn-sm">
            <i class="bi bi-folder2-open"></i>{{ 'dashboard.director.allProjects' | transloco }}
          </a>
        </div>

        <!-- Director KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-briefcase-fill"></i>
                </div>
                <span class="text-caption">{{ 'dashboard.director.portfolio' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.totalProjects' | transloco }}</div>
              <div class="metric-value">{{ projects().length }}</div>
              <div class="metric-sub"><i class="bi bi-arrow-up-right" style="color:var(--c-success)"></i>{{ 'dashboard.director.globalView' | transloco }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-circle-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-success)">{{ 'dashboard.director.inProgress' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.active' | transloco }}</div>
              <div class="metric-value">{{ activeCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--warning">
                  <i class="bi bi-pause-circle-fill"></i>
                </div>
                <span style="font-size:11px;color:var(--c-warning)">{{ 'dashboard.director.paused' | transloco }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.onHold' | transloco }}</div>
              <div class="metric-value">{{ onHoldCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-check2-circle"></i>
                </div>
                <span style="font-size:11px;color:var(--c-teal)">{{ 'dashboard.director.completed' | transloco | lowercase }}</span>
              </div>
              <div class="metric-label">{{ 'dashboard.director.completed' | transloco }}</div>
              <div class="metric-value">{{ completedCount() }}</div>
            </div>
          </div>
        </div>

        <!-- Portfolio table + breakdown -->
        <div class="row g-3">
          <div class="col-lg-8">
            <div class="card">
              <div class="card-header justify-content-between">
                <span>{{ 'dashboard.director.portfolioTitle' | transloco }}</span>
                <div style="display:flex;align-items:center;gap:.5rem">
                  <div class="input-wrap" style="width:200px">
                    <i class="bi bi-search input-icon"></i>
                    <!-- The search box is bound to the dirSearch signal in the
                         two directions by hand: [ngModel] reads the signal,
                         (ngModelChange) writes it back. Why not [(ngModel)]:
                         a signal is read with dirSearch() and written with
                         .set(), which the banana-in-a-box syntax cannot do.
                         The filtering itself is client-side, in dirFiltered(),
                         so typing costs no HTTP call. -->
                    <input type="search" class="form-control form-control-sm"
                           [placeholder]="'common.search' | transloco"
                           [ngModel]="dirSearch()" (ngModelChange)="dirSearch.set($event)">
                  </div>
                  <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
                    {{ 'dashboard.director.viewAll' | transloco }} <i class="bi bi-arrow-right"></i>
                  </a>
                </div>
              </div>
              <div class="table-responsive">
                <table class="table">
                  <thead>
                    <tr>
                      <th>{{ 'dashboard.table.project' | transloco }}</th>
                      <th>{{ 'dashboard.table.projectManager' | transloco }}</th>
                      <th>{{ 'common.status' | transloco }}</th>
                      <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- track p.id: Angular follows each row by the database
                         id of the project. Why it matters: when the director
                         types in the search box the list is rebuilt; with a
                         stable id Angular moves the existing rows instead of
                         destroying and recreating every one of them. -->
                    @for (p of dirFiltered(); track p.id) {
                      <!-- The whole row is clickable (open() navigates). -->
                      <tr class="row-link" (click)="open(p.id)">
                        <td>
                          <!-- stopPropagation() keeps the click on the link from
                               also firing the row click. Without it the router
                               would be asked to navigate twice to the same
                               project, and the browser history would get a
                               duplicate entry. -->
                          <a [routerLink]="['/projects', p.id]" (click)="$event.stopPropagation()"
                             class="text-decoration-none" style="font-size:13px;font-weight:600;color:var(--text-1)">{{ p.name }}</a>
                          <div class="text-caption">{{ p.code }}</div>
                        </td>
                        <!-- "??" is the null-coalescing operator: use the dash
                             only when the value is null or undefined. A project
                             can exist before a manager is named, and an empty
                             cell would look like a display bug. -->
                        <td style="color:var(--text-2)">{{ p.chefProjetName ?? '—' }}</td>
                        <!-- statusBadge() picks the colour class, and the key
                             "status.ACTIVE" is translated. The status is never
                             printed raw, so the director reads "Actif" and not
                             the database word ACTIVE. -->
                        <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                        <!-- number:'1.0-0' = at least 1 digit before the comma,
                             and 0 decimals. Why: budgets are large amounts in
                             TND; without it 1234567.89 would be printed in full
                             and the column would not line up. -->
                        <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                      </tr>
                    }
                    <!-- @empty is used when the loop produced no row at all -
                         either the portfolio is empty, or the search matched
                         nothing. Without it the table would show only its
                         header and look broken. -->
                    @empty {
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                          <div class="es-title">{{ 'dashboard.table.noProjects' | transloco }}</div>
                          <div class="es-desc">{{ 'dashboard.table.noProjectsDesc' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div class="col-lg-4">
            <div class="card h-100">
              <div class="card-header">{{ 'dashboard.director.breakdownTitle' | transloco }}</div>
              <div class="p-3">
                <!-- One line per status, tracked by the status name, which is
                     unique in the list built by statusBreakdown(). -->
                @for (item of statusBreakdown(); track item.key) {
                  <div class="d-flex align-items-center gap-2 mb-3">
                    <span [class]="item.badgeClass" style="min-width:72px;justify-content:center">{{ item.labelKey | transloco }}</span>
                    <div class="flex-grow-1">
                      <div class="pms-progress">
                        <!-- [style.width.%] writes the share of this status as
                             a CSS width in percent, which draws the bar. The
                             percentage is computed in statusBreakdown(), not
                             here: a template must stay free of arithmetic
                             because it is re-evaluated on every change. -->
                        <div class="pms-progress-bar" [style.width.%]="item.pct" [style.background]="item.color"></div>
                      </div>
                    </div>
                    <span style="font-size:12px;font-weight:700;color:var(--text-1);min-width:20px;text-align:right">{{ item.count }}</span>
                  </div>
                }
              </div>
            </div>
          </div>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- CHEF DE PROJET DASHBOARD                                           -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- The project manager only cares about the projects he leads. The
           counters below all read myProjects(), which keeps the projects whose
           chefProjetId is his own user id. -->
      @else if (role() === 'CHEF_PROJET') {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.chef.title' | transloco }}</h1>
          </div>
          @if (auth.hasPermission('CREATE_PROJECT')) {
            <a routerLink="/projects/new" class="btn btn-primary btn-sm">
              <i class="bi bi-plus-lg"></i>{{ 'nav.newProject' | transloco }}
            </a>
          }
        </div>

        <!-- Chef KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-folder-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.myProjects' | transloco }}</div>
              <div class="metric-value">{{ myProjects().length }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.inProgress' | transloco }}</div>
              <div class="metric-value">{{ myActiveCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-wallet2"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.totalBudget' | transloco }}</div>
              <div class="metric-value" style="font-size:1.4rem">{{ myBudgetFormatted() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--warning">
                  <i class="bi bi-pencil-square"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.chef.drafts' | transloco }}</div>
              <div class="metric-value">{{ myDraftCount() }}</div>
            </div>
          </div>
        </div>

        <!-- My projects table -->
        <div class="card">
          <div class="card-header justify-content-between">
            <span>{{ 'dashboard.chef.assignedProjects' | transloco }}</span>
            <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
              {{ 'dashboard.chef.manage' | transloco }} <i class="bi bi-arrow-right"></i>
            </a>
          </div>
          <div class="table-responsive">
            <table class="table">
              <thead>
                <tr>
                  <th>{{ 'dashboard.table.project' | transloco }}</th>
                  <th>{{ 'dashboard.table.client' | transloco }}</th>
                  <th class="d-none d-md-table-cell">{{ 'dashboard.table.period' | transloco }}</th>
                  <th>{{ 'common.status' | transloco }}</th>
                  <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <!-- Only the first 10 projects. The dashboard is a summary,
                     not the project list: the "manage" link above leads to the
                     full, paginated page. Without the cut, a manager with 200
                     projects would get a page several screens long. -->
                @for (p of myProjects().slice(0, 10); track p.id) {
                  <tr>
                    <td>
                      <a [routerLink]="['/projects', p.id]" class="text-decoration-none fw-semibold" style="color:var(--text-1)">{{ p.name }}</a>
                      <div class="text-caption">{{ p.code }}</div>
                    </td>
                    <td style="color:var(--text-2)">{{ p.client ?? '—' }}</td>
                    <td class="d-none d-md-table-cell" style="font-size:12px;color:var(--text-2);white-space:nowrap">
                      {{ p.startDate ?? '—' }} → {{ p.endDate ?? '—' }}
                    </td>
                    <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                    <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    <td class="text-end">
                      <!-- The button shows only an arrow icon, so a screen
                           reader would announce "link" and nothing else. The
                           aria-label gives it a spoken name, built with the
                           project code passed as a parameter to the
                           translation ("Open project PRJ-014"). -->
                      <a [routerLink]="['/projects', p.id]" class="btn btn-ghost btn-icon btn-sm"
                         [title]="'common.viewProject' | transloco"
                         [attr.aria-label]="'common.viewProjectAria' | transloco: { code: p.code }">
                        <i class="bi bi-arrow-right"></i>
                      </a>
                    </td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="6">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                      <div class="es-title">{{ 'dashboard.chef.noAssigned' | transloco }}</div>
                      <div class="es-desc">{{ 'dashboard.chef.noAssignedDesc' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- DEVELOPER DASHBOARD                                                -->
      <!-- ═══════════════════════════════════════════════════════════════════ -->
      <!-- Default branch: everything that is not ADMIN, DIRECTEUR or
           CHEF_PROJET lands here, typically the developer. It is deliberately
           the fallback, so a user whose role is unknown to this screen still
           gets a usable page instead of an empty one. The money column is the
           only sensitive part, and it is hidden without VIEW_KPI. -->
      @else {
        <div class="page-header d-flex align-items-start justify-content-between flex-wrap gap-2">
          <div>
            <h1 class="page-title">{{ 'dashboard.developer.title' | transloco }}</h1>
          </div>
          <span style="font-size:12px;color:var(--text-3);padding-top:.375rem">{{ today() }}</span>
        </div>

        <!-- Developer KPIs -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--brand">
                  <i class="bi bi-folder2-open"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.projects' | transloco }}</div>
              <div class="metric-value">{{ projects().length }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--success">
                  <i class="bi bi-play-fill"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.active' | transloco }}</div>
              <div class="metric-value">{{ activeCount() }}</div>
            </div>
          </div>
          <div class="col-sm-6 col-xl-4">
            <div class="metric-card">
              <div class="d-flex align-items-start justify-content-between mb-3">
                <div class="metric-icon metric-icon--teal">
                  <i class="bi bi-check2-circle"></i>
                </div>
              </div>
              <div class="metric-label">{{ 'dashboard.developer.completed' | transloco }}</div>
              <div class="metric-value">{{ completedCount() }}</div>
            </div>
          </div>
        </div>

        <!-- Recent projects -->
        <div class="card">
          <div class="card-header justify-content-between">
            <span>{{ 'dashboard.developer.recentProjects' | transloco }}</span>
            <a routerLink="/projects" class="btn btn-ghost btn-sm dash-link">
              {{ 'dashboard.director.viewAll' | transloco }} <i class="bi bi-arrow-right"></i>
            </a>
          </div>
          <div class="table-responsive">
            <table class="table">
              <thead>
                <tr>
                  <th>{{ 'dashboard.table.project' | transloco }}</th>
                  <th>{{ 'dashboard.table.projectManager' | transloco }}</th>
                  <th>{{ 'common.status' | transloco }}</th>
                  <!-- Budget: BR-050 - the money column is hidden when the user
                       does not own VIEW_KPI (a developer, for example). The
                       same test is repeated on the cells and on the colspan of
                       the empty row, otherwise the header and the body would
                       not have the same number of columns and the table would
                       be shifted by one. The real protection is on the server:
                       this only avoids showing a figure the user must not read
                       on a screen he is allowed to open. -->
                  @if (auth.hasPermission('VIEW_KPI')) { <th class="text-end">{{ 'dashboard.table.budgetTnd' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
                <!-- Only the 8 most recent projects, in the order sent by the
                     back end. Same reason as above: this block is a preview,
                     the full list lives on /projects. -->
                @for (p of projects().slice(0, 8); track p.id) {
                  <tr class="row-link" (click)="open(p.id)">
                    <td>
                      <a [routerLink]="['/projects', p.id]" (click)="$event.stopPropagation()"
                         class="text-decoration-none" style="font-size:13px;font-weight:600;color:var(--text-1)">{{ p.name }}</a>
                      <div class="text-caption">{{ p.code }}</div>
                    </td>
                    <td style="color:var(--text-2)">{{ p.chefProjetName ?? '—' }}</td>
                    <td><span [class]="statusBadge(p.status)">{{ ('status.' + p.status) | transloco }}</span></td>
                    @if (auth.hasPermission('VIEW_KPI')) {
                      <td class="text-end" style="font-weight:600">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }}</td>
                    }
                  </tr>
                }
                @empty {
                  <!-- The empty message must span every column, and the number
                       of columns depends on VIEW_KPI (4 with the budget, 3
                       without). [attr.colspan] writes the real HTML attribute.
                       Without this test the message would leave an empty cell
                       on the right of the table. -->
                  <tr><td [attr.colspan]="auth.hasPermission('VIEW_KPI') ? 4 : 3">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-folder2-open"></i></div>
                      <div class="es-title">{{ 'dashboard.table.noProjects' | transloco }}</div>
                      <div class="es-desc">{{ 'dashboard.developer.notAssigned' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

    </div>
  `
})
/**
 * The component class. It holds the state as SIGNALS (a signal is a value that
 * remembers who reads it, so the template redraws by itself when the value
 * changes - no manual refresh, no ChangeDetectorRef) and it loads the data in
 * ngOnInit().
 *
 * It implements OnInit rather than loading in the constructor, because at
 * construction time the route parameters are not guaranteed to be readable and
 * an HTTP call started there is harder to reason about in tests.
 */
export class DashboardComponent implements OnInit {
  // Public on purpose: the template calls auth.hasPermission(...) directly.
  // A private field would not be reachable from the template.
  readonly auth           = inject(AuthService);
  // Gives the project list. The screen never builds the URL itself, so the
  // day the endpoint changes only the service is touched.
  private readonly projSvc = inject(ProjectService);
  // Used only for the five small admin counters, which have no service yet.
  private readonly http    = inject(HttpClient);
  // Reads the ?forbidden=1 query parameter left by a route guard.
  private readonly route   = inject(ActivatedRoute);
  // Used by open() to navigate when a table row is clicked.
  private readonly router  = inject(Router);

  // True until the first answer arrives; drives the grey skeleton. It starts
  // at true so the page never flashes an empty "0 projects" state first.
  loading = signal(true);

  /**
   * Opens a project page. Exists because a click on a <tr> cannot be a router
   * link: a table row is not an <a>. The real links inside the row call
   * stopPropagation() so they do not trigger this method as well.
   */
  open(id: number): void { this.router.navigate(['/projects', id]); }

  // The language chosen by the user (fr / en). Read as a signal below, so the
  // date re-formats itself when the user switches language.
  private readonly lang = inject(LanguageService);
  // Re-exposes the signed-in user (name, roles, permissions) so the template
  // can read context()?.fullName without going through auth every time.
  readonly context = this.auth.context;

  /**
   * Today's date, written in the active language. It is a computed(), which
   * means it recalculates only when lang.current() changes.
   * Why not a plain string built once: the user can switch language without
   * reloading the page, and a fixed string would stay in French in the middle
   * of an English screen.
   */
  readonly today = computed(() => {
    const locale = this.lang.current() === 'en' ? 'en-GB' : 'fr-FR';
    return new Date().toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  });

  /**
   * The first role of the signed-in user, or an empty string.
   * It is used ONLY to choose which of the four layouts to draw. It is never
   * used to decide whether an action is allowed: that is always a permission
   * (auth.hasPermission) on the screen and @PreAuthorize on the server.
   * The two "?." and the "?? ''" cover the moment just after a page refresh,
   * when the context is not restored yet: without them role() would throw and
   * the dashboard would never render.
   */
  readonly role    = computed(() => this.auth.context()?.roles?.[0] ?? '');
  /**
   * The i18n key of the current role, translated in the template (for example
   * "roles.DIRECTEUR"). Empty when there is no role, so the template can skip
   * the translation instead of printing the broken key "roles.".
   */
  readonly roleKey = computed(() => this.role() ? `roles.${this.role()}` : '');

  // ---- Raw state, filled by the HTTP answers in ngOnInit() ---------------
  // The project list. Typed Project[] so a typo such as p.nom instead of
  // p.name is caught at build time, not on screen.
  projects        = signal<Project[]>([]);
  userCount       = signal<number>(0);
  activeUserCount = signal<number>(0);
  roleCount       = signal<number>(0);
  permissionCount = signal<number>(0);
  resourceCount   = signal<number>(0);
  // True when the user arrived here after being refused another page; shows
  // the warning banner at the top of the body.
  forbidden       = signal(false);

  // What the director typed in the search box. Kept in a signal so that
  // dirFiltered() below recomputes on its own at every keystroke.
  dirSearch = signal('');

  // ---- Derived values -----------------------------------------------------
  // These are computed(), so they are recalculated only when projects()
  // changes, and the result is cached in between. Writing the same filters
  // directly in the template would re-run them on every change detection pass,
  // several times per second while the user types.
  readonly activeCount    = computed(() => this.projects().filter(p => p.status === 'ACTIVE').length);
  readonly onHoldCount    = computed(() => this.projects().filter(p => p.status === 'ON_HOLD').length);
  readonly completedCount = computed(() => this.projects().filter(p => p.status === 'COMPLETED').length);
  readonly draftCount     = computed(() => this.projects().filter(p => p.status === 'DRAFT').length);

  /**
   * The rows of the director table: the projects that match the search box,
   * capped at 10.
   * The search is done here in the browser, on the list already loaded, rather
   * than by calling the server on each keystroke. Why: the dashboard shows a
   * short portfolio, so one HTTP call per letter would cost far more than it
   * gives. The real, paginated search lives on the /projects page.
   */
  readonly dirFiltered = computed(() => {
    // Lower case + trim, so "  ACME " also finds "Acme". Without it the user
    // types a capital letter or leaves a trailing space and sees no result at
    // all, which looks like missing data.
    const s = this.dirSearch().toLowerCase().trim();
    const list = this.projects();
    // Empty search = the first 10 projects, no filtering.
    if (!s) return list.slice(0, 10);
    // Four searchable fields. client and chefProjetName are optional in the
    // Project model, so "?? ''" replaces a missing value by an empty string:
    // without it, one project with no client would throw on .toLowerCase()
    // and the whole table would disappear.
    return list.filter(p =>
      p.code.toLowerCase().includes(s) ||
      p.name.toLowerCase().includes(s) ||
      (p.client ?? '').toLowerCase().includes(s) ||
      (p.chefProjetName ?? '').toLowerCase().includes(s)
    ).slice(0, 10);
  });

  /**
   * The four lines of the "breakdown" card on the director page: for each
   * status, its label key, its count, its share in percent, its bar colour and
   * its badge class.
   * Everything is prepared here, in one object per line, so the template just
   * loops and prints. The alternative - computing the percentages inside the
   * template - would repeat the same division on every redraw and would make
   * the HTML unreadable.
   */
  readonly statusBreakdown = computed(() => {
    // "|| 1" protects the division below. With an empty portfolio the total
    // would be 0 and every percentage would be NaN, which CSS refuses, so the
    // four bars would keep whatever width they had before.
    const total = this.projects().length || 1;
    return [
      { key: 'ACTIVE',    labelKey: 'status.ACTIVE',    count: this.activeCount(),    pct: this.activeCount()    / total * 100, color: 'var(--c-success)', badgeClass: 'badge-active'    },
      { key: 'COMPLETED', labelKey: 'status.COMPLETED', count: this.completedCount(), pct: this.completedCount() / total * 100, color: 'var(--c-brand)',   badgeClass: 'badge-completed' },
      { key: 'ON_HOLD',   labelKey: 'status.ON_HOLD',   count: this.onHoldCount(),    pct: this.onHoldCount()    / total * 100, color: 'var(--c-warning)', badgeClass: 'badge-on-hold'   },
      { key: 'DRAFT',     labelKey: 'status.DRAFT',     count: this.draftCount(),     pct: this.draftCount()     / total * 100, color: 'var(--text-3)',    badgeClass: 'badge-draft'     },
    ];
  });

  /**
   * The projects led by the signed-in user, whatever their status.
   *
   * The comparison uses the numeric user id, not the name: two people can
   * share a name, ids cannot collide. If currentUserId is null (context not
   * loaded yet) the comparison is simply false for every project, so the table
   * shows its empty state instead of crashing.
   */
  readonly myProjects    = computed(() => {
    const myId = this.auth.currentUserId;
    return this.projects().filter(p => p.chefProjetId === myId);
  });
  readonly myActiveCount = computed(() => this.myProjects().filter(p => p.status === 'ACTIVE').length);
  readonly myDraftCount  = computed(() => this.myProjects().filter(p => p.status === 'DRAFT').length);
  /**
   * The total budget of my projects, shortened for the KPI card:
   * 2 400 000 -> "2.4M", 850 000 -> "850K", 640 -> "640".
   * Why shorten: the card is small and the raw figure would overflow or be
   * shrunk to an unreadable size. The exact amounts stay visible, unshortened,
   * in the budget column of the table below.
   * "?? 0" is needed because effectiveBudget is optional: a project still in
   * draft may have no budget, and adding undefined would turn the whole sum
   * into NaN and print "NaN" on the card.
   */
  readonly myBudgetFormatted = computed(() => {
    const t = this.myProjects().reduce((s, p) => s + (p.effectiveBudget ?? 0), 0);
    if (t >= 1_000_000) return (t / 1_000_000).toFixed(1) + 'M';
    if (t >= 1_000)     return (t / 1_000).toFixed(0) + 'K';
    return t.toString();
  });

  /**
   * Runs once, right after Angular has created the component. It reads the
   * query parameter left by a guard, then loads the data that matches the
   * layout about to be drawn.
   */
  ngOnInit(): void {
    // A route guard that refuses a page redirects to "/?forbidden=1".
    // snapshot is enough here (no subscription): the dashboard is created
    // fresh on each such redirect, so there is no later change to listen to.
    if (this.route.snapshot.queryParamMap.get('forbidden') === '1') this.forbidden.set(true);

    // Two different loads, because the two pages need different data: the
    // admin page never shows projects, so asking for the project list would be
    // a useless call - and, for an admin with no project permission, a call
    // the server would answer with 403.
    if (this.role() === 'ADMIN') {
      this.loadAdminData();
    } else {
      // listAll() returns an Observable (a stream of one answer here).
      // subscribe() is what actually fires the HTTP request; without it
      // nothing would ever be sent.
      this.projSvc.listAll().subscribe({
        next: list => { this.projects.set(list); this.loading.set(false); },
        // On error we still stop the skeleton. Otherwise the user would stare
        // at grey bars for ever and think the page is frozen. The interceptor
        // is the one that shows the error message.
        error: () => this.loading.set(false)
      });
    }
  }

  /**
   * Loads the five counters of the administrator page.
   *
   * The five calls are fired in parallel and each one is independent: if the
   * resources endpoint fails, the users counter is still displayed. That is
   * why every error callback is empty except the first one - only the first
   * call is responsible for turning the skeleton off, so the page appears as
   * soon as the main figure is known instead of waiting for the slowest call.
   */
  private loadAdminData(): void {
    // "page=0&size=1" asks for ONE row only. We do not want the users, we want
    // totalElements, the count that Spring Data puts in every paged answer.
    // Without the size=1 trick the browser would download the whole user table
    // just to display a number.
    // environment.apiUrl is used instead of a hard-coded "http://localhost" so
    // the same build works in development and once deployed.
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1`)
      .subscribe({ next: p => { this.userCount.set(p.totalElements); this.loading.set(false); }, error: () => this.loading.set(false) });
    // Same trick, plus enabled=true: counts only the accounts that can still
    // sign in. A user who left the company is disabled, not deleted, so
    // without this filter the two counters would always show the same number.
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/users?page=0&size=1&enabled=true`)
      .subscribe({ next: p => this.activeUserCount.set(p.totalElements), error: () => {} });
    // Roles and permissions are short lists (a few dozen rows), and these two
    // endpoints are not paged: we receive the whole array and count it here.
    // They are typed any[] because only the length is used - no field of the
    // objects is read, so declaring a full interface would buy nothing.
    this.http.get<any[]>(`${environment.apiUrl}/admin/roles`)
      .subscribe({ next: r => this.roleCount.set(r.length), error: () => {} });
    this.http.get<any[]>(`${environment.apiUrl}/admin/permissions`)
      .subscribe({ next: p => this.permissionCount.set(p.length), error: () => {} });
    // TCC resources (the cost catalogue used by the internal quote).
    this.http.get<{ totalElements: number }>(`${environment.apiUrl}/resources?page=0&size=1`)
      .subscribe({ next: r => this.resourceCount.set(r.totalElements), error: () => {} });
  }

  /**
   * Turns a project status into the CSS class of its coloured badge.
   * A lookup table is used instead of a chain of if/else so that adding a
   * status is one new line. The fallback 'badge-draft' at the end matters: if
   * the back end one day sends a status this screen does not know yet, the row
   * still shows a neutral grey badge instead of an unstyled, invisible label.
   */
  statusBadge(s: string): string {
    // Record<string, string> = a plain object whose keys and values are both
    // strings. It lets TypeScript check the map at build time.
    const map: Record<string, string> = {
      ACTIVE:    'badge-active',
      COMPLETED: 'badge-completed',
      DRAFT:     'badge-draft',
      ON_HOLD:   'badge-on-hold',
      CANCELLED: 'badge-cancelled',
    };
    return map[s] ?? 'badge-draft';
  }
}
