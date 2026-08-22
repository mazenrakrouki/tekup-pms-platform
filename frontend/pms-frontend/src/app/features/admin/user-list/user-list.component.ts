import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { User, UserCreateResult } from '../../../core/models/user.model';
import { PagedResponse } from '../../../core/models/pagination.model';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { PaginationComponent } from '../../../shared/pagination/pagination.component';
import { environment } from '../../../../environments/environment';

interface Role { id: number; name: string; }
type SortCol = 'lastName' | 'email';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [CommonModule, FormsModule, PaginationComponent],
  styles: [`
    .toolbar { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap;
      padding:.75rem 1rem; border-bottom:1px solid var(--border); }
    .toolbar .count { font-size:12px; color:var(--text-3); margin-left:auto; white-space:nowrap; }

    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }

    .u-name { font-weight:600; color:var(--text-1); }
    .u-mail { color:var(--text-2); }
    .act-info { color:var(--c-brand); }
    .act-warn { color:var(--c-warning); }
    .act-ok   { color:var(--c-success); }
    .act-danger { color:var(--c-danger); }

    .sk-line { height:12px; border-radius:var(--r-xs); }
    .sk-w-40{width:40%} .sk-w-55{width:55%} .sk-w-70{width:70%} .sk-w-30{width:30%}
    .pw-field { }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-person-lines-fill" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">Utilisateurs</span>
      </div>
      <div class="tb-right">
        <button class="btn btn-primary btn-sm" (click)="openCreate()">
          <i class="bi bi-plus-lg"></i>Nouvel utilisateur
        </button>
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">Gestion des utilisateurs</h1>
      </div>

      <div class="card">
        <!-- Toolbar -->
        <div class="toolbar">
          <div class="input-wrap" style="flex:1;min-width:200px;max-width:320px">
            <i class="bi bi-search input-icon"></i>
            <input type="search" class="form-control form-control-sm"
                   placeholder="Rechercher par nom ou email…"
                   [ngModel]="search()" (ngModelChange)="onSearchChange($event)"
                   aria-label="Rechercher un utilisateur">
          </div>

          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="roleFilter()" (ngModelChange)="onRoleChange($event)" aria-label="Filtrer par rôle">
            <option value="">Tous les rôles</option>
            @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
          </select>

          <select class="form-select form-select-sm" style="width:auto"
                  [ngModel]="statusFilter()" (ngModelChange)="onStatusChange($event)" aria-label="Filtrer par statut">
            <option value="">Tous les statuts</option>
            <option value="true">Actif</option>
            <option value="false">Inactif</option>
          </select>

          @if (hasFilters()) {
            <button class="btn btn-ghost btn-sm" (click)="clearFilters()">
              <i class="bi bi-x-lg me-1"></i>Réinitialiser
            </button>
          }

          <span class="count">{{ totalElements() }} résultat{{ totalElements() !== 1 ? 's' : '' }}</span>
        </div>

        <div class="table-responsive">
          <table class="table mb-0 align-middle">
            <thead>
              <tr>
                <th class="th-sort" [class.is-sorted]="sortCol()==='lastName'" [attr.aria-sort]="ariaSort('lastName')"
                    tabindex="0" (click)="toggleSort('lastName')" (keydown.enter)="toggleSort('lastName')" (keydown.space)="toggleSort('lastName'); $event.preventDefault()">
                  <span class="th-inner">Nom <i class="bi caret" [ngClass]="caret('lastName')"></i></span>
                </th>
                <th class="th-sort" [class.is-sorted]="sortCol()==='email'" [attr.aria-sort]="ariaSort('email')"
                    tabindex="0" (click)="toggleSort('email')" (keydown.enter)="toggleSort('email')" (keydown.space)="toggleSort('email'); $event.preventDefault()">
                  <span class="th-inner">Email <i class="bi caret" [ngClass]="caret('email')"></i></span>
                </th>
                <th>Rôle</th>
                <th>Statut</th>
                <th class="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              @if (loading()) {
                @for (i of skeletonRows; track i) {
                  <tr>
                    <td><div class="skeleton sk-line sk-w-55"></div></td>
                    <td><div class="skeleton sk-line sk-w-70"></div></td>
                    <td><div class="skeleton sk-line sk-w-40"></div></td>
                    <td><div class="skeleton sk-line sk-w-40"></div></td>
                    <td><div class="skeleton sk-line sk-w-30 ms-auto"></div></td>
                  </tr>
                }
              } @else {
                @for (u of users(); track u.id) {
                  <tr>
                    <td class="u-name">{{ u.firstName }} {{ u.lastName }}</td>
                    <td class="u-mail">{{ u.email }}</td>
                    <td><span class="role-badge-light">{{ u.roleName }}</span></td>
                    <td>
                      <span [class]="u.active ? 'badge-active' : 'badge-cancelled'">{{ u.active ? 'Actif' : 'Inactif' }}</span>
                    </td>
                    <td class="text-end" style="white-space:nowrap">
                      <button class="btn btn-ghost btn-icon btn-sm" (click)="openEdit(u)"
                              title="Modifier" aria-label="Modifier l'utilisateur">
                        <i class="bi bi-pencil"></i>
                      </button>
                      @if (u.active) {
                        <button class="btn btn-ghost btn-icon btn-sm act-info" (click)="resetAccount(u)"
                                title="Réinitialiser le mot de passe" aria-label="Réinitialiser le mot de passe">
                          <i class="bi bi-key"></i>
                        </button>
                        <button class="btn btn-ghost btn-icon btn-sm act-warn" (click)="deactivate(u)"
                                title="Désactiver" aria-label="Désactiver l'utilisateur">
                          <i class="bi bi-person-dash"></i>
                        </button>
                      } @else {
                        <button class="btn btn-ghost btn-icon btn-sm act-ok" (click)="reactivate(u)"
                                title="Réactiver" aria-label="Réactiver l'utilisateur">
                          <i class="bi bi-person-check"></i>
                        </button>
                      }
                    </td>
                  </tr>
                }
                @empty {
                  <tr>
                    <td colspan="5">
                      @if (hasFilters()) {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-search"></i></div>
                          <div class="es-title">Aucun résultat</div>
                          <div class="es-desc">Aucun utilisateur ne correspond à votre recherche ou à vos filtres.</div>
                          <button class="btn btn-outline-secondary btn-sm mt-3" (click)="clearFilters()">
                            <i class="bi bi-x-lg me-1"></i>Réinitialiser les filtres
                          </button>
                        </div>
                      } @else {
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-people"></i></div>
                          <div class="es-title">Aucun utilisateur</div>
                          <div class="es-desc">Créez le premier compte utilisateur.</div>
                          <button class="btn btn-primary btn-sm mt-3" (click)="openCreate()">
                            <i class="bi bi-plus-lg me-1"></i>Nouvel utilisateur
                          </button>
                        </div>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        @if (!loading() && totalElements() > 0) {
          <app-pagination
            [page]="page()" [pageSize]="pageSize()" [total]="totalElements()"
            (pageChange)="onPage($event)" (pageSizeChange)="onPageSize($event)" />
        }
      </div>
    </div>

    <!-- Modale réinitialisation mot de passe -->
    @if (showResetModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showResetModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-key me-2 act-info"></i>Compte réinitialisé</h5>
              <button type="button" class="btn-close" (click)="showResetModal.set(false)"></button>
            </div>
            <div class="modal-body">
              @if (resetResult()) {
                <p class="mb-3" style="font-size:13px">
                  Le compte de <strong>{{ resetResult()!.userName }}</strong> a été réinitialisé.
                  L'utilisateur devra changer son mot de passe à sa prochaine connexion.
                </p>
                <div class="alert alert-warning py-2" style="font-size:12px">
                  <i class="bi bi-exclamation-triangle me-1"></i>
                  Communiquez ce mot de passe temporaire à l'utilisateur — il ne sera plus affiché.
                </div>
                <div class="input-group mt-2">
                  <input type="text" class="form-control monospace" [value]="resetResult()!.pwd" readonly aria-label="Mot de passe temporaire">
                  <button class="btn btn-outline-secondary" type="button" (click)="copyResetPassword()" title="Copier" aria-label="Copier le mot de passe">
                    <i class="bi bi-clipboard"></i>
                  </button>
                </div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-primary" (click)="showResetModal.set(false)">Fermer</button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modale création / édition -->
    @if (showModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ editingId() ? "Modifier l'utilisateur" : 'Nouvel utilisateur' }}</h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">Prénom <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.firstName" placeholder="Prénom" autocomplete="given-name">
                </div>
                <div class="col-6">
                  <label class="form-label">Nom <span class="text-danger">*</span></label>
                  <input type="text" class="form-control" [(ngModel)]="form.lastName" placeholder="Nom" autocomplete="family-name">
                </div>
                <div class="col-12">
                  <label class="form-label">Email <span class="text-danger">*</span></label>
                  <input type="email" class="form-control" [(ngModel)]="form.email" placeholder="email@example.com" autocomplete="email">
                </div>
                <div class="col-12">
                  <label class="form-label">Rôle <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="form.roleId">
                    <option [value]="0" disabled>Sélectionner un rôle</option>
                    @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
                  </select>
                </div>
              </div>
              @if (errorMsg()) { <div class="alert alert-danger py-2 mt-3">{{ errorMsg() }}</div> }
              @if (initialPassword()) {
                <div class="alert alert-success mt-3">
                  <strong>Utilisateur créé.</strong> Communiquez ce mot de passe initial — il ne sera plus affiché&nbsp;:
                  <div class="input-group mt-2">
                    <input type="text" class="form-control monospace" [value]="initialPassword()" readonly aria-label="Mot de passe initial">
                    <button class="btn btn-outline-secondary" type="button" (click)="copyPassword()" title="Copier" aria-label="Copier le mot de passe">
                      <i class="bi bi-clipboard"></i>
                    </button>
                  </div>
                </div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ initialPassword() ? 'Fermer' : 'Annuler' }}</button>
              @if (!initialPassword()) {
                <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                  @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                  Enregistrer
                </button>
              }
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class UserListComponent implements OnInit {
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  constructor(private http: HttpClient) {}

  users = signal<User[]>([]);
  roles = signal<Role[]>([]);
  loading = signal(true);
  page = signal(0);
  pageSize = signal(20);
  totalElements = signal(0);
  sortCol = signal<SortCol>('lastName');
  sortDir = signal<SortDir>('asc');

  search = signal('');
  roleFilter = signal('');
  statusFilter = signal('');
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  showModal = signal(false);
  editingId = signal<number | null>(null);
  saving = signal(false);
  errorMsg = signal('');
  initialPassword = signal<string | null>(null);
  showResetModal = signal(false);
  resetResult = signal<{ userName: string; pwd: string } | null>(null);

  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8];

  form: { firstName: string; lastName: string; email: string; roleId: number } = {
    firstName: '', lastName: '', email: '', roleId: 0
  };

  hasFilters = () => !!this.search() || !!this.roleFilter() || !!this.statusFilter();

  ngOnInit(): void {
    this.load();
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe(r => this.roles.set(r));
  }

  load(): void {
    this.loading.set(true);
    let params = new HttpParams()
      .set('page', this.page())
      .set('size', this.pageSize())
      .set('sort', `${this.sortCol()},${this.sortDir()}`);
    const s = this.search().trim();
    if (s) params = params.set('search', s);
    if (this.roleFilter()) params = params.set('roleId', this.roleFilter());
    if (this.statusFilter()) params = params.set('active', this.statusFilter());
    this.http.get<PagedResponse<User>>(`${environment.apiUrl}/users`, { params }).subscribe({
      next: res => {
        this.users.set(res.content);
        this.totalElements.set(res.totalElements);
        const maxPage = Math.max(0, Math.ceil(res.totalElements / this.pageSize()) - 1);
        if (this.page() > maxPage) { this.page.set(maxPage); }
        this.loading.set(false);
      },
      error: () => { this.users.set([]); this.totalElements.set(0); this.loading.set(false); this.toast.error('Impossible de charger les utilisateurs.'); }
    });
  }

  onPage(n: number): void { this.page.set(n); this.load(); }
  onPageSize(n: number): void { this.pageSize.set(n); this.page.set(0); this.load(); }

  onSearchChange(value: string): void {
    this.search.set(value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => { this.page.set(0); this.load(); }, 300);
  }
  onRoleChange(value: string): void { this.roleFilter.set(value); this.page.set(0); this.load(); }
  onStatusChange(value: string): void { this.statusFilter.set(value); this.page.set(0); this.load(); }
  clearFilters(): void {
    this.search.set(''); this.roleFilter.set(''); this.statusFilter.set('');
    this.page.set(0); this.load();
  }

  toggleSort(col: SortCol): void {
    if (this.sortCol() === col) this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    else { this.sortCol.set(col); this.sortDir.set('asc'); }
    this.page.set(0);
    this.load();
  }
  ariaSort(col: SortCol): 'ascending' | 'descending' | 'none' {
    if (this.sortCol() !== col) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }
  caret(col: SortCol): string {
    if (this.sortCol() !== col) return 'bi-chevron-expand';
    return this.sortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  openCreate(): void {
    this.editingId.set(null);
    this.form = { firstName: '', lastName: '', email: '', roleId: 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  openEdit(u: User): void {
    this.editingId.set(u.id);
    const role = this.roles().find(r => r.name === u.roleName);
    this.form = { firstName: u.firstName, lastName: u.lastName, email: u.email, roleId: role?.id ?? 0 };
    this.errorMsg.set('');
    this.initialPassword.set(null);
    this.showModal.set(true);
  }

  save(): void {
    if (!this.form.firstName || !this.form.lastName || !this.form.email || !this.form.roleId) {
      this.errorMsg.set('Tous les champs sont requis.');
      return;
    }
    this.saving.set(true);
    this.errorMsg.set('');
    const id = this.editingId();
    if (id) {
      this.http.put<User>(`${environment.apiUrl}/users/${id}`, this.form).subscribe({
        next: () => { this.load(); this.showModal.set(false); this.saving.set(false); this.toast.success('Utilisateur mis à jour.'); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? 'Erreur lors de l\'enregistrement.'); this.saving.set(false); }
      });
    } else {
      this.http.post<UserCreateResult>(`${environment.apiUrl}/users`, this.form).subscribe({
        next: (res) => { this.load(); this.saving.set(false); this.initialPassword.set(res.initialPassword); this.toast.success('Utilisateur créé.'); },
        error: (e) => { this.errorMsg.set(e.error?.message ?? 'Erreur lors de l\'enregistrement.'); this.saving.set(false); }
      });
    }
  }

  copyPassword(): void {
    const pwd = this.initialPassword();
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info('Mot de passe copié.')).catch(() => {});
  }

  async resetAccount(u: User): Promise<void> {
    const confirmed = await this.confirm.ask(
      `Réinitialiser le compte de ${u.firstName} ${u.lastName} ? Un mot de passe temporaire sera généré et devra être changé à la prochaine connexion.`,
      'Réinitialiser le compte'
    );
    if (!confirmed) return;
    this.http.patch<UserCreateResult>(`${environment.apiUrl}/users/${u.id}/reset-account`, {}).subscribe({
      next: (res) => {
        this.resetResult.set({ userName: `${u.firstName} ${u.lastName}`, pwd: res.initialPassword });
        this.showResetModal.set(true);
        this.load();
      },
      error: () => this.toast.error('Erreur lors de la réinitialisation du compte.')
    });
  }

  copyResetPassword(): void {
    const pwd = this.resetResult()?.pwd;
    if (pwd) navigator.clipboard.writeText(pwd).then(() => this.toast.info('Mot de passe copié.')).catch(() => {});
  }

  async deactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(`Désactiver ${u.firstName} ${u.lastName} ?`, 'Désactiver le compte')) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/deactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success('Utilisateur désactivé.'); },
      error: () => this.toast.error('Erreur lors de la désactivation.')
    });
  }

  async reactivate(u: User): Promise<void> {
    if (!await this.confirm.ask(`Réactiver ${u.firstName} ${u.lastName} ?`, 'Réactiver le compte')) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/reactivate`, {}).subscribe({
      next: () => { this.load(); this.toast.success('Utilisateur réactivé.'); },
      error: () => this.toast.error('Erreur lors de la réactivation.')
    });
  }
}
