import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-change-password',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslocoModule],
  providers: [provideTranslocoScope('auth')],
  template: `
    <div style="min-height:100%;display:flex;align-items:center;justify-content:center;background:var(--bg);padding:2rem">
      <div class="card" style="width:400px">
        <div class="card-body p-4">
          <h5 style="font-size:1.125rem;font-weight:700;color:var(--text-1);margin:0 0 .25rem">{{ 'auth.changePassword.title' | transloco }}</h5>
          <p style="font-size:13px;color:var(--text-2);margin:0 0 1.5rem">{{ 'auth.changePassword.subtitle' | transloco }}</p>

          <form (ngSubmit)="submit()" #f="ngForm">
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.current' | transloco }}</label>
              <input type="password" class="form-control" name="current"
                     [(ngModel)]="current" required>
            </div>
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.new' | transloco }}</label>
              <input type="password" class="form-control" name="next"
                     [(ngModel)]="next" required minlength="8">
              <div class="form-text">{{ 'auth.changePassword.hint' | transloco }}</div>
            </div>
            <div class="mb-4">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.confirm' | transloco }}</label>
              <input type="password" class="form-control" name="confirm"
                     [(ngModel)]="confirm" required>
            </div>

            @if (error()) {
              <div class="alert alert-danger py-2 small">{{ error() | transloco }}</div>
            }
            @if (success()) {
              <div class="alert alert-success py-2 small">{{ 'auth.changePassword.successRedirect' | transloco }}</div>
            }

            <button type="submit" class="btn btn-primary w-100"
                    [disabled]="loading() || !f.valid">
              @if (loading()) { <span class="spinner-border spinner-border-sm me-2"></span> }
              {{ 'common.save' | transloco }}
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
      this.error.set('auth.changePassword.mismatch');
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
      error: () => {
        this.loading.set(false);
        this.error.set('auth.changePassword.error');
      }
    });
  }
}
