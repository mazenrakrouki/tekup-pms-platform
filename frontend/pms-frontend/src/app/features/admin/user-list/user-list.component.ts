import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { User } from '../../../core/models/user.model';
import { environment } from '../../../../environments/environment';

interface Role { id: number; name: string; }

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-people me-2"></i>Gestion des utilisateurs</h5>
    </div>
    <div class="p-4">
      <div class="card">
        <div class="card-header bg-white py-3 d-flex justify-content-between align-items-center">
          <span class="fw-semibold">Utilisateurs ({{ users().length }})</span>
          <button class="btn btn-primary btn-sm" (click)="openCreate()">
            <i class="bi bi-plus-lg me-1"></i>Nouvel utilisateur
          </button>
        </div>
        <div class="table-responsive">
          <table class="table table-hover mb-0 align-middle">
            <thead class="table-light">
              <tr><th>Nom</th><th>Email</th><th>Rôle</th><th>Statut</th><th class="text-end">Actions</th></tr>
            </thead>
            <tbody>
              @for (u of users(); track u.id) {
                <tr>
                  <td class="fw-semibold">{{ u.firstName }} {{ u.lastName }}</td>
                  <td>{{ u.email }}</td>
                  <td><span class="badge bg-secondary">{{ u.roleName }}</span></td>
                  <td>
                    <span [class]="u.active ? 'badge bg-success' : 'badge bg-secondary'">
                      {{ u.active ? 'Actif' : 'Inactif' }}
                    </span>
                  </td>
                  <td class="text-end">
                    <button class="btn btn-sm btn-outline-secondary me-1" (click)="openEdit(u)" title="Modifier">
                      <i class="bi bi-pencil"></i>
                    </button>
                    @if (u.active) {
                      <button class="btn btn-sm btn-outline-warning" (click)="deactivate(u)" title="Désactiver">
                        <i class="bi bi-person-dash"></i>
                      </button>
                    }
                  </td>
                </tr>
              }
              @empty {
                <tr><td colspan="5" class="text-center py-4 text-muted">Aucun utilisateur</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Modal backdrop -->
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
              <div class="mb-3">
                <label class="form-label fw-semibold">Prénom <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="form.firstName" placeholder="Prénom">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Nom <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="form.lastName" placeholder="Nom">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Email <span class="text-danger">*</span></label>
                <input type="email" class="form-control" [(ngModel)]="form.email" placeholder="email@example.com">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Rôle <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="form.roleId">
                  <option [value]="0" disabled>Sélectionner un rôle</option>
                  @for (r of roles(); track r.id) {
                    <option [value]="r.id">{{ r.name }}</option>
                  }
                </select>
              </div>
              @if (errorMsg()) {
                <div class="alert alert-danger py-2">{{ errorMsg() }}</div>
              }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showModal.set(false)">Annuler</button>
              <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class UserListComponent implements OnInit {
  users = signal<User[]>([]);
  roles = signal<Role[]>([]);
  showModal = signal(false);
  editingId = signal<number | null>(null);
  saving = signal(false);
  errorMsg = signal('');

  form: { firstName: string; lastName: string; email: string; roleId: number } = {
    firstName: '', lastName: '', email: '', roleId: 0
  };

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
    this.http.get<Role[]>(`${environment.apiUrl}/roles`).subscribe(r => this.roles.set(r));
  }

  load(): void {
    this.http.get<User[]>(`${environment.apiUrl}/users`).subscribe(list => this.users.set(list));
  }

  openCreate(): void {
    this.editingId.set(null);
    this.form = { firstName: '', lastName: '', email: '', roleId: 0 };
    this.errorMsg.set('');
    this.showModal.set(true);
  }

  openEdit(u: User): void {
    this.editingId.set(u.id);
    const role = this.roles().find(r => r.name === u.roleName);
    this.form = { firstName: u.firstName, lastName: u.lastName, email: u.email, roleId: role?.id ?? 0 };
    this.errorMsg.set('');
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
    const req = id
      ? this.http.put<User>(`${environment.apiUrl}/users/${id}`, this.form)
      : this.http.post<User>(`${environment.apiUrl}/users`, this.form);

    req.subscribe({
      next: () => { this.load(); this.showModal.set(false); this.saving.set(false); },
      error: (e) => { this.errorMsg.set(e.error?.message ?? 'Erreur lors de l\'enregistrement.'); this.saving.set(false); }
    });
  }

  deactivate(u: User): void {
    if (!confirm(`Désactiver ${u.firstName} ${u.lastName} ?`)) return;
    this.http.patch(`${environment.apiUrl}/users/${u.id}/deactivate`, {}).subscribe(() => this.load());
  }
}
