import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { Permission, Role } from '../../../core/models/rbac.model';

// Roles and permissions admin screen: lists roles and opens a modal to edit a role's
// permission set. Authorization is dynamic (permission codes, not role names), so this screen
// is the only way to change who holds what without a SQL migration. Nothing here grants access
// by itself — the server re-checks every permission (see RoleAdminService @PreAuthorize).

// One block of the permission editor: a module name plus its permissions. A plain array (not
// a Map) because @for needs a stable, trackable list; moduleGroups() builds it once below.
interface ModuleGroup { module: string; permissions: Permission[]; }

@Component({
  selector: 'app-role-list',
  standalone: true,
  // Loads public/i18n/admin/<lang>.json under the 'admin.' prefix, lazily, so non-admin pages
  // don't download admin texts.
  providers: [provideTranslocoScope('admin')],
  imports: [CommonModule, FormsModule, TranslocoModule],
  // Inline template (project convention). Only HTML comments work inside this block.
  template: `
    <!-- .topbar/.page-body/.card come from the global styles.scss design system. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-shield-lock" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'admin.breadcrumb.roles' | transloco }}</span>
      </div>
      <div class="tb-right">
        <!-- openCreate() resets the form first so the modal doesn't reopen with the previous edit's data. -->
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
            <!-- [ngModel]/(ngModelChange) split keeps the search signal as single source of truth; filtering is client-side. -->
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
                <!-- Hidden below md: on phone, five columns would push the action buttons off screen. -->
                <th class="d-none d-md-table-cell">{{ 'common.description' | transloco }}</th>
                <th class="text-center">{{ 'common.permissions' | transloco }}</th>
                <th class="text-center">{{ 'admin.roles.table.users' | transloco }}</th>
                <th class="text-end">{{ 'admin.roles.table.actions' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              <!-- Skeleton rows while loading, so a security screen never briefly reads as "no roles". -->
              @if (loading()) {
                @for (i of [1,2,3,4]; track i) {
                  <tr><td colspan="5"><div class="skeleton-row"></div></td></tr>
                }
              } @else {
                <!-- track r.id lets Angular reuse rows on reload instead of rebuilding the whole table. -->
                @for (r of filtered(); track r.id) {
                  <tr>
                    <td>
                      <div class="d-flex align-items-center gap-2">
                        <span class="fw-semibold">{{ r.name }}</span>
                        <!-- Marks built-in roles: server refuses to rename/delete them, but their permissions stay editable. -->
                        @if (r.system) {
                          <span class="badge-draft" style="font-size:10px" [title]="'admin.roles.systemTitle' | transloco">
                            <i class="bi bi-lock-fill me-1"></i>{{ 'admin.roles.system' | transloco }}
                          </span>
                        }
                      </div>
                    </td>
                    <!-- Translated description when available, else the stored text. -->
                    <td class="d-none d-md-table-cell cell-desc">{{ describeRole(r) }}</td>
                    <td class="text-center"><span class="badge-active">{{ r.permissions.length }}</span></td>
                    <!-- Server-computed count, same value the server checks before allowing a delete. -->
                    <td class="text-center cell-muted">{{ r.userCount }}</td>
                    <td class="text-end">
                      <button class="btn btn-ghost btn-icon btn-sm" (click)="openEdit(r)" [title]="'admin.roles.editAction' | transloco">
                        <i class="bi bi-pencil"></i>
                      </button>
                      <!-- Disabled for the two cases the server also refuses (built-in role, or still assigned); remove() re-checks both. -->
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
                <!-- Runs when the search matches nothing. -->
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
    <!-- @if (not a hidden class) so the form doesn't exist in the DOM between edits and can't leak stale data. -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Click outside the dialog closes the modal. -->
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
        <!-- stopPropagation so a click inside (e.g. a checkbox) doesn't bubble up and close the modal. -->
        <div class="modal-dialog modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <!-- One modal serves both modes; editing() is null for create. -->
              <h5 class="modal-title">
                {{ (editing() ? 'admin.roles.form.editTitle' : 'admin.roles.form.newTitle') | transloco }}
              </h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3 mb-3">
                <div class="col-md-5">
                  <label class="form-label">{{ 'common.name' | transloco }} <span class="text-danger">*</span></label>
                  <!-- Locked for built-in roles (server refuses to rename them); forced uppercase to match the server's naming rule up front. -->
                  <input type="text" class="form-control" [(ngModel)]="form.name"
                         placeholder="EX_NOUVEAU_ROLE" [disabled]="editing()?.system ?? false"
                         (ngModelChange)="form.name = $event.toUpperCase()">
                  <!-- Explains the locked field above. -->
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
                <!-- Parameterized translation since the count's position varies by language. -->
                <span class="sel-count">{{ 'admin.roles.form.selected' | transloco: { count: selectedIds().size } }}</span>
              </div>

              <!-- Grouped by module (not a flat list) so over-broad grants on one module are easy to spot. -->
              <div class="perm-groups">
                @for (g of moduleGroups(); track g.module) {
                  <div class="perm-group">
                    <div class="perm-group-head">
                      <!-- Translates the raw module code, e.g. 'admin.modules.PROJET'. -->
                      <span class="perm-group-title">{{ 'admin.modules.' + g.module | transloco }}</span>
                      <!-- Label follows allSelected(g), so it toggles between select-all/deselect-all. -->
                      <button type="button" class="btn btn-ghost btn-sm perm-toggle"
                              (click)="toggleModule(g)">
                        {{ (allSelected(g) ? 'admin.roles.form.deselectAll' : 'admin.roles.form.selectAll') | transloco }}
                      </button>
                    </div>
                    <div class="perm-grid">
                      <!-- track p.id keeps the checkbox tied to the right permission across redraws. -->
                      @for (p of g.permissions; track p.id) {
                        <!-- Label wraps the checkbox for a larger click target; [class.perm-on] highlights selected lines. -->
                        <label class="perm-item" [class.perm-on]="selectedIds().has(p.id)">
                          <!-- Not ngModel on purpose: selectedIds is the single source of truth, also used by toggleModule/save. -->
                          <input type="checkbox" [checked]="selectedIds().has(p.id)"
                                 (change)="togglePerm(p.id)">
                          <!-- The exact code checked server-side in hasAuthority(...), shown for auditability. -->
                          <span class="perm-code">{{ p.code }}</span>
                          <!-- Only rendered when there's text, so an undocumented permission leaves no gap in the grid. -->
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
              <!-- Blocks a double click from sending the create/update request twice. -->
              <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  // Inline styles, scoped to this component by Angular. Only /* */ comments work in this block.
  styles: [`
    /* Animated placeholder row shown while roles load. */
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    /* Respect the OS reduced-motion setting. */
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    .cell-desc { color: var(--text-2); font-size: 12px; max-width: 340px; }
    .cell-muted { color: var(--text-2); }
    .sel-count { font-size: 12px; color: var(--text-3); }
    .perm-toggle { font-size: 11px; color: var(--c-brand); }
    /* Keeps the modal within the viewport and scrolls the body, so Save stays reachable as the permission list grows. */
    .modal-content { max-height: calc(100vh - 3.5rem); }
    .modal-body { overflow-y: auto; }
    .perm-groups { display: flex; flex-direction: column; gap: .75rem; padding-right: .25rem; }
    .perm-group { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
    .perm-group-head { display: flex; align-items: center; justify-content: space-between;
      padding: .4rem .75rem; background: var(--surface-2, rgba(0,0,0,.03)); border-bottom: 1px solid var(--border); }
    .perm-group-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    /* auto-fill/minmax gives a responsive column count without a media query. */
    .perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: .25rem; padding: .5rem; }
    .perm-item { display: flex; align-items: baseline; gap: .5rem; padding: .4rem .5rem; border-radius: 8px;
      cursor: pointer; border: 1px solid transparent; }
    .perm-item:hover { background: var(--surface-2, rgba(0,0,0,.03)); }
    /* Border is transparent above and colored here so selection doesn't shift the layout. */
    .perm-item.perm-on { background: var(--c-brand-dim); border-color: var(--c-brand); }
    .perm-item input { margin-top: 2px; flex-shrink: 0; }
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    /* flex-basis: 100% wraps the description onto its own line, indented under the code. */
    .perm-desc { font-size: 11px; color: var(--text-3); flex-basis: 100%; padding-left: 1.4rem; }
  `]
})
// Holds the displayed data, the modal state, and the methods the template calls. Uses
// OnInit rather than the constructor for the first load, since the component isn't attached
// to the page yet at construction time.
export class RoleListComponent implements OnInit {
  // rbac: HTTP calls; confirm: shared "are you sure?" modal (replaced window.confirm); toast:
  // corner messages; t: Transloco for texts used outside the template.
  private readonly rbac = inject(RbacService);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly t     = inject(TranslocoService);

  // Signals so filtered() and moduleGroups() below stay correct with no manual refresh call.

  // Every role returned by the server, with its permissions and its user count.
  roles = signal<Role[]>([]);
  // Full permission catalogue for the modal's checkboxes; loaded once since it won't change while open.
  allPermissions = signal<Permission[]>([]);
  // Starts true so the first paint shows the skeleton, not an empty table reading as "no roles".
  loading = signal(true);
  // What is typed in the search box.
  search = signal('');

  // Whether the create/edit modal is on screen.
  showModal = signal(false);
  // Role being edited, or null when creating — drives the modal title and PUT vs POST in save().
  editing = signal<Role | null>(null);
  // True while the save request is in flight; blocks a double-click from sending it twice.
  saving = signal(false);
  // Ticked permission ids. A Set, not an array: Set.has() is O(1) for every checkbox on every redraw.
  selectedIds = signal<Set<number>>(new Set());
  // Plain object, not a signal: ngModel writes into it directly. Reset by openCreate/openEdit.
  form: { name: string; description: string } = { name: '', description: '' };

  /**
   * Roles matching the search box. computed() caches the result instead of re-filtering
   * on every redraw; done client-side since the roles list is small.
   */
  readonly filtered = computed(() => {
    // Normalized so " chef " still finds CHEF_PROJET without exact-case matching.
    const s = this.search().toLowerCase().trim();
    const list = this.roles();
    // Empty search: give back the original array as it is, without building a copy for nothing.
    if (!s) return list;
    // Matches name and description: admins often recall what a role does, not its exact code.
    return list.filter(r =>
      r.name.toLowerCase().includes(s) || this.describeRole(r).toLowerCase().includes(s));
  });

  /**
   * Translated text for a key, falling back to the DB-stored text, then ''. Transloco returns
   * the key itself when untranslated, so comparing the result to the key detects a miss.
   */
  private translated(key: string, stored: string | undefined): string {
    const value = this.t.translate(key);
    if (value && value !== key) return value;
    // stored || '' also covers undefined, since the API's description field is optional.
    return stored || '';
  }

  /** Table description for a role; '—' fallback since an empty cell looks like a display bug. */
  describeRole(r: Role): string {
    return this.translated('admin.roleDesc.' + r.name, r.description) || '—';
  }

  /** Modal description for a permission; no dash fallback since the template hides an empty line. */
  describePerm(p: Permission): string {
    return this.translated('admin.permissionDesc.' + p.code, p.description);
  }

  /** Permission catalogue split into one block per module, sorted — a flat checkbox list would hide over-broad grants. */
  readonly moduleGroups = computed<ModuleGroup[]>(() => {
    // Map keyed by module: O(1) lookup instead of re-scanning an array per permission.
    const groups = new Map<string, Permission[]>();
    for (const p of this.allPermissions()) {
      // ?? creates the module's array on first use via set(); the ! tells TS get() can't be undefined now.
      (groups.get(p.module) ?? groups.set(p.module, []).get(p.module)!).push(p);
    }
    // Map has no sort and can't be walked by @for, so copy its entries into an array.
    return [...groups.entries()]
      // localeCompare gives a human-readable order; without it, block order could vary per load.
      .sort((a, b) => a[0].localeCompare(b[0]))
      // Named object so the template reads g.module/g.permissions instead of g[0]/g[1].
      .map(([module, permissions]) => ({ module, permissions }));
  });

  /** Fetches roles and the permission catalogue in parallel; the table renders as soon as roles arrive. */
  ngOnInit(): void {
    this.load();
    // Cold observable: subscribe() is what actually sends the request.
    this.rbac.listPermissions().subscribe(p => this.allPermissions.set(p));
  }

  /** Reloads the roles list after every save/delete, since the server recomputes userCount and sort order. */
  load(): void {
    this.loading.set(true);
    this.rbac.listRoles().subscribe({
      next: r => { this.roles.set(r); this.loading.set(false); },
      // Also clears loading, else a failed request leaves the skeleton spinning forever.
      error: () => { this.loading.set(false); this.toast.error(this.t.translate('admin.roles.msg.loadFailed')); }
    });
  }

  /** Delete-button tooltip: why it's disabled, or the plain delete title — mirrors the server's two rules. */
  deleteHint(r: Role): string {
    if (r.system) return this.t.translate('admin.roles.msg.cannotDeleteSystem');
    if (r.userCount > 0) return this.t.translate('admin.roles.msg.cannotDeleteAssigned');
    return this.t.translate('admin.roles.msg.deleteTitle');
  }

  /** Opens the modal for a new role, resetting state so it isn't pre-filled from a prior edit. */
  openCreate(): void {
    this.editing.set(null);
    this.form = { name: '', description: '' };
    this.selectedIds.set(new Set());
    this.showModal.set(true);
  }

  /**
   * Opens the modal pre-filled with an existing role. Copies the ticked ids into a new Set so
   * Cancel leaves the original role untouched; ?? '' avoids ngModel printing "null".
   */
  openEdit(r: Role): void {
    this.editing.set(r);
    this.form = { name: r.name, description: r.description ?? '' };
    this.selectedIds.set(new Set(r.permissions.map(p => p.id)));
    this.showModal.set(true);
  }

  /** Ticks/unticks one permission. Builds a new Set: mutating the old one in place wouldn't trigger a signal update. */
  togglePerm(id: number): void {
    this.selectedIds.update(s => {
      const n = new Set(s);
      // Add if missing, remove if present.
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  }

  /** True when every permission of a module is ticked; drives both the button label and toggleModule(). */
  allSelected(g: ModuleGroup): boolean {
    return g.permissions.every(p => this.selectedIds().has(p.id));
  }

  /** Ticks or unticks a whole module. Reads allSelected() once before the loop, so the state can't flip mid-loop. */
  toggleModule(g: ModuleGroup): void {
    const on = this.allSelected(g);
    this.selectedIds.update(s => {
      const n = new Set(s);
      for (const p of g.permissions) { on ? n.delete(p.id) : n.add(p.id); }
      return n;
    });
  }

  /** Creates or updates the role being edited; same request body either way, only the verb/URL differ. */
  save(): void {
    const name = this.form.name.trim();
    // Checked client-side for an immediate message; the server re-checks with @NotBlank.
    if (!name) { this.toast.error(this.t.translate('admin.roles.msg.nameRequired')); return; }
    // Mirrors the server's @Pattern on RoleRequest: uppercase start, then letters/digits/underscore.
    // Authorization is always decided by permission code, never role name; this regex is naming/display only.
    if (!/^[A-Z][A-Z0-9_]*$/.test(name)) {
      this.toast.error(this.t.translate('admin.roles.msg.nameFormat'));
      return;
    }
    this.saving.set(true);
    const req = {
      name,
      // Empty description sent as undefined (omitted from JSON) so the DB always holds null, never ''.
      description: this.form.description.trim() || undefined,
      // Complete list, not a diff: the server replaces the role's permissions rather than merging.
      permissionIds: [...this.selectedIds()],
    };
    // Local copy so the same value is used below and inside the later-running callbacks.
    const editing = this.editing();
    // PUT to update, POST to create; nothing sent until subscribe() below.
    const obs = editing ? this.rbac.updateRole(editing.id, req) : this.rbac.createRole(req);
    obs.subscribe({
      next: () => {
        this.saving.set(false);
        this.showModal.set(false);
        this.toast.success(this.t.translate(editing ? 'admin.roles.msg.updated' : 'admin.roles.msg.created'));
        // Reload to pick up server-only values: the generated id and sorted permissions.
        this.load();
      },
      error: (e) => {
        // Cleared here too, else the Save button stays disabled after a failed attempt.
        this.saving.set(false);
        // e.error?.detail is the server's ProblemDetail message (e.g. duplicate name, HTTP 409).
        // Fallback text is hardcoded French, unlike the rest of this screen's translated messages.
        this.toast.error(e.error?.detail ?? 'Erreur lors de l\'enregistrement.');
      }
    });
  }

  /** Deletes a role after confirmation; async only so it can await ConfirmService.ask() (M-11). */
  async remove(r: Role): Promise<void> {
    // Same two rules as the server; the disabled button is only a UI hint, this is the real re-check.
    if (r.system || r.userCount > 0) return;
    // Not undoable, so confirm first; the role name is passed so the modal names it.
    if (!await this.confirm.ask(
      this.t.translate('admin.roles.msg.deleteConfirm', { name: r.name }),
      this.t.translate('admin.roles.msg.deleteTitle'))) return;
    this.rbac.deleteRole(r.id).subscribe({
      // Reload rather than remove locally, in case the server actually refused the delete.
      next: () => { this.toast.success(this.t.translate('admin.roles.msg.deleted')); this.load(); },
      // Same ProblemDetail reading as save(); the French fallback is likewise untranslated.
      error: (e) => this.toast.error(e.error?.detail ?? 'Suppression impossible.')
    });
  }
}
