import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';

/**
 * WHAT THIS FILE IS
 * The single screen where a logged-in user types his old password and chooses a new one.
 * It is one standalone Angular component: the class, the HTML and the styling all live
 * in this one file.
 *
 * WHERE IT SITS IN THE FLOW
 * Who brings the user here:
 *   - app.routes.ts, path 'change-password', among the ShellComponent children, so the
 *     page sits behind authGuard: you must already be logged in to reach it;
 *   - login.component.ts, right after a first login, because the server answered that the
 *     password must be changed (audit item H-2);
 *   - auth.interceptor.ts, when any call came back 403 with the code FIRST_LOGIN_REQUIRED.
 * What this file calls next:
 *   - AuthService.changePassword(), which POSTs { currentPassword, newPassword } to
 *     /api/auth/change-password;
 *   - Router, to send the user to /dashboard once the change worked.
 * On the server that request lands on AuthController.changePassword(), then
 * AuthService.changePassword(), which checks the old password, stores the new hash,
 * turns the firstLogin flag off and revokes the sessions opened with the old password.
 *
 * WHY IT EXISTS
 * Without it a brand-new account would be stuck for ever. While the firstLogin flag is
 * true, FirstLoginFilter refuses almost every endpoint; change-password is one of the few
 * it still allows. If this screen did not exist, the user would be told "change your
 * password" with no place to do it, and a new account could never be used.
 *
 * ONE WORD FIRST: a "signal" is Angular's small box that holds a value. You read it by
 * calling it, error(), and you write it with error.set('...'). When the value inside
 * changes, Angular redraws only the parts of the page that read that box. Signals are
 * used here instead of plain fields because the template must react to them.
 */
