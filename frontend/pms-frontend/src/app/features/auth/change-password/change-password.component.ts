import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-change-password',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="min-vh-100 d-flex align-items-center justify-content-center bg-light">
      <div class="card shadow-sm" style="width:400px">
        <div class="card-body p-4">
          <h5 class="fw-bold mb-1">Changement de mot de passe</h5>
          <p class="text-muted small mb-4">Votre mot de passe doit être changé avant de continuer.</p>

          <form (ngSubmit)="submit()" #f="ngForm">
            <div class="mb-3">
              <label class="form-label small fw-semibold">Mot de passe actuel</label>
              <input type="password" class="form-control" name="current"
                     [(ngModel)]="current" required>
            </div>
            <div class="mb-3">
              <label class="form-label small fw-semibold">Nouveau mot de passe</label>
              <input type="password" class="form-control" name="next"
                     [(ngModel)]="next" required minlength="8">
              <div class="form-text">Minimum 8 caractères, une majuscule, un chiffre.</div>
            </div>
            <div class="mb-4">
              <label class="form-label small fw-semibold">Confirmer le nouveau mot de passe</label>
              <input type="password" class="form-control" name="confirm"
                     [(ngModel)]="confirm" required>
            </div>

            @if (error()) {
              <div class="alert alert-danger py-2 small">{{ error() }}</div>
            }
            @if (success()) {
              <div class="alert alert-success py-2 small">Mot de passe changé. Redirection…</div>
            }

            <button type="submit" class="btn btn-primary w-100"
                    [disabled]="loading() || !f.valid">
              @if (loading()) { <span class="spinner-border spinner-border-sm me-2"></span> }
              Enregistrer
            </button>
          </form>
        </div>
      </div>
    </div>
  `
})
export class ChangePasswordComponent {
  current = '';
  next = '';
  confirm = '';
  loading = signal(false);
  error = signal('');
  success = signal(false);

  constructor(private auth: AuthService, private router: Router) {}

  submit(): void {
    if (this.next !== this.confirm) {
      this.error.set('Les mots de passe ne correspondent pas.');
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.auth.changePassword(this.current, this.next).subscribe({
      next: () => {
        this.success.set(true);
        this.loading.set(false);
        setTimeout(() => this.router.navigate(['/dashboard']), 1500);
      },
      error: (e) => {
        this.loading.set(false);
        this.error.set(e.error?.message ?? 'Erreur lors du changement de mot de passe.');
      }
    });
  }
}
