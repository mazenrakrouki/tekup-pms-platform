import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';

import { AgileService } from '../../core/services/agile.service';
import { ProjectService } from '../../core/services/project.service';
import { TeamService } from '../../core/services/team.service';
import { TeamAssignment } from '../../core/models/team.model';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';
import {
  BACKLOG_PRIORITIES, BACKLOG_STATUSES, SPRINT_STATUSES,
  BacklogItem, BacklogItemStatus, BacklogPriority, Sprint, SprintStatus
} from '../../core/models/agile.model';

/*
 * ============================================================================
 * WHAT THIS FILE IS
 * One standalone Angular component that draws the whole "Sprints & Backlog"
 * screen: the project picker, the rail of chips (product backlog + one chip per
 * sprint), the sprint header with its progress bar, the three-column board
 * (TODO / IN PROGRESS / DONE), the flat product-backlog list, and the two edit
 * dialogs (one for a sprint, one for a backlog item).
 *
 * WHERE IT SITS IN THE FLOW
 *   Who calls it: the Angular router. In app.routes.ts the path 'agile' is
 *   guarded by permissionGuard with data { permission: 'VIEW_AGILE' } and the
 *   component is lazy-loaded. So this file is only downloaded and created for a
 *   user who already holds VIEW_AGILE.
 *   What it calls next:
 *     - AgileService     sprints and backlog items over HTTP
 *                        (/api/projects/{projectId}/sprints and .../backlog)
 *     - TeamService      the project team, the only people an item may be
 *                        assigned to (/api/projects/{projectId}/team)
 *     - ProjectService   re-loads the project named in the ?p= query parameter
 *     - AuthService      reads the permission list of the logged-in user
 *     - ConfirmService   the "are you sure?" dialog shown before a delete
 *     - ToastService     the small success / error messages
 *     - TranslocoService the texts, in French or in English
 *   On the server side those URLs land on SprintController and
 *   BacklogItemController.
 *
 * WHY IT EXISTS
 * It is the only screen where iterations and backlog items are created,
 * estimated, assigned, moved between columns and deleted. Delete this file and
 * the agile module still exists in the database and in the REST API, but nobody
 * in the company can use it: there is no other way in.
 *
 * SECURITY, AND THIS MATTERS FOR THE DEFENCE
 * Everything this file does about rights is cosmetic. The getter canManage only
 * decides whether a button is drawn. The real decision is taken on the server:
 *   - permission: @PreAuthorize("hasAuthority('MANAGE_AGILE')") sits on the
 *     methods of SprintService and BacklogItemService. The code tests a
 *     permission CODE, never a role name.
 *   - scope (ADR-021): every URL used here has the shape
 *     /api/projects/{id}/... , so ProjectScopeInterceptor also checks that this
 *     user is allowed on THAT project. Holding MANAGE_AGILE is not enough by
 *     itself.
 * Concretely: if somebody brings the hidden buttons back with the browser
 * developer tools and fires the request by hand, the answer is still HTTP 403.
 * ============================================================================
 */

/*
 * Which of the two views the middle of the screen shows.
 *   null     -> the product backlog, drawn as a flat ordered list
 *   a number -> the sprint carrying that id, drawn as a three-column board
 * WHY one union type and not an enum, or two separate variables: the value is
 * also the sprint id the items are filtered with, so a single variable carries
 * both "which view" and "which sprint". Two variables could disagree with each
 * other and the screen would show a board with no sprint behind it.
 */
type View = number | null;

/*
 * @Component turns the class below into an Angular component: a piece of screen made of
 * one HTML template, its own CSS, and the TypeScript that drives them.
 * Why the decorator and not a plain class: without it Angular has no idea this class draws
 * anything, so <app-agile> in the router outlet would stay an unknown empty tag.
 */