@Component({
  // The tag name this component answers to. It is never written by hand here, because the
  // router creates the component; the selector is still needed so Angular can name it.
  selector: 'app-change-password',
  // standalone: the component declares its own dependencies below and needs no NgModule.
  // Why: the route uses loadComponent(), which only works with a standalone component.
  // EXAMPLE without it: the page could not be lazy-loaded and would travel inside the
  // main bundle, downloaded by everyone, including users who never change their password.
  standalone: true,
  // Only what the template really uses is imported. CommonModule for the base directives,
  // FormsModule for ngModel and #f="ngForm", TranslocoModule for the | transloco pipe.
  // EXAMPLE if FormsModule is removed: [(ngModel)] is no longer a known property, the
  // build fails, and nothing typed in the fields would ever reach the class.
  imports: [CommonModule, FormsModule, TranslocoModule],
  // Loads the 'auth' translation file, and only that one, for this component.
  // Why a scope instead of the global file: the texts of the sign-in area stay in their
  // own file instead of swelling the translation bundle that every screen downloads.
  // EXAMPLE without it: 'auth.changePassword.title' finds no translation and the raw key
  // is printed on the page.
  providers: [provideTranslocoScope('auth')],
  // The template is written inline, between backticks. Inside it, only HTML comments
  // <!-- like this --> are legal; // or /* */ would be printed on the page.
  template: `
    <div style="min-height:100%;display:flex;align-items:center;justify-content:center;background:var(--bg);padding:2rem">
      <div class="card" style="width:400px">
        <div class="card-body p-4">
          <h5 style="font-size:1.125rem;font-weight:700;color:var(--text-1);margin:0 0 .25rem">{{ 'auth.changePassword.title' | transloco }}</h5>
          <p style="font-size:13px;color:var(--text-2);margin:0 0 1.5rem">{{ 'auth.changePassword.subtitle' | transloco }}</p>

          <!-- (ngSubmit) runs submit() when the form is sent, by the button or by the -->
          <!-- Enter key. #f="ngForm" gives the whole form a local name, so the button -->
          <!-- lower down can read f.valid. Without ngSubmit the browser would reload -->
          <!-- the page by itself instead of calling our code. -->
          <form (ngSubmit)="submit()" #f="ngForm">
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.current' | transloco }}</label>
              <!-- type="password" hides the characters on screen. -->
              <!-- [(ngModel)] keeps the field and the class property in step in both -->
              <!-- directions. name="current" is what registers the field inside the -->
              <!-- form: with no name, ngForm ignores it and f.valid never changes. -->
              <!-- required: the old password is mandatory, the server refuses without it. -->
              <input type="password" class="form-control" name="current"
                     [(ngModel)]="current" required>
            </div>
            <div class="mb-3">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.new' | transloco }}</label>
              <!-- minlength="8" mirrors the @Size(min = 8) rule carried by the server -->
              <!-- side record ChangePasswordRequest. It is only a comfort check: it -->
              <!-- warns the user at once instead of after a round trip. The server -->
              <!-- checks again, because this browser rule can be skipped by calling -->
              <!-- the API directly with curl or Postman. -->
              <input type="password" class="form-control" name="next"
                     [(ngModel)]="next" required minlength="8">
              <div class="form-text">{{ 'auth.changePassword.hint' | transloco }}</div>
            </div>
            <div class="mb-4">
              <label class="form-label small fw-semibold">{{ 'auth.changePassword.confirm' | transloco }}</label>
              <!-- The second copy of the new password. It is on purpose not compared -->
              <!-- here but in submit(), so the warning can be translated like the rest. -->
              <input type="password" class="form-control" name="confirm"
                     [(ngModel)]="confirm" required>
            </div>

            <!-- @if is the Angular block syntax: the red box exists in the page only -->
            <!-- while the error signal holds something. error() holds a translation -->
            <!-- KEY, not a sentence, which is why it goes through the transloco pipe. -->
            @if (error()) {
              <div class="alert alert-danger py-2 small">{{ error() | transloco }}</div>
            }
            <!-- Shown during the short pause before the redirect, so the user sees that -->
            <!-- the change worked and understands why the page moves on its own. -->
            @if (success()) {
              <div class="alert alert-success py-2 small">{{ 'auth.changePassword.successRedirect' | transloco }}</div>
            }

            <!-- The button is locked while a call is running, which stops a double -->
            <!-- click from sending two change requests, and locked while the form is -->
            <!-- invalid: an empty field, or a new password under 8 characters. -->
            <button type="submit" class="btn btn-primary w-100"
                    [disabled]="loading() || !f.valid">
              <!-- The small spinner is added in front of the label while we wait for -->
              <!-- the server, so the user sees that something is happening. -->
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
 * Holds the three typed passwords and the state of the screen, and sends the change.
 *
 * WHY THE STATE IS SPLIT IN TWO KINDS: the three passwords are plain fields, because
 * [(ngModel)] writes into them directly and the template never has to react to them.
 * loading, error and success are signals, because the template must be redrawn the
 * moment they change. Turning the passwords into signals too would add noise for no gain.
 */
export class ChangePasswordComponent {
  current = '';
  next = '';
  confirm = '';
  // True only while the HTTP call is in the air. Drives the spinner and the locked button.
  loading = signal(false);
  // Holds a translation KEY ('auth.changePassword.mismatch'), never a finished sentence.
  // Why: the same code must show the message in French or in English without a change.
  error = signal('');
  success = signal(false);

  // AuthService is the only place that knows the /api/auth URLs and the token rules;
  // Router is used for the redirect after success. Angular hands both of them over.
  constructor(private auth: AuthService, private router: Router) {}

  /**
   * Runs when the form is sent. Checks that the two copies of the new password match,
   * then asks the server to change the password. Gives nothing back: it works by setting
   * the signals and, when it worked, by moving the user to the dashboard.
   *
   * WHY THE MATCH IS TESTED HERE and not by a form validator: comparing two fields needs
   * a separate cross-field directive placed on the whole form. For one comparison used
   * on one screen, this single line is easier to read and to defend.
   */
  submit(): void {
    // Caught before any network call. Sending a new password the user mistyped twice
    // would lock him out with a password he cannot remember, and the server could not
    // notice anything, because it only ever receives one new password.
    if (this.next !== this.confirm) {
      this.error.set('auth.changePassword.mismatch');
      return;
    }
    this.loading.set(true);
    // The previous message is cleared before the new try. Without this line, a user who
    // just corrected his mistake would still see the old red box while the call runs,
    // and would believe the screen is broken.
    this.error.set('');
    // changePassword() gives back an Observable: nothing is sent until subscribe() runs.
    // The two passwords travel in the body of a POST, never in the URL, so they do not
    // end up in the browser history or in the server access log.
    // The identity of the user is NOT sent: the server reads it from the verified token.
    // EXAMPLE of why that matters: if the account name came from the body, anyone could
    // name somebody else here and this would become a "change anybody's password" call.
    this.auth.changePassword(this.current, this.next).subscribe({
      // The server answered 204 No Content: the password is changed.
      next: () => {
        this.success.set(true);
        this.loading.set(false);
        // The short pause lets the green message be read before the page moves.
        // Going to /dashboard also matters after a first login: the server has just
        // turned the firstLogin flag off, so the rest of the application is open again.
        setTimeout(() => this.router.navigate(['/dashboard']), 1500);
      },
      // Any failure lands here: 401 when the old password is wrong, 400 when the new one
      // breaks a server rule, or no network at all. One generic translated message is
      // shown on purpose; the raw server message is not repeated, as it is not translated.
      error: () => {
        this.loading.set(false);
        this.error.set('auth.changePassword.error');
      }
    });
  }
}
