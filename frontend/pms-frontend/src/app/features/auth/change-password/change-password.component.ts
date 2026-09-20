import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';

/**
 * Screen where a logged-in user sets a new password, typically right after a forced first
 * login (server firstLogin flag). Stays reachable while FirstLoginFilter blocks other
 * endpoints, so a new account is never stuck with no way to comply.
 */
@Component({
  selector: 'app-change-password',
  // standalone: true lets the route lazy-load this file via loadComponent().
  standalone: true,
  // FormsModule enables [(ngModel)]/#f="ngForm"; TranslocoModule the | transloco pipe.
  imports: [CommonModule, FormsModule, TranslocoModule],
  // Loads only the 'auth' translation scope, instead of the global bundle every screen ships.
  providers: [provideTranslocoScope('auth')],
  // Inline template; only HTML comments (<!-- -->) are valid inside it, not // or /* */.
  template: `
    <div style="min-height:100%;display:flex;align-items:center;justify-content:center;background:var(--bg);padding:2rem">
      <div class="card" style="width:400px">
        <div class="card-body p-4">
          <h5 style="font-size:1.125rem;font-weight:700;color:var(--text-1);margin:0 0 .25rem">{{ 'auth.changePassword.title' | transloco }}</h5>
          <p style="font-size:13px;color:var(--text-2);margin:0 0 1.5rem">{{ 'auth.changePassword.subtitle' | transloco }}</p>

          <!-- ngSubmit handles Enter-to-submit too; #f="ngForm" exposes f.valid to the button below. -->
          <form (ngSubmit)="submit()" #f="ngForm">
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.current' | transloco }}</label>
              <!-- name="current" registers this field with #f so f.valid can track it. -->
              <input type="password" class="form-control" name="current"
                     [(ngModel)]="current" required>
            </div>
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.new' | transloco }}</label>
              <!-- minlength=8 mirrors the server's @Size(min=8); a UX hint only, the server re-checks. -->
              <input type="password" class="form-control" name="next"
                     [(ngModel)]="next" required minlength="8">
              <div class="form-text">{{ 'auth.changePassword.hint' | transloco }}</div>
            </div>
            <div class="mb-4">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.confirm' | transloco }}</label>
              <!-- Compared to "next" in submit(), not here, so the mismatch message can be translated. -->
              <input type="password" class="form-control" name="confirm"
                     [(ngModel)]="confirm" required>
            </div>

            <!-- error() holds a translation key, not a sentence, hence the transloco pipe. -->
            @if (error()) {
              <div class="alert alert-danger py-2 small">{{ error() | transloco }}</div>
            }
            <!-- Shown during the brief pause before the redirect. -->
            @if (success()) {
              <div class="alert alert-success py-2 small">{{ 'auth.changePassword.successRedirect' | transloco }}</div>
            }

            <!-- Disabled while loading (avoids a double-submit) or while the form is invalid. -->
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
/**
 * Holds the three typed passwords and screen state, and sends the password change.
 * Passwords are plain fields (ngModel writes them directly); loading/error/success are
 * signals so the template reacts to them.
 */
export class ChangePasswordComponent {
  current = '';
  next = '';
  confirm = '';
  // True only while the HTTP call is in the air. Drives the spinner and the locked button.
  loading = signal(false);
  // Holds a translation key (e.g. 'auth.changePassword.mismatch'), never a finished sentence.
  error = signal('');
  success = signal(false);

  // AuthService owns the /api/auth calls and token; Router redirects after success.
  constructor(private auth: AuthService, private router: Router) {}

  /**
   * Validates that the two new-password copies match, then submits the change. Sets the
   * signals and, on success, redirects to the dashboard.
   */
  submit(): void {
    // Checked before any network call: the server only ever receives one new password, so
    // it has no way to catch a typo made here.
    if (this.next !== this.confirm) {
      this.error.set('auth.changePassword.mismatch');
      return;
    }
    this.loading.set(true);
    // Clears any previous error so a corrected retry doesn't still show a stale message.
    this.error.set('');
    // Passwords travel in the POST body, never the URL. Identity comes from the verified
    // token, not the request body, so nobody could pass another user's account here.
    this.auth.changePassword(this.current, this.next).subscribe({
      // The server answered 204 No Content: the password is changed.
      next: () => {
        this.success.set(true);
        this.loading.set(false);
        // Brief pause lets the success message be read; /dashboard is open again now that
        // the server has cleared firstLogin.
        setTimeout(() => this.router.navigate(['/dashboard']), 1500);
      },
      // 401 = wrong old password, 400 = new one fails a server rule; shown as one generic
      // translated message rather than repeating the untranslated server text.
      error: () => {
        this.loading.set(false);
        this.error.set('auth.changePassword.error');
      }
    });
  }
}