@Component({
  // The HTML tag name Angular answers to. The router creates <app-agile> when the user
  // opens /agile.
  selector: 'app-agile',
  // standalone: true means this component declares its own dependencies just below, in
  // `imports`, instead of being listed in an NgModule.
  // Why: it is what makes the component lazy-loadable on its own. Without it we would need
  // an extra NgModule file whose only job is to declare this one component.
  standalone: true,
  // Everything the template is allowed to use. Angular refuses anything not listed here.
  //   CommonModule            -> pipes such as the ones used in the template
  //   FormsModule             -> [(ngModel)] in the two dialogs
  //   ProjectPickerComponent  -> the <app-project-picker> tag
  //   TranslocoModule         -> the "| transloco" pipe that prints the texts
  // Why it matters: forget TranslocoModule and every {{ 'agile.title' | transloco }} in the
  // template fails to compile with "no pipe found named transloco".
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  // provideTranslocoScope('agile') tells Transloco to also load assets/i18n/agile/fr.json
  // (and en.json) when this component is created, on top of the global dictionary.
  // Why a scope and not one huge global file: the texts of this screen are only downloaded
  // by somebody who actually opens this screen. Without the scope, every key below would
  // be missing and the page would print the raw keys, e.g. "agile.title", to the user.
  providers: [provideTranslocoScope('agile')],
  // styles: [`...`] holds the CSS of this component only. Angular adds a private attribute
  // to these elements, so a rule like .chip here cannot leak out and repaint a .chip on
  // another screen.
  // CAREFUL: inside this block only /* */ comments are legal. A // would be sent to the
  // browser as broken CSS and the rule after it would be dropped.
  // The var(--...) values come from the global styles.scss design tokens, which is what
  // makes this screen follow the light and dark themes without any extra code here.
  styles: [`
    /* ── Selection rail: the row of chips at the top of the card ── */
    .rail { display:flex; align-items:center; gap:.4rem; flex-wrap:wrap;
            padding:.7rem 1rem; border-bottom:1px solid var(--border); }
    .chip { display:inline-flex; align-items:center; gap:.4rem;
            border:1px solid var(--border); background:var(--surface); color:var(--text-2);
            border-radius:var(--r-full); padding:.32rem .8rem; font-size:12.5px;
            cursor:pointer; white-space:nowrap;
            transition:background var(--t), color var(--t), border-color var(--t); }
    .chip:hover { color:var(--text-1); border-color:var(--border-2); }
    .chip:focus-visible { outline:2px solid var(--c-brand); outline-offset:2px; }
    .chip.is-active { background:var(--c-brand); border-color:var(--c-brand); color:#fff; }
    .chip.is-active .chip-count { background:rgba(255,255,255,.22); color:#fff; }
    .chip-count { background:var(--surface-3); color:var(--text-2); border-radius:var(--r-full);
                  padding:0 .4rem; font-size:11px; font-variant-numeric:tabular-nums; }
    .chip .dot { width:6px; height:6px; border-radius:50%; flex:none; }
    .dot-PLANNED { background:var(--text-3); }
    .dot-ACTIVE  { background:var(--c-success); }
    .dot-CLOSED  { background:var(--text-3); opacity:.45; }

    /* ── Sprint header + progress bar ──────────────────────────── */
    .sprint-head { padding:1rem; display:flex; flex-direction:column; gap:.85rem; }
    .sh-top { display:flex; align-items:flex-start; gap:.75rem; flex-wrap:wrap; }
    .sh-name { font-size:15px; font-weight:600; color:var(--text-1); }
    .sh-goal { font-size:13px; color:var(--text-2); margin:.15rem 0 0; max-width:60ch; }
    .sh-meta { font-size:12px; color:var(--text-3); font-variant-numeric:tabular-nums; }
    .pill { border-radius:var(--r-full); padding:.12rem .55rem; font-size:11px; font-weight:600; }
    .pill-PLANNED { background:var(--surface-3);      color:var(--text-2); }
    .pill-ACTIVE  { background:var(--c-success-dim);  color:var(--c-success); }
    .pill-CLOSED  { background:var(--surface-3);      color:var(--text-3); }

    /* The bar is also the legend: it uses the very same colours as the three columns.
       Why: the user reads one colour in the bar and finds the same colour on the board,
       with no key to learn. Using different colours here would force a second reading. */
    .bar { display:flex; height:8px; border-radius:var(--r-full); overflow:hidden;
           background:var(--surface-3); }
    .bar span { display:block; transition:width var(--t-slow); }
    .seg-DONE        { background:var(--c-success); }
    .seg-IN_PROGRESS { background:var(--c-brand); }
    .seg-TODO        { background:var(--border-2); }
    .legend { display:flex; gap:1rem; flex-wrap:wrap; font-size:11.5px; color:var(--text-2); }
    /* The same little square is used in the legend and in the column header, so it is
       described once. Two separate rules would drift apart the day a colour changes. */
    .swatch { width:8px; height:8px; border-radius:2px; display:inline-block; flex:none; }
    .legend .swatch { margin-right:.35rem; }

    /* ── The board (three columns) ─────────────────────────────── */
    /* minmax(0,1fr) instead of plain 1fr: a grid column will not shrink below the widest
       word inside it unless the minimum is forced to 0. Without minmax(0,...) one long
       card title would push the third column off the screen and add a sideways scrollbar. */
    .board { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.85rem; padding:1rem; }
    /* Under 900px the three columns are stacked one under the other.
       Why: on a phone three columns leave about 100px each, and a card title becomes
       unreadable, one or two letters per line. */
    @media (max-width:900px) { .board { grid-template-columns:1fr; } }
    .col { background:var(--surface-2); border:1px solid var(--border);
           border-radius:var(--r-md); display:flex; flex-direction:column;
           transition:background var(--t), border-color var(--t); }
    .col.is-over { border-color:var(--c-brand); background:var(--c-brand-dim); }
    .col-head { display:flex; align-items:center; gap:.5rem; padding:.55rem .75rem;
                border-bottom:1px solid var(--border); font-size:11px; font-weight:600;
                text-transform:uppercase; letter-spacing:.07em; color:var(--text-2); }
    .col-head .n { margin-left:auto; font-variant-numeric:tabular-nums; color:var(--text-3); }
    .col-body { padding:.6rem; display:flex; flex-direction:column; gap:.5rem; min-height:72px; }

    .ticket { background:var(--surface); border:1px solid var(--border);
              border-left:3px solid var(--border-2);
              border-radius:var(--r-sm); padding:.55rem .65rem; cursor:grab;
              transition:box-shadow var(--t), border-color var(--t), opacity var(--t); }
    .ticket:hover { box-shadow:0 1px 3px rgba(15,23,42,.10); border-color:var(--border-2); }
    .ticket:focus-visible { outline:2px solid var(--c-brand); outline-offset:1px; }
    .ticket.is-dragging { opacity:.4; cursor:grabbing; }
    /* The left border of a card carries its priority. MEDIUM keeps the neutral colour set
       on .ticket above, so it is not repeated here.
       Why not declare .ticket.p-MEDIUM anyway: a rule that says exactly what the default
       already says is one more line to keep in step when the default colour changes. */
    .ticket.p-CRITICAL { border-left-color:var(--c-danger); }
    .ticket.p-HIGH     { border-left-color:var(--c-warning); }
    .ticket.p-LOW      { border-left-color:var(--border); }
    .tk-title { font-size:13px; font-weight:500; color:var(--text-1); line-height:1.35; }

    /* Assignee chip: who is doing the work, readable at a glance on the card. */
    .tk-owner { display:flex; align-items:center; gap:.35rem; margin-top:.4rem; min-width:0; }
    .tk-owner-name { font-size:11px; color:var(--text-2); overflow:hidden;
      text-overflow:ellipsis; white-space:nowrap; }
    .tk-owner--none .tk-owner-name { color:var(--text-3); font-style:italic; }
    .tk-avatar { flex:0 0 auto; width:20px; height:20px; border-radius:50%;
      background:var(--c-brand-dim); color:var(--c-brand);
      font-size:9px; font-weight:700; letter-spacing:.02em;
      display:inline-flex; align-items:center; justify-content:center; }
    .tk-avatar--none { background:var(--surface-2); color:var(--text-3); font-size:11px; }
    .tk-foot { display:flex; align-items:center; gap:.5rem; margin-top:.4rem;
               font-size:11px; color:var(--text-3); font-variant-numeric:tabular-nums; }
    .tk-prio { font-weight:600; }
    .tk-prio.p-CRITICAL { color:var(--c-danger); }
    .tk-prio.p-HIGH     { color:var(--c-warning); }
    /* The small buttons of a card are invisible until the card is hovered or focused, so
       the board stays calm. opacity:0 is used, NOT display:none: the buttons keep their
       place, so the card does not jump when they appear, and they stay reachable by the
       keyboard (:focus-within brings them back for a user who navigates with Tab). */
    .tk-actions { display:flex; gap:.2rem; margin-left:auto; opacity:0; transition:opacity var(--t); }
    .ticket:hover .tk-actions, .ticket:focus-within .tk-actions { opacity:1; }
    /* (hover:none) is true on a touch screen, where there is no mouse pointer and
       therefore no hover at all. Without this rule, a phone user would never see the
       edit, delete and move buttons: they would stay at opacity 0 for ever. */
    @media (hover:none) { .tk-actions { opacity:1; } }
    .icon-btn { border:none; background:none; color:var(--text-3); padding:.1rem .25rem;
                border-radius:var(--r-xs); cursor:pointer; font-size:12px; line-height:1;
                transition:color var(--t), background var(--t); }
    .icon-btn:hover { color:var(--text-1); background:var(--surface-3); }
    .icon-btn:focus-visible { outline:2px solid var(--c-brand); outline-offset:1px; }
    .icon-btn.danger:hover { color:var(--c-danger); }
    .col-empty { text-align:center; padding:1.1rem .5rem; font-size:12px; color:var(--text-3); }

    /* ── Product backlog list (the flat view, when no sprint is picked) ── */
    .bl-row { display:flex; align-items:center; gap:.75rem; padding:.6rem 1rem;
              border-bottom:1px solid var(--border); transition:background var(--t); }
    .bl-row:last-child { border-bottom:none; }
    .bl-row:hover { background:var(--surface-2); }
    .bl-title { font-size:13.5px; color:var(--text-1); font-weight:500; }
    .bl-desc { font-size:12px; color:var(--text-3); margin-top:.1rem; }
    /* tabular-nums makes every digit take the same width.
       Why: counters and percentages change all the time on this screen. Without it, "11%"
       is narrower than "88%" and the whole line shifts left and right while the board
       refreshes, which looks like flickering. */
    .num { font-variant-numeric:tabular-nums; }

    /* prefers-reduced-motion is a system setting a user turns on when movement makes them
       dizzy or sick. Here it switches off every animation of the screen.
       Without it, such a user would still get the progress bar sliding and the cards
       fading on every drag, which is exactly what the setting exists to avoid. */
    @media (prefers-reduced-motion:reduce) {
      .bar span, .col, .ticket, .tk-actions { transition:none; }
    }
  `],
  // The inline template: the HTML of the screen, written here instead of in a separate
  // .html file, which is the convention used everywhere in this project.
  // CAREFUL: inside these backticks only <!-- --> comments are legal. A // or a /* */
  // would simply be printed on the page as text, because this is HTML, not TypeScript.
  // The @if / @for / @empty blocks are Angular's own control flow (Angular 17+). They
  // replace *ngIf and *ngFor: the compiler understands them directly, so a typo is a build
  // error instead of a silently empty page.
  template: `
    <!-- Top bar: the breadcrumb tells the user where he is. When a project is picked it
         also offers the way back to the picker, so the browser Back button is not the
         only escape. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-kanban" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()">
            <i class="bi bi-arrow-left"></i> {{ 'agile.breadcrumb' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <!-- selected()! : the "!" tells the compiler the value is not null here. It is
               true because we are inside @if (selected()). Without the "!" the build fails
               with "object is possibly null", because Angular cannot follow the @if into
               the type system. -->
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'agile.breadcrumb' | transloco }}</span>
        }
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'agile.title' | transloco }}</h1>
      </div>

      <!-- No project picked yet -> the whole screen is the picker. This is the firm UX
           rule of the project: never a giant dropdown of hundreds of projects. The shared
           <app-project-picker> searches and pages through them instead.
           Without this step the board would not know which /api/projects/{id}/... to
           call, so there would be nothing to draw. -->
      @if (!selected()) {
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-kanban"
                            (projectSelected)="select($event)" />
      } @else {

        <div class="card mb-4">
          <!-- The rail: one chip for the product backlog, then one chip per sprint. Chips,
               never a long dropdown, so the user sees every iteration and its count at a
               glance and switches in one click.
               role="tablist" + role="tab" + aria-selected describe the rail to a screen
               reader as a set of tabs. Without those attributes a blind user hears a row
               of plain buttons and never knows which view is the current one. -->
          <div class="rail" role="tablist" [attr.aria-label]="'agile.tabs.sprints' | transloco">
            <button class="chip" role="tab" [class.is-active]="view() === null"
                    [attr.aria-selected]="view() === null" (click)="setView(null)">
              <i class="bi bi-inbox"></i>{{ 'agile.item.unassigned' | transloco }}
              <span class="chip-count">{{ backlogItems().length }}</span>
            </button>
            <!-- track s.id : Angular keeps the DOM node of a sprint tied to its id. Without
                 it Angular tracks by position, so inserting one sprint at the top would
                 make it rebuild every chip and the focus would jump away. -->
            @for (s of sprints(); track s.id) {
              <button class="chip" role="tab" [class.is-active]="view() === s.id"
                      [attr.aria-selected]="view() === s.id" (click)="setView(s.id)">
                <span class="dot" [class]="'dot dot-' + s.status"></span>{{ s.name }}
                <span class="chip-count">{{ itemsOf(s.id).length }}</span>
              </button>
            }
            <!-- canManage only DRAWS or hides these two buttons. It protects nothing: a
                 user without MANAGE_AGILE who puts the buttons back with the browser
                 developer tools still gets HTTP 403, because the check lives on the server
                 service methods and ProjectScopeInterceptor also checks the project
                 (ADR-021). Hiding them is politeness, not security. -->
            @if (canManage) {
              <div class="ms-auto d-flex gap-2">
                <button class="btn btn-outline-secondary btn-sm" (click)="openSprintModal(null)">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'agile.sprint.new' | transloco }}
                </button>
                <button class="btn btn-primary btn-sm" (click)="openItemModal(null)">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'agile.item.new' | transloco }}
                </button>
              </div>
            }
          </div>

          <!-- While the two HTTP calls are in the air we draw grey placeholder blocks
               shaped like the real header, not a spinner.
               Why: the height of the card stays the same, so nothing jumps under the
               mouse when the data lands. A spinner would collapse the card and the user
               would click on the wrong thing. -->
          @if (loading()) {
            <div class="p-4 d-flex flex-column gap-2">
              <div class="skeleton" style="height:14px;width:30%;border-radius:var(--r-xs)"></div>
              <div class="skeleton" style="height:8px;border-radius:var(--r-full)"></div>
            </div>
          <!-- "; as s" stores the result of currentSprint() in a local name s for the whole
               block. Why: currentSprint() is a computed signal read a dozen times below;
               without "as s" we would call it a dozen times and, worse, we would have to
               add "!" everywhere to convince the compiler it is not null. -->
          } @else if (currentSprint(); as s) {
            <!-- Sprint header + progress -->
            <div class="sprint-head">
              <div class="sh-top">
                <div style="flex:1;min-width:200px">
                  <div class="d-flex align-items-center gap-2">
                    <span class="sh-name">{{ s.name }}</span>
                    <span class="pill" [class]="'pill pill-' + s.status">
                      {{ 'agile.status.' + s.status | transloco }}
                    </span>
                  </div>
                  @if (s.goal) { <p class="sh-goal">{{ s.goal }}</p> }
                  @if (s.startDate || s.endDate) {
                    <div class="sh-meta mt-1">
                      <!-- ?? '—' is the "null coalescing" operator: it prints the dash when
                           the date is null or undefined. Without it an unset date would be
                           printed as the empty string and the line would read
                           " → 2026-07-30", which looks like a bug to the user. -->
                      <i class="bi bi-calendar3 me-1"></i>{{ s.startDate ?? '—' }} → {{ s.endDate ?? '—' }}
                    </div>
                  }
                </div>
                @if (canManage) {
                  <div class="d-flex gap-1">
                    <button class="btn btn-ghost btn-sm" (click)="openSprintModal(s)"
                            [attr.aria-label]="'agile.sprint.edit' | transloco"><i class="bi bi-pencil"></i></button>
                    <button class="btn btn-ghost btn-sm" (click)="removeSprint(s)"
                            [attr.aria-label]="'agile.sprint.delete' | transloco"
                            style="color:var(--c-danger)"><i class="bi bi-trash"></i></button>
                  </div>
                }
              </div>

              <div>
                <div class="d-flex justify-content-between align-items-baseline mb-1">
                  <span class="sh-meta">{{ progressLabel() }}</span>
                  <span class="sh-meta num">{{ donePercent() }}%</span>
                </div>
                <!-- role="progressbar" plus aria-valuenow / valuemin / valuemax turn these
                     three coloured <span> into a real progress bar for a screen reader,
                     which then announces "45 percent". Without them a blind user hears
                     nothing at all: colour alone carries no information. -->
                <div class="bar" role="progressbar"
                     [attr.aria-valuenow]="donePercent()" aria-valuemin="0" aria-valuemax="100"
                     [attr.aria-label]="progressLabel()">
                  <!-- [style.width.%] sets the CSS width in percent. The three segments are
                       laid out side by side and always add up to 100, so the bar shows the
                       share of DONE, IN_PROGRESS and TODO in one single line.
                       Order matters: DONE first, so the finished part grows from the left,
                       which is how people read a progress bar. -->
                  <span class="seg-DONE"        [style.width.%]="pct('DONE')"></span>
                  <span class="seg-IN_PROGRESS" [style.width.%]="pct('IN_PROGRESS')"></span>
                  <span class="seg-TODO"        [style.width.%]="pct('TODO')"></span>
                </div>
                <div class="legend mt-2">
                  @for (c of columns; track c) {
                    <span><i [class]="'swatch seg-' + c"></i>{{ 'agile.column.' + c | transloco }}</span>
                  }
                </div>
              </div>
            </div>
          }
        </div>

        @if (!loading()) {
          @if (view() === null) {
            <!-- Product backlog: a plain ordered list, NOT a board. These items are not in
                 a workflow yet. Forcing them into TODO / IN PROGRESS / DONE columns would
                 tell the reader that work has started on them, which is false. -->
            <div class="card">
              <div class="card-header">
                <span>{{ 'agile.item.unassigned' | transloco }}</span>
                <span class="ms-auto text-muted small num">{{ backlogItems().length }}</span>
              </div>
              @for (it of backlogItems(); track it.id) {
                <div class="bl-row">
                  <span class="tk-prio" [class]="'tk-prio p-' + it.priority">
                    {{ 'agile.priority.' + it.priority | transloco }}
                  </span>
                  <div style="flex:1;min-width:0">
                    <div class="bl-title">{{ it.title }}</div>
                    @if (it.description) { <div class="bl-desc text-truncate">{{ it.description }}</div> }
                  </div>
                  @if (it.assigneeName) {
                    <span class="tk-avatar" [title]="it.assigneeName">{{ initials(it.assigneeName) }}</span>
                  }
                  @if (it.estimateDays) {
                    <span class="text-muted small num">{{ it.estimateDays }} {{ 'agile.unit.days' | transloco }}</span>
                  }
                  @if (canManage) {
                    <button class="icon-btn" (click)="openItemModal(it)"
                            [attr.aria-label]="'agile.item.edit' | transloco"><i class="bi bi-pencil"></i></button>
                    <button class="icon-btn danger" (click)="removeItem(it)"
                            [attr.aria-label]="'agile.item.delete' | transloco"><i class="bi bi-trash"></i></button>
                  }
                </div>
              <!-- @empty runs when the list above produced nothing. It is part of the @for
                   block, so there is no second "if list is empty" test to keep in step.
                   Without it the card would simply be blank and the user could not tell an
                   empty backlog from a screen that failed to load. -->
              } @empty {
                <div class="empty-state">
                  <div class="es-icon"><i class="bi bi-inbox"></i></div>
                  <div class="es-title">{{ 'agile.empty.noItemTitle' | transloco }}</div>
                  <div class="es-desc">{{ 'agile.empty.noItemDesc' | transloco }}</div>
                </div>
              }
            </div>
          } @else if (currentSprint()) {
            <!-- The sprint board -->
            <div class="card">
              <div class="board">
                <!-- One column per entry of "columns", which is BACKLOG_STATUSES from the
                     model file: TODO, IN_PROGRESS, DONE, in that order. The board is not
                     hard-coded in the HTML, so the day a status is added the column appears
                     by itself. -->
                @for (col of columns; track col) {
                  <!-- The three drag events are what makes a column a drop target:
                       (dragover) must call preventDefault, otherwise the browser refuses
                       the drop; (dragleave) clears the highlight when the pointer goes out;
                       (drop) actually moves the card. Remove (dragover) and dropping a card
                       does nothing at all, with no error message. -->
                  <div class="col" [class.is-over]="dragOver() === col"
                       (dragover)="onDragOver($event, col)"
                       (dragleave)="dragOver.set(null)"
                       (drop)="onDrop($event, col)">
                    <div class="col-head">
                      <span class="swatch" [class]="'swatch seg-' + col"></span>
                      {{ 'agile.column.' + col | transloco }}
                      <span class="n">{{ itemsIn(col).length }}</span>
                    </div>
                    <div class="col-body">
                      @for (it of itemsIn(col); track it.id) {
                        <!-- [attr.draggable]="canManage": the card can only be grabbed by a
                             user who may manage the agile module. A read-only user can
                             still read the board but the cards do not move under his mouse,
                             which avoids a drag that would end in a 403.
                             dragging()?.id : the "?." stops the read when dragging() is
                             null (nothing is being dragged). Without it, reading .id of
                             null would throw on every single card, every refresh.
                             tabindex="0" puts the card in the Tab order, which is what
                             makes :focus-within reveal the action buttons for a keyboard
                             user. -->
                        <div class="ticket" [class]="'ticket p-' + it.priority"
                             [class.is-dragging]="dragging()?.id === it.id"
                             [attr.draggable]="canManage"
                             (dragstart)="onDragStart(it)" (dragend)="onDragEnd()"
                             tabindex="0">
                          <div class="tk-title">{{ it.title }}</div>
                          @if (it.assigneeName) {
                            <div class="tk-owner" [title]="it.assigneeName">
                              <span class="tk-avatar">{{ initials(it.assigneeName) }}</span>
                              <span class="tk-owner-name">{{ it.assigneeName }}</span>
                            </div>
                          } @else {
                            <div class="tk-owner tk-owner--none">
                              <span class="tk-avatar tk-avatar--none"><i class="bi bi-person"></i></span>
                              <span class="tk-owner-name">{{ 'agile.item.unassigned2' | transloco }}</span>
                            </div>
                          }
                          <div class="tk-foot">
                            <span class="tk-prio" [class]="'tk-prio p-' + it.priority">
                              {{ 'agile.priority.' + it.priority | transloco }}
                            </span>
                            @if (it.estimateDays) {
                              <span class="num">{{ it.estimateDays }} {{ 'agile.unit.days' | transloco }}</span>
                            }
                            @if (canManage) {
                              <span class="tk-actions">
                                <!-- Native drag and drop cannot be used with a keyboard.
                                     These arrow buttons are the accessible path to the same
                                     action, not a duplicate of it. Remove them and a user
                                     who cannot use a mouse can never move a card. -->
                                @for (target of columns; track target) {
                                  <!-- Skip the column the card is already in: a button
                                       "move to TODO" on a card that sits in TODO would do
                                       nothing and only add noise. -->
                                  @if (target !== col) {
                                    <button class="icon-btn" (click)="move(it, target)"
                                            [attr.aria-label]="'agile.actions.moveTo' | transloco: { column: ('agile.column.' + target | transloco) }"
                                            [title]="'agile.actions.moveTo' | transloco: { column: ('agile.column.' + target | transloco) }">
                                      <!-- The arrow points the way the card will travel on
                                           the board: left when the target column is placed
                                           before this one, right otherwise. Two fixed
                                           arrows would lie half of the time. -->
                                      <i class="bi" [class.bi-arrow-left]="isBefore(target, col)"
                                                    [class.bi-arrow-right]="!isBefore(target, col)"></i>
                                    </button>
                                  }
                                }
                                <button class="icon-btn" (click)="openItemModal(it)"
                                        [attr.aria-label]="'agile.item.edit' | transloco"><i class="bi bi-pencil"></i></button>
                                <button class="icon-btn danger" (click)="removeItem(it)"
                                        [attr.aria-label]="'agile.item.delete' | transloco"><i class="bi bi-trash"></i></button>
                              </span>
                            }
                          </div>
                        </div>
                      } @empty {
                        <div class="col-empty">{{ 'agile.empty.emptyColumn' | transloco }}</div>
                      }
                    </div>
                  </div>
                }
              </div>
            </div>
          } @else {
            <div class="card">
              <div class="empty-state">
                <div class="es-icon"><i class="bi bi-calendar-range"></i></div>
                <div class="es-title">{{ 'agile.empty.noSprintTitle' | transloco }}</div>
                <div class="es-desc">{{ 'agile.empty.noSprintDesc' | transloco }}</div>
                @if (canManage) {
                  <button class="btn btn-primary btn-sm mt-3" (click)="openSprintModal(null)">
                    <i class="bi bi-plus-lg me-1"></i>{{ 'agile.sprint.new' | transloco }}
                  </button>
                }
              </div>
            </div>
          }
        }
      }
    </div>

    <!-- Sprint dialog: create or edit one iteration. It is written here by hand rather than
         with the Bootstrap JavaScript plugin, so that opening it is just a signal going to
         true and nothing outside Angular touches the DOM. -->
    @if (sprintModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Click anywhere on the grey area, or press Escape, and the dialog closes. These
           are the two exits users expect; without them the only way out would be the small
           cross at the top right. -->
      <div class="modal d-block" tabindex="-1" (click)="sprintModal.set(false)" (keydown.escape)="sprintModal.set(false)">
        <!-- stopPropagation keeps a click INSIDE the white box from bubbling up to the
             (click) above. Without it, clicking a text field or the Save button would be
             read as "click outside" and would close the dialog, losing what was typed. -->
        <div class="modal-dialog modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <!-- One dialog serves both cases. An id already present means we are editing
                   an existing sprint, so the title changes accordingly. Two separate
                   dialogs would mean keeping the same fields in step in two places. -->
              <h5 class="modal-title">{{ (sprintForm.id ? 'agile.sprint.edit' : 'agile.sprint.new') | transloco }}</h5>
              <button type="button" class="btn-close" (click)="sprintModal.set(false)"
                      [attr.aria-label]="'agile.actions.close' | transloco"></button>
            </div>
            <div class="modal-body">
              <label class="form-label" for="sp-name">{{ 'agile.sprint.name' | transloco }}</label>
              <!-- [(ngModel)] binds the field both ways: typing writes into sprintForm.name
                   and changing sprintForm.name in the code repaints the field.
                   maxlength="100" matches the column length on the server. Without it the
                   user could type 300 characters, press Save, and get a 400 error he
                   cannot understand, after losing nothing but his time. -->
              <input id="sp-name" class="form-control mb-3" [(ngModel)]="sprintForm.name" maxlength="100">
              <label class="form-label" for="sp-goal">{{ 'agile.sprint.goal' | transloco }}</label>
              <textarea id="sp-goal" class="form-control mb-3" rows="2" [(ngModel)]="sprintForm.goal" maxlength="1000"></textarea>
              <div class="row g-2 mb-3">
                <div class="col">
                  <label class="form-label" for="sp-start">{{ 'agile.sprint.startDate' | transloco }}</label>
                  <input id="sp-start" type="date" class="form-control" [(ngModel)]="sprintForm.startDate">
                </div>
                <div class="col">
                  <label class="form-label" for="sp-end">{{ 'agile.sprint.endDate' | transloco }}</label>
                  <input id="sp-end" type="date" class="form-control" [(ngModel)]="sprintForm.endDate">
                </div>
              </div>
              <label class="form-label" for="sp-status">{{ 'agile.sprint.status' | transloco }}</label>
              <select id="sp-status" class="form-select" [(ngModel)]="sprintForm.status">
                @for (s of sprintStatuses; track s) {
                  <option [value]="s">{{ 'agile.status.' + s | transloco }}</option>
                }
              </select>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="sprintModal.set(false)">{{ 'agile.actions.cancel' | transloco }}</button>
              <!-- [disabled]="saving()" blocks the button while the request is running.
                   Without it an impatient user clicking three times would create three
                   sprints with the same name. -->
              <button class="btn btn-primary" (click)="saveSprint()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'agile.actions.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Backlog item dialog: title, description, priority, estimate, column, sprint and
         assignee. Same construction as the sprint dialog above. -->
    @if (itemModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="itemModal.set(false)" (keydown.escape)="itemModal.set(false)">
        <div class="modal-dialog modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ (itemForm.id ? 'agile.item.edit' : 'agile.item.new') | transloco }}</h5>
              <button type="button" class="btn-close" (click)="itemModal.set(false)"
                      [attr.aria-label]="'agile.actions.close' | transloco"></button>
            </div>
            <div class="modal-body">
              <label class="form-label" for="it-title">{{ 'agile.item.title' | transloco }}</label>
              <input id="it-title" class="form-control mb-3" [(ngModel)]="itemForm.title" maxlength="255">
              <label class="form-label" for="it-desc">{{ 'agile.item.description' | transloco }}</label>
              <textarea id="it-desc" class="form-control mb-3" rows="3" [(ngModel)]="itemForm.description" maxlength="2000"></textarea>
              <div class="row g-2 mb-3">
                <div class="col">
                  <label class="form-label" for="it-prio">{{ 'agile.item.priority' | transloco }}</label>
                  <select id="it-prio" class="form-select" [(ngModel)]="itemForm.priority">
                    @for (p of priorities; track p) {
                      <option [value]="p">{{ 'agile.priority.' + p | transloco }}</option>
                    }
                  </select>
                </div>
                <div class="col">
                  <label class="form-label" for="it-est">{{ 'agile.item.estimate' | transloco }}</label>
                  <!-- The estimate is in man-days. step="0.25" allows quarters of a day,
                       min="0" refuses a negative estimate. Without min="0" a "-3" would be
                       accepted here and would make the progress bar compute a share above
                       100 percent. -->
                  <input id="it-est" type="number" min="0" step="0.25" class="form-control" [(ngModel)]="itemForm.estimateDays">
                </div>
              </div>
              <div class="row g-2">
                <div class="col">
                  <label class="form-label" for="it-status">{{ 'agile.item.status' | transloco }}</label>
                  <select id="it-status" class="form-select" [(ngModel)]="itemForm.status">
                    @for (s of columns; track s) {
                      <option [value]="s">{{ 'agile.column.' + s | transloco }}</option>
                    }
                  </select>
                </div>
                <div class="col">
                  <label class="form-label" for="it-sprint">{{ 'agile.item.sprint' | transloco }}</label>
                  <!-- [ngValue] here, and not [value] as in the two dropdowns above.
                       [value] can only carry text, so a sprint id would come back as the
                       string "12" and null would come back as the string "null". [ngValue]
                       keeps the real JavaScript value, so the number 12 stays a number and
                       null stays null - which is what "no sprint, leave it in the product
                       backlog" means for the server. The dropdowns above use [value] safely
                       because their values really are strings ('TODO', 'HIGH'...). -->
                  <select id="it-sprint" class="form-select" [(ngModel)]="itemForm.sprintId">
                    <option [ngValue]="null">{{ 'agile.item.unassigned' | transloco }}</option>
                    @for (s of sprints(); track s.id) {
                      <option [ngValue]="s.id">{{ s.name }}</option>
                    }
                  </select>
                </div>
              </div>
              <div class="row g-2 mt-1">
                <div class="col">
                  <label class="form-label" for="it-assignee">{{ 'agile.item.assignee' | transloco }}</label>
                  <!-- The list only holds the members of THIS project team, not every user
                       of the company. Why: an item may only be given to somebody who is
                       assigned to the project. Listing everybody would let a user pick a
                       person the server then refuses, and would also leak the full staff
                       list to anybody who can open a board. -->
                  <select id="it-assignee" class="form-select" [(ngModel)]="itemForm.assigneeId">
                    <option [ngValue]="null">{{ 'agile.item.unassigned2' | transloco }}</option>
                    @for (m of team(); track m.userId) {
                      <option [ngValue]="m.userId">{{ m.userFullName }}</option>
                    }
                  </select>
                  <div class="form-text">{{ 'agile.item.assigneeHint' | transloco }}</div>
                </div>
              </div>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="itemModal.set(false)">{{ 'agile.actions.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveItem()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'agile.actions.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
/**
 * The class that drives the screen described above: it holds the state (which project,
 * which view, the sprints, the items, the team, the two forms), reacts to the clicks, and
 * calls the services.
 *
 * implements OnInit: Angular calls ngOnInit() once, right after the component is built and
 * its inputs are set.
 * Why the work is not done in a constructor: at construction time the route parameters may
 * not be readable yet, and a constructor that fires HTTP calls is very hard to test.
 */
export class AgileComponent implements OnInit {
  /*
   * The dependencies. inject() replaces the long constructor parameter list.
   * readonly means nothing can swap one of them later by mistake; the reference is fixed
   * for the whole life of the component.
   */
  private readonly agile    = inject(AgileService);
  private readonly projects = inject(ProjectService);
  private readonly auth     = inject(AuthService);
  private readonly confirm  = inject(ConfirmService);
  private readonly toast    = inject(ToastService);
  private readonly t        = inject(TranslocoService);
  private readonly router   = inject(Router);
  private readonly route    = inject(ActivatedRoute);
  private readonly teamSvc  = inject(TeamService);

  /*
   * The three fixed lists the template loops over. They come from agile.model.ts, so the
   * board columns and the dropdowns always show exactly the values the Java enums accept.
   * 'columns' also carries the ORDER of the board: TODO, IN_PROGRESS, DONE. isBefore()
   * below reads that same order to choose the direction of the arrow buttons.
   * Why point at the shared constants instead of writing the values here: if a status were
   * added on the server and only half of this file were updated, one dropdown would offer
   * a value the board could not draw.
   */
  readonly columns: BacklogItemStatus[]   = BACKLOG_STATUSES;
  readonly priorities: BacklogPriority[]  = BACKLOG_PRIORITIES;
  readonly sprintStatuses: SprintStatus[] = SPRINT_STATUSES;

  /*
   * The state of the screen, held in signals. A signal is a value that remembers who reads
   * it: calling .set() repaints exactly the parts of the template that used it.
   * Why signals and not plain fields: with plain fields Angular has to re-check the whole
   * template on every mouse move to notice a change. During a drag that is hundreds of
   * useless checks per second, and the board becomes sticky on a long backlog.
   */
  /** The project being looked at; null means the picker is on screen. */
  selected = signal<Project | null>(null);
  /** Every sprint of the project, in the order the server sent them. */
  sprints  = signal<Sprint[]>([]);
  /** Every backlog item of the project: those inside a sprint AND those still free. */
  items    = signal<BacklogItem[]>([]);
  /** Project team: the only people an item may be assigned to. */
  team     = signal<TeamAssignment[]>([]);
  /** null = show the product backlog; a number = show the board of that sprint. */
  view     = signal<View>(null);
  /** true while the sprints and the items are being fetched; draws the grey placeholders. */
  loading  = signal(false);
  /** true while a dialog is saving; it is what disables the Save button. */
  saving   = signal(false);

  /** The card currently held by the mouse, or null when nothing is being dragged. */
  dragging = signal<BacklogItem | null>(null);
  /** The column the pointer is flying over, so it can be highlighted. */
  dragOver = signal<BacklogItemStatus | null>(null);

  /** Is the sprint dialog open? */
  sprintModal = signal(false);
  /** Is the backlog item dialog open? */
  itemModal   = signal(false);

  /*
   * The working copy of the sprint dialog. It is a plain object, NOT a signal, because
   * [(ngModel)] writes into its fields directly and the dialog is redrawn as a whole when
   * sprintModal() flips.
   * id === null means "this is a new sprint"; a number means "edit that one". Keeping the
   * id here is what lets saveSprint() choose between POST and PUT.
   * Why a copy and not the Sprint object itself: the user must be able to press Cancel. If
   * the form wrote straight into the object held by sprints(), a cancelled edit would
   * already be visible on the rail behind the dialog.
   */
  sprintForm: { id: number | null; name: string; goal: string;
                startDate: string | null; endDate: string | null; status: SprintStatus } =
    { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };

  /*
   * The working copy of the backlog item dialog, same idea as sprintForm.
   * sprintId and assigneeId are allowed to be null on purpose: null is a real answer here,
   * it means "no sprint, keep it in the product backlog" and "nobody in charge yet".
   * Without that null a card could never be pulled back out of an iteration.
   */
  itemForm: { id: number | null; title: string; description: string;
              priority: BacklogPriority; estimateDays: number | null;
              status: BacklogItemStatus; sprintId: number | null;
              assigneeId: number | null } =
    { id: null, title: '', description: '', priority: 'MEDIUM',
      estimateDays: null, status: 'TODO', sprintId: null, assigneeId: null };

  /*
   * computed() builds a value out of other signals. It runs again only when one of the
   * signals it read has changed, and it remembers its last result.
   * Why computed and not a method called from the template: a method is executed on every
   * single redraw. With a hundred cards on the board, filtering the list again on every
   * mouse move during a drag is what makes a board feel slow.
   */

  /**
   * The sprint the rail is currently on, or null when the product backlog is shown.
   * '?? null' turns the 'undefined' that .find() returns when nothing matches into a real
   * null. Why it matters: the template writes '@if (currentSprint(); as s)', and mixing
   * undefined and null in a type declared 'Sprint | null' makes the compiler complain.
   */
  readonly currentSprint  = computed(() => this.sprints().find(s => s.id === this.view()) ?? null);

  /**
   * The items that belong to NO sprint: the product backlog.
   * '(i.sprintId ?? null) === null' catches both cases at once - the field absent from the
   * JSON (undefined) and the field present and empty (null). A plain '=== null' would miss
   * the undefined case and those items would disappear from both views.
   */
  readonly backlogItems   = computed(() => this.items().filter(i => (i.sprintId ?? null) === null));

  /** The items of the sprint currently shown; this is what the three columns are cut from. */
  readonly sprintItems    = computed(() => this.items().filter(i => (i.sprintId ?? null) === this.view()));

  /**
   * How much one item weighs in the progress bar: its estimate in man-days, or 1 when it
   * has no estimate.
   * 'Number(x) || 1' gives 1 for null, undefined, 0 and NaN alike.
   * Why the fallback: a team that never estimates would give a total weight of 0, the bar
   * would be empty for ever and would say nothing. With the fallback the bar silently
   * degrades into "share of items done", which is still useful.
   */
  private weight(i: BacklogItem): number { return Number(i.estimateDays) || 1; }

  /** Total weight of the items of the current sprint sitting in one given column. */
  private weightOf(status: BacklogItemStatus): number {
    // reduce() adds the weights one by one, starting from 0. filter() first keeps only the
    // items of that column.
    return this.sprintItems().filter(i => i.status === status).reduce((s, i) => s + this.weight(i), 0);
  }

  /** Total weight of the sprint; it is the 100 percent the three segments are shares of. */
  private readonly totalWeight = computed(() =>
    this.sprintItems().reduce((s, i) => s + this.weight(i), 0));

  /**
   * The finished share of the sprint, rounded to a whole number, shown next to the bar and
   * read out by a screen reader through aria-valuenow.
   * The 'total === 0' test is the guard against a division by zero: an empty sprint would
   * otherwise give NaN, and the page would print "NaN%".
   */
  readonly donePercent = computed(() => {
    const total = this.totalWeight();
    return total === 0 ? 0 : Math.round((this.weightOf('DONE') / total) * 100);
  });

  /**
   * The width, in percent, of one segment of the progress bar. Not rounded on purpose, so
   * the three segments still add up to exactly 100 and no thin gap shows at the end.
   *
   * Special case: an empty sprint gives TODO 100 percent and the two others 0.
   * Why: a bar of width 0 would collapse to nothing and the user would think the component
   * failed to load. A full grey bar clearly reads as "nothing started yet".
   */
  pct(status: BacklogItemStatus): number {
    const total = this.totalWeight();
    return total === 0 ? (status === 'TODO' ? 100 : 0) : (this.weightOf(status) / total) * 100;
  }

  /**
   * The sentence printed above the bar, for example "12.5 / 30 days" or "4 / 9 items".
   * It counts in man-days as soon as at least one item of the sprint is estimated, and
   * falls back to counting items otherwise.
   *
   * Why two units and not always days: a sprint where nobody estimated would read
   * "0 / 0 days", which looks broken. Counting items is less precise but always true.
   * Why a method and not a computed: it reads the current language through
   * TranslocoService, so it must run again when the user switches French to English.
   */
  progressLabel(): string {
    const list = this.sprintItems();
    if (list.length === 0) return this.t.translate('agile.progress.empty');
    const estimated = list.some(i => Number(i.estimateDays) > 0);
    if (estimated) {
      const done  = list.filter(i => i.status === 'DONE').reduce((s, i) => s + (Number(i.estimateDays) || 0), 0);
      const total = list.reduce((s, i) => s + (Number(i.estimateDays) || 0), 0);
      return this.t.translate('agile.progress.days', { done: round2(done), total: round2(total) });
    }
    return this.t.translate('agile.progress.items', {
      done: list.filter(i => i.status === 'DONE').length, total: list.length
    });
  }

  /**
   * May the logged-in user create, edit, move or delete? It asks AuthService whether the
   * permission CODE 'MANAGE_AGILE' is in the list carried by the access token. It never
   * asks for a role name such as "ADMIN": the authorization of this application is
   * permission-based and dynamic, so an administrator can grant MANAGE_AGILE to any role
   * without a single line of code being changed.
   *
   * THIS IS COSMETIC ONLY. It decides which buttons are drawn, nothing more. The real
   * refusal happens on the server, twice: @PreAuthorize("hasAuthority('MANAGE_AGILE')") on
   * the service methods, and ProjectScopeInterceptor which checks this user against THAT
   * project because the URLs look like /api/projects/{id}/** (ADR-021).
   * Concretely: bring the delete button back with the browser developer tools and press
   * it, and the answer is still HTTP 403.
   */
  get canManage(): boolean { return this.auth.hasPermission('MANAGE_AGILE'); }

  /**
   * Runs once when the screen opens. It restores the project named in the ?p= part of the
   * address, so a bookmarked or shared link lands straight on the right board instead of
   * on the picker.
   */
  ngOnInit(): void {
    // Number('') and Number(null) both give 0, and Number('abc') gives NaN. All three are
    // falsy, so the `if (pid)` below covers "no parameter" and "rubbish in the parameter"
    // with one test, and 0 is never a valid project id anyway.
    const pid = Number(this.route.snapshot.queryParamMap.get('p'));
    if (pid) {
      // The project is fetched again instead of being trusted from the URL: the URL only
      // carries an id, and we need the code and the name for the breadcrumb. It is also
      // the server that decides whether this user may see that project at all.
      this.projects.get(pid).subscribe({
        next: p => { this.selected.set(p); this.reload(); },
        // Unknown project, or 403 on it: fall back to the picker rather than showing an
        // error page. Without this branch the screen would stay stuck on an empty board.
        error: () => this.selected.set(null)
      });
    }
  }

  /**
   * Two-letter monogram for the assignee chip, for example "Mazen Rakrouki" -> "MR".
   * A full name does not fit on a card, and an avatar picture would mean one more request
   * per person.
   */
  initials(name: string): string {
    // The regular expression /\s+/ means "one or more blank characters". Splitting on it
    // instead of on a single space handles a double space or a tab typed by mistake.
    // Without the "+", "Jean  Dupont" would split into ['Jean', '', 'Dupont'] and the
    // monogram would come out as "JD" only by luck of the last-element rule.
    const parts = name.trim().split(/\s+/);
    // parts[0]?.charAt(0) ?? '' : the "?." and the "?? ''" protect against an empty name.
    // Without them an item saved with a blank assignee name would throw and the whole
    // board would stop drawing.
    const first = parts[0]?.charAt(0) ?? '';
    // Take the LAST word, not the second. Why: "Jean Pierre Dupont" must give "JD", the
    // family name, not "JP".
    const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (first + last).toUpperCase();
  }

  /**
   * How many items belong to one given sprint. Used for the small counter inside each chip
   * of the rail, so the user sees how loaded an iteration is before opening it.
   * It reads items(), not sprintItems(), because it must answer for any sprint, not only
   * for the one currently shown.
   */
  itemsOf(sprintId: number): BacklogItem[] {
    return this.items().filter(i => (i.sprintId ?? null) === sprintId);
  }

  /** The cards of one column of the board, cut out of the items of the current sprint. */
  itemsIn(status: BacklogItemStatus): BacklogItem[] {
    return this.sprintItems().filter(i => i.status === status);
  }

  /**
   * Is column 'a' placed before column 'b' on the board? It compares their positions in
   * 'columns', which is the single source of truth for the order.
   * Used only to point the arrow of a move button the right way. Comparing the names
   * alphabetically instead would put DONE before TODO and every arrow would be wrong.
   */
  isBefore(a: BacklogItemStatus, b: BacklogItemStatus): boolean {
    return this.columns.indexOf(a) < this.columns.indexOf(b);
  }

  /**
   * Called when the picker gives us a project: remember it, write ?p=<id> in the address
   * bar, and load the data.
   * Why the address is updated: the page can then be refreshed, bookmarked or sent to a
   * colleague and it still opens on the same board. Without it, F5 would send the user
   * back to the picker.
   */
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id } });
    this.reload();
  }

  /** Back to the picker: forget the project and clear ?p= so a refresh does not bring it back. */
  clearSelection(): void {
    this.selected.set(null);
    this.router.navigate([], { queryParams: {} });
  }

  /** Switch the middle of the screen: null = product backlog, a number = that sprint board. */
  setView(v: View): void { this.view.set(v); }

  /**
   * Loads everything the screen needs for the selected project: the team, the sprints, and
   * then the backlog items. It is called after a project is picked and after every save or
   * delete, so the counters of the rail and the progress bar are never stale.
   *
   * Why the backlog is fetched INSIDE the answer of the sprints, and not next to it: the
   * rail is drawn from the sprints and each chip shows a count coming from the items. If
   * the items landed first, the board would briefly try to draw cards for a sprint it does
   * not know yet, and the chips would flash with a count of zero.
   */
  private reload(): void {
    const p = this.selected();
    // No project, nothing to load. This guard also stops a late call after the user has
    // gone back to the picker, which would otherwise build the URL /api/projects/undefined.
    if (!p) return;
    this.loading.set(true);
    // The team drives the assignee list; a failure there must not block the board,
    // so it is loaded independently and simply leaves the list empty.
    this.teamSvc.list(p.id).subscribe({
      next: t => this.team.set(t),
      error: () => this.team.set([]),
    });
    this.agile.listSprints(p.id).subscribe({
      next: list => {
        this.sprints.set(list);
        // Open on the running iteration when there is one: that is the one people come to
        // look at. The `this.view() === null` part keeps a sprint the user has already
        // chosen, so a reload after a save does not jump away from the board he is on.
        // Known limit: null also means "the user is on the product backlog", so a reload
        // while he is there does move him to the active sprint.
        const active = list.find(s => s.status === 'ACTIVE');
        if (active && this.view() === null) this.view.set(active.id);
        this.agile.listBacklog(p.id).subscribe({
          next: b => { this.items.set(b); this.loading.set(false); },
          error: () => { this.loading.set(false); this.toast.error(this.t.translate('agile.msg.loadFailed')); }
        });
      },
      error: () => { this.loading.set(false); this.toast.error(this.t.translate('agile.msg.loadFailed')); }
    });
  }

  // ── Drag and drop ───────────────────────────────────────────────
  // The native HTML drag and drop of the browser is used, with no external library. It
  // costs nothing to download and it is the behaviour the user already knows.

  /** The user starts dragging a card: remember which one, so the drop knows what to move. */
  onDragStart(it: BacklogItem): void { if (this.canManage) this.dragging.set(it); }

  /**
   * The gesture is finished, whatever the outcome. It also runs when the card is dropped
   * outside any column, or when the user presses Escape mid-drag.
   * Without it, dragging() would stay set and the card would keep its faded look for ever.
   */
  onDragEnd(): void { this.dragging.set(null); this.dragOver.set(null); }

  /** The pointer flies over a column: accept the drop and highlight that column. */
  onDragOver(e: DragEvent, col: BacklogItemStatus): void {
    // Nothing of ours is being dragged (a file from the desktop, for instance): let the
    // browser do its usual thing instead of pretending this is a card.
    if (!this.dragging()) return;
    // preventDefault() cancels the browser's default answer to dragover, which is "refuse
    // the drop". Without this one line the drop event is never fired at all and the card
    // simply springs back, with no error anywhere to explain it.
    e.preventDefault();
    this.dragOver.set(col);
  }

  /** The card is released over a column: move it, unless it was already in that column. */
  onDrop(e: DragEvent, col: BacklogItemStatus): void {
    // Here preventDefault() stops the browser from treating the drop as a navigation, for
    // example opening the dragged text as a URL.
    e.preventDefault();
    // Read the dragged card BEFORE clearing the two signals; clearing first would leave
    // nothing to move.
    const it = this.dragging();
    this.dragOver.set(null);
    this.dragging.set(null);
    // Dropping a card back where it came from must not fire a request. Without this test
    // every small slip of the mouse would send a useless PATCH to the server.
    if (it && it.status !== col) this.move(it, col);
  }

  // ── Sprint ──────────────────────────────────────────────────────

  /**
   * Fills the sprint form and opens the dialog.
   * Passing a Sprint means "edit this one", passing null means "create a new one", which
   * is why the id is set to null and the status defaults to PLANNED in that case.
   * Note the ?? '' on the goal: the form field is typed 'string', but the server may send
   * null. Without it ngModel would print "null" inside the textarea.
   */
  openSprintModal(s: Sprint | null): void {
    this.sprintForm = s
      ? { id: s.id, name: s.name, goal: s.goal ?? '',
          startDate: s.startDate ?? null, endDate: s.endDate ?? null, status: s.status }
      : { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };
    this.sprintModal.set(true);
  }

  /**
   * Saves the sprint dialog: POST when the form has no id, PUT when it has one.
   *
   * Why one method for both: the body is exactly the same in the two cases, so splitting
   * it in two would mean keeping the same six fields in step in two places.
   * The checks done here are only there to save the user a round trip; the server checks
   * everything again, and it is the server that refuses a user without MANAGE_AGILE.
   */
  saveSprint(): void {
    const p = this.selected();
    if (!p) return;
    // A sprint with no name would be an unreadable chip in the rail. The message is shown
    // at once instead of waiting for the 400 that the server would send back.
    if (!this.sprintForm.name.trim()) { this.toast.error(this.t.translate('agile.msg.nameRequired')); return; }
    const body = {
      // trim() removes the blanks around the name, so "  Sprint 4  " and "Sprint 4" are
      // not saved as two different sprints.
      name: this.sprintForm.name.trim(),
      // `|| null` turns an empty text field into a real null in the JSON.
      // Why: the server stores "no goal" as null. Sending "" would fill the column with an
      // empty string, and the template test `@if (s.goal)` would still be false, so we
      // would keep a meaningless row of data in the database for nothing.
      goal: this.sprintForm.goal || null,
      startDate: this.sprintForm.startDate || null,
      endDate: this.sprintForm.endDate || null,
      status: this.sprintForm.status
    };
    this.saving.set(true);
    // The request is only BUILT here. An Observable sends nothing until somebody
    // subscribes, which is why the choice between PUT and POST can be made calmly on one
    // line and the call fired once below.
    const req = this.sprintForm.id
      ? this.agile.updateSprint(p.id, this.sprintForm.id, body)
      : this.agile.createSprint(p.id, body);
    req.subscribe({
      next: saved => {
        this.saving.set(false); this.sprintModal.set(false);
        this.toast.success(this.t.translate('agile.msg.sprintSaved'));
        // A brand new sprint becomes the current view, so the user lands on the board he
        // has just created instead of having to look for it in the rail. `saved.id` comes
        // from the server answer; the form never knew that id.
        if (!this.sprintForm.id) this.view.set(saved.id);
        // Read everything again rather than patching the local array by hand. The server
        // may have normalised a date or a status, and the counters of the rail depend on
        // the items as well.
        this.reload();
      },
      error: () => { this.saving.set(false); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  /**
   * Deletes a sprint, after asking the user to confirm.
   *
   * async / await is used so the confirmation reads like a straight line: ask, wait for the
   * answer, then act. ConfirmService.ask() gives back a Promise that resolves to true or
   * false when the user presses one of the two buttons.
   * Why a custom dialog and not the browser confirm(): the native one cannot be translated,
   * cannot be styled, and is blocked in some browsers.
   * Note: on the server this is a SOFT delete, the row stays with deleted = true, so the
   * history of the project is not lost.
   */
  async removeSprint(s: Sprint): Promise<void> {
    const p = this.selected();
    if (!p) return;
    // Nothing is sent while the user has not said yes. Deleting a sprint takes its whole
    // board with it, so a misplaced click must not be enough.
    if (!await this.confirm.ask(this.t.translate('agile.sprint.deleteConfirm'))) return;
    this.agile.deleteSprint(p.id, s.id).subscribe({
      next: () => {
        // We were looking at the sprint that has just been deleted: fall back to the
        // product backlog. Without this line the screen would keep asking for a sprint
        // that no longer exists and would show an empty board with no explanation.
        if (this.view() === s.id) this.view.set(null);
        this.toast.success(this.t.translate('agile.msg.sprintDeleted'));
        this.reload();
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }

  // ── Backlog item ────────────────────────────────────────────────

  /**
   * Fills the item form and opens its dialog. Passing an item means "edit", passing null
   * means "create".
   *
   * Note 'sprintId: this.view()' in the creation case: a new item is pre-attached to the
   * sprint currently shown, and to no sprint at all when the user is on the product
   * backlog. It is exactly what he is looking at, so the field is already right and he has
   * one less thing to pick.
   */
  openItemModal(it: BacklogItem | null): void {
    this.itemForm = it
      ? { id: it.id, title: it.title, description: it.description ?? '',
          priority: it.priority, estimateDays: it.estimateDays ?? null,
          status: it.status, sprintId: it.sprintId ?? null,
          assigneeId: it.assigneeId ?? null }
      : { id: null, title: '', description: '', priority: 'MEDIUM',
          estimateDays: null, status: 'TODO', sprintId: this.view(),
          assigneeId: null };
    this.itemModal.set(true);
  }

  /**
   * Saves the item dialog: POST for a new card, PUT for an existing one. The PUT sends the
   * WHOLE form, because the server route replaces the row; sending half of it would erase
   * the fields left out.
   */
  saveItem(): void {
    const p = this.selected();
    if (!p) return;
    // A card with no title would be an empty rectangle on the board, impossible to tell
    // apart from the others.
    if (!this.itemForm.title.trim()) { this.toast.error(this.t.translate('agile.msg.titleRequired')); return; }
    const body = {
      title: this.itemForm.title.trim(),
      description: this.itemForm.description || null,
      priority: this.itemForm.priority,
      // `?? null` and not `|| null` on the numbers. With `||` the value 0 would be turned
      // into null, because 0 is falsy in JavaScript. An estimate of 0 day is a real answer
      // and must reach the server as 0.
      estimateDays: this.itemForm.estimateDays ?? null,
      status: this.itemForm.status,
      // Explicit null, never "field missing". The server reads null as "take this card out
      // of its sprint" / "nobody in charge". If the field were simply absent, the row would
      // keep its old sprint and its old owner and the user's change would be silently lost.
      sprintId: this.itemForm.sprintId ?? null,
      assigneeId: this.itemForm.assigneeId ?? null
    };
    this.saving.set(true);
    const req = this.itemForm.id
      ? this.agile.updateItem(p.id, this.itemForm.id, body)
      : this.agile.createItem(p.id, body);
    req.subscribe({
      next: () => {
        this.saving.set(false); this.itemModal.set(false);
        this.toast.success(this.t.translate('agile.msg.itemSaved'));
        this.reload();
      },
      error: () => { this.saving.set(false); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  /**
   * Moves one card into another column, both for the drag and drop and for the arrow
   * buttons. It uses the dedicated PATCH .../move rather than a full update, so a move
   * does not resend the title, the description and the estimate - and therefore cannot
   * overwrite a change a colleague has just made to them.
   */
  move(it: BacklogItem, target: BacklogItemStatus): void {
    const p = this.selected();
    if (!p) return;
    /*
     * Optimistic update: the card is redrawn in its new column at once, without waiting
     * for the server. The gesture then feels instant.
     * 'before' keeps the previous list so the move can be undone if the server refuses -
     * for instance a 403 from ProjectScopeInterceptor, or a lost network.
     * Without that rollback, a refused move would stay on screen and the user would
     * believe the work item has changed column when the database says otherwise.
     */
    const before = this.items();
    // A NEW array with a NEW object for the moved card ({ ...x, status: target } copies the
    // card and overrides one field). Changing x.status in place would not create a new
    // reference, the signal would not consider it changed, and the board would not repaint.
    this.items.set(before.map(x => x.id === it.id ? { ...x, status: target } : x));
    this.agile.moveItem(p.id, it.id, target, it.sprintId ?? null).subscribe({
      // The server answer replaces our guess. It may carry fields we could not know, and
      // it is the version that is really stored.
      next: updated => this.items.set(this.items().map(x => x.id === updated.id ? updated : x)),
      // Refused: put the whole list back as it was and say so.
      error: () => { this.items.set(before); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  /**
   * Deletes one backlog item, after the confirmation dialog.
   * Unlike the other write operations this one does NOT call reload(): removing one card
   * from the local list is enough, and a full reload would make the whole board blink for
   * a change the user can already see. On the server the row is soft-deleted, not erased.
   */
  async removeItem(it: BacklogItem): Promise<void> {
    const p = this.selected();
    if (!p) return;
    if (!await this.confirm.ask(this.t.translate('agile.item.deleteConfirm'))) return;
    this.agile.deleteItem(p.id, it.id).subscribe({
      next: () => {
        // filter() builds a new array without that card. A new array is required for the
        // signal to notice the change and repaint the column and its counter.
        this.items.set(this.items().filter(x => x.id !== it.id));
        this.toast.success(this.t.translate('agile.msg.itemDeleted'));
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }
}

/**
 * Rounds a number to two decimals, for example 12.499999999 -> 12.5.
 *
 * Why it is needed: adding decimal numbers in JavaScript is not exact. 0.1 + 0.2 gives
 * 0.30000000000000004. Without this helper the progress sentence of a sprint estimated in
 * quarters of a day could read "7.000000000000001 / 15 days".
 * Multiply by 100, round, divide by 100 is the plain way to do it; toFixed(2) would give
 * back a string and would also force a trailing "0" on a whole number ("7.00").
 *
 * It sits outside the class because it uses nothing of the component. A free function is
 * easier to test and the Angular build can drop it if it ever stops being used.
 */
function round2(n: number): number { return Math.round(n * 100) / 100; }
