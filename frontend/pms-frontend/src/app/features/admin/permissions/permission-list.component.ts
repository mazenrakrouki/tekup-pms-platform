import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionWithRoles } from '../../../core/models/rbac.model';

// Read-only "Permissions" catalogue page: every permission grouped by module, with the roles
// that hold it. Guarded by MANAGE_ROLES both by the route and again by the server; no
// create/edit/delete here since permission codes are owned by the code and migrations, not this UI.

// One module (e.g. "PROJET") and its permissions — matches one block in the template.
interface ModuleGroup { module: string; permissions: PermissionWithRoles[]; }

/** Read-only view of the RBAC permission catalogue (ADR-001), grouped by module and searchable. */
@Component({
  selector: 'app-permission-list',
  standalone: true,
  // Loads the 'admin' i18n scope lazily, only when this page opens.
  providers: [provideTranslocoScope('admin')],
  // FormsModule for [ngModel], TranslocoModule for the | transloco pipe.
  imports: [CommonModule, FormsModule, TranslocoModule],
  template: `
    <!-- Layout classes (topbar, page-body, card, etc.) come from src/styles.scss, shared by every page. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-key" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'common.permissions' | transloco }}</span>
      </div>
    </div>

    <div class="page-body">
      <div class="card">
        <div class="card-header justify-content-between">
          <span>{{ 'admin.permissions.title' | transloco }}</span>
          <div class="input-wrap" style="width:260px;max-width:100%">
            <i class="bi bi-search input-icon"></i>
            <!-- Split binding, not [(ngModel)]: a signal needs search.set(v), not property assignment. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.permissions.search' | transloco"
                   [ngModel]="search()" (ngModelChange)="search.set($event)">
          </div>
        </div>

        <!-- Two states, never both: skeleton rows while loading, real table after. -->
        @if (loading()) {
          <div class="table-responsive">
            <table class="table mb-0"><tbody>
              <!-- Skeleton rows keep the card's height stable so it doesn't jump when data arrives. -->
              @for (i of [1,2,3,4,5]; track i) {
                <tr><td><div class="skeleton-row"></div></td></tr>
              }
            </tbody></table>
          </div>
        } @else {
          <!-- One block per module, from the computed below. track g.module avoids rebuilding blocks on every keystroke. -->
          @for (g of filteredGroups(); track g.module) {
            <div class="module-block">
              <div class="module-head">
                <!-- Key built at runtime from the module code, e.g. 'admin.modules.PROJET'. -->
                <span class="module-name">{{ 'admin.modules.' + g.module | transloco }}</span>
                <span class="module-count">{{ g.permissions.length }}</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <!-- Fixed width so the Code column lines up across separately-sized module tables. -->
                      <th style="width:230px">Code</th>
                      <th>{{ 'common.description' | transloco }}</th>
                      <th>{{ 'admin.permissions.roles' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- track p.id: the only stable, unique value here. -->
                    @for (p of g.permissions; track p.id) {
                      <tr>
                        <td><span class="perm-code">{{ p.code }}</span></td>
                        <!-- describe(): translated text, falling back to the stored French description. -->
                        <td class="cell-desc">{{ describe(p) }}</td>
                        <td>
                          <!-- Empty roleNames means the permission blocks everyone; shown explicitly, not as a blank cell. -->
                          @if (p.roleNames.length) {
                            <div class="d-flex flex-wrap gap-1">
                              <!-- Role names are unique here, so the name itself is a safe track value. -->
                              @for (rn of p.roleNames; track rn) {
                                <span class="role-badge-light">{{ rn }}</span>
                              }
                            </div>
                          } @else {
                            <span class="no-role"><i class="bi bi-dash-circle me-1"></i>{{ 'admin.permissions.noRole' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }
          <!-- No match or empty catalogue — without this the card would just look broken. -->
          @if (filteredGroups().length === 0) {
            <div class="empty-state">
              <div class="es-icon"><i class="bi bi-key"></i></div>
              <div class="es-title">{{ 'admin.permissions.empty' | transloco }}</div>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: [`
    /* Page-specific rules only; Angular scopes them so .module-head can't leak to other screens. */

    /* Local class, not the global .skeleton: needs its own height/radius to fit a table cell. */
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    /* Gradient sweeps right to left until the real table replaces these rows. */
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    /* Respects prefers-reduced-motion: keeps the grey bar but drops the sweep animation. */
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    /* Divider between module blocks; removed above the first one to avoid doubling the card-header border. */
    .module-block { border-top: 1px solid var(--border); }
    .module-block:first-of-type { border-top: 0; }
    /* Uses the --surface-2 token so this follows the dark theme automatically. */
    .module-head { display: flex; align-items: center; gap: .5rem; padding: .5rem 1rem;
      background: var(--surface-2, rgba(0,0,0,.03)); }
    .module-name { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    .module-count { font-size: 11px; color: var(--text-3); background: var(--surface-3, rgba(0,0,0,.06));
      border-radius: 20px; padding: 0 .5rem; }
    /* Monospace for easy code comparison; --font-mono isn't defined yet so this uses the generic fallback. */
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    .cell-desc { color: var(--text-2); font-size: 12px; }
    .no-role { font-size: 11px; color: var(--text-3); }
  `]
})
// implements OnInit: TypeScript then flags it if ngOnInit's signature ever drifts.
export class PermissionListComponent implements OnInit {
  // inject() replaces constructor params; these are app-level singletons shared by every screen.
  private readonly rbac = inject(RbacService);
  private readonly toast = inject(ToastService);
  // Injected directly (not just the pipe): describe() builds a translation key at runtime.
  private readonly tr = inject(TranslocoService);

  /**
   * Description shown for a permission: translated text if the key exists, else the
   * French text stored in the DB, else an em dash. Also used by the search filter below.
   */
  describe(p: PermissionWithRoles): string {
    const key = 'admin.permissionDesc.' + p.code;
    const translated = this.tr.translate(key);
    if (translated && translated !== key) return translated;
    return p.description || '—';
  }

  // Signals so filteredGroups (a computed) only reruns when one actually changes.
  // permissions() is the untouched server list, so clearing search needs no new HTTP call.
  permissions = signal<PermissionWithRoles[]>([]);
  // Starts true so the first frame is the skeleton, not a flash of the empty-state message.
  loading = signal(true);
  // What the user typed in the search box.
  search = signal('');

  /**
   * Permissions kept by the search, grouped by module and sorted. computed() caches the
   * result instead of re-filtering on every one of the template's several reads per render.
   */
  readonly filteredGroups = computed<ModuleGroup[]>(() => {
    // Normalized once here so "PROJET", "projet" and " projet " all match the same way.
    const s = this.search().toLowerCase().trim();
    // Empty search skips filtering entirely rather than running it for nothing.
    const list = s
      // Matches code, translated description, module, or any role name (lets you audit a role by typing its name).
      ? this.permissions().filter(p =>
          p.code.toLowerCase().includes(s) ||
          this.describe(p).toLowerCase().includes(s) ||
          p.module.toLowerCase().includes(s) ||
          p.roleNames.some(rn => rn.toLowerCase().includes(s)))
      : this.permissions();

    // Map, not a plain object: avoids collisions with inherited names like "constructor".
    const groups = new Map<string, PermissionWithRoles[]>();
    for (const p of list) {
      const arr = groups.get(p.module);
      // get() returns undefined for a new module, so create the array first.
      if (arr) arr.push(p); else groups.set(p.module, [p]);
    }
    // Map has no sort/map(), so copy its entries into an array first.
    return [...groups.entries()]
      // localeCompare handles accented letters correctly, unlike a plain < comparison.
      .sort((a, b) => a[0].localeCompare(b[0]))
      // Unpacks each [module, permissions] pair into the ModuleGroup shape the template expects.
      .map(([module, permissions]) => ({ module, permissions }));
  });

  /** Loads the catalogue once the component is ready (not in the constructor, to keep it test-friendly). */
  ngOnInit(): void {
    // Cold observable: nothing is sent until subscribe() is called.
    this.rbac.listPermissionsWithRoles().subscribe({
      // Setting permissions() triggers the computed above; loading(false) swaps in the real table.
      next: p => { this.permissions.set(p); this.loading.set(false); },
      // Also clears loading, else a server error leaves the skeleton spinning forever.
      // A 403 lands here too: the server's MANAGE_ROLES check is the one that actually decides.
      error: () => { this.loading.set(false); this.toast.error('Impossible de charger les permissions.'); }
    });
    // No unsubscribe needed: HttpClient observables complete on their own after one value.
  }
}
