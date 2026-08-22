import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionWithRoles } from '../../../core/models/rbac.model';

interface ModuleGroup { module: string; permissions: PermissionWithRoles[]; }

@Component({
  selector: 'app-permission-list',
  standalone: true,
  providers: [provideTranslocoScope('admin')],
  imports: [CommonModule, FormsModule, TranslocoModule],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-key" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">Permissions</span>
      </div>
    </div>

    <div class="page-body">
      <div class="card">
        <div class="card-header justify-content-between">
          <span>{{ 'admin.permissions.title' | transloco }}</span>
          <div class="input-wrap" style="width:260px;max-width:100%">
            <i class="bi bi-search input-icon"></i>
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.permissions.search' | transloco"
                   [ngModel]="search()" (ngModelChange)="search.set($event)">
          </div>
        </div>

        @if (loading()) {
          <div class="table-responsive">
            <table class="table mb-0"><tbody>
              @for (i of [1,2,3,4,5]; track i) {
                <tr><td><div class="skeleton-row"></div></td></tr>
              }
            </tbody></table>
          </div>
        } @else {
          @for (g of filteredGroups(); track g.module) {
            <div class="module-block">
              <div class="module-head">
                <span class="module-name">{{ 'admin.modules.' + g.module | transloco }}</span>
                <span class="module-count">{{ g.permissions.length }}</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <th style="width:230px">Code</th>
                      <th>Description</th>
                      <th>{{ 'admin.permissions.roles' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (p of g.permissions; track p.id) {
                      <tr>
                        <td><span class="perm-code">{{ p.code }}</span></td>
                        <td class="cell-desc">{{ p.description || '—' }}</td>
                        <td>
                          @if (p.roleNames.length) {
                            <div class="d-flex flex-wrap gap-1">
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
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    .module-block { border-top: 1px solid var(--border); }
    .module-block:first-of-type { border-top: 0; }
    .module-head { display: flex; align-items: center; gap: .5rem; padding: .5rem 1rem;
      background: var(--surface-2, rgba(0,0,0,.03)); }
    .module-name { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    .module-count { font-size: 11px; color: var(--text-3); background: var(--surface-3, rgba(0,0,0,.06));
      border-radius: 20px; padding: 0 .5rem; }
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    .cell-desc { color: var(--text-2); font-size: 12px; }
    .no-role { font-size: 11px; color: var(--text-3); }
  `]
})
export class PermissionListComponent implements OnInit {
  private readonly rbac = inject(RbacService);
  private readonly toast = inject(ToastService);

  permissions = signal<PermissionWithRoles[]>([]);
  loading = signal(true);
  search = signal('');

  readonly filteredGroups = computed<ModuleGroup[]>(() => {
    const s = this.search().toLowerCase().trim();
    const list = s
      ? this.permissions().filter(p =>
          p.code.toLowerCase().includes(s) ||
          (p.description ?? '').toLowerCase().includes(s) ||
          p.module.toLowerCase().includes(s) ||
          p.roleNames.some(rn => rn.toLowerCase().includes(s)))
      : this.permissions();

    const groups = new Map<string, PermissionWithRoles[]>();
    for (const p of list) {
      const arr = groups.get(p.module);
      if (arr) arr.push(p); else groups.set(p.module, [p]);
    }
    return [...groups.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([module, permissions]) => ({ module, permissions }));
  });

  ngOnInit(): void {
    this.rbac.listPermissionsWithRoles().subscribe({
      next: p => { this.permissions.set(p); this.loading.set(false); },
      error: () => { this.loading.set(false); this.toast.error('Impossible de charger les permissions.'); }
    });
  }
}
