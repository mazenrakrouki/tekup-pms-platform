import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { Permission, Role } from '../../../core/models/rbac.model';

interface ModuleGroup { module: string; permissions: Permission[]; }

@Component({
  selector: 'app-role-list',
  standalone: true,
  providers: [provideTranslocoScope('admin')],
  imports: [CommonModule, FormsModule, TranslocoModule],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-shield-lock" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'admin.breadcrumb.roles' | transloco }}</span>
      </div>
      <div class="tb-right">
        <button class="btn btn-primary btn-sm" (click)="openCreate()">
          <i class="bi bi-plus-lg"></i>{{ 'admin.roles.new' | transloco }}
        </button>
      </div>
    </div>

    <div class="page-body">
      <div class="card">
        <div class="card-header justify-content-between">
          <span>{{ 'admin.roles.title' | transloco }}</span>
          <div class="input-wrap" style="width:260px;max-width:100%">
            <i class="bi bi-search input-icon"></i>
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.roles.search' | transloco"
                   [ngModel]="search()" (ngModelChange)="search.set($event)">
          </div>
        </div>

        <div class="table-responsive">
          <table class="table table-hover mb-0 align-middle">
            <thead>
              <tr>
                <th>{{ 'admin.roles.table.role' | transloco }}</th>
                <th class="d-none d-md-table-cell">{{ 'common.description' | transloco }}</th>
                <th class="text-center">{{ 'common.permissions' | transloco }}</th>
                <th class="text-center">{{ 'admin.roles.table.users' | transloco }}</th>
                <th class="text-end">{{ 'admin.roles.table.actions' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              @if (loading()) {
                @for (i of [1,2,3,4]; track i) {
                  <tr><td colspan="5"><div class="skeleton-row"></div></td></tr>
                }
              } @else {
                @for (r of filtered(); track r.id) {
                  <tr>
                    <td>
                      <div class="d-flex align-items-center gap-2">
                        <span class="fw-semibold">{{ r.name }}</span>
                        @if (r.system) {
                          <span class="badge-draft" style="font-size:10px" [title]="'admin.roles.systemTitle' | transloco">
                            <i class="bi bi-lock-fill me-1"></i>{{ 'admin.roles.system' | transloco }}
                          </span>
                        }
                      </div>
                    </td>
                    <td class="d-none d-md-table-cell cell-desc">{{ describeRole(r) }}</td>
                    <td class="text-center"><span class="badge-active">{{ r.permissions.length }}</span></td>
                    <td class="text-center cell-muted">{{ r.userCount }}</td>
                    <td class="text-end">
                      <button class="btn btn-ghost btn-icon btn-sm" (click)="openEdit(r)" [title]="'admin.roles.editAction' | transloco">
                        <i class="bi bi-pencil"></i>
                      </button>
                      <button class="btn btn-ghost btn-icon btn-sm"
                              [disabled]="r.system || r.userCount > 0"
                              [title]="deleteHint(r)"
                              (click)="remove(r)"
                              style="color:var(--c-danger,#dc3545)">
                        <i class="bi bi-trash"></i>
                      </button>
                    </td>
                  </tr>
                }
                @empty {
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-shield-lock"></i></div>
                      <div class="es-title">{{ 'admin.roles.empty' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Create / Edit modal -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
        <div class="modal-dialog modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">
                {{ (editing() ? 'admin.roles.form.editTitle' : 'admin.roles.form.newTitle') | transloco }}
              </h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3 mb-3">
                <div class="col-md-5">
                  <label class="form-label">Nom <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.name"
                         placeholder="EX_NOUVEAU_ROLE" [disabled]="editing()?.system ?? false"
                         (ngModelChange)="form.name = $event.toUpperCase()">
                  @if (editing()?.system) {
                    <div style="font-size:11px;color:var(--text-3);margin-top:.25rem">
                      {{ 'admin.roles.form.nameLocked' | transloco }}
                    </div>
                  }
                </div>
                <div class="col-md-7">
                  <label class="form-label">{{ 'common.description' | transloco }}</label>
                  <input type="text" class="form-control" [(ngModel)]="form.description"
                         [placeholder]="'admin.roles.form.descriptionPlaceholder' | transloco">
                </div>
              </div>

              <div class="d-flex align-items-center justify-content-between mb-2">
                <label class="form-label mb-0">{{ 'common.permissions' | transloco }}</label>
                <span class="sel-count">{{ 'admin.roles.form.selected' | transloco: { count: selectedIds().size } }}</span>
              </div>

              <div class="perm-groups">
                @for (g of moduleGroups(); track g.module) {
                  <div class="perm-group">
                    <div class="perm-group-head">
                      <span class="perm-group-title">{{ 'admin.modules.' + g.module | transloco }}</span>
                      <button type="button" class="btn btn-ghost btn-sm perm-toggle"
                              (click)="toggleModule(g)">
                        {{ allSelected(g) ? 'Tout retirer' : 'Tout cocher' }}
                      </button>
                    </div>
                    <div class="perm-grid">
                      @for (p of g.permissions; track p.id) {
                        <label class="perm-item" [class.perm-on]="selectedIds().has(p.id)">
                          <input type="checkbox" [checked]="selectedIds().has(p.id)"
                                 (change)="togglePerm(p.id)">
                          <span class="perm-code">{{ p.code }}</span>
                          @if (describePerm(p)) { <span class="perm-desc">{{ describePerm(p) }}</span> }
                        </label>
                      }
                    </div>
                  </div>
                }
              </div>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    .cell-desc { color: var(--text-2); font-size: 12px; max-width: 340px; }
    .cell-muted { color: var(--text-2); }
    .sel-count { font-size: 12px; color: var(--text-3); }
    .perm-toggle { font-size: 11px; color: var(--c-brand); }
    /* Keep the modal within the viewport and scroll the body — footer (Enregistrer) stays reachable. */
    .modal-content { max-height: calc(100vh - 3.5rem); }
    .modal-body { overflow-y: auto; }
    .perm-groups { display: flex; flex-direction: column; gap: .75rem; padding-right: .25rem; }
    .perm-group { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
    .perm-group-head { display: flex; align-items: center; justify-content: space-between;
      padding: .4rem .75rem; background: var(--surface-2, rgba(0,0,0,.03)); border-bottom: 1px solid var(--border); }
    .perm-group-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    .perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: .25rem; padding: .5rem; }
    .perm-item { display: flex; align-items: baseline; gap: .5rem; padding: .4rem .5rem; border-radius: 8px;
      cursor: pointer; border: 1px solid transparent; }
    .perm-item:hover { background: var(--surface-2, rgba(0,0,0,.03)); }
    .perm-item.perm-on { background: var(--c-brand-dim); border-color: var(--c-brand); }
    .perm-item input { margin-top: 2px; flex-shrink: 0; }
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    .perm-desc { font-size: 11px; color: var(--text-3); flex-basis: 100%; padding-left: 1.4rem; }
  `]
})
export class RoleListComponent implements OnInit {
  private readonly rbac = inject(RbacService);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly t     = inject(TranslocoService);

  roles = signal<Role[]>([]);
  allPermissions = signal<Permission[]>([]);
  loading = signal(true);
  search = signal('');

  showModal = signal(false);
  editing = signal<Role | null>(null);
  saving = signal(false);
  selectedIds = signal<Set<number>>(new Set());
  form: { name: string; description: string } = { name: '', description: '' };

  readonly filtered = computed(() => {
    const s = this.search().toLowerCase().trim();
    const list = this.roles();
    if (!s) return list;
    return list.filter(r =>
      r.name.toLowerCase().includes(s) || this.describeRole(r).toLowerCase().includes(s));
  });

  /**
   * Role and permission descriptions are seeded in French by the migrations. The
   * catalogue carries a translation per code; the stored text stays the fallback so
   * a role or permission created later still shows something.
   */
  private translated(key: string, stored: string | undefined): string {
    const value = this.t.translate(key);
    if (value && value !== key) return value;
    return stored || '';
  }

  describeRole(r: Role): string {
    return this.translated('admin.roleDesc.' + r.name, r.description) || '—';
  }

  describePerm(p: Permission): string {
    return this.translated('admin.permissionDesc.' + p.code, p.description);
  }

  readonly moduleGroups = computed<ModuleGroup[]>(() => {
    const groups = new Map<string, Permission[]>();
    for (const p of this.allPermissions()) {
      (groups.get(p.module) ?? groups.set(p.module, []).get(p.module)!).push(p);
    }
    return [...groups.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([module, permissions]) => ({ module, permissions }));
  });

  ngOnInit(): void {
    this.load();
    this.rbac.listPermissions().subscribe(p => this.allPermissions.set(p));
  }

  load(): void {
    this.loading.set(true);
    this.rbac.listRoles().subscribe({
      next: r => { this.roles.set(r); this.loading.set(false); },
      error: () => { this.loading.set(false); this.toast.error(this.t.translate('admin.roles.msg.loadFailed')); }
    });
  }

  deleteHint(r: Role): string {
    if (r.system) return this.t.translate('admin.roles.msg.cannotDeleteSystem');
    if (r.userCount > 0) return this.t.translate('admin.roles.msg.cannotDeleteAssigned');
    return this.t.translate('admin.roles.msg.deleteTitle');
  }

  openCreate(): void {
    this.editing.set(null);
    this.form = { name: '', description: '' };
    this.selectedIds.set(new Set());
    this.showModal.set(true);
  }

  openEdit(r: Role): void {
    this.editing.set(r);
    this.form = { name: r.name, description: r.description ?? '' };
    this.selectedIds.set(new Set(r.permissions.map(p => p.id)));
    this.showModal.set(true);
  }

  togglePerm(id: number): void {
    this.selectedIds.update(s => {
      const n = new Set(s);
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  }

  allSelected(g: ModuleGroup): boolean {
    return g.permissions.every(p => this.selectedIds().has(p.id));
  }

  toggleModule(g: ModuleGroup): void {
    const on = this.allSelected(g);
    this.selectedIds.update(s => {
      const n = new Set(s);
      for (const p of g.permissions) { on ? n.delete(p.id) : n.add(p.id); }
      return n;
    });
  }

  save(): void {
    const name = this.form.name.trim();
    if (!name) { this.toast.error(this.t.translate('admin.roles.msg.nameRequired')); return; }
    if (!/^[A-Z][A-Z0-9_]*$/.test(name)) {
      this.toast.error(this.t.translate('admin.roles.msg.nameFormat'));
      return;
    }
    this.saving.set(true);
    const req = {
      name,
      description: this.form.description.trim() || undefined,
      permissionIds: [...this.selectedIds()],
    };
    const editing = this.editing();
    const obs = editing ? this.rbac.updateRole(editing.id, req) : this.rbac.createRole(req);
    obs.subscribe({
      next: () => {
        this.saving.set(false);
        this.showModal.set(false);
        this.toast.success(this.t.translate(editing ? 'admin.roles.msg.updated' : 'admin.roles.msg.created'));
        this.load();
      },
      error: (e) => {
        this.saving.set(false);
        this.toast.error(e.error?.detail ?? 'Erreur lors de l\'enregistrement.');
      }
    });
  }

  async remove(r: Role): Promise<void> {
    if (r.system || r.userCount > 0) return;
    if (!await this.confirm.ask(
      this.t.translate('admin.roles.msg.deleteConfirm', { name: r.name }),
      this.t.translate('admin.roles.msg.deleteTitle'))) return;
    this.rbac.deleteRole(r.id).subscribe({
      next: () => { this.toast.success(this.t.translate('admin.roles.msg.deleted')); this.load(); },
      error: (e) => this.toast.error(e.error?.detail ?? 'Suppression impossible.')
    });
  }
}
