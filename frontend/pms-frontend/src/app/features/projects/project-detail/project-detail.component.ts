// =============================================================================
// FILE: project-detail.component.ts   ("the file of ONE project")
// =============================================================================
// WHAT THIS SCREEN IS
//   The read screen of a single project, at the URL /projects/{id}. Everything the
//   company knows about that project is here, split into six tabs: the identity
//   sheet and the KPI, the team, the workload, the billing, the missions and the
//   governance (risks, deliverables, change requests).
//   It is mostly a READ screen. The few things it can change are listed below:
//   change the status, archive / unarchive, assign a project manager, add or
//   remove a team member, and take the monthly KPI snapshot.
//
// WHO CAN REACH IT
//   Any user holding VIEW_PROJECT (the route guard checks that). What he SEES
//   inside depends on his other permissions: VIEW_KPI opens the money figures,
//   VIEW_TEAM / VIEW_WORKLOAD / VIEW_BILLING / VIEW_MISSION / VIEW_GOVERNANCE each
//   open one tab, EDIT_PROJECT shows the edit, archive and status controls,
//   ASSIGN_CHEF_PROJET and ASSIGN_DEVELOPER show the two assignment buttons, and
//   MANAGE_DI shows the link to the internal quote (DI).
//   IMPORTANT for the jury: every permission test written in this file only HIDES
//   a control. It protects nothing. The real refusal happens on the server, with
//   @PreAuthorize("hasAuthority('X')") on the SERVICE methods. On top of that,
//   every URL used here has the shape /api/projects/{id}/..., so
//   ProjectScopeInterceptor (ADR-021) also checks that this user is in scope for
//   THAT project - holding VIEW_TEAM says "may read teams", not "may read the team
//   of project 42".
//
// WHERE IT SITS IN THE FLOW (which service, which endpoint)
//   ProjectService   -> GET  /api/projects/{id}              the sheet
//                       GET  /api/projects/{id}/kpi          the live KPI
//                       POST /api/projects/{id}/kpi/snapshots the monthly review
//                       PATCH .../archive, .../unarchive, .../status,
//                             .../assign-chef
//                       GET  /api/users/assignable           the candidate people
//   TeamService      -> GET / POST / DELETE  /api/projects/{id}/team
//   WorkloadService  -> GET  /api/projects/{id}/plan-charges
//                       GET  /api/projects/{id}/charges-reelles
//   BillingService   -> GET  /api/projects/{id}/jalons and /avenants
//   MissionService   -> GET  /api/projects/{id}/missions
//   GovernanceService-> GET  /api/projects/{id}/risques, /livrables, /changements
//   It also uses AuthService (to hide controls), ConfirmService (the shared "are
//   you sure?" modal), ToastService (the small message in the corner),
//   LanguageService (current language) and ProjectsListStateService (the memory of
//   the list, used by the back link).
//
// WHAT WOULD BE MISSING WITHOUT IT
//   The projects list only shows a row per project. Without this screen there is
//   no place at all to read one project: no KPI, no team, no billing, no risks,
//   and no way to assign a manager or to take the monthly snapshot. The list would
//   be a table nobody can open.
//
// ONE DESIGN CHOICE TO DEFEND: THE TABS LOAD LAZILY
//   Opening the screen only calls the project and its KPI. The data of a tab is
//   fetched the first time that tab is clicked, and only once (see setTab and
//   loadedTabs). Without it, opening any project would fire around ten HTTP calls
//   at once, most of them for tabs the user never opens.
// =============================================================================

import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink, ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { ProjectService } from '../../../core/services/project.service';
import { TeamService } from '../../../core/services/team.service';
import { WorkloadService } from '../../../core/services/workload.service';
import { BillingService } from '../../../core/services/billing.service';
import { MissionService } from '../../../core/services/mission.service';
import { GovernanceService } from '../../../core/services/governance.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsListStateService } from '../projects-list-state.service';
import { LanguageService } from '../../../core/i18n/language.service';
import { Project, ProjectStatus } from '../../../core/models/project.model';
import { KpiResponse } from '../../../core/models/kpi.model';
import { TeamAssignment } from '../../../core/models/team.model';
import { PlanCharge, ChargeReelle } from '../../../core/models/workload.model';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { JalonFacturation, Avenant } from '../../../core/models/billing.model';
import { Mission } from '../../../core/models/mission.model';
import { Risk, Livrable, DemandeChangement } from '../../../core/models/governance.model';

/**
 * The six tabs, written as a union of exact strings ("union type": the value may
 * only be one of these six words).
 * Why not a plain string: the tab name is used as a key in TRANSITIONS-like maps
 * (TAB_PERM below), in the URL (?tab=), and in a switch. With a plain string a typo
 * such as 'equipes' would compile, the tab would stay empty, and nothing would say
 * why. Here the compiler refuses the typo.
 */
type Tab = 'info' | 'equipe' | 'charges' | 'facturation' | 'missions' | 'gouvernance';

/**
 * standalone: true  - the component declares its own imports, no NgModule.
 * providers: provideTranslocoScope('project') - loads the 'project' translation
 *   file (i18n/project/fr.json and en.json) only when this screen is opened, and
 *   lets its keys be written short. Without the scope, the whole dictionary of the
 *   application would have to be loaded up front for every user.
 * imports: only what the template really uses. CommonModule brings the date and
 *   number pipes, FormsModule brings ngModel, RouterLink brings the links,
 *   PaginationComponent is the shared pager, TranslocoModule brings the | transloco
 *   pipe. An import missing here is a template that silently does nothing.
 */
