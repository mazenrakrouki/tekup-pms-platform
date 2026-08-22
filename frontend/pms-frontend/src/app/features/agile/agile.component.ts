import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';

import { AgileService } from '../../core/services/agile.service';
import { ProjectService } from '../../core/services/project.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';
import {
  BACKLOG_PRIORITIES, BACKLOG_STATUSES, SPRINT_STATUSES,
  BacklogItem, BacklogItemStatus, BacklogPriority, Sprint, SprintStatus
} from '../../core/models/agile.model';

@Component({
  selector: 'app-agile',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  providers: [provideTranslocoScope('agile')],
  styles: [`
    .sprint-bar { display:flex; align-items:center; gap:.5rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .sprint-chip { border:1px solid var(--border); background:var(--surface); color:var(--text-2);
      border-radius:999px; padding:.3rem .8rem; font-size:12.5px; cursor:pointer;
      transition:background var(--t), color var(--t), border-color var(--t); white-space:nowrap; }
    .sprint-chip:hover { color:var(--text-1); border-color:var(--text-3); }
    .sprint-chip.is-active { background:var(--c-brand); border-color:var(--c-brand); color:#fff; }
    .sprint-chip .dot { display:inline-block; width:6px; height:6px; border-radius:50%; margin-right:.4rem; }
    .dot-PLANNED { background:var(--text-3); }
    .dot-ACTIVE  { background:var(--c-success); }
    .dot-CLOSED  { background:var(--text-3); opacity:.5; }

    .board { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:1rem; padding:1rem; }
    @media (max-width: 900px) { .board { grid-template-columns:1fr; } }
    .col { background:var(--bg-2); border:1px solid var(--border); border-radius:var(--r-md); min-height:120px; }
    .col-head { display:flex; align-items:center; justify-content:space-between;
      padding:.6rem .8rem; border-bottom:1px solid var(--border); font-size:12px;
      text-transform:uppercase; letter-spacing:.06em; color:var(--text-3); }
    .col-body { padding:.6rem; display:flex; flex-direction:column; gap:.5rem; }
    .card-item { background:var(--surface); border:1px solid var(--border); border-radius:var(--r-sm);
      padding:.6rem .7rem; }
    .card-item .ci-title { font-size:13px; font-weight:600; color:var(--text-1); }
    .card-item .ci-meta { display:flex; align-items:center; gap:.5rem; margin-top:.35rem;
      font-size:11.5px; color:var(--text-3); }
    .prio { border-radius:var(--r-xs); padding:.05rem .35rem; font-size:10.5px; font-weight:600; }
    .prio-LOW      { background:var(--bg-3); color:var(--text-3); }
    .prio-MEDIUM   { background:var(--bg-3); color:var(--text-2); }
    .prio-HIGH     { background:rgba(217,119,6,.14);  color:var(--c-warning); }
    .prio-CRITICAL { background:rgba(220,38,38,.14);  color:var(--c-danger); }
    .ci-actions { display:flex; gap:.25rem; margin-top:.45rem; }
    .ci-actions .btn { --bs-btn-padding-y:.1rem; --bs-btn-padding-x:.35rem; font-size:11px; }
    .col-empty { color:var(--text-3); font-size:12px; text-align:center; padding:1rem .5rem; }
    .num { font-variant-numeric:tabular-nums; }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-kanban" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()">
            <i class="bi bi-arrow-left"></i> {{ 'agile.breadcrumb' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'agile.breadcrumb' | transloco }}</span>
        }
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'agile.title' | transloco }}</h1>
        <p class="page-subtitle">{{ 'agile.subtitle' | transloco }}</p>
      </div>

      @if (!selected()) {
        <app-project-picker [selected]="selected()"
                            [featureTitle]="'agile.title' | transloco"
                            featureIcon="bi-kanban"
                            [featureDescription]="'agile.selectProject' | transloco"
                            (projectSelected)="select($event)" />
      } @else {
        <div class="card mb-4">
          <!-- Sprint selector: chips, never a long native select -->
          <div class="sprint-bar">
            <button class="sprint-chip" [class.is-active]="currentSprintId() === null"
                    (click)="setSprint(null)">
              <i class="bi bi-inbox me-1"></i>{{ 'agile.item.unassigned' | transloco }}
            </button>
            @for (s of sprints(); track s.id) {
              <button class="sprint-chip" [class.is-active]="currentSprintId() === s.id"
                      (click)="setSprint(s.id)">
                <span class="dot" [class]="'dot ' + 'dot-' + s.status"></span>{{ s.name }}
              </button>
            }
            <div class="ms-auto d-flex gap-2">
              @if (canManage) {
                <button class="btn btn-outline-secondary btn-sm" (click)="openSprintModal(null)">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'agile.sprint.new' | transloco }}
                </button>
                <button class="btn btn-primary btn-sm" (click)="openItemModal(null)">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'agile.item.new' | transloco }}
                </button>
              }
            </div>
          </div>

          @if (currentSprint(); as s) {
            <div class="px-3 py-2 d-flex align-items-center gap-3 flex-wrap"
                 style="border-bottom:1px solid var(--border)">
              <div>
                <span class="fw-semibold">{{ s.name }}</span>
                <span class="text-muted small ms-2">{{ 'agile.status.' + s.status | transloco }}</span>
              </div>
              @if (s.goal) { <span class="text-muted small">{{ s.goal }}</span> }
              <span class="text-muted small ms-auto num">
                {{ 'agile.sprint.committed' | transloco: { count: visibleItems().length, days: totalDays() } }}
              </span>
              @if (canManage) {
                <button class="btn btn-ghost btn-sm" (click)="openSprintModal(s)"><i class="bi bi-pencil"></i></button>
                <button class="btn btn-ghost btn-sm act-danger" (click)="removeSprint(s)"><i class="bi bi-trash"></i></button>
              }
            </div>
          }

          @if (loading()) {
            <div class="p-4"><div class="skeleton" style="height:80px;border-radius:var(--r-sm)"></div></div>
          } @else {
            <div class="board">
              @for (col of columns; track col) {
                <div class="col">
                  <div class="col-head">
                    <span>{{ 'agile.column.' + col | transloco }}</span>
                    <span class="num">{{ itemsIn(col).length }}</span>
                  </div>
                  <div class="col-body">
                    @for (it of itemsIn(col); track it.id) {
                      <div class="card-item">
                        <div class="ci-title">{{ it.title }}</div>
                        <div class="ci-meta">
                          <span class="prio" [class]="'prio prio-' + it.priority">
                            {{ 'agile.priority.' + it.priority | transloco }}
                          </span>
                          @if (it.estimateDays) { <span class="num">{{ it.estimateDays }} JH</span> }
                        </div>
                        @if (canManage) {
                          <div class="ci-actions">
                            @for (target of columns; track target) {
                              @if (target !== it.status) {
                                <button class="btn btn-outline-secondary"
                                        [attr.aria-label]="'agile.actions.moveTo' | transloco: { column: ('agile.column.' + target | transloco) }"
                                        (click)="move(it, target)">
                                  {{ 'agile.column.' + target | transloco }}
                                </button>
                              }
                            }
                            <button class="btn btn-ghost act-danger ms-auto" (click)="removeItem(it)"
                                    [attr.aria-label]="'agile.item.delete' | transloco">
                              <i class="bi bi-trash"></i>
                            </button>
                          </div>
                        }
                      </div>
                    } @empty {
                      <div class="col-empty">{{ 'agile.empty.emptyColumn' | transloco }}</div>
                    }
                  </div>
                </div>
              }
            </div>
          }
        </div>
      }
    </div>

    <!-- Sprint modal -->
    @if (sprintModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="sprintModal.set(null)" (keydown.escape)="sprintModal.set(null)">
        <div class="modal-dialog modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">
                {{ (sprintForm.id ? 'agile.sprint.edit' : 'agile.sprint.new') | transloco }}
              </h5>
              <button type="button" class="btn-close" (click)="sprintModal.set(null)"
                      [attr.aria-label]="'agile.actions.close' | transloco"></button>
            </div>
            <div class="modal-body">
              <label class="form-label">{{ 'agile.sprint.name' | transloco }}</label>
              <input class="form-control mb-3" [(ngModel)]="sprintForm.name" maxlength="100">
              <label class="form-label">{{ 'agile.sprint.goal' | transloco }}</label>
              <textarea class="form-control mb-3" rows="2" [(ngModel)]="sprintForm.goal" maxlength="1000"></textarea>
              <div class="row g-2 mb-3">
                <div class="col">
                  <label class="form-label">{{ 'agile.sprint.startDate' | transloco }}</label>
                  <input type="date" class="form-control" [(ngModel)]="sprintForm.startDate">
                </div>
                <div class="col">
                  <label class="form-label">{{ 'agile.sprint.endDate' | transloco }}</label>
                  <input type="date" class="form-control" [(ngModel)]="sprintForm.endDate">
                </div>
              </div>
              <label class="form-label">{{ 'agile.sprint.status' | transloco }}</label>
              <select class="form-select" [(ngModel)]="sprintForm.status">
                @for (s of sprintStatuses; track s) {
                  <option [value]="s">{{ 'agile.status.' + s | transloco }}</option>
                }
              </select>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="sprintModal.set(null)">{{ 'agile.actions.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveSprint()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'agile.actions.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Backlog item modal -->
    @if (itemModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="itemModal.set(null)" (keydown.escape)="itemModal.set(null)">
        <div class="modal-dialog modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">
                {{ (itemForm.id ? 'agile.item.edit' : 'agile.item.new') | transloco }}
              </h5>
              <button type="button" class="btn-close" (click)="itemModal.set(null)"
                      [attr.aria-label]="'agile.actions.close' | transloco"></button>
            </div>
            <div class="modal-body">
              <label class="form-label">{{ 'agile.item.title' | transloco }}</label>
              <input class="form-control mb-3" [(ngModel)]="itemForm.title" maxlength="255">
              <label class="form-label">{{ 'agile.item.description' | transloco }}</label>
              <textarea class="form-control mb-3" rows="3" [(ngModel)]="itemForm.description" maxlength="2000"></textarea>
              <div class="row g-2 mb-3">
                <div class="col">
                  <label class="form-label">{{ 'agile.item.priority' | transloco }}</label>
                  <select class="form-select" [(ngModel)]="itemForm.priority">
                    @for (p of priorities; track p) {
                      <option [value]="p">{{ 'agile.priority.' + p | transloco }}</option>
                    }
                  </select>
                </div>
                <div class="col">
                  <label class="form-label">{{ 'agile.item.estimate' | transloco }}</label>
                  <input type="number" min="0" step="0.25" class="form-control" [(ngModel)]="itemForm.estimateDays">
                </div>
              </div>
              <div class="row g-2">
                <div class="col">
                  <label class="form-label">{{ 'agile.item.status' | transloco }}</label>
                  <select class="form-select" [(ngModel)]="itemForm.status">
                    @for (s of columns; track s) {
                      <option [value]="s">{{ 'agile.column.' + s | transloco }}</option>
                    }
                  </select>
                </div>
                <div class="col">
                  <label class="form-label">{{ 'agile.item.sprint' | transloco }}</label>
                  <select class="form-select" [(ngModel)]="itemForm.sprintId">
                    <option [ngValue]="null">{{ 'agile.item.unassigned' | transloco }}</option>
                    @for (s of sprints(); track s.id) {
                      <option [ngValue]="s.id">{{ s.name }}</option>
                    }
                  </select>
                </div>
              </div>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="itemModal.set(null)">{{ 'agile.actions.cancel' | transloco }}</button>
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
export class AgileComponent implements OnInit {
  private readonly agile    = inject(AgileService);
  private readonly projects = inject(ProjectService);
  private readonly auth     = inject(AuthService);
  private readonly confirm  = inject(ConfirmService);
  private readonly toast    = inject(ToastService);
  private readonly t        = inject(TranslocoService);
  private readonly router   = inject(Router);
  private readonly route    = inject(ActivatedRoute);

  readonly columns: BacklogItemStatus[]   = BACKLOG_STATUSES;
  readonly priorities: BacklogPriority[]  = BACKLOG_PRIORITIES;
  readonly sprintStatuses: SprintStatus[] = SPRINT_STATUSES;

  selected        = signal<Project | null>(null);
  sprints         = signal<Sprint[]>([]);
  items           = signal<BacklogItem[]>([]);
  currentSprintId = signal<number | null>(null);
  loading         = signal(false);
  saving          = signal(false);

  sprintModal = signal<boolean | null>(null);
  itemModal   = signal<boolean | null>(null);

  sprintForm: { id: number | null; name: string; goal: string;
                startDate: string | null; endDate: string | null; status: SprintStatus } =
    { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };

  itemForm: { id: number | null; title: string; description: string;
              priority: BacklogPriority; estimateDays: number | null;
              status: BacklogItemStatus; sprintId: number | null } =
    { id: null, title: '', description: '', priority: 'MEDIUM',
      estimateDays: null, status: 'TODO', sprintId: null };

  readonly currentSprint = computed(() =>
    this.sprints().find(s => s.id === this.currentSprintId()) ?? null);

  /** Éléments de la colonne courante : ceux du sprint sélectionné, ou le backlog produit. */
  readonly visibleItems = computed(() => {
    const sid = this.currentSprintId();
    return this.items().filter(i => (i.sprintId ?? null) === sid);
  });

  readonly totalDays = computed(() =>
    this.visibleItems().reduce((sum, i) => sum + (Number(i.estimateDays) || 0), 0));

  get canManage(): boolean { return this.auth.hasPermission('MANAGE_AGILE'); }

  ngOnInit(): void {
    const pid = Number(this.route.snapshot.queryParamMap.get('p'));
    if (pid) {
      this.projects.get(pid).subscribe({
        next: p => { this.selected.set(p); this.reload(); },
        error: () => this.selected.set(null)
      });
    }
  }

  itemsIn(status: BacklogItemStatus): BacklogItem[] {
    return this.visibleItems().filter(i => i.status === status);
  }

  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id } });
    this.reload();
  }

  clearSelection(): void {
    this.selected.set(null);
    this.router.navigate([], { queryParams: {} });
  }

  setSprint(id: number | null): void { this.currentSprintId.set(id); }

  private reload(): void {
    const p = this.selected();
    if (!p) return;
    this.loading.set(true);
    this.agile.listSprints(p.id).subscribe({
      next: list => {
        this.sprints.set(list);
        // Ouvrir sur l'itération en cours s'il y en a une : c'est celle qu'on vient consulter.
        if (this.currentSprintId() === null) {
          this.currentSprintId.set(list.find(s => s.status === 'ACTIVE')?.id ?? null);
        }
        this.agile.listBacklog(p.id).subscribe({
          next: b => { this.items.set(b); this.loading.set(false); },
          error: () => { this.loading.set(false); this.toast.error(this.t.translate('agile.msg.loadFailed')); }
        });
      },
      error: () => { this.loading.set(false); this.toast.error(this.t.translate('agile.msg.loadFailed')); }
    });
  }

  // ── Sprint ──────────────────────────────────────────────────────
  openSprintModal(s: Sprint | null): void {
    this.sprintForm = s
      ? { id: s.id, name: s.name, goal: s.goal ?? '',
          startDate: s.startDate ?? null, endDate: s.endDate ?? null, status: s.status }
      : { id: null, name: '', goal: '', startDate: null, endDate: null, status: 'PLANNED' };
    this.sprintModal.set(true);
  }

  saveSprint(): void {
    const p = this.selected();
    if (!p) return;
    if (!this.sprintForm.name.trim()) { this.toast.error(this.t.translate('agile.msg.nameRequired')); return; }

    const body = {
      name: this.sprintForm.name.trim(),
      goal: this.sprintForm.goal || null,
      startDate: this.sprintForm.startDate || null,
      endDate: this.sprintForm.endDate || null,
      status: this.sprintForm.status
    };
    this.saving.set(true);
    const req = this.sprintForm.id
      ? this.agile.updateSprint(p.id, this.sprintForm.id, body)
      : this.agile.createSprint(p.id, body);

    req.subscribe({
      next: () => {
        this.saving.set(false); this.sprintModal.set(null);
        this.toast.success(this.t.translate('agile.msg.sprintSaved'));
        this.reload();
      },
      error: () => { this.saving.set(false); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  async removeSprint(s: Sprint): Promise<void> {
    const p = this.selected();
    if (!p) return;
    if (!await this.confirm.ask(this.t.translate('agile.sprint.deleteConfirm'))) return;
    this.agile.deleteSprint(p.id, s.id).subscribe({
      next: () => {
        if (this.currentSprintId() === s.id) this.currentSprintId.set(null);
        this.toast.success(this.t.translate('agile.msg.sprintDeleted'));
        this.reload();
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }

  // ── Backlog item ────────────────────────────────────────────────
  openItemModal(it: BacklogItem | null): void {
    this.itemForm = it
      ? { id: it.id, title: it.title, description: it.description ?? '',
          priority: it.priority, estimateDays: it.estimateDays ?? null,
          status: it.status, sprintId: it.sprintId ?? null }
      : { id: null, title: '', description: '', priority: 'MEDIUM',
          estimateDays: null, status: 'TODO', sprintId: this.currentSprintId() };
    this.itemModal.set(true);
  }

  saveItem(): void {
    const p = this.selected();
    if (!p) return;
    if (!this.itemForm.title.trim()) { this.toast.error(this.t.translate('agile.msg.titleRequired')); return; }

    const body = {
      title: this.itemForm.title.trim(),
      description: this.itemForm.description || null,
      priority: this.itemForm.priority,
      estimateDays: this.itemForm.estimateDays ?? null,
      status: this.itemForm.status,
      sprintId: this.itemForm.sprintId ?? null
    };
    this.saving.set(true);
    const req = this.itemForm.id
      ? this.agile.updateItem(p.id, this.itemForm.id, body)
      : this.agile.createItem(p.id, body);

    req.subscribe({
      next: () => {
        this.saving.set(false); this.itemModal.set(null);
        this.toast.success(this.t.translate('agile.msg.itemSaved'));
        this.reload();
      },
      error: () => { this.saving.set(false); this.toast.error(this.t.translate('agile.msg.saveFailed')); }
    });
  }

  move(it: BacklogItem, target: BacklogItemStatus): void {
    const p = this.selected();
    if (!p) return;
    this.agile.moveItem(p.id, it.id, target, it.sprintId ?? null).subscribe({
      next: updated => this.items.set(this.items().map(x => x.id === updated.id ? updated : x)),
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }

  async removeItem(it: BacklogItem): Promise<void> {
    const p = this.selected();
    if (!p) return;
    if (!await this.confirm.ask(this.t.translate('agile.item.deleteConfirm'))) return;
    this.agile.deleteItem(p.id, it.id).subscribe({
      next: () => {
        this.items.set(this.items().filter(x => x.id !== it.id));
        this.toast.success(this.t.translate('agile.msg.itemDeleted'));
      },
      error: () => this.toast.error(this.t.translate('agile.msg.saveFailed'))
    });
  }
}
