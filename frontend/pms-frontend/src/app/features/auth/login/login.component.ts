import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { LanguageSwitcherComponent } from '../../../layout/language-switcher/language-switcher.component';

/**
 * Two-panel login screen: branding on the left, the email/password form on the right.
 * The only door into the app - every other route sits behind authGuard. Holds no security
 * itself; the server enforces access with @PreAuthorize("hasAuthority(...)") (ADR-021).
 */
@Component({
  selector: 'app-login',
  // standalone: true lets the route lazy-load this file only when /login is visited.
  standalone: true,
  // FormsModule enables [(ngModel)]/#f="ngForm"; TranslocoModule the pipe; LanguageSwitcherComponent <app-language-switcher>.
  imports: [CommonModule, FormsModule, TranslocoModule, LanguageSwitcherComponent],
  // Loads only the 'auth' translation scope, kept separate from the global bundle.
  providers: [provideTranslocoScope('auth')],
  /* Angular scopes these styles to this component's elements only, so a class as common as
     .login-title can't repaint a title elsewhere. Only block comments are legal in here. */
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
    /* Decorative dot pattern; pointer-events:none keeps it from swallowing clicks meant
       for the content painted above it. */
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
      /* Falls back to white when the theme variable isn't defined. The left panel stays
         hard-coded dark on purpose (it's the brand block); this side follows the user's theme. */
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

    /* Room on the right so a long password never runs under the eye button. */
    .input-icon-wrap input.has-reveal { padding-right: 2.5rem; }
    .input-icon-wrap .reveal {
      position: absolute; right: .35rem; top: 50%;
      transform: translateY(-50%);
      display: flex; align-items: center; justify-content: center;
      /* Visual icon is small, but the tappable area is stretched to the 44px Apple/Material ask. */
      width: 2.75rem; height: 2.75rem;
      padding: 0; border: none; background: none; border-radius: 6px;
      /* --text-2 (4.76:1), not --text-3 (2.56:1): this is an interactive control and must
         clear the 3:1 WCAG minimum, unlike the decorative icons that use --text-3. */
      color: var(--text-2, #64748B); font-size: 14px; line-height: 1;
      cursor: pointer;
      transition: color 140ms ease, background 140ms ease;
    }
    .input-icon-wrap .reveal:hover { color: var(--text-1, #E6EDF3); }
    /* Own focus ring, since this button sits on top of the input's. */
    .input-icon-wrap .reveal:focus-visible {
      outline: none;
      color: #2563EB;
      box-shadow: 0 0 0 3px rgba(37,99,235,.28);
    }
    /* Skips the color-fade animation for users with prefers-reduced-motion on. */
    @media (prefers-reduced-motion: reduce) {
      .input-icon-wrap .reveal { transition: none; }
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

    /* Below 991px (Bootstrap's "lg" break, same one the template's d-lg-none uses) the
       branding panel hides and the form takes the full width. !important overrides
       Bootstrap's own flex utility classes on .login-left/.login-right. */
    @media (max-width: 991px) {
      .login-left  { display: none !important; }
      .login-right { width: 100%; }
    }
  `],
  // Inline template; only HTML comments (<!-- -->) are valid inside it, not // or /* */.
  template: `
    <div class="login-wrap">

      <!-- ── Left: Branding ── -->
      <div class="login-left">
        <!-- Logo + name; text comes through transloco keys, never hard-coded, so the same
             page serves French and English. position-relative keeps this above the
             decorative ::before dot layer. -->
        <div class="position-relative d-flex align-items-center gap-3">
          <div class="login-brand-icon">
            <i class="bi bi-diagram-3-fill text-white"></i>
          </div>
          <div>
            <div style="font-size:13px;font-weight:700;color:#F3F4F6;line-height:1.2">{{ 'app.name' | transloco }}</div>
            <div class="login-hero-dim fs-10" style="text-transform:uppercase;letter-spacing:.06em">{{ 'app.tagline' | transloco }}</div>
          </div>
        </div>

        <!-- Hero: the big illustration in the middle of the branding panel. -->
        <div class="position-relative d-flex flex-column align-items-center text-center">
          <!-- [attr.alt] binds so the alt text is translated too, and is read by screen readers. -->
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

          <!-- Mobile-only logo: the branding panel is hidden below the "lg" width, so this
               replaces it so the form isn't left unbranded. -->
          <div class="d-flex d-lg-none align-items-center justify-content-center gap-2 mb-5">
            <i class="bi bi-diagram-3-fill fs-4 text-brand"></i>
            <span style="font-size:16px;font-weight:700;color:var(--text-1)">{{ 'app.name' | transloco }} · ST2I</span>
          </div>

          <!-- Header -->
          <h2 class="login-title">{{ 'auth.login.welcome' | transloco }}</h2>
          <p class="login-sub">{{ 'auth.login.subtitle' | transloco }}</p>

          <!-- Shown when the interceptor couldn't renew the access token and redirected
               here with ?expired=true; the flag is read in ngOnInit() below. -->
          @if (sessionExpired()) {
            <div class="alert alert-warning d-flex align-items-center gap-2 mb-3 fs-12">
              <i class="bi bi-clock-history flex-shrink-0"></i>
              {{ 'auth.login.sessionExpired' | transloco }}
            </div>
          }

          <!-- (ngSubmit) also fires on Enter, so a keyboard user isn't stuck needing to
               click. #f="ngForm" exposes f.valid to the submit button below. -->
          <form (ngSubmit)="submit()" #f="ngForm">

            <div class="mb-3">
              <label class="form-label" for="loginEmail">{{ 'auth.login.email' | transloco }}</label>
              <div class="input-icon-wrap">
                <i class="bi bi-envelope icon"></i>
                <!-- name="email" registers this field with #f; autocomplete="username" lets
                     password managers recognize and offer the saved pair. -->
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
                <!-- [type] toggles via the showPassword() signal (see eye button below).
                     autocomplete="current-password", not "new-password", or the browser
                     would offer to generate a fresh password on the login screen. -->
                <input id="loginPassword"
                       [type]="showPassword() ? 'text' : 'password'"
                       class="form-control has-reveal"
                       name="password"
                       [(ngModel)]="password"
                       required
                       autocomplete="current-password"
                       placeholder="••••••••">
                <!-- type="button" so it never submits the form. showPassword.set(...) flips
                     the signal and every reader of it (input type, icon, aria) redraws.
                     aria-pressed/aria-label carry the state for screen readers, since the
                     button is icon-only. -->
                <button type="button"
                        class="reveal"
                        (click)="showPassword.set(!showPassword())"
                        [attr.aria-label]="(showPassword() ? 'auth.login.hidePassword'
                                                          : 'auth.login.showPassword') | transloco"
                        [attr.title]="(showPassword() ? 'auth.login.hidePassword'
                                                      : 'auth.login.showPassword') | transloco"
                        [attr.aria-pressed]="showPassword()">
                  <i class="bi" [class.bi-eye]="!showPassword()"
                                [class.bi-eye-slash]="showPassword()"></i>
                </button>
              </div>
            </div>

            <!-- Sent to the server, which alone decides the refresh cookie's lifetime. -->
            <div class="mb-4 form-check">
              <input type="checkbox" class="form-check-input" id="rememberMe"
                     name="rememberMe" [(ngModel)]="rememberMe">
              <label class="form-check-label" style="font-size:13px;color:var(--text-2)" for="rememberMe">
                {{ 'auth.login.rememberMe' | transloco }}
              </label>
            </div>

            <!-- error() is a translation key (see submit() below); empty string is falsy,
                 so the block simply doesn't render when there's nothing to show. -->
            @if (error()) {
              <div class="alert alert-danger d-flex align-items-center gap-2 mb-3 fs-12">
                <i class="bi bi-exclamation-triangle-fill flex-shrink-0"></i>{{ error() | transloco }}
              </div>
            }

            <!-- Disabled while loading() (repeated clicks would trip the server's
                 brute-force lockout) or while a required field is still empty. -->
            <button type="submit" class="btn-login mb-3" [disabled]="loading() || !f.valid">
              @if (loading()) {
                <span class="spinner-border spinner-border-sm"></span>
              }
              {{ 'auth.login.submit' | transloco }}
            </button>

          </form>

          <!-- Theme + language must be choosable here, before the user has a session, since
               the usual controls for them live in the top bar once inside the app. -->
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
/**
 * Backs the screen above: form state, the page's busy/error/expired-session state, and
 * submit(). ngOnInit() (not the constructor) reads the route, since the component isn't
 * fully set up yet during construction.
 */
export class LoginComponent implements OnInit {
  // Plain fields, not signals: [(ngModel)] writes them directly and nothing else needs to
  // react when they change. The password lives only here until submit() posts it.
  email = '';
  password = '';
  rememberMe = false;
  // Signals: the template must redraw when these change (spinner, error banner, expired banner).
  // loading: a login request is on its way.
  loading      = signal(false);
  // error: the translation KEY of the message to show, or '' for no message.
  error        = signal('');
  // sessionExpired: set from the ?expired=true query parameter in ngOnInit().
  sessionExpired = signal(false);
  /** Reveals the password in clear text while the user checks what they typed. */
  showPassword = signal(false);

  // Public + readonly: the template calls theme.toggle()/theme.current() directly; readonly
  // just stops the field itself from being reassigned.
  readonly theme = inject(ThemeService);

  // auth handles the HTTP call and stores the session; router navigates on; route reads
  // the current URL's query parameters.
  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  /**
   * Sets the "session expired" banner from the ?expired=true query param, which the
   * interceptor adds when it redirects here after failing to renew the access token.
   */
  ngOnInit(): void {
    // Subscribed, not read once: the router reuses this component when only the query
    // string changes, so a later ?expired=true arrival must be seen too.
    this.route.queryParams.subscribe(params => {
      // Compared to the string 'true': URL values are always text, and '?expired=false'
      // would otherwise also be truthy.
      if (params['expired'] === 'true') this.sessionExpired.set(true);
    });
  }

  /**
   * Submits the credentials through AuthService, then routes onward or shows an error.
   * Goes through AuthService (not HttpClient directly) because it's the one place that
   * stores the access token every later request needs.
   */
  submit(): void {
    // Clears any previous error so a correct retry doesn't still show the old banner while
    // this one loads.
    this.loading.set(true);
    this.error.set('');
    // subscribe() is what actually sends the request; an Observable does nothing alone.
    this.auth.login({ email: this.email, password: this.password, rememberMe: this.rememberMe }).subscribe({
      // AuthService has already stored the access token and permissions by this point, and
      // the browser has kept the refresh cookie, so the next route's guards will pass.
      next: res => {
        this.loading.set(false);
        // firstLogin: FirstLoginFilter blocks every other endpoint until the password is
        // changed, so sending straight to /dashboard would just show failed calls.
        if (res.firstLogin) {
          this.router.navigate(['/change-password']);
        } else {
          this.router.navigate(['/dashboard']);
        }
      },
      // Only the HTTP status code is read here.
      error: (e: { status: number }) => {
        this.loading.set(false);
        // Stored as a translation key, not a finished sentence, so it re-translates if the
        // user switches language via the selector on this same page.
        // 403 = the account exists but has been disabled by an administrator.
        if (e.status === 403)       this.error.set('auth.login.errors.disabled');
        // 429 needs its own message: telling a locked-out user "wrong password" would just
        // make them retry and extend the lockout.
        else if (e.status === 429)  this.error.set('auth.login.errors.tooManyAttempts');
        // Deliberately vague otherwise: a specific "no such email" message would let a
        // stranger discover which addresses have an account here.
        else                        this.error.set('auth.login.errors.invalid');
      }
    });
  }
}