@Component({
  selector: 'app-project-detail',
  standalone: true,
  providers: [provideTranslocoScope('project')],
  imports: [CommonModule, FormsModule, RouterLink, PaginationComponent, TranslocoModule],
  styles: [`
    /* Every colour below is a CSS variable (var(--...)) defined once in styles.scss.
       Why never a raw colour such as #c00 here: the application has a light theme and a
       dark theme, and styles.scss gives each variable a different value in each theme. A
       hard-coded red would stay the same red on a dark background and become unreadable. */
    .act-danger { color: var(--c-danger); }
    /* Interactive status control (Jira/Linear style) */
    .status-ctl { position: relative; display: inline-flex; }
    .status-btn { display: inline-flex; align-items: center; gap: .35rem; border: 1px solid var(--border);
      background: var(--surface); border-radius: var(--r); padding: .15rem .4rem; cursor: pointer;
      transition: border-color var(--t), background var(--t); }
    .status-btn:hover { border-color: var(--border-2); background: var(--surface-2); }
    /* :focus-visible, not :focus. It draws the ring only when the browser judges that the
       user is navigating with the keyboard. With plain :focus the ring would also appear
       after every mouse click, which designers remove - and removing it leaves a keyboard
       user with no way to see where he is on the page. */
    .status-btn:focus-visible { outline: 2px solid var(--c-brand); outline-offset: 2px; }
    .status-btn .bi-chevron-down { font-size: 10px; color: var(--text-3); }
    /* An invisible sheet covering the whole window, placed UNDER the menu (z-index 300 vs
       301) and over everything else. Clicking it closes the menu. Without it the menu would
       stay open while the user clicks somewhere else, and two menus could be open at once.
       position: fixed + inset: 0 means "stuck to the four sides of the window". */
    .status-backdrop { position: fixed; inset: 0; z-index: 300; }
    /* position: absolute places the menu relative to .status-ctl, which is position:
       relative above. top: calc(100% + 4px) means "just under the button, 4px lower".
       Without the relative parent the menu would be placed against the page itself and
       would stay in the top-left corner when the page is scrolled. */
    .status-menu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 301; min-width: 190px;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-md);
      box-shadow: var(--sh-lg); padding: .3rem; }
    .status-menu-label { font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: .06em;
      color: var(--text-3); padding: .35rem .5rem .3rem; }
    .status-menu-item { display: flex; align-items: center; width: 100%; border: 0; background: transparent;
      padding: .4rem .5rem; border-radius: var(--r-sm); cursor: pointer; transition: background var(--t); }
    .status-menu-item:hover { background: var(--surface-2); }
    .status-menu-item:focus-visible { outline: 2px solid var(--c-brand); outline-offset: -2px; }

    /* KPI / financial tiles */
    .kpi-tile { border-radius: var(--r-md); padding: .875rem; margin-bottom: .5rem; text-align: center; }
    /* tabular-nums asks the font for digits that all have the SAME width. The KPI tiles sit
       in a grid, so without it 1 250 000 and 999 999 would not line up and the numbers would
       seem to shift when a figure is refreshed. */
    .kpi-tile .fs-5 { font-variant-numeric: tabular-nums; }
    .kpi-tile--brand   { background: var(--c-brand-dim); }
    .kpi-tile--warning { background: var(--c-warning-dim); }
    .kpi-tile--teal     { background: var(--c-teal-dim); }
    .kpi-tile--success { background: var(--c-success-dim); }
    .kpi-tile--neutral { background: var(--surface-2, var(--bg)); }

    /* Candidate picker list (chef / team modals) */
    /* This list is the project UX rule in action: never a giant dropdown for a large set of
       rows. The two modals show a search box plus this scrollable list of people instead.
       max-height + overflow-y: auto keep the box a fixed size and scroll inside it; without
       them a company with 200 employees would push the Cancel and Assign buttons of the
       modal off the bottom of the screen. */
    .picker-list { max-height: 230px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--r-sm); }
    .picker-item { display: flex; align-items: center; gap: .5rem; width: 100%; border: 0;
      border-bottom: 1px solid var(--border); padding: .5rem .75rem; text-align: left; cursor: pointer;
      color: var(--text-1); background: transparent; transition: background var(--t); }
    .picker-item:last-child { border-bottom: 0; }
    .picker-item:hover { background: var(--surface-2); }
    .picker-item.is-selected { background: var(--c-brand-dim); }
    .picker-item .pi-name { flex: 1; font-size: 13px; }
    .picker-empty { padding: .75rem; text-align: center; color: var(--text-3); font-size: 12px; }
    .picker-count { font-size: 11px; color: var(--text-3); margin-top: .25rem; }
  `],
  template: `
    <!-- Inside this template the ONLY way to write a comment is this one. A // or a /* */
         here would not be a comment at all: Angular would print it on the page as text. -->

    <!-- The bar at the top: the way back to the list, and the actions on the project. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <!-- The back link carries [queryParams]="listState.query()". That service remembers
             the search, the filters and the page number the user had on the projects list.
             Without it, a user who searched "BAD", filtered, went to page 3 and opened a
             project would come back to page 1 of the full list and would have to redo
             everything. -->
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <!-- | transloco is the translation pipe. It takes the KEY 'projects.title' and
               gives back the text of the current language, read from the JSON dictionary.
               It also redraws by itself when the user switches language. Never write a
               comment or anything else inside such a key. -->
          <i class="bi bi-arrow-left"></i> {{ 'projects.title' | transloco }}
        </a>
        <span class="bc-sep">›</span>
        <!-- project() reads the signal. A signal is a box holding a value that Angular
             watches: when the code puts a new project inside, every line reading it is
             redrawn. The ? in project()?.code means "only if the project is not null" - it
             is null for the first instants, while the HTTP answer is still travelling, and
             without the ? the page would crash on those first instants.
             ?? '—' is the text shown while that is the case. -->
        <span class="bc-curr">{{ project()?.code ?? '—' }}</span>
        @if (project()?.archived) {
          <span class="badge-draft" style="margin-left:.5rem"><i class="bi bi-archive me-1"></i>{{ 'project.archived' | transloco }}</span>
        }
      </div>
      <div class="tb-right">
        <!-- READ THIS ONCE FOR THE WHOLE FILE.
             auth.hasPermission('X') only decides what is DRAWN. It hides a button, it does
             not protect anything: anybody can show the button again with the developer
             tools of the browser. The real refusal is on the server, on the SERVICE method,
             with @PreAuthorize("hasAuthority('X')"), plus the project scope check of
             ADR-021. So hiding here is politeness towards the user, not security.
             Note also that the code tests a PERMISSION code, never a role NAME: that is why
             an administrator can create a new role without touching this file. -->
        @if (auth.hasPermission('MANAGE_DI')) {
          <a [routerLink]="['/projects', project()?.id, 'devis-interne']" class="btn btn-outline-secondary btn-sm">
            <i class="bi bi-file-earmark-lock2"></i>DI
          </a>
        }
        @if (auth.hasPermission('EDIT_PROJECT')) {
          <!-- An archived project is read-only for everybody. So the two sides of this @if
               are exclusive: either the normal actions (edit, and archive once the project
               is COMPLETED), or the single button that brings it back. Showing Edit on an
               archived project would send the user to a form the server refuses to save. -->
          @if (!project()?.archived) {
            <a [routerLink]="['/projects', project()?.id, 'edit']" class="btn btn-outline-secondary btn-sm">
              <i class="bi bi-pencil"></i>{{ 'common.edit' | transloco }}
            </a>
            @if (project()?.status === 'COMPLETED') {
              <button class="btn btn-outline-secondary btn-sm" (click)="archiveProject()">
                <i class="bi bi-archive"></i>{{ 'project.actions.archive' | transloco }}
              </button>
            }
          } @else {
            <button class="btn btn-outline-secondary btn-sm" (click)="unarchiveProject()">
              <i class="bi bi-arrow-counterclockwise"></i>{{ 'project.unarchive' | transloco }}
            </button>
          }
        }
      </div>
    </div>

    <div class="page-body">
      <div class="page-header" style="flex-direction:column;align-items:flex-start;gap:.75rem;padding-bottom:.75rem">
        <div>
          <h1 class="page-title">{{ project()?.name ?? '...' }}</h1>
          <!-- "@if (project(); as p)" reads the signal ONCE and names the result p for the
               whole block. Why it is worth it: without it every line below would write
               project()?.something, which is heavier to read and calls the signal again and
               again. Inside the block p is also known to be non-null, so no ? is needed. -->
          @if (project(); as p) {
            <div class="page-subtitle" style="display:flex;align-items:center;gap:.5rem">
              @if (p.client) { <span>{{ p.client }}</span><span class="bc-sep">·</span> }
              <!-- The status badge becomes a BUTTON only when the three conditions hold:
                   the user may edit, the project is not archived, and the current status
                   still has at least one allowed next status. Otherwise the @else below
                   shows the very same badge as plain text.
                   Example of what the third test avoids: a CANCELLED project whose list of
                   moves would be empty - the user would open a menu with nothing in it. -->
              @if (auth.hasPermission('EDIT_PROJECT') && !p.archived && allowedTransitions(p.status).length) {
                <span class="status-ctl">
                  <!-- The click flips the open/closed signal: read the value, invert it,
                       write it back. aria-expanded and aria-haspopup tell a screen reader
                       that this button opens a menu and whether it is open; without them a
                       blind user only hears the name of the current status. -->
                  <button class="status-btn" (click)="statusMenuOpen.set(!statusMenuOpen())"
                          [attr.aria-expanded]="statusMenuOpen()" aria-haspopup="menu" [title]="'project.actions.changeStatus' | transloco">
                    <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
                    <i class="bi bi-chevron-down"></i>
                  </button>
                  @if (statusMenuOpen()) {
                    <div class="status-backdrop" (click)="statusMenuOpen.set(false)"></div>
                    <div class="status-menu" role="menu">
                      <div class="status-menu-label">{{ 'project.actions.changeStatus' | transloco }}</div>
                      <!-- @for needs "track": it tells Angular how to recognise a row it
                           has already drawn. Here the status word itself is unique, so
                           track s is enough. Without a correct track, Angular throws away
                           and rebuilds the rows on every redraw, which loses the keyboard
                           focus and the scroll position of the list. -->
                      @for (s of allowedTransitions(p.status); track s) {
                        <button class="status-menu-item" role="menuitem" (click)="changeStatus(s)">
                          <span [class]="badge(s)">{{ 'status.' + s | transloco }}</span>
                        </button>
                      }
                    </div>
                  }
                </span>
              } @else {
                <span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span>
              }
            </div>
          }
        </div>
        <!-- The tab bar. Each tab is shown only if the user holds the permission that opens
             it, except the first one which is always available. The @if around a tab and
             the TAB_PERM map in the class say the same thing; TAB_PERM is what protects the
             ?tab= parameter of the URL, because hiding the button does not stop somebody
             from typing /projects/12?tab=facturation by hand. -->
        <div class="pms-tabs">
          <!-- [class.active] adds the CSS class only when the test is true - this is what
               underlines the current tab. -->
          <button class="tab-item" [class.active]="tab()==='info'" (click)="setTab('info')">
            <!-- The KEY itself is chosen before the pipe translates it: a user with
                 VIEW_KPI reads a label that mentions the indicators, the others read the
                 short label. Without this the tab would promise KPI to a developer who is
                 not allowed to see a single figure of money. -->
            <i class="bi bi-info-circle me-1"></i>{{ (auth.hasPermission('VIEW_KPI') ? 'project.tabs.overview' : 'project.tabs.overviewShort') | transloco }}
          </button>
          @if (auth.hasPermission('VIEW_TEAM')) {
            <button class="tab-item" [class.active]="tab()==='equipe'" (click)="setTab('equipe')">
              <i class="bi bi-people me-1"></i>{{ 'project.tabs.team' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_WORKLOAD')) {
            <button class="tab-item" [class.active]="tab()==='charges'" (click)="setTab('charges')">
              <i class="bi bi-calendar3 me-1"></i>{{ 'project.tabs.workload' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_BILLING')) {
            <button class="tab-item" [class.active]="tab()==='facturation'" (click)="setTab('facturation')">
              <i class="bi bi-receipt me-1"></i>{{ 'project.tabs.billing' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_MISSION')) {
            <button class="tab-item" [class.active]="tab()==='missions'" (click)="setTab('missions')">
              <i class="bi bi-airplane me-1"></i>{{ 'project.tabs.missions' | transloco }}
            </button>
          }
          @if (auth.hasPermission('VIEW_GOVERNANCE')) {
            <button class="tab-item" [class.active]="tab()==='gouvernance'" (click)="setTab('gouvernance')">
              <i class="bi bi-shield-check me-1"></i>{{ 'project.tabs.governance' | transloco }}
            </button>
          }
        </div>
      </div>

      <!-- ===== TAB: INFOS & KPI ===== -->
      <!-- Only ONE of the six tab blocks below is in the page at a time. @if really removes
           the markup, it does not hide it, so the tables of the other tabs cost nothing.
           Here the condition is also the "as p" trick: the block is drawn only when the tab
           is the right one AND the project has arrived. -->
      @if (tab() === 'info' && project(); as p) {
        <div class="row g-4">
          <div class="col-lg-5">
            <div class="card h-100">
              <div class="card-header">{{ 'project.info.title' | transloco }}</div>
              <div class="card-body">
                <dl class="row small mb-0">
                  <dt class="col-5 text-muted">Code</dt>
                  <dd class="col-7 fw-semibold">{{ p.code }}</dd>
                  <dt class="col-5 text-muted">{{ 'project.info.status' | transloco }}</dt>
                  <dd class="col-7"><span [class]="badge(p.status)">{{ 'status.' + p.status | transloco }}</span></dd>
                  @if (p.contractId) {
                    <dt class="col-5 text-muted">{{ 'project.info.contractRef' | transloco }}</dt>
                    <dd class="col-7">{{ p.contractId }}</dd>
                  }
                  @if (p.client) {
                    <dt class="col-5 text-muted">{{ 'project.info.client' | transloco }}</dt>
                    <dd class="col-7">{{ p.client }}</dd>
                  }
                  @if (p.funder) {
                    <dt class="col-5 text-muted">{{ 'project.info.funder' | transloco }}</dt>
                    <dd class="col-7">{{ p.funder }}</dd>
                  }
                  @if (p.businessModel || p.engagementType) {
                    <dt class="col-5 text-muted">{{ 'project.info.engagement' | transloco }}</dt>
                    <dd class="col-7">
                      {{ p.businessModel === 'GROUPEMENT' ? 'Groupement' : (p.businessModel === 'SEUL' ? 'Seul' : '') }}
                      @if (p.businessModel && p.engagementType) { · }
                      {{ p.engagementType ? ('labels.engagement.' + p.engagementType | transloco) : '' }}
                    </dd>
                  }
                  <dt class="col-5 text-muted">{{ 'project.info.manager' | transloco }}</dt>
                  <dd class="col-7">
                    {{ p.chefProjetName ?? '—' }}
                    @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                      <button class="btn btn-sm btn-link p-0 ms-2" (click)="openChefModal()" [title]="'project.manager.assign' | transloco">
                        <i class="bi bi-pencil-square"></i>
                      </button>
                    }
                  </dd>
                  <dt class="col-5 text-muted">{{ 'project.info.period' | transloco }}</dt>
                  <dd class="col-7">
                    {{ p.startDate ?? '—' }} → {{ p.endDate ?? '—' }}
                    @if (p.durationDays) { <span class="text-muted">({{ p.durationDays }} j)</span> }
                  </dd>
                  @if (p.createdAt) {
                    <dt class="col-5 text-muted">{{ 'project.info.createdAt' | transloco }}</dt>
                    <!-- The date pipe takes three arguments: the format, the time zone
                         (undefined = the one of the browser) and the LOCALE. locale() is the
                         signal below, so the date is rewritten when the user switches
                         language. Without that third argument the date would stay in the
                         language chosen when the application started. -->
                    <dd class="col-7">{{ p.createdAt | date:'mediumDate':undefined:locale() }}, {{ p.createdAt | date:'shortTime':undefined:locale() }}</dd>
                  }
                  <!-- Money figures: BR-050 - hidden without VIEW_KPI (a developer, for
                       example). The budgets tell the margin of the company on the project,
                       so they are not for everyone on the team. Remember that the server
                       also refuses them: the check here only keeps them off the screen. -->
                  <!-- {{ x | number:'1.0-0' }} means: at least 1 digit before the decimal
                       point, and between 0 and 0 digits after it - so a rounded amount with
                       the thousands separator of the language. Without the pipe the page
                       would print 1250000.0000001 as JavaScript stores it. -->
                  @if (auth.hasPermission('VIEW_KPI')) {
                    <dt class="col-5 text-muted">{{ 'project.info.initialBudget' | transloco }}</dt>
                    <dd class="col-7">{{ (p.initialBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    @if (p.revisedBudget) {
                      <dt class="col-5 text-muted">{{ 'project.info.revisedBudget' | transloco }}</dt>
                      <dd class="col-7">{{ p.revisedBudget | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    }
                    <dt class="col-5 text-muted">{{ 'project.info.effectiveBudget' | transloco }}</dt>
                    <dd class="col-7 fw-bold text-primary">{{ (p.effectiveBudget ?? 0) | number:'1.0-0' }} {{ p.currency ?? 'TND' }}</dd>
                    @if (p.currency && p.currency !== 'TND' && p.budgetTnd) {
                      <dt class="col-5 text-muted">{{ 'project.info.budgetTnd' | transloco }}</dt>
                      <dd class="col-7">{{ p.budgetTnd | number:'1.0-0' }} TND</dd>
                    }
                    @if (p.pprTnd) {
                      <dt class="col-5 text-muted">PPR (5%)</dt>
                      <dd class="col-7">{{ p.pprTnd | number:'1.0-0' }} TND</dd>
                    }
                  }
                  @if (p.soldWorkloadDays) {
                    <dt class="col-5 text-muted">{{ 'project.info.soldWorkload' | transloco }}</dt>
                    <dd class="col-7">{{ p.soldWorkloadDays | number:'1.0-0' }} {{ 'common.manDays' | transloco }}
                      @if (p.warrantyWorkloadDays) { <span class="text-muted">({{ 'project.info.warranty' | transloco }} {{ p.warrantyWorkloadDays | number:'1.0-0' }} {{ 'common.manDays' | transloco }})</span> }
                    </dd>
                  }
                </dl>
              </div>
            </div>
          </div>

          <!-- The KPI card. Two conditions: the permission, and the figures actually
               arrived. The second one matters because the KPI call is fired after the
               project call in ngOnInit and answers later; without the kpi() test the tiles
               below would read kpi()!.marge on null and the page would crash.
               Inside this block kpi()! is written with a "!" (non-null assertion): it tells
               the compiler "I checked it above". It is safe HERE only, because of this @if. -->
          @if (auth.hasPermission('VIEW_KPI') && kpi()) {
            <div class="col-lg-7">
              <div class="card h-100">
                <div class="card-header">{{ 'project.kpi.title' | transloco }}</div>
                <div class="card-body">
                  <!-- Every figure in this card comes from GET /api/projects/{id}/kpi and is
                       computed by the server at the moment it is asked for, from the
                       workload, the billing and the internal quote. Nothing here is read
                       from a saved column, so two screens can never show two different
                       margins for the same project. -->
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--brand">
                        <div class="small text-muted">{{ 'project.kpi.plannedBudget' | transloco }}</div>
                        <div class="fs-5 fw-bold text-primary">{{ kpi()!.budgetPlanifie | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--warning">
                        <div class="small text-muted">{{ 'project.kpi.consumedBudget' | transloco }}</div>
                        <div class="fs-5 fw-bold text-warning">{{ kpi()!.budgetConsome | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--teal">
                        <div class="small text-muted">EAC</div>
                        <div class="fs-5 fw-bold text-info">{{ kpi()!.eac | number:'1.0-0' }}</div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <!-- The background colour is bound, not fixed: green while the margin
                           is positive, red as soon as it goes below zero. A losing project
                           has to be visible in one glance, not read digit by digit. -->
                      <div class="kpi-tile"
                           [style.background]="kpi()!.marge >= 0 ? 'var(--c-success-dim)' : 'var(--c-danger-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.margin' | transloco }}</div>
                        <div class="fs-5 fw-bold" [class]="kpi()!.marge >= 0 ? 'text-success' : 'text-danger'">
                          {{ kpi()!.marge | number:'1.0-0' }}
                        </div>
                        <div class="small text-muted">TND</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--neutral">
                        <div class="small text-muted">{{ 'project.kpi.consumptionRate' | transloco }}</div>
                        <!-- The server sends a ratio between 0 and 1, so it is multiplied by
                             100 here for display. number:'1.1-1' keeps exactly one digit
                             after the point: 0.6666 becomes 66.7 %, not 66.66666666 %. -->
                        <div class="fs-5 fw-bold">{{ (kpi()!.tauxConsommation * 100) | number:'1.1-1' }}%</div>
                        <div class="progress mt-1" style="height:4px">
                          <div class="progress-bar" [style.width.%]="kpi()!.tauxConsommation * 100"></div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <!-- EVM indicators (F-AFF-13). EVM = Earned Value Management, the method
                       used in the Excel file that this module replaces: it compares what
                       was planned, what was really spent, and how much of the work is
                       really done (EV). The tiles below are that comparison.
                       Each one is written "value != null ? show it : show a dash", because
                       these fields are empty until the first monthly snapshot is taken.
                       Without the test, a brand new project would display "null %". -->
                  <hr class="my-3">
                  <div class="row g-3">
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--brand">
                        <div class="small text-muted">{{ 'project.kpi.earnedValue' | transloco }}</div>
                        <div class="fs-5 fw-bold text-primary">
                          {{ kpi()!.evPct != null ? (kpi()!.evPct | number:'1.0-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.monthlyReview' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--success">
                        <div class="small text-muted">{{ 'project.kpi.delivery' | transloco }}</div>
                        <div class="fs-5 fw-bold text-success">
                          {{ kpi()!.deliveryPct != null ? (kpi()!.deliveryPct | number:'1.0-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.deliveredPlanned' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile"
                           [style.background]="(kpi()!.deriveJh ?? 0) < 0 ? 'var(--c-danger-dim)' : 'var(--c-brand-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.variance' | transloco }}</div>
                        <div class="fs-5 fw-bold" [class.text-danger]="(kpi()!.deriveJh ?? 0) < 0">
                          {{ kpi()!.deriveJh != null ? (kpi()!.deriveJh | number:'1.0-1') + ' ' + ('common.manDays' | transloco) : '—' }}
                        </div>
                        <div class="small text-muted">
                          {{ kpi()!.consommeJh | number:'1.0-1' }} {{ 'project.kpi.consumedShort' | transloco }} / {{ kpi()!.rafJh | number:'1.0-1' }} {{ 'project.kpi.remainingShort' | transloco }}
                        </div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--teal">
                        <div class="small text-muted">{{ 'project.kpi.caProduction' | transloco }}</div>
                        <div class="fs-5 fw-bold text-info">
                          {{ kpi()!.caProduction != null ? (kpi()!.caProduction | number:'1.0-0') : '—' }}
                        </div>
                        <div class="small text-muted">{{ 'project.kpi.contractByEv' | transloco }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile kpi-tile--warning">
                        <div class="small text-muted">{{ 'project.kpi.faeStock' | transloco }}</div>
                        <div class="fs-5 fw-bold text-warning">
                          {{ kpi()!.fae != null ? (kpi()!.fae | number:'1.0-0') : '—' }}
                        </div>
                        <!-- The transloco pipe can take arguments: the object after the
                             colon fills the placeholder written in the JSON dictionary, for
                             example "Invoiced: {{amount}}". This is how a translated
                             sentence keeps a figure inside it without being cut into three
                             pieces, which would be impossible to translate properly. -->
                        <div class="small text-muted">{{ 'project.kpi.invoiced' | transloco: { amount: (kpi()!.totalFacture | number:'1.0-0') } }}</div>
                      </div>
                    </div>
                    <div class="col-6 col-md-4 text-center">
                      <div class="kpi-tile"
                           [style.background]="(kpi()!.margeActuellePct ?? 0) >= (kpi()!.margeVenduePct ?? 0) ? 'var(--c-success-dim)' : 'var(--c-danger-dim)'">
                        <div class="small text-muted">{{ 'project.kpi.actualVsSold' | transloco }}</div>
                        <div class="fs-5 fw-bold">
                          {{ kpi()!.margeActuellePct != null ? ((kpi()!.margeActuellePct! * 100) | number:'1.1-1') + ' %' : '—' }}
                        </div>
                        <div class="small text-muted">
                          vendue : {{ kpi()!.margeVenduePct != null ? ((kpi()!.margeVenduePct! * 100) | number:'1.1-1') + ' %' : '—' }}
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- The server can send warnings with the KPI (for example: a month has
                       declared days but no planned days). They are shown as they come; the
                       ? in warnings?.length guards against the field being absent. -->
                  @if (kpi()!.warnings?.length) {
                    <div class="alert alert-warning py-2 small mt-3 mb-0 d-flex align-items-start gap-2" role="alert">
                      <i class="bi bi-exclamation-triangle-fill flex-shrink-0 mt-1"></i>
                      <ul class="mb-0 ps-2">
                        @for (w of kpi()!.warnings; track w) {
                          <li>{{ w }}</li>
                        }
                      </ul>
                    </div>
                  }

                  <!-- Monthly review: the snapshot, with the EV typed by hand
                       (F-AFF-13, the "Situation actuelle" sheet of the Excel file).
                       The snapshot freezes today's KPI so that next month we can still say
                       where the project stood. The live figures change every day, so without
                       these saved snapshots no trend over the months would be possible.
                       Only EV %, the estimated end date and the highlights are typed: the
                       money is recomputed by the server, never sent by the browser. -->
                  @if (auth.hasPermission('EDIT_PROJECT') && !project()?.archived) {
                    <hr class="my-3">
                    <div class="row g-2 align-items-end">
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">EV % (avancement)</label>
                        <!-- [(ngModel)] is two-way binding: what the user types goes into
                             the field snapEvPct of the class, and a change made in the code
                             comes back into the box. It is used here because the three
                             fields are a plain little form and nothing has to react to each
                             keystroke. Elsewhere in this file (the search boxes of the two
                             modals) the one-way pair [ngModel] + (ngModelChange) is used
                             instead, because there the target is a signal and a signal is
                             written with .set().
                             min and max are only a comfort for the arrows of the number
                             input; the real check on 0..100 is done by the server. -->
                        <input type="number" class="form-control form-control-sm" min="0" max="100"
                               [(ngModel)]="snapEvPct" [placeholder]="'project.kpi.evPlaceholder' | transloco">
                      </div>
                      <div class="col-sm-3">
                        <label class="form-label small fw-semibold mb-1">{{ 'project.kpi.estimatedEndDate' | transloco }}</label>
                        <input type="date" class="form-control form-control-sm" [(ngModel)]="snapDateFin">
                      </div>
                      <div class="col-sm-4">
                        <label class="form-label small fw-semibold mb-1">{{ 'project.kpi.highlights' | transloco }}</label>
                        <input class="form-control form-control-sm" [(ngModel)]="snapFaits" maxlength="2000">
                      </div>
                      <div class="col-sm-2 d-grid">
                        <!-- [disabled] while the call is travelling. Without it, an
                             impatient double click would send two POST, and the second one
                             comes back 409 because one snapshot per month is allowed. -->
                        <button class="btn btn-sm btn-primary" (click)="createSnapshot()" [disabled]="snapshotLoading()">
                          @if (snapshotLoading()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                          <i class="bi bi-camera me-1"></i>{{ 'project.kpi.snapshot' | transloco }}
                        </button>
                      </div>
                    </div>
                    @if (snapshotMsg()) {
                      <div class="small mt-2" [class.text-success]="!snapshotError()" [class.text-danger]="snapshotError()">
                        {{ snapshotMsg() }}
                      </div>
                    }
                  }
                </div>
              </div>
            </div>
          }
        </div>
      }

      <!-- ===== TAB: TEAM ===== -->
      <!-- Data: TeamService.list -> GET /api/projects/{id}/team, fired once by setTab.
           Each row carries userFullName already built by the server, so the screen never
           has to call the users endpoint to turn an id into a name. -->
      @if (tab() === 'equipe') {
        <div class="card">
          <div class="card-header d-flex justify-content-between align-items-center">
            <span><i class="bi bi-people me-2"></i>{{ 'project.team.title' | transloco }}</span>
            <div class="d-flex align-items-center gap-2">
              <span class="badge-draft">{{ team().length }} membres</span>
              @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                <button class="btn btn-primary btn-sm" (click)="openTeamModal()">
                  <i class="bi bi-person-plus me-1"></i>{{ 'project.team.assignTitle' | transloco }}
                </button>
              }
            </div>
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr><th>{{ 'project.team.member' | transloco }}</th><th>{{ 'project.team.role' | transloco }}</th><th>{{ 'project.team.from' | transloco }}</th><th>{{ 'project.team.until' | transloco }}</th>
                  @if (auth.hasPermission('ASSIGN_DEVELOPER')) { <th class="text-end">{{ 'common.actions' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
                <!-- track m.id: the id of the ASSIGNMENT row, which is unique and stable.
                     Tracking by the user id would be wrong the day somebody has an old
                     closed assignment and a new one on the same project. -->
                @for (m of team(); track m.id) {
                  <tr>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.roleInTeam ?? '—' }}</td>
                    <td>{{ m.startDate }}</td>
                    <td>{{ m.endDate ?? 'Actif' }}</td>
                    @if (auth.hasPermission('ASSIGN_DEVELOPER')) {
                      <td class="text-end">
                        <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="removeMember(m)" [title]="'project.team.remove' | transloco" [attr.aria-label]="'project.team.removeAria' | transloco">
                          <i class="bi bi-person-dash"></i>
                        </button>
                      </td>
                    }
                  </tr>
                }
                <!-- @empty is drawn when the list has zero row. It is what turns a blank
                     table into a readable message. Without it the user cannot tell an empty
                     team from a page that failed to load. -->
                @empty {
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-people"></i></div>
                      <div class="es-title">{{ 'project.team.empty' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ===== TAB: WORKLOAD ===== -->
      <!-- Two tables that answer the same question from the two sides: the days PLANNED for
           somebody on a month, and the days he says he really SPENT.
           Data: WorkloadService -> GET /api/projects/{id}/plan-charges and
           /charges-reelles, asked once in setTab.
           Both lists are paged IN THE BROWSER (see pagedPlanCharges in the class): the rows
           are already here, so turning a page must not cost a new HTTP call. -->
      @if (tab() === 'charges') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-calendar3 me-2"></i>{{ 'project.workload.planTitle' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.workload.resource' | transloco }}</th><th>{{ 'project.workload.year' | transloco }}</th><th>{{ 'project.workload.month' | transloco }}</th><th class="text-end">{{ 'project.workload.plannedDays' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (c of pagedPlanCharges(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.plannedDays }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-calendar3"></i></div>
                          <div class="es-title">{{ 'project.workload.emptyPlan' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
              <!-- The pager is drawn only when there is more than one page. Showing
                   "page 1 of 1" on a table of three rows is noise.
                   <app-pagination> is the shared component of the project, reused here
                   instead of writing another set of arrows: same look, same keyboard
                   behaviour, one place to fix a bug.
                   (pageSizeChange) also resets the page to 0. Without that reset, changing
                   the size from 10 to 50 while on page 4 would ask for rows 200 to 250,
                   which do not exist, and the table would look empty. -->
              @if (planCharges().length > planPageSize()) {
                <app-pagination
                  [page]="planPage()" [pageSize]="planPageSize()" [total]="planCharges().length"
                  (pageChange)="planPage.set($event)"
                  (pageSizeChange)="planPageSize.set($event); planPage.set(0)" />
              }
            </div>
          </div>
          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-clock-history me-2"></i>{{ 'project.workload.actualTitle' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.workload.resource' | transloco }}</th><th>{{ 'project.workload.year' | transloco }}</th><th>{{ 'project.workload.month' | transloco }}</th><th class="text-end">{{ 'project.workload.actualDays' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (c of pagedChargesReelles(); track c.id) {
                      <tr>
                        <td>{{ c.userFullName }}</td>
                        <td>{{ c.year }}</td>
                        <td>{{ monthLabel(c.month) }}</td>
                        <td class="text-end fw-semibold">{{ c.actualDays }}</td>
                        <td>
                          <!-- A declared month counts in the cost of the project only once
                               somebody has validated it. validatedAt being filled is what
                               says so, so the badge is read from that date and not from a
                               status field. -->
                          @if (c.validatedAt) {
                            <span class="badge-active">{{ 'labels.workload.VALIDEE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">{{ 'labels.workload.SOUMISE' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-clock-history"></i></div>
                          <div class="es-title">{{ 'project.workload.emptyActual' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (chargesReelles().length > actualPageSize()) {
                <app-pagination
                  [page]="actualPage()" [pageSize]="actualPageSize()" [total]="chargesReelles().length"
                  (pageChange)="actualPage.set($event)"
                  (pageSizeChange)="actualPageSize.set($event); actualPage.set(0)" />
              }
            </div>
          </div>
        </div>
      }

      <!-- ===== TAB: BILLING ===== -->
      <!-- Data: BillingService -> GET /api/projects/{id}/jalons and /avenants.
           A "jalon" is a billing milestone: a share of the budget invoiced when a step is
           reached. Its life is PREVU (planned) -> FACTURE (invoiced) -> PAYE (paid).
           An "avenant" is a contract amendment; its amount can be negative, which is why
           its cell is coloured red below when it is. -->
      @if (tab() === 'facturation') {
        <div class="row g-4">
          <div class="col-12">
            <div class="card">
              <div class="card-header d-flex justify-content-between">
                <span><i class="bi bi-receipt me-2"></i>{{ 'project.billing.milestones' | transloco }}</span>
                <!-- The total is added up in the browser from the rows already loaded (see
                     jalonTotal in the class). It is a display total only; the amount that
                     counts for the accounts is the one computed by the server. -->
                <span class="text-muted small">{{ 'common.total' | transloco }} {{ jalonTotal() | number:'1.0-0' }} TND</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.billing.label' | transloco }}</th><th class="text-end">%</th><th class="text-end">{{ 'project.billing.amountTnd' | transloco }}</th><th>{{ 'project.billing.dueDatePlanned' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (j of jalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }}</td>
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>
                          @if (j.statut === 'PAYE') {
                            <span class="badge-active">{{ 'labels.milestone.PAYE' | transloco }}</span>
                          } @else if (j.statut === 'FACTURE') {
                            <span class="badge-completed">{{ 'labels.milestone.FACTURE' | transloco }}</span>
                          } @else {
                            <span class="badge-draft">{{ 'labels.milestone.PREVU' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-list-check"></i></div>
                          <div class="es-title">{{ 'project.billing.emptyMilestones' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-file-earmark-plus me-2"></i>{{ 'project.billing.amendments' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.billing.number' | transloco }}</th><th>{{ 'project.billing.subject' | transloco }}</th><th class="text-end">{{ 'project.billing.amountTnd' | transloco }}</th><th>{{ 'project.billing.date' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (a of avenants(); track a.id) {
                      <tr>
                        <td class="fw-semibold">{{ a.numero }}</td>
                        <td>{{ a.objet }}</td>
                        <td class="text-end" [class.text-danger]="a.montant < 0" [class.text-success]="a.montant >= 0">
                          {{ a.montant | number:'1.0-0' }}
                        </td>
                        <td>{{ a.dateAvenant }}</td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-file-earmark-plus"></i></div>
                          <div class="es-title">{{ 'project.billing.emptyAmendments' | transloco }}</div>
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

      <!-- ===== TAB: MISSIONS ===== -->
      <!-- Data: MissionService.list -> GET /api/projects/{id}/missions. A mission is a trip
           made for the project. Read-only here; the missions screen is where they are
           created, with their cost lines. -->
      @if (tab() === 'missions') {
        <div class="card">
          <div class="card-header">
            <i class="bi bi-airplane me-2"></i>{{ 'project.tabs.missions' | transloco }}
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr><th>{{ 'project.missions.collaborator' | transloco }}</th><th>{{ 'project.missions.subject' | transloco }}</th><th>{{ 'project.missions.place' | transloco }}</th><th>{{ 'project.missions.start' | transloco }}</th><th>{{ 'project.missions.end' | transloco }}</th></tr>
              </thead>
              <tbody>
                @for (m of missions(); track m.id) {
                  <tr>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.objet }}</td>
                    <td>{{ m.lieu }}</td>
                    <td>{{ m.dateDebut }}</td>
                    <td>{{ m.dateFin }}</td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-kanban"></i></div>
                      <div class="es-title">{{ 'project.missions.empty' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- ===== TAB: GOVERNANCE ===== -->
      <!-- Three lists loaded together by setTab, from GovernanceService:
           the risk register, the deliverables and the change requests.
           They are the three things a steering committee asks about, which is why they sit
           on one tab instead of three. -->
      @if (tab() === 'gouvernance') {
        <div class="row g-4">
          <!-- Risks -->
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header">
                <i class="bi bi-exclamation-triangle me-2 text-warning"></i>{{ 'governance.riskRegister' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.description' | transloco }}</th><th>{{ 'project.governance.probability' | transloco }}</th><th>{{ 'project.governance.impact' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (r of risks(); track r.id) {
                      <tr>
                        <td class="small">{{ r.description }}</td>
                        <!-- Two different things happen on the same value: niveauBadge()
                             picks the COLOUR class, and the key 'riskLevel.' + value picks
                             the translated TEXT. The value itself (ELEVE, MOYEN, FAIBLE)
                             stays the code of the server and is never shown raw. -->
                        <td><span [class]="niveauBadge(r.probabilite)">{{ 'riskLevel.' + r.probabilite | transloco }}</span></td>
                        <td><span [class]="niveauBadge(r.impact)">{{ 'riskLevel.' + r.impact | transloco }}</span></td>
                        <td>
                          @if (r.statut === 'FERME') {
                            <span class="badge-active">{{ 'labels.risk.FERME' | transloco }}</span>
                          } @else if (r.statut === 'MITIGE') {
                            <span class="badge-on-hold">{{ 'labels.risk.MITIGE' | transloco }}</span>
                          } @else {
                            <span class="badge-cancelled">{{ 'labels.risk.OUVERT' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="4" class="text-center py-3 text-muted small">{{ 'project.governance.emptyRisks' | transloco }}</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Livrables -->
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header">
                <i class="bi bi-check2-square me-2 text-success"></i>{{ 'project.governance.deliverables' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.title' | transloco }}</th><th>{{ 'project.governance.dueDate' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (l of livrables(); track l.id) {
                      <tr>
                        <td class="fw-semibold small">{{ l.titre }}</td>
                        <td class="small">{{ l.dateEcheance ?? '—' }}</td>
                        <td><span [class]="livrableBadge(l.statut)">{{ 'labels.deliverable.' + l.statut | transloco }}</span></td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="3" class="text-center py-3 text-muted small">{{ 'project.governance.emptyDeliverables' | transloco }}</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Demandes de changement -->
          <div class="col-12">
            <div class="card">
              <div class="card-header">
                <i class="bi bi-arrow-repeat me-2"></i>{{ 'project.governance.changeRequests' | transloco }}
              </div>
              <div class="table-responsive">
                <table class="table table-sm mb-0 align-middle">
                  <thead>
                    <tr><th>{{ 'project.governance.title' | transloco }}</th><th>{{ 'project.governance.requester' | transloco }}</th><th>{{ 'project.governance.priority' | transloco }}</th><th>{{ 'project.billing.date' | transloco }}</th><th>{{ 'project.info.status' | transloco }}</th></tr>
                  </thead>
                  <tbody>
                    @for (dc of changes(); track dc.id) {
                      <tr>
                        <td class="fw-semibold small">{{ dc.titre }}</td>
                        <td class="small">{{ dc.demandeurFullName }}</td>
                        <td><span [class]="prioriteBadge(dc.priorite)">{{ 'changePriority.' + dc.priorite | transloco }}</span></td>
                        <td class="small">{{ dc.dateDemande ?? '—' }}</td>
                        <td>
                          @if (dc.statut === 'APPROUVE') {
                            <span class="badge-active">{{ 'labels.changeRequest.APPROUVE' | transloco }}</span>
                          } @else if (dc.statut === 'REJETE') {
                            <span class="badge-cancelled">{{ 'labels.changeRequest.REJETE' | transloco }}</span>
                          } @else {
                            <span class="badge-on-hold">{{ 'labels.changeRequest.EN_ATTENTE' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="5" class="text-center py-3 text-muted small">{{ 'project.governance.emptyChangeRequests' | transloco }}</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }

    </div>

    <!-- Modal: assign the project manager.
         It is written by hand rather than with the Bootstrap JavaScript: the whole block
         exists only while the signal is true, so there is no hidden window left in the page
         and no third-party script to keep in step with Angular. -->
    @if (showChefModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- A click anywhere on the grey area closes the window... -->
      <div class="modal d-block" tabindex="-1" (click)="showChefModal.set(false)">
        <!-- ...and stopPropagation() on the white box stops that click from travelling up
             when the user clicks INSIDE the form. Without this line, clicking a field or
             picking a name would close the window immediately. -->
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'project.manager.assignTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showChefModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.manager.label' | transloco }} <span class="text-danger">*</span></label>
                <div class="input-wrap mb-2">
                  <i class="bi bi-search input-icon"></i>
                  <!-- Here the binding is split in two: [ngModel] puts the value of the
                       signal into the box, (ngModelChange) writes what the user types back
                       into the signal with .set(). The short form [(ngModel)] cannot be
                       used because the target is a signal, not a plain field.
                       The filtering itself is immediate, on every keystroke. There is no
                       debounce (no small wait before reacting) and none is needed, because
                       nothing is sent to the server: filteredChefs() only sorts through a
                       list already in memory. -->
                  <input type="search" class="form-control form-control-sm"
                         [placeholder]="'common.searchByName' | transloco"
                         [ngModel]="chefSearch()" (ngModelChange)="chefSearch.set($event)">
                </div>
                <!-- The project rule again: a search box plus a clickable list, never a
                     <select> holding every employee of the company. -->
                <div class="picker-list">
                  @for (u of filteredChefs(); track u.id) {
                    <!-- The chosen candidate is kept in a plain field, chefUserId, not in a
                         signal: it is only read when the user presses Assign. -->
                    <button type="button" class="picker-item" [class.is-selected]="chefUserId === u.id"
                            (click)="chefUserId = u.id">
                      <span class="pi-name">{{ u.firstName }} {{ u.lastName }}</span>
                      <span class="badge-draft" style="font-size:10px">{{ u.roleName }}</span>
                      @if (chefUserId === u.id) { <i class="bi bi-check-lg" style="color:var(--c-brand)"></i> }
                    </button>
                  } @empty {
                    <div class="picker-empty">{{ 'project.manager.noneFound' | transloco }}</div>
                  }
                </div>
                <div class="picker-count">{{ filteredChefs().length }} candidat{{ filteredChefs().length !== 1 ? 's' : '' }}</div>
              </div>
              <!-- The error is shown INSIDE the window, not as a toast in the corner: the
                   user must keep the form under his eyes to correct it. -->
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChefModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveChef()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Assigner
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal: put somebody on the team. Same construction as the one above; what changes
         is that the list can be filtered by role as well, and that the people already on
         the team are removed from it (see filteredMembers in the class). -->
    @if (showTeamModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showTeamModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'project.team.assignTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showTeamModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.member' | transloco }} <span class="text-danger">*</span></label>
                <!-- Search box + role filter, to make the choice easier. -->
                <div class="d-flex gap-2 mb-2">
                  <div class="input-wrap" style="flex:1">
                    <i class="bi bi-search input-icon"></i>
                    <input type="search" class="form-control form-control-sm"
                           [placeholder]="'common.searchByName' | transloco"
                           [ngModel]="memberSearch()" (ngModelChange)="memberSearch.set($event)">
                  </div>
                  <!-- A <select> is fine HERE: it holds the roles, and a company has a
                       handful of them. The rule of the project forbids a dropdown for a
                       LARGE set of rows, such as the people below. -->
                  <select class="form-select form-select-sm" style="max-width:170px"
                          [ngModel]="memberRoleFilter()" (ngModelChange)="memberRoleFilter.set($event)">
                    <option value="">{{ 'project.team.allRoles' | transloco }}</option>
                    @for (r of memberRoles(); track r) { <option [value]="r">{{ r }}</option> }
                  </select>
                </div>
                <!-- Clickable list of the filtered candidates. -->
                <div class="picker-list">
                  @for (u of filteredMembers(); track u.id) {
                    <button type="button" class="picker-item" [class.is-selected]="teamForm.userId === u.id"
                            (click)="teamForm.userId = u.id">
                      <span class="pi-name">{{ u.firstName }} {{ u.lastName }}</span>
                      <span class="badge-draft" style="font-size:10px">{{ u.roleName }}</span>
                      @if (teamForm.userId === u.id) { <i class="bi bi-check-lg" style="color:var(--c-brand)"></i> }
                    </button>
                  } @empty {
                    <div class="picker-empty">{{ 'project.team.noMemberFound' | transloco }}</div>
                  }
                </div>
                <div class="picker-count">{{ filteredMembers().length }} membre{{ filteredMembers().length !== 1 ? 's' : '' }} disponible{{ filteredMembers().length !== 1 ? 's' : '' }}</div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.roleInTeam' | transloco }} <span class="text-danger">*</span></label>
                <!-- This role is free text (developer, analyst, tester...): it is the job
                     held INSIDE this team, which has nothing to do with the security role
                     of the account. Confusing the two is the classic mistake here.
                     maxlength matches the column on the server, so a too long value is
                     stopped before the request instead of coming back as an error 400. -->
                <input type="text" class="form-control" maxlength="50" [(ngModel)]="teamForm.roleInTeam" [placeholder]="'project.team.rolePlaceholder' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'project.team.startDate' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="teamForm.startDate">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showTeamModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveTeamMember()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'project.team.assign' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
/**
 * The class behind the template above.
 *
 * Its job in one sentence: read the id in the URL, ask the right service for the data of
 * the tab being looked at, put the answers into signals, and send back the few changes the
 * user is allowed to make.
 *
 * implements OnInit forces this class to have an ngOnInit() method; Angular calls it once,
 * after the component is built. Why not do the work in a constructor: at construction time
 * the inputs and the route are not ready yet, so the id could be read as NaN.
 *
 * All the fields below are declared with inject(...) instead of constructor arguments. It
 * is the modern Angular way and it is the same dependency injection: one shared instance of
 * each service, given by Angular.
 * Note that 'auth' and 'listState' are public: the template uses them directly.
 */
export class ProjectDetailComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly t = inject(TranslocoService);
  private readonly lang = inject(LanguageService);
  /**
   * Reactive locale: the date and number pipes follow the change of language without a
   * reload of the page.
   *
   * computed() builds a value out of other signals: it recomputes itself, and only when one
   * of them really changed. Here it watches the language service.
   * Without it the locale would be read once at start-up, and a user switching to English
   * would keep French month names and French number separators until he reloads the page.
   */
  readonly locale = computed(() => this.lang.current() === 'en' ? 'en-US' : 'fr');
  private readonly svc        = inject(ProjectService);
  private readonly http       = inject(HttpClient);
  private readonly teamSvc    = inject(TeamService);
  private readonly workloadSvc = inject(WorkloadService);
  private readonly billingSvc = inject(BillingService);
  private readonly missionSvc = inject(MissionService);
  private readonly govSvc     = inject(GovernanceService);
  private readonly route      = inject(ActivatedRoute);
  private readonly router     = inject(Router);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  readonly listState          = inject(ProjectsListStateService);

  /**
   * Allowed status transitions (enterprise workflow). Empty = terminal via this control.
   *
   * Read it as: from DRAFT the project may only go to ACTIVE or CANCELLED, and so on. The
   * menu of the status button is built from this map, so an impossible move is simply never
   * offered - for example ACTIVE straight back to DRAFT.
   * IMPORTANT for the jury: this is comfort for the user, not the rule. The same rule lives
   * on the server, which refuses a forbidden move whatever the browser sends.
   * Record<ProjectStatus, ProjectStatus[]> means "one entry for EVERY status, each holding
   * a list of statuses". The compiler refuses the file if a new status is added to the enum
   * and forgotten here.
   */
  private readonly TRANSITIONS: Record<ProjectStatus, ProjectStatus[]> = {
    DRAFT:     ['ACTIVE', 'CANCELLED'],
    ACTIVE:    ['ON_HOLD', 'COMPLETED', 'CANCELLED'],
    ON_HOLD:   ['ACTIVE', 'CANCELLED'],
    COMPLETED: ['ACTIVE'],
    CANCELLED: ['DRAFT'],
  };
  /** Is the little status menu open? A signal, so the template redraws when it flips. */
  statusMenuOpen = signal(false);

  // ── The data of the screen, one signal per box ──────────────────────────────
  // A signal is a box holding a value that Angular watches: .set() a new value inside and
  // every line of the template reading it is redrawn, with no extra code.
  // Why a signal rather than a plain field: with a plain field Angular would have to check
  // the whole screen after every event to notice the change, and with the OnPush strategy
  // it would simply not notice it at all - the table would stay empty although the data
  // arrived.
  //
  // The two "null at the start" boxes hold ONE object that has not arrived yet; the others
  // start as an empty array, which is why the tables can be drawn before any call returns.

  /** The project sheet itself: GET /api/projects/{id}. */
  project = signal<Project | null>(null);
  /** The live indicators: GET /api/projects/{id}/kpi. Loaded only with VIEW_KPI. */
  kpi = signal<KpiResponse | null>(null);
  /** The people on the project (team tab). */
  team = signal<TeamAssignment[]>([]);
  /** The days planned per person and per month (workload tab). */
  planCharges = signal<PlanCharge[]>([]);
  /** The days declared as really spent (workload tab). */
  chargesReelles = signal<ChargeReelle[]>([]);

  // These two tables can hold hundreds of rows: one resource per month and per year. They
  // used to be drawn in one block, which made the tab grow without end.
  // Paging on the browser side: the rows are already loaded, so there is no reason to call
  // the server again just to turn a page. The page number and the page size are signals, so
  // clicking the pager redraws the table on its own.
  /** Current page of the planned table, counted from 0. */
  planPage       = signal(0);
  /** How many rows of the planned table are shown at once. */
  planPageSize   = signal(10);
  /** Current page of the declared table. */
  actualPage     = signal(0);
  /** How many rows of the declared table are shown at once. */
  actualPageSize = signal(10);

  /**
   * The slice of planned rows the template really draws.
   * It is a computed, so it is rebuilt only when the rows, the page or the page size change
   * - not on every redraw of the screen. With a method called from the template, the slice
   * would be recomputed at every single check of the page.
   */
  readonly pagedPlanCharges = computed(() =>
    slicePage(this.planCharges(), this.planPage(), this.planPageSize()));
  /** Same thing for the table of the days declared as really spent. */
  readonly pagedChargesReelles = computed(() =>
    slicePage(this.chargesReelles(), this.actualPage(), this.actualPageSize()));
  /** Billing milestones (billing tab). */
  jalons = signal<JalonFacturation[]>([]);
  /** Contract amendments (billing tab). */
  avenants = signal<Avenant[]>([]);
  /** Trips made for this project (missions tab). */
  missions = signal<Mission[]>([]);
  /** Risk register (governance tab). */
  risks = signal<Risk[]>([]);
  /** Deliverables (governance tab). */
  livrables = signal<Livrable[]>([]);
  /** Change requests (governance tab). */
  changes = signal<DemandeChangement[]>([]);

  /** The tab being looked at. It starts on the sheet, and setTab() also writes it in the URL. */
  tab = signal<Tab>('info');
  /**
   * The tabs whose data has already been fetched.
   * A Set only keeps one copy of each value, which is exactly what is needed here: setTab()
   * asks the services only when the tab is not in it yet.
   * Without this, going back and forth between two tabs would fire the same HTTP calls
   * again at every click.
   * It is a plain field and not a signal on purpose: the template never reads it.
   */
  private loadedTabs = new Set<Tab>();

  // ── Project manager / team management (B7/B8) ──────────────────────────────
  /**
   * The people who may be put on a project, from GET /api/users/assignable.
   * The shape is written inline (id, first name, last name, role name) because that is all
   * the two lists need. The server answer carries a few more fields; this narrow type keeps
   * the screen from using them by accident.
   */
  allUsers = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);
  /** Is the "assign a manager" window open? */
  showChefModal = signal(false);
  /** Is the "add a team member" window open? */
  showTeamModal = signal(false);
  /** True while a save is travelling; it greys out the button so nothing is sent twice. */
  saving = signal(false);
  /** The error shown inside the open window. Emptied every time a window is opened. */
  modalError = signal('');
  /** The manager chosen in the list. 0 means nobody chosen yet. */
  chefUserId = 0;
  /**
   * The little form of the team window.
   * A plain object, not a signal: it is bound with [(ngModel)] and only read when Assign is
   * pressed, so nothing has to react while it is being filled.
   * The start date is today, written as "2026-09-20": toISOString() gives
   * "2026-09-20T09:12:33.000Z" and split('T')[0] keeps the day. A date input only accepts
   * that exact shape, and sending a full Date to the server could shift the day by one
   * because of the time zone.
   */
  teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };

  // Search box and role filter of the team window.
  /** What the user typed in the search box. */
  memberSearch = signal('');
  /** The role chosen in the filter, or an empty string for "all roles". */
  memberRoleFilter = signal('');

  /**
   * The distinct roles found in the list of assignable users, used to fill the filter.
   *
   * new Set(...) removes the repeats, the spread [...] turns it back into an array, and
   * sort() puts it in alphabetical order.
   * Why build it from the data instead of writing the list by hand: the roles are dynamic
   * in this application, an administrator can create one. A fixed list would silently stop
   * offering the new role.
   */
  readonly memberRoles = computed(() =>
    [...new Set(this.allUsers().map(u => u.roleName))].sort()
  );

  /**
   * The filtered candidates: search by name, filter by role, and the people already on the
   * team are taken out.
   *
   * The already-on-the-team check uses a Set of user ids. A Set answers "is it in there?"
   * immediately, while looking through the array for each of 200 users would be 200 x 20
   * comparisons on every keystroke.
   * Why exclude them at all: offering a name that is already on the project only leads to
   * the server refusing a second active assignment, with an error the user does not deserve.
   * Being a computed, this list is rebuilt when the search text, the role filter, the users
   * or the team change - and nothing else has to remember to refresh it.
   */
  readonly filteredMembers = computed(() => {
    const q = this.memberSearch().trim().toLowerCase();
    const role = this.memberRoleFilter();
    const already = new Set(this.team().map(m => m.userId));
    return this.allUsers().filter(u =>
      !already.has(u.id) &&
      (!role || u.roleName === role) &&
      (!q || `${u.firstName} ${u.lastName}`.toLowerCase().includes(q))
    );
  });

  // Search box of the "assign a manager" window.
  /** What the user typed there. Emptied each time the window is opened. */
  chefSearch = signal('');

  /**
   * The manager candidates kept by the search. The list handed to it is already narrowed
   * down to the people whose role is CHEF_PROJET (see chefCandidates below).
   * trim() drops the spaces around the text and toLowerCase() makes the search ignore
   * capitals, so typing " ben" still finds "Ben Salah".
   */
  readonly filteredChefs = computed(() => {
    const q = this.chefSearch().trim().toLowerCase();
    return this.chefCandidates().filter(u =>
      !q || `${u.firstName} ${u.lastName}`.toLowerCase().includes(q)
    );
  });

  // ── Monthly review (the EVM snapshot, F-AFF-13) ────────────────────────────
  // Three plain fields, bound with [(ngModel)], because nothing has to react while they are
  // being typed; they are read once when the Snapshot button is pressed.
  /** EV %: the share of the work really done, judged by the project manager, 0 to 100. */
  snapEvPct: number | null = null;
  /** Estimated end date, as a day string "2026-12-31". Empty means "do not send it". */
  snapDateFin = '';
  /** Free text: what happened this month. */
  snapFaits = '';
  /** True while the POST is travelling; it greys out the Snapshot button. */
  snapshotLoading = signal(false);
  /** The message shown under the little form, success or failure. */
  snapshotMsg = signal('');
  /** Tells the template to paint that message red instead of green. */
  snapshotError = signal(false);

  /**
   * The id read from the URL. Kept in a field because every call of this screen needs it.
   * It is a plain number and not a signal: it is set once in ngOnInit and never changes
   * while the screen lives.
   */
  private projectId = 0;

  /**
   * Permission required to open each tab (null = always allowed).
   *
   * This map is what really protects the ?tab= parameter. Hiding the tab button is not
   * enough: a user can type /projects/12?tab=facturation in the address bar. ngOnInit reads
   * this map before honouring the parameter.
   * Even then it is only the screen being tidy - the billing data itself is refused by the
   * server without VIEW_BILLING.
   */
  private readonly TAB_PERM: Record<Tab, string | null> = {
    info: null, equipe: 'VIEW_TEAM', charges: 'VIEW_WORKLOAD',
    facturation: 'VIEW_BILLING', missions: 'VIEW_MISSION', gouvernance: 'VIEW_GOVERNANCE'
  };

  /**
   * Called once by Angular when the screen appears. It does three things: read the id in
   * the URL, fetch the project (and its KPI if allowed), and reopen the tab written in the
   * URL.
   */
  ngOnInit(): void {
    // snapshot.paramMap reads the route parameters ONCE. It is enough here because leaving
    // this screen for another project rebuilds the component.
    // The + turns the text "12" into the number 12, and the ! tells the compiler the
    // parameter is there - it always is, because the route is /projects/:id.
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    // subscribe() is what actually sends the request: an Observable does nothing until
    // somebody subscribes. The function inside runs when the answer comes back.
    this.svc.get(this.projectId).subscribe(p => {
      this.project.set(p);
      // The KPI are asked only if the user may see them. Without this test the browser
      // would fire a call the server answers 403, and the console would show a red error on
      // a screen where nothing is wrong.
      // The call is nested inside the first answer, not fired beside it, so a project that
      // does not exist (404) does not lead to a second useless call.
      if (this.auth.hasPermission('VIEW_KPI')) {
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      }
    });
    // The first tab is already loaded by the two calls above, so it is marked as done.
    // Without this line, the first click back on it would fetch the project again.
    this.loadedTabs.add('info');

    // Restore the active tab from the URL (?tab=), honouring permissions.
    // Why it matters: the user can copy the address of the billing tab and send it to a
    // colleague, or simply refresh the page, and land where he was.
    // The permission is checked again here because the URL can be typed by hand.
    const t = this.route.snapshot.queryParamMap.get('tab') as Tab | null;
    if (t && t !== 'info' && (!this.TAB_PERM[t] || this.auth.hasPermission(this.TAB_PERM[t]!))) {
      this.setTab(t);
    }
  }

  /**
   * Monthly review: freezes today's KPI together with the EV % that was typed
   * (F-AFF-13, the "Situation actuelle" sheet).
   * Calls ProjectService.createSnapshot -> POST /api/projects/{id}/kpi/snapshots, which the
   * server guards with EDIT_PROJECT plus the project scope (ADR-021).
   *
   * Only three values are sent. Every figure of money in the snapshot is recomputed by the
   * server at that moment; letting the browser send them would let a modified request write
   * a margin that matches no line of the project.
   * '|| undefined' on the two texts: an empty box must be sent as "no value", not as an
   * empty string, otherwise the server would store an empty end date as a real answer.
   *
   * The subscribe has two branches, next and error, because this one has a message to show
   * either way. 409 is the special case: one snapshot per month is allowed, so a second one
   * gets its own sentence instead of a raw technical error.
   */
  createSnapshot(): void {
    this.snapshotLoading.set(true);
    this.snapshotMsg.set('');
    this.svc.createSnapshot(this.projectId, {
      evPct: this.snapEvPct ?? undefined,
      dateFinEstimee: this.snapDateFin || undefined,
      faitsMarquants: this.snapFaits || undefined
    }).subscribe({
      next: () => {
        this.snapshotLoading.set(false);
        this.snapshotError.set(false);
        this.snapshotMsg.set(this.t.translate('project.msg.snapshotCreated'));
        // The KPI are read again after the snapshot: the EV % just typed feeds the earned
        // value figures, so without this second call the tiles would still show the old
        // values until the user reloads the page.
        this.svc.getLiveKpi(this.projectId).subscribe(k => this.kpi.set(k));
      },
      error: (e: { status: number; error?: { detail?: string } }) => {
        this.snapshotLoading.set(false);
        this.snapshotError.set(true);
        this.snapshotMsg.set(e.status === 409
          ? this.t.translate('project.msg.snapshotExists')
          : (e.error?.detail ?? this.t.translate('project.msg.snapshotError')));
      }
    });
  }

  /**
   * Archives the project: PATCH /api/projects/{id}/archive, guarded by EDIT_PROJECT on the
   * server.
   *
   * Archiving is NOT deleting. The project leaves the daily lists and keeps all its data,
   * its KPI history and its quote, because a closed project is still needed for the figures
   * of the year.
   * The method is async only so that it can 'await' the shared confirm modal: ask() hands
   * back a promise that settles when the user clicks Yes or No, and the code stops there
   * without freezing the page. window.confirm() would freeze the tab, could not be
   * translated and could not be tested.
   * The server answer replaces the project in the signal, so the top bar swaps to the
   * Unarchive button on its own.
   */
  async archiveProject(): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.msg.archiveConfirm'))) return;
    this.svc.archive(this.projectId).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.archived')); },
      error: e => this.toast.error(e.error?.detail ?? 'Erreur lors de l\'archivage.')
    });
  }

  /**
   * Brings the project back into the active lists: PATCH /api/projects/{id}/unarchive.
   * No confirmation is asked here, on purpose: bringing a project back breaks nothing and
   * the user can archive it again in one click.
   */
  unarchiveProject(): void {
    this.svc.unarchive(this.projectId).subscribe(p => {
      this.project.set(p);
      this.toast.success(this.t.translate('project.msg.unarchived'));
    });
  }

  // ── Status management ────────────────────────────────────────────
  /**
   * The statuses this project may move to right now. Used twice by the template: to decide
   * whether the badge becomes a button, and to fill the little menu.
   * The '?? []' is a safety net: if the project ever comes back with a status this map does
   * not know, the screen shows no move instead of crashing on undefined.
   */
  allowedTransitions(status: ProjectStatus): ProjectStatus[] {
    return this.TRANSITIONS[status] ?? [];
  }

  /**
   * Moves the project to another status: PATCH /api/projects/{id}/status?status=...,
   * guarded by EDIT_PROJECT on the server, which also refuses a move the workflow forbids.
   *
   * The menu is closed first, so it does not stay open behind the confirmation window.
   * The translated label is computed once and reused in the question and in the success
   * message, which keeps the two sentences in step.
   * The error branch reads detail, then message, then a translated fallback: different
   * layers of the server fill different fields, and the user must never be shown a blank
   * message.
   */
  async changeStatus(target: ProjectStatus): Promise<void> {
    this.statusMenuOpen.set(false);
    const label = this.t.translate('status.' + target);
    if (!await this.confirm.ask(
      this.t.translate('project.msg.statusChangeConfirm', { status: label }),
      this.t.translate('project.msg.statusChangeTitle'))) return;
    this.svc.changeStatus(this.projectId, target).subscribe({
      next: p => { this.project.set(p); this.toast.success(this.t.translate('project.msg.statusUpdated', { status: label })); },
      error: e => this.toast.error(e.error?.detail ?? e.error?.message
        ?? this.t.translate('project.msg.statusChangeError'))
    });
  }

  /**
   * Opens a tab. Three jobs in one method: show it, write it in the URL, and fetch its data
   * the first time only.
   *
   * This is the heart of the screen. Without the loadedTabs guard at the end, every click
   * on a tab would fire its HTTP calls again - going back and forth between team and
   * billing four times would be sixteen requests for data that has not changed.
   * The price to pay is known and accepted: the data of a tab is NOT refreshed while the
   * user stays on the screen. It is fine here because this is a reading screen; the one
   * place where it would hurt, the team list, is refreshed by hand after an add or a remove.
   */
  setTab(t: Tab): void {
    this.tab.set(t);
    // Writes ?tab=... in the address without leaving the screen.
    // relativeTo keeps the current route, so the id in the URL is untouched.
    // replaceUrl: true replaces the current entry in the browser history instead of adding
    // one. Without it, a user who looked at five tabs would have to press Back five times
    // to return to the list.
    // The info tab sends {} so the address stays the clean /projects/12.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: t === 'info' ? {} : { tab: t },
      replaceUrl: true,
    });
    // Already fetched once: nothing more to do.
    if (this.loadedTabs.has(t)) return;
    // Marked BEFORE the calls are fired. If it were marked in the answer, two fast clicks
    // on the same tab would start the same requests twice.
    this.loadedTabs.add(t);

    // Each tab asks its own service. The calls of one tab are fired side by side, not one
    // after the other: they do not depend on each other, so waiting would only be slower.
    switch (t) {
      case 'equipe':
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        break;
      case 'charges':
        // This screen only gives an overview, so the whole list is asked in ONE very wide
        // page (page 0, size 1000) and then paged in the browser by pagedPlanCharges above.
        // The dedicated workload screen is the one that pages on the server side.
        // 'd.content' is the rows: this endpoint answers the Spring envelope
        // { content, totalElements, ... }, not a plain array. Reading d as an array would
        // give undefined and an empty table with no error anywhere.
        // The known limit: a project with more than 1000 workload lines would not show the
        // last ones here.
        this.workloadSvc.listPlanCharges(this.projectId, 0, 1000).subscribe(d => this.planCharges.set(d.content));
        this.workloadSvc.listChargesReelles(this.projectId, 0, 1000).subscribe(d => this.chargesReelles.set(d.content));
        break;
      case 'facturation':
        this.billingSvc.listJalons(this.projectId).subscribe(d => this.jalons.set(d));
        this.billingSvc.listAvenants(this.projectId).subscribe(d => this.avenants.set(d));
        break;
      case 'missions':
        this.missionSvc.list(this.projectId).subscribe(d => this.missions.set(d));
        break;
      case 'gouvernance':
        this.govSvc.listRisks(this.projectId).subscribe(d => this.risks.set(d));
        this.govSvc.listLivrables(this.projectId).subscribe(d => this.livrables.set(d));
        this.govSvc.listChanges(this.projectId).subscribe(d => this.changes.set(d));
        break;
    }
  }

  /**
   * Adds up the amounts of the billing milestones for the header of the table.
   * reduce() walks the array and keeps a running total, starting at 0. The starting 0
   * matters: without it reduce() throws on an empty list.
   * This is a display total; the figure that counts for the accounts comes from the server.
   */
  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  /**
   * Turns the month number stored in the database (1 to 12) into a short month name in the
   * language of the user.
   * Intl gives the month in the current language; a fixed array written in the code would
   * stay French for an English user.
   * The guard on the range keeps a wrong value readable instead of showing the month of a
   * date that JavaScript would have shifted into the next year.
   */
  monthLabel(m: number): string {
    if (m < 1 || m > 12) return String(m);
    // new Date(2000, m - 1, 1): the month of a JavaScript Date counts from 0, so January is
    // 0. The year 2000 and the day 1 are only there to build a valid date; nothing but the
    // month is shown.
    return new Intl.DateTimeFormat(this.locale(), { month: 'short' }).format(new Date(2000, m - 1, 1));
  }


  /**
   * Gives the CSS class that paints a status badge.
   *
   * The four badge helpers below all work the same way: a small map from the code of the
   * server to a class name, and a default value with '??' so an unknown code still gets a
   * neutral badge instead of an element with no class at all.
   * Why the colour is chosen here and not in the template: a long chain of tests inside the
   * HTML would be repeated in every table that shows a status.
   */
  badge(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }

  /** Colour of a risk level: high is red, medium is orange, anything else is green. */
  niveauBadge(n: string): string {
    return n === 'ELEVE' ? 'badge-cancelled' : n === 'MOYEN' ? 'badge-on-hold' : 'badge-active';
  }

  /** Colour of a deliverable state: waiting, in progress, delivered, accepted. */
  livrableBadge(s: string): string {
    const m: Record<string, string> = {
      EN_ATTENTE: 'badge-draft', EN_COURS: 'badge-active',
      LIVRE: 'badge-completed', VALIDE: 'badge-active'
    };
    return m[s] ?? 'badge-draft';
  }

  /** Colour of the priority of a change request, from low to critical. */
  prioriteBadge(p: string): string {
    const m: Record<string, string> = {
      FAIBLE: 'badge-active', NORMALE: 'badge-draft',
      ELEVEE: 'badge-on-hold', CRITIQUE: 'badge-cancelled'
    };
    return m[p] ?? 'badge-draft';
  }

  // ── Project manager (B7) ─────────────────────────────────────────
  /**
   * Fetches the list of assignable people, but only the first time a window is opened.
   * GET /api/users/assignable.
   * Why the guard: both windows call this method, and the user can open them several times.
   * Without it, every opening would fetch the same list of people again.
   */
  private ensureUsersLoaded(): void {
    if (this.allUsers().length) return;
    // /users/assignable: on the server it accepts ASSIGN_CHEF_PROJET, ASSIGN_DEVELOPER or
    // MANAGE_USERS. It deliberately does NOT require MANAGE_USERS alone, because whoever
    // may put somebody on a project must be able to read the list of people without being
    // an administrator of the accounts.
    this.svc.listAssignableUsers().subscribe(u => this.allUsers.set(u));
  }

  /**
   * The people offered as project manager.
   *
   * It keeps those whose role is CHEF_PROJET, and falls back to the whole list when there
   * is none. Careful with how to explain this to the jury: it is a comfort filter for the
   * list, NOT a security rule - the roles are dynamic in this application, so a company may
   * name its managers differently, and hiding everybody would leave a window with no
   * candidate at all. Who is really allowed to be assigned is decided by the server.
   */
  chefCandidates(): { id: number; firstName: string; lastName: string; roleName: string }[] {
    const chefs = this.allUsers().filter(u => u.roleName === 'CHEF_PROJET');
    return chefs.length ? chefs : this.allUsers();
  }

  /**
   * Opens the "assign a manager" window, in a clean state: the current manager is
   * preselected, the search box and the error are emptied.
   * Without that reset, reopening the window would still show the search text and the error
   * of the previous time.
   */
  openChefModal(): void {
    this.ensureUsersLoaded();
    this.chefUserId = this.project()?.chefProjetId ?? 0;
    this.chefSearch.set('');
    this.modalError.set('');
    this.showChefModal.set(true);
  }

  /**
   * Saves the chosen manager: PATCH /api/projects/{id}/assign-chef?userId=...
   *
   * On the server this needs its own permission, ASSIGN_CHEF_PROJET, which is NOT
   * EDIT_PROJECT: choosing who leads a project is a management decision, while correcting a
   * line of its sheet is day-to-day work.
   * The first line is a check of the form, not a security check: it only avoids sending a
   * request that would come back as an error.
   * The answer of the server replaces the project in the signal, so the name on the sheet
   * changes without reloading anything.
   * Both branches put saving back to false; if the error branch forgot it, the Assign
   * button would stay greyed out for ever after one failure.
   */
  saveChef(): void {
    if (!this.chefUserId) { this.modalError.set(this.t.translate('project.manager.required')); return; }
    this.saving.set(true);
    this.svc.assignChef(this.projectId, this.chefUserId).subscribe({
      next: (p) => { this.project.set(p); this.showChefModal.set(false); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Team management (B8) ─────────────────────────────────────────
  /**
   * Opens the "add a member" window in a clean state: an empty form with today as the start
   * date, no search text, no role filter and no error.
   * The whole teamForm object is rebuilt rather than emptied field by field, so no value of
   * the previous opening can survive.
   */
  openTeamModal(): void {
    this.ensureUsersLoaded();
    this.teamForm = { userId: 0, roleInTeam: '', startDate: new Date().toISOString().split('T')[0] };
    this.memberSearch.set('');
    this.memberRoleFilter.set('');
    this.modalError.set('');
    this.showTeamModal.set(true);
  }

  /**
   * Puts the chosen person on the project: POST /api/projects/{id}/team, guarded on the
   * server by ASSIGN_DEVELOPER plus the project scope (ADR-021). Without that scope check,
   * holding the permission would be enough to add oneself to any project of the company -
   * and being on the team is what opens the rest of its data.
   *
   * The three tests at the top are the form check. trim() on the role refuses a value made
   * only of spaces, which would otherwise be saved as a role nobody can read.
   * After the save the team list is fetched again instead of pushing the new row into the
   * signal by hand: the row from the server carries its own id and the full name, and a row
   * built in the browser would have neither, so the remove button on it would not work.
   */
  saveTeamMember(): void {
    if (!this.teamForm.userId || !this.teamForm.roleInTeam.trim() || !this.teamForm.startDate) {
      this.modalError.set(this.t.translate('project.msg.teamFormRequired'));
      return;
    }
    this.saving.set(true);
    this.teamSvc.assign(this.projectId, this.teamForm).subscribe({
      next: () => {
        this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d));
        this.showTeamModal.set(false); this.saving.set(false);
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /**
   * Takes somebody off the project: DELETE /api/projects/{id}/team/{assignmentId}.
   *
   * Note what is sent: m.id, the id of the ASSIGNMENT row, not the id of the user. The row
   * is the thing being removed, and it is the only value that is never ambiguous when the
   * same person has an old closed assignment and a new one.
   * The confirmation carries the name of the person, so the user sees WHO he is about to
   * remove. The team is then read again from the server, for the same reason as above.
   */
  async removeMember(m: TeamAssignment): Promise<void> {
    if (!await this.confirm.ask(this.t.translate('project.team.removeConfirm', { name: m.userFullName }))) return;
    this.teamSvc.remove(this.projectId, m.id).subscribe(() =>
      this.teamSvc.list(this.projectId).subscribe(d => this.team.set(d))
    );
  }
}

/**
 * Cuts one page out of an array, with the page number kept inside the real range.
 *
 * 'Math.min' prevents an empty page when the list shrinks (a filter, a reload) while the
 * current page index still points past the end.
 * Concrete example: the user is on page 4 of the workload, somebody deletes rows and the
 * list now has only two pages. Without the clamp, slice() would return nothing and the
 * table would look empty although the data is there.
 * The <T> makes the function generic: it works for planned rows and for declared rows
 * without being written twice, and TypeScript still knows the exact type that comes out.
 * It sits outside the class because it uses nothing of the component.
 */
function slicePage<T>(rows: T[], page: number, size: number): T[] {
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const p = Math.min(page, pages - 1);
  return rows.slice(p * size, p * size + size);
}
