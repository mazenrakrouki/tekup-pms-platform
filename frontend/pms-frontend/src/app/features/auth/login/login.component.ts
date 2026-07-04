import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="min-vh-100 d-flex align-items-center justify-content-center bg-light">
      <div class="card shadow-sm" style="width:380px">
        <div class="card-body p-4">
          <div class="text-center mb-4">
            <i class="bi bi-kanban fs-1 text-primary"></i>
            <h4 class="mt-2 fw-bold">PMS — Connexion</h4>
            <p class="text-muted small">Plateforme de gestion de projets IT</p>
          </div>

          @if (sessionExpired()) {
            <div class="alert alert-warning py-2 small d-flex align-items-center gap-2 mb-3">
              <i class="bi bi-clock-history"></i>
              Votre session a expiré. Veuillez vous reconnecter.
            </div>
          }

          <form (ngSubmit)="submit()" #f="ngForm">
            <div class="mb-3">
              <label class="form-label small fw-semibold">Email</label>
              <input type="email" class="form-control" name="email"
                     [(ngModel)]="email" required autocomplete="username">
            </div>
            <div class="mb-3">
              <label class="form-label small fw-semibold">Mot de passe</label>
              <input type="password" class="form-control" name="password"
                     [(ngModel)]="password" required autocomplete="current-password">
            </div>

            @if (error()) {
              <div class="alert alert-danger py-2 small">{{ error() }}</div>
            }

            <button type="submit" class="btn btn-primary w-100"
                    [disabled]="loading() || !f.valid">
              @if (loading()) { <span class="spinner-border spinner-border-sm me-2"></span> }
              Se connecter
            </button>
          </form>
        </div>
      </div>
    </div>
  `
})
export class LoginComponent implements OnInit {
  email = '';
  password = '';
  loading = signal(false);
  error = signal('');
  sessionExpired = signal(false);

  constructor(private auth: AuthService, private router: Router, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      if (params['expired'] === 'true') this.sessionExpired.set(true);
    });
  }

  submit(): void {
    this.loading.set(true);
    this.error.set('');
    this.auth.login({ email: this.email, password: this.password }).subscribe({
      next: res => {
        this.loading.set(false);
        if (res.firstLogin) {
          this.router.navigate(['/change-password']);
        } else {
          this.router.navigate(['/dashboard']);
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Email ou mot de passe incorrect.');
      }
    });
  }
}
