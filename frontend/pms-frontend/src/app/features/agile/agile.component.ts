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

// Draws the Sprints & Backlog screen: project picker, sprint/backlog rail, sprint board and
// list, and the two edit dialogs. Route-guarded by VIEW_AGILE; all writes are re-checked
// server-side (MANAGE_AGILE permission + ADR-021 project scoping) - buttons here are cosmetic.

// Which view is shown: null = product backlog, a number = that sprint's board (also used to filter items).
type View = number | null;

@Component({
  // Tag name the router creates for the '/agile' route.
  selector: 'app-agile',
  // No NgModule needed; lets the component be lazy-loaded on its own.
  standalone: true,
  // Everything the template uses: pipes, [(ngModel)], <app-project-picker>, and the transloco pipe.
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  // Lazily loads assets/i18n/agile/{fr,en}.json instead of bundling these texts globally.
  providers: [provideTranslocoScope('agile')],
  // Component-scoped CSS (Angular scopes selectors, so .chip here won't leak). Only /* */
  // comments are legal inside this block. var(--...) tokens come from global styles.scss.
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

    /* Same colours as the board columns, so the bar doubles as a legend with no key needed. */
    .bar { display:flex; height:8px; border-radius:var(--r-full); overflow:hidden;
           background:var(--surface-3); }
    .bar span { display:block; transition:width var(--t-slow); }
    .seg-DONE        { background:var(--c-success); }
    .seg-IN_PROGRESS { background:var(--c-brand); }
    .seg-TODO        { background:var(--border-2); }
    .legend { display:flex; gap:1rem; flex-wrap:wrap; font-size:11.5px; color:var(--text-2); }
    /* Shared by the legend and column header, so the two can't drift apart. */
    .swatch { width:8px; height:8px; border-radius:2px; display:inline-block; flex:none; }
    .legend .swatch { margin-right:.35rem; }

    /* ── The board (three columns) ─────────────────────────────── */
    /* minmax(0,1fr): without the 0, a long card title would push a column off-screen. */
    .board { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.85rem; padding:1rem; }
    /* Stack columns under 900px; three side by side leave too little room on a phone. */
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
    /* Left border shows priority; MEDIUM uses .ticket's default colour, so no rule needed here. */
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
    /* opacity:0, not display:none, keeps layout stable and stays reachable via :focus-within. */
    .tk-actions { display:flex; gap:.2rem; margin-left:auto; opacity:0; transition:opacity var(--t); }
    .ticket:hover .tk-actions, .ticket:focus-within .tk-actions { opacity:1; }
    /* Touch screens have no hover, so force the action buttons visible there. */
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
    /* tabular-nums: equal digit width stops the line from jittering as counters update. */
    .num { font-variant-numeric:tabular-nums; }

    /* Disables all animation for users who set prefers-reduced-motion. */
    @media (prefers-reduced-motion:reduce) {
      .bar span, .col, .ticket, .tk-actions { transition:none; }
    }
  `],
  // Inline template (project convention). Only <!-- --> comments are legal inside these backticks.
  // @if/@for/@empty are Angular's built-in control flow (replacing *ngIf/*ngFor).
  template: `
    <!-- Breadcrumb: shows current location and, once a project is picked, a way back besides browser Back. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-kanban" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()">
            <i class="bi bi-arrow-left"></i> {{ 'agile.breadcrumb' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <!-- "!" needed: compiler can't infer non-null from the enclosing @if. -->
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

      <!-- No project yet: show the picker (project UX rule: never a giant dropdown, always search+page). -->
      @if (!selected()) {
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-kanban"
                            (projectSelected)="select($event)" />
      } @else {

        <div class="card mb-4">
          <!-- Chips (not a dropdown) for backlog + each sprint. role="tablist"/"tab"/aria-selected make it a tab set for screen readers. -->
          <div class="rail" role="tablist" [attr.aria-label]="'agile.tabs.sprints' | transloco">
            <button class="chip" role="tab" [class.is-active]="view() === null"
                    [attr.aria-selected]="view() === null" (click)="setView(null)">
              <i class="bi bi-inbox"></i>{{ 'agile.item.unassigned' | transloco }}
              <span class="chip-count">{{ backlogItems().length }}</span>
            </button>
            <!-- track s.id: keeps DOM nodes stable by id instead of position, so focus doesn't jump. -->
            @for (s of sprints(); track s.id) {
              <button class="chip" role="tab" [class.is-active]="view() === s.id"
                      [attr.aria-selected]="view() === s.id" (click)="setView(s.id)">
                <span class="dot" [class]="'dot dot-' + s.status"></span>{{ s.name }}
                <span class="chip-count">{{ itemsOf(s.id).length }}</span>
              </button>
            }
            <!-- canManage only hides these buttons cosmetically; server enforces MANAGE_AGILE + project scope (ADR-021). -->
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

          <!-- Skeleton placeholders (not a spinner) keep the card's height stable while loading. -->
          @if (loading()) {
            <div class="p-4 d-flex flex-column gap-2">
              <div class="skeleton" style="height:14px;width:30%;border-radius:var(--r-xs)"></div>
              <div class="skeleton" style="height:8px;border-radius:var(--r-full)"></div>
            </div>
          <!-- "as s" caches currentSprint() locally, avoiding repeated calls and repeated "!" null-checks. -->
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
                      <!-- ?? '—' shows a dash for an unset date instead of a blank that looks like a bug. -->
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
                <!-- ARIA progressbar attributes let a screen reader announce the percentage; colour alone conveys nothing. -->
                <div class="bar" role="progressbar"
                     [attr.aria-valuenow]="donePercent()" aria-valuemin="0" aria-valuemax="100"
                     [attr.aria-label]="progressLabel()">
                  <!-- Three segments always sum to 100%; DONE first so it grows from the left, as expected. -->
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
            <!-- Plain list, not a board: these items aren't in a workflow yet, so no TODO/IN PROGRESS/DONE columns. -->
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
              <!-- @empty shows this when the list is empty, distinguishing that from a failed load. -->
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
                <!-- One column per entry of "columns" (BACKLOG_STATUSES); a new status appears here automatically. -->
                @for (col of columns; track col) {
                  <!-- Makes the column a drop target: (dragover) must preventDefault or drops are refused silently. -->
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
                        <!-- draggable only for canManage (avoids a drag that ends in 403); dragging()?.id guards against null;
                             tabindex="0" puts the card in the Tab order so :focus-within reveals the action buttons. -->
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
                                <!-- Arrow buttons are the keyboard-accessible equivalent of drag-and-drop, not a duplicate. -->
                                @for (target of columns; track target) {
                                  <!-- Skip the card's current column: a no-op "move to X" button would just add noise. -->
                                  @if (target !== col) {
                                    <button class="icon-btn" (click)="move(it, target)"
                                            [attr.aria-label]="'agile.actions.moveTo' | transloco: { column: ('agile.column.' + target | transloco) }"
                                            [title]="'agile.actions.moveTo' | transloco: { column: ('agile.column.' + target | transloco) }">
                                      <!-- Arrow direction reflects the actual move; fixed arrows would be wrong half the time. -->
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

    <!-- Sprint dialog, hand-built (not the Bootstrap JS plugin) so opening it is just a signal flip. -->
    @if (sprintModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Backdrop click and Escape both close the dialog, the two exits users expect. -->
      <div class="modal d-block" tabindex="-1" (click)="sprintModal.set(false)" (keydown.escape)="sprintModal.set(false)">
        <!-- stopPropagation keeps inside clicks from bubbling to the backdrop and closing the dialog. -->
        <div class="modal-dialog modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <!-- One dialog for create and edit; an existing id just changes the title. -->
              <h5 class="modal-title">{{ (sprintForm.id ? 'agile.sprint.edit' : 'agile.sprint.new') | transloco }}</h5>
              <button type="button" class="btn-close" (click)="sprintModal.set(false)"
                      [attr.aria-label]="'agile.actions.close' | transloco"></button>
            </div>
            <div class="modal-body">
              <label class="form-label" for="sp-name">{{ 'agile.sprint.name' | transloco }}</label>
              <!-- maxlength="100" matches the server column, avoiding a confusing 400 after Save. -->
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
              <!-- disabled=saving() stops a triple-click from creating duplicate sprints. -->
              <button class="btn btn-primary" (click)="saveSprint()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'agile.actions.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Backlog item dialog: same construction as the sprint dialog above. -->
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
                  <!-- step="0.25" allows quarter-day estimates; min="0" prevents a negative value skewing the progress bar. -->
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
                  <!-- [ngValue], not [value]: keeps the real number/null (not the string "12"/"null") the server expects. -->
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
                  <!-- Only this project's team, not every user: an outside assignee would be refused server-side,
                       and listing everybody would leak the full staff list. -->
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
/** Holds the screen's state and drives it. Uses ngOnInit (not the constructor) because
 *  route params aren't readable yet at construction time. */
export class AgileComponent implements OnInit {
  // Dependencies via inject(); readonly so they can't be swapped later.
  private readonly agile    = inject(AgileService);
  private readonly projects = inject(ProjectService);
  private readonly auth     = inject(AuthService);
  private readonly confirm  = inject(ConfirmService);
  private readonly toast    = inject(ToastService);
  private readonly t        = inject(TranslocoService);
  private readonly router   = inject(Router);
  private readonly route    = inject(ActivatedRoute);
  private readonly teamSvc  = inject(TeamService);

  // From agile.model.ts, so dropdowns always match the server enums. `columns` also carries
  // the board ORDER, which isBefore() below uses for the arrow-button direction.
  readonly columns: BacklogItemStatus[]   = BACKLOG_STATUSES;
  readonly priorities: BacklogPriority[]  = BACKLOG_PRIORITIES;
  readonly sprintStatuses: SprintStatus[] = SPRINT_STATUSES;

  // Screen state as signals: .set() repaints only the template parts that read it, unlike plain fields.
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

  // Plain object (not a signal) since [(ngModel)] writes fields directly. A copy of the Sprint,
  // not the object itself, so Cancel discards edits; id null/number picks POST vs PUT in saveSprint().
  sprintForm: { id: number | null; name: string; goal: string;
                startDate: string | null; endDate: string | null; status: SprintStatus } =
    { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };

  // Same idea as sprintForm. sprintId/assigneeId may be null on purpose: null means "no sprint" / "no owner".
  itemForm: { id: number | null; title: string; description: string;
              priority: BacklogPriority; estimateDays: number | null;
              status: BacklogItemStatus; sprintId: number | null;
              assigneeId: number | null } =
    { id: null, title: '', description: '', priority: 'MEDIUM',
      estimateDays: null, status: 'TODO', sprintId: null, assigneeId: null };

  // computed() caches its result and only reruns when a dependency changes, unlike a template method.

  /** Current sprint, or null for the product backlog. '?? null' turns .find()'s undefined into
   *  a real null so the type stays 'Sprint | null'. */
  readonly currentSprint  = computed(() => this.sprints().find(s => s.id === this.view()) ?? null);

  /** Items with no sprint. '?? null' catches both undefined (field absent) and null. */
  readonly backlogItems   = computed(() => this.items().filter(i => (i.sprintId ?? null) === null));

  /** The items of the sprint currently shown; this is what the three columns are cut from. */
  readonly sprintItems    = computed(() => this.items().filter(i => (i.sprintId ?? null) === this.view()));

  /** Item's weight in the bar: its estimate, or 1 if unset — otherwise an unestimated team's
   *  bar would always read 0/empty. */
  private weight(i: BacklogItem): number { return Number(i.estimateDays) || 1; }

  /** Total weight of the items of the current sprint sitting in one given column. */
  private weightOf(status: BacklogItemStatus): number {
    return this.sprintItems().filter(i => i.status === status).reduce((s, i) => s + this.weight(i), 0);
  }

  /** Total weight of the sprint; it is the 100 percent the three segments are shares of. */
  private readonly totalWeight = computed(() =>
    this.sprintItems().reduce((s, i) => s + this.weight(i), 0));

  /** Finished share of the sprint (rounded). Guards total===0 to avoid a NaN% on an empty sprint. */
  readonly donePercent = computed(() => {
    const total = this.totalWeight();
    return total === 0 ? 0 : Math.round((this.weightOf('DONE') / total) * 100);
  });

  /** Segment width, unrounded so the three add up to exactly 100. Empty sprint -> TODO 100%,
   *  so the bar reads "nothing started" instead of collapsing to nothing. */
  pct(status: BacklogItemStatus): number {
    const total = this.totalWeight();
    return total === 0 ? (status === 'TODO' ? 100 : 0) : (this.weightOf(status) / total) * 100;
  }

  /** Sentence above the bar, e.g. "12.5 / 30 days"; falls back to counting items when nothing
   *  is estimated (else it'd read "0 / 0 days"). A method, not computed, since it re-reads the active language. */
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

  /** Checks the MANAGE_AGILE permission code (never a role name) to decide which buttons to draw.
   *  Cosmetic only - the server re-enforces it via @PreAuthorize and ProjectScopeInterceptor (ADR-021). */
  get canManage(): boolean { return this.auth.hasPermission('MANAGE_AGILE'); }

  /** Restores the project from the ?p= query param, so a bookmarked link skips the picker. */
  ngOnInit(): void {
    // Number() on empty/invalid input gives 0/NaN, both falsy, so one 'if (pid)' covers both cases.
    const pid = Number(this.route.snapshot.queryParamMap.get('p'));
    if (pid) {
      // Re-fetched (not trusted from the URL) for the breadcrumb's code/name, and so the server can check access.
      this.projects.get(pid).subscribe({
        next: p => { this.selected.set(p); this.reload(); },
        // Unknown project or 403: fall back to the picker instead of an error page.
        error: () => this.selected.set(null)
      });
    }
  }

  /** Two-letter monogram for the assignee chip (full name won't fit; an avatar photo would cost
   *  a request per person). */
  initials(name: string): string {
    // /\s+/ handles double spaces/tabs; splitting on a single space could break on stray whitespace.
    const parts = name.trim().split(/\s+/);
    // ?./?? '' guard against an empty name, which would otherwise throw and break the whole board.
    const first = parts[0]?.charAt(0) ?? '';
    // Last word, not second: a middle name shouldn't be used as the family-name initial.
    const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (first + last).toUpperCase();
  }

  /** Items in a given sprint, for the rail's chip counters. Reads items(), not sprintItems(),
   *  to answer for any sprint. */
  itemsOf(sprintId: number): BacklogItem[] {
    return this.items().filter(i => (i.sprintId ?? null) === sprintId);
  }

  /** The cards of one column of the board, cut out of the items of the current sprint. */
  itemsIn(status: BacklogItemStatus): BacklogItem[] {
    return this.sprintItems().filter(i => i.status === status);
  }

  /** Is column a before b on the board? Compares positions in 'columns' (alphabetical order would be wrong). */
  isBefore(a: BacklogItemStatus, b: BacklogItemStatus): boolean {
    return this.columns.indexOf(a) < this.columns.indexOf(b);
  }

  /** Project picked: remember it, write ?p=<id> so refresh/bookmark/share lands on the same board, and load data. */
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

  /** Loads team, sprints, then items for the selected project; called after every save/delete so nothing
   *  goes stale. Backlog is fetched inside the sprints callback so the board never draws items before it has sprints. */
  private reload(): void {
    const p = this.selected();
    // Also guards against a late call after the user returned to the picker (avoids /api/projects/undefined).
    if (!p) return;
    this.loading.set(true);
    // Loaded independently: a team-fetch failure must not block the board, just leaves the list empty.
    this.teamSvc.list(p.id).subscribe({
      next: t => this.team.set(t),
      error: () => this.team.set([]),
    });
    this.agile.listSprints(p.id).subscribe({
      next: list => {
        this.sprints.set(list);
        // Default to the active sprint, but not if the user already picked a view. Known limit: null also
        // means "on the product backlog", so a reload there still jumps to the active sprint.
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

  // ── Drag and drop (native HTML DnD, no library) ──────────────

  /** The user starts dragging a card: remember which one, so the drop knows what to move. */
  onDragStart(it: BacklogItem): void { if (this.canManage) this.dragging.set(it); }

  /** Runs whenever the drag ends (drop outside a column, Escape...), clearing the faded/dragging state. */
  onDragEnd(): void { this.dragging.set(null); this.dragOver.set(null); }

  /** The pointer flies over a column: accept the drop and highlight that column. */
  onDragOver(e: DragEvent, col: BacklogItemStatus): void {
    // Not one of our cards (e.g. a file from the desktop): let the browser handle it normally.
    if (!this.dragging()) return;
    // preventDefault() overrides the browser's default "refuse the drop", or drop never fires.
    e.preventDefault();
    this.dragOver.set(col);
  }

  /** The card is released over a column: move it, unless it was already in that column. */
  onDrop(e: DragEvent, col: BacklogItemStatus): void {
    // preventDefault() here stops the browser from treating the drop as a navigation.
    e.preventDefault();
    // Read the dragged card before clearing the signals, or there'd be nothing left to move.
    const it = this.dragging();
    this.dragOver.set(null);
    this.dragging.set(null);
    // Skip if dropped back in its own column, or every small mouse slip would PATCH the server.
    if (it && it.status !== col) this.move(it, col);
  }

  // ── Sprint ──────────────────────────────────────────────────────

  /** Fills and opens the sprint dialog; null means "create new" (id null, status PLANNED).
   *  '?? ""' on goal avoids ngModel printing the literal "null" when the server sends none. */
  openSprintModal(s: Sprint | null): void {
    this.sprintForm = s
      ? { id: s.id, name: s.name, goal: s.goal ?? '',
          startDate: s.startDate ?? null, endDate: s.endDate ?? null, status: s.status }
      : { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };
    this.sprintModal.set(true);
  }

  /** Saves the sprint form: POST if new, PUT if editing - same body either way. Client-side checks
   *  here only save a round trip; the server re-validates and enforces MANAGE_AGILE. */
  saveSprint(): void {
    const p = this.selected();
    if (!p) return;
    // Checked here to avoid waiting on a server 400 for a name-less, unreadable chip.
    if (!this.sprintForm.name.trim()) { this.toast.error(this.t.translate('agile.msg.nameRequired')); return; }
    const body = {
      // trim() so surrounding blanks don't create a visually-duplicate sprint name.
      name: this.sprintForm.name.trim(),
      // '|| null' sends a real null (matching "no goal" server-side) instead of a meaningless empty string.
      goal: this.sprintForm.goal || null,
      startDate: this.sprintForm.startDate || null,
      endDate: this.sprintForm.endDate || null,
      status: this.sprintForm.status
    };
    this.saving.set(true);
    // Just builds the Observable; nothing is sent until .subscribe() below.
    const req = this.sprintForm.id
      ? this.agile.updateSprint(p.id, this.sprintForm.id, body)
      : this.agile.createSprint(p.id, body);
    req.subscribe({
      next: saved => {
        this.saving.set(false); this.sprintModal.set(false);
        this.toast.success(this.t.translate('agile.msg.sprintSaved'));
        // New sprint becomes the current view, so the user lands on the board he just created.
        if (!this.sprintForm.id) this.view.set(saved.id);
        // Full reload rather than a local patch: the server may normalise fields, and rail counters need items too.
        this.reload();
      },
      error: () => { this.saving.set(false); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  /** Deletes a sprint after confirmation (async/await over ConfirmService's Promise, not the
   *  unstylable native confirm()). Server-side this is a soft delete; history is kept. */
  async removeSprint(s: Sprint): Promise<void> {
    const p = this.selected();
    if (!p) return;
    // Confirmed first: deleting a sprint takes its whole board with it.
    if (!await this.confirm.ask(this.t.translate('agile.sprint.deleteConfirm'))) return;
    this.agile.deleteSprint(p.id, s.id).subscribe({
      next: () => {
        // If we were viewing the deleted sprint, fall back to the product backlog.
        if (this.view() === s.id) this.view.set(null);
        this.toast.success(this.t.translate('agile.msg.sprintDeleted'));
        this.reload();
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }

  // ── Backlog item ────────────────────────────────────────────────

  /** Fills and opens the item dialog; null means "create". A new item pre-attaches to
   *  the sprint currently shown (this.view()), matching what the user is looking at. */
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

  /** POST for a new card, PUT for an existing one; PUT sends the whole form since the server route replaces the row. */
  saveItem(): void {
    const p = this.selected();
    if (!p) return;
    // A title-less card would be a blank, unreadable rectangle on the board.
    if (!this.itemForm.title.trim()) { this.toast.error(this.t.translate('agile.msg.titleRequired')); return; }
    const body = {
      title: this.itemForm.title.trim(),
      description: this.itemForm.description || null,
      priority: this.itemForm.priority,
      // '?? null', not '|| null': with '||' a real estimate of 0 would be wiped to null.
      estimateDays: this.itemForm.estimateDays ?? null,
      status: this.itemForm.status,
      // Sent as an explicit null, not omitted - the server reads null as "clear this field";
      // omitting it would silently keep the old sprint/owner instead.
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

  /** Moves a card (drag-drop or arrow buttons) via the dedicated PATCH .../move, so it can't
   *  overwrite a colleague's concurrent edit to the title/description/estimate. */
  move(it: BacklogItem, target: BacklogItemStatus): void {
    const p = this.selected();
    if (!p) return;
    // Optimistic update: redraw instantly; 'before' lets us roll back if the server refuses (403, network loss).
    const before = this.items();
    // New array + new object for the moved card: mutating x.status in place wouldn't change the
    // reference, so the signal wouldn't notice and the board wouldn't repaint.
    this.items.set(before.map(x => x.id === it.id ? { ...x, status: target } : x));
    this.agile.moveItem(p.id, it.id, target, it.sprintId ?? null).subscribe({
      // Replace with the server's version, which may carry fields we couldn't know.
      next: updated => this.items.set(this.items().map(x => x.id === updated.id ? updated : x)),
      // Refused: put the whole list back as it was and say so.
      error: () => { this.items.set(before); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  /** Deletes a backlog item after confirmation. No reload() - removing locally avoids a visible
   *  blink for a change already seen; the row is soft-deleted server-side. */
  async removeItem(it: BacklogItem): Promise<void> {
    const p = this.selected();
    if (!p) return;
    if (!await this.confirm.ask(this.t.translate('agile.item.deleteConfirm'))) return;
    this.agile.deleteItem(p.id, it.id).subscribe({
      next: () => {
        // New array (not mutation) so the signal notices the change and repaints.
        this.items.set(this.items().filter(x => x.id !== it.id));
        this.toast.success(this.t.translate('agile.msg.itemDeleted'));
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }
}

/** Rounds to two decimals (JS float addition isn't exact, e.g. 0.1+0.2). Outside the class since it needs no component state. */
function round2(n: number): number { return Math.round(n * 100) / 100; }
