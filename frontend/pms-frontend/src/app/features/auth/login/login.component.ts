import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { LanguageSwitcherComponent } from '../../../layout/language-switcher/language-switcher.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslocoModule, LanguageSwitcherComponent],
  providers: [provideTranslocoScope('auth')],
  styles: [`
    .login-wrap {
      display: flex;
      height: 100%;
    }
    .login-hero-dim { color: rgba(255,255,255,.35); }

    /* ── Left branding panel ── */
    .login-left {
      width: 50%; flex: 0 0 50%;
      background: #111827;
      display: flex; flex-direction: column; justify-content: space-between;
      padding: 2.5rem; position: relative; overflow: hidden;
      color: #fff;
    }
    .login-left::before {
      content: '';
      position: absolute; inset: 0; pointer-events: none;
      background-image: radial-gradient(rgba(37,99,235,.5) 1px, transparent 1px);
      background-size: 28px 28px;
      opacity: .16;
    }

    .login-brand-icon {
      width: 32px; height: 32px;
      background: #2563EB;
      border-radius: 6px;
      display: flex; align-items: center; justify-content: center;
      flex-shrink: 0;
      font-size: 14px;
    }

    .login-hero-text {
      font-size: 1.5rem; font-weight: 700;
      letter-spacing: -.025em; line-height: 1.25;
      color: #F3F4F6;
      margin: 0;
    }
    .login-hero-sub {
      font-size: 13px; color: #6B7280;
      line-height: 1.6; max-width: 300px;
    }

    /* ── Right form panel ── */
    .login-right {
      flex: 1;
      background: var(--surface, #fff);
      display: flex; align-items: center; justify-content: center;
      padding: 2rem;
    }
    .login-form-box { width: 100%; max-width: 400px; }

    .login-title {
      font-size: 1.625rem; font-weight: 700;
      letter-spacing: -.025em;
      color: var(--text-1, #0F172A);
      margin: 0 0 .25rem;
    }
    .login-sub {
      font-size: 13px; color: var(--text-2, #64748B);
      margin: 0 0 1.5rem;
    }

    .input-icon-wrap { position: relative; }
    .input-icon-wrap .icon {
      position: absolute; left: .65rem; top: 50%;
      transform: translateY(-50%);
      color: var(--text-3, #94A3B8); pointer-events: none; font-size: 14px;
    }
    .input-icon-wrap input { padding-left: 2.2rem; }
    .input-icon-wrap input:focus {
      border-color: #2563EB;
      box-shadow: 0 0 0 3px rgba(37,99,235,.12);
      outline: none;
    }

    .btn-login {
      width: 100%; padding: .55rem 1rem;
      background: #2563EB; color: #fff;
      border: none; border-radius: 7px;
      font-size: 13px; font-weight: 600;
      cursor: pointer;
      transition: background 140ms ease;
      display: flex; align-items: center; justify-content: center; gap: .5rem;
    }
    .btn-login:hover:not(:disabled) { background: #1D4ED8; }
    .btn-login:disabled { opacity: .6; cursor: not-allowed; }

    @media (max-width: 991px) {
      .login-left  { display: none !important; }
      .login-right { width: 100%; }
    }
  `],
  template: `
    <div class="login-wrap">

      <!-- ── Left: Branding ── -->
      <div class="login-left">
        <!-- Brand -->
        <div class="position-relative d-flex align-items-center gap-3">
          <div class="login-brand-icon">
            <i class="bi bi-diagram-3-fill text-white"></i>
          </div>
          <div>
            <div style="font-size:13px;font-weight:700;color:#F3F4F6;line-height:1.2">{{ 'app.name' | transloco }}</div>
            <div class="login-hero-dim fs-10" style="text-transform:uppercase;letter-spacing:.06em">{{ 'app.tagline' | transloco }}</div>
          </div>
        </div>

        <!-- Hero -->
        <div class="position-relative d-flex flex-column align-items-center text-center">
          <img
            src="https://lh3.googleusercontent.com/aida-public/AB6AXuCw_yrHN-OmVPPni9xX2nNod_E6MTlwO1lflvFjx-0UznQ8bwUkA4RwBfzUcTVnbQ-5HH23gMON8novBTrdrNinlybsrRx6sUzhLrjTuN8UrDZFRnTOuB-TI3jfnxp7WcHqMwIRDv21_t0z5w__oYTKLe6WGemmNGbYMy7G0tBNC-hc4vnfeq_EM_3VW4O3rdQ8IxGtfB9nsEsgFShhYidPbQ52BLx6wfZLiluM4NwiUdsguWnzG3v7f8gq4nQOMZeqk2w1Rnazt3k"
            [attr.alt]="'app.name' | transloco"
            style="width:160px;height:160px;object-fit:contain;filter:drop-shadow(0 8px 32px rgba(0,0,0,.5));margin-bottom:1.5rem"
          >
          <h2 class="login-hero-text mb-3">{{ 'app.name' | transloco }}</h2>
          <p class="login-hero-sub">{{ 'app.fullName' | transloco }}</p>
        </div>

        <!-- Footer -->
        <div class="position-relative">
          <p class="login-hero-dim fs-11" style="margin:0">{{ 'auth.footer.rights' | transloco }}</p>
        </div>
      </div>

      <!-- ── Right: Form ── -->
      <div class="login-right">
        <div class="login-form-box">

          <!-- Mobile logo -->
          <div class="d-flex d-lg-none align-items-center justify-content-center gap-2 mb-5">
            <i class="bi bi-diagram-3-fill fs-4 text-brand"></i>
            <span style="font-size:16px;font-weight:700;color:var(--text-1)">{{ 'app.name' | transloco }} · ST2I</span>
          </div>

          <!-- Header -->
          <h2 class="login-title">{{ 'auth.login.welcome' | transloco }}</h2>
          <p class="login-sub">{{ 'auth.login.subtitle' | transloco }}</p>

          <!-- Session expired -->
          @if (sessionExpired()) {
            <div class="alert alert-warning d-flex align-items-center gap-2 mb-3 fs-12">
              <i class="bi bi-clock-history flex-shrink-0"></i>
              {{ 'auth.login.sessionExpired' | transloco }}
            </div>
          }

          <!-- Form -->
          <form (ngSubmit)="submit()" #f="ngForm">

            <div class="mb-3">
              <label class="form-label" for="loginEmail">{{ 'auth.login.email' | transloco }}</label>
              <div class="input-icon-wrap">
                <i class="bi bi-envelope icon"></i>
                <input id="loginEmail"
                       type="email"
                       class="form-control"
                       name="email"
                       [(ngModel)]="email"
                       required
                       autocomplete="username"
                       placeholder="admin@pms.local">
              </div>
            </div>

            <div class="mb-3">
              <div class="d-flex justify-content-between align-items-center mb-1">
                <label class="form-label mb-0" for="loginPassword">{{ 'auth.login.password' | transloco }}</label>
                <span class="fs-12" style="color:var(--text-3)">{{ 'auth.login.forgotPassword' | transloco }}</span>
              </div>
              <div class="input-icon-wrap">
                <i class="bi bi-lock icon"></i>
                <input id="loginPassword"
                       type="password"
                       class="form-control"
                       name="password"
                       [(ngModel)]="password"
                       required
                       autocomplete="current-password"
                       placeholder="••••••••">
              </div>
            </div>

            <div class="mb-4 form-check">
              <input type="checkbox" class="form-check-input" id="rememberMe">
              <label class="form-check-label" style="font-size:13px;color:var(--text-2)" for="rememberMe">
                {{ 'auth.login.rememberMe' | transloco }}
              </label>
            </div>

            @if (error()) {
              <div class="alert alert-danger d-flex align-items-center gap-2 mb-3 fs-12">
                <i class="bi bi-exclamation-triangle-fill flex-shrink-0"></i>{{ error() | transloco }}
              </div>
            }

            <button type="submit" class="btn-login mb-3" [disabled]="loading() || !f.valid">
              @if (loading()) {
                <span class="spinner-border spinner-border-sm"></span>
              }
              {{ 'auth.login.submit' | transloco }}
            </button>

          </form>

          <!-- Theme toggle + Language + Support -->
          <div class="d-flex align-items-center justify-content-between">
            <p class="fs-12" style="color:var(--text-2);margin:0">
              {{ 'auth.login.supportQuestion' | transloco }}
              <a href="#" class="fw-medium text-decoration-none text-brand">{{ 'auth.login.contactSupport' | transloco }}</a>
            </p>
            <div class="d-flex align-items-center gap-2">
              <app-language-switcher></app-language-switcher>
              <button (click)="theme.toggle()"
                      [title]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco"
                      [attr.aria-label]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco"
                      style="width:30px;height:30px;border:1px solid var(--border);border-radius:6px;background:transparent;cursor:pointer;display:flex;align-items:center;justify-content:center;color:var(--text-2)">
                <i class="bi fs-13" [class.bi-sun]="theme.current() === 'dark'" [class.bi-moon]="theme.current() === 'light'"></i>
              </button>
            </div>
          </div>

        </div>
      </div>

    </div>
  `
})
export class LoginComponent implements OnInit {
  email = '';
  password = '';
  loading      = signal(false);
  error        = signal('');
  sessionExpired = signal(false);

  readonly theme = inject(ThemeService);

  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

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
      error: (e: { status: number }) => {
        this.loading.set(false);
        // On stocke la CLÉ i18n ; le template la traduit (reste correct après changement de langue).
        if (e.status === 403)       this.error.set('auth.login.errors.disabled');
        else if (e.status === 429)  this.error.set('auth.login.errors.tooManyAttempts');
        else                        this.error.set('auth.login.errors.invalid');
      }
    });
  }
}
