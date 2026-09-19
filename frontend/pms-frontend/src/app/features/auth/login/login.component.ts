import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { TranslocoModule, provideTranslocoScope } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { ThemeService } from '../../../core/services/theme.service';
import { LanguageSwitcherComponent } from '../../../layout/language-switcher/language-switcher.component';

/**
 * WHAT THIS FILE IS
 * The login screen: the two-panel page where the user types an e-mail and a password,
 * and the small piece of TypeScript that sends them to the server and decides where the
 * user lands next.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it:
 *   - app.routes.ts, on the route 'login', loads this component lazily
 *     (loadComponent: () => import(...)). "Lazily" means the file is only downloaded when
 *     somebody actually goes to /login.
 *   - core/guards/auth.guard.ts sends here every visitor with no session.
 *   - core/interceptors/auth.interceptor.ts, through AuthService.expireSession(), sends
 *     here with the query parameter ?expired=true when the token could not be renewed.
 * What it calls next:
 *   - AuthService.login(), which does the HTTP POST /api/auth/login, stores the access
 *     token and the identity, and lets the refresh cookie be set by the browser.
 *   - Router, to go to /change-password or to /dashboard.
 *   - ThemeService.toggle() and <app-language-switcher>, so that the look and the
 *     language can be chosen BEFORE having an account open.
 *
 * WHY IT EXISTS
 * This is the only door into the application. Delete it and there is no way to obtain a
 * token at all: every other page is behind authGuard, so the whole site would be an
 * endless redirect to a route that shows nothing.
 *
 * WHAT THIS FILE IS NOT
 * It holds no security. It never checks a role, never decides what the user may do; it
 * only shows a form. The e-mail and the password are checked by the server, and what the
 * user is allowed to do is enforced there too, with @PreAuthorize("hasAuthority('X')") on
 * the service methods (plus ProjectScopeInterceptor for /api/projects/{id}/**, ADR-021).
 * Nothing here can be trusted, because anybody can edit it in the browser tools.
 */
@Component({
  selector: 'app-login',
  // A standalone component declares its own dependencies and needs no NgModule.
  // Why: the route can then load this single file on demand. Without standalone we would
  // have to ship a whole AuthModule, and the login page would drag in code it never uses.
  standalone: true,
  // Everything the template below is allowed to use has to be listed here.
  // CommonModule = the basic Angular directives, FormsModule = [(ngModel)] and #f="ngForm",
  // TranslocoModule = the | transloco pipe, LanguageSwitcherComponent = <app-language-switcher>.
  // Forget one of them and the template silently stops working: for example without
  // FormsModule, [(ngModel)] is treated as an unknown attribute and the fields you type in
  // never reach the email/password fields of the class, so submit() posts two empty strings.
  imports: [CommonModule, FormsModule, TranslocoModule, LanguageSwitcherComponent],
  // Transloco keeps translations in separate files called "scopes". This line asks for the
  // 'auth' scope, which is the file holding every 'auth.*' key used below.
  // Why declare it here and not globally: the auth wording is only needed on this page, so
  // it is downloaded with this page. Without this provider the screen would display the raw
  // keys, and the user would read "auth.login.welcome" instead of "Welcome back".
  providers: [provideTranslocoScope('auth')],
  /* Styles written here are scoped by Angular: it adds a hidden attribute to the elements
     of this component and to these selectors, so a class as common as .login-title cannot
     repaint a title somewhere else in the application. Inside this block only the
     slash-star form of comment is legal - a // would break the stylesheet parser. */
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
    /* ::before adds one extra decorative box inside the panel, and the repeated
       radial-gradient paints the faint blue dot pattern on it.
       Why a pseudo-element instead of painting the panel itself: the panel already has a
       flat dark background and holds the logo and the text. pointer-events: none is what
       keeps this decoration from swallowing the clicks meant for what sits on top of it. */
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
      /* var(--surface, #fff) reads the theme colour, and falls back to white when that
         variable is not defined. The left panel keeps hard-coded dark colours on purpose:
         it is the brand panel and must look the same in light and in dark theme, while
         this right half follows the theme the user picks with the moon/sun button. */
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
      /* The icon only needs 2rem, but a finger needs more: the tappable box is
         stretched to 44px, the size Apple and Material both ask for, while the
         eye keeps its small visual size. */
      width: 2.75rem; height: 2.75rem;
      padding: 0; border: none; background: none; border-radius: 6px;
      /* --text-3 is what the decorative lock and envelope icons use, but it
         only reaches 2.56:1 on the light background. This eye is a control the
         user has to find and click, so it takes --text-2 (4.76:1) to clear the
         3:1 minimum WCAG asks of interactive elements. */
      color: var(--text-2, #64748B); font-size: 14px; line-height: 1;
      cursor: pointer;
      transition: color 140ms ease, background 140ms ease;
    }
    .input-icon-wrap .reveal:hover { color: var(--text-1, #E6EDF3); }
    /* The button sits on top of the input, which already draws its own focus
       ring, so give the button a visible ring of its own for keyboard users. */
    .input-icon-wrap .reveal:focus-visible {
      outline: none;
      color: #2563EB;
      box-shadow: 0 0 0 3px rgba(37,99,235,.28);
    }
    /* prefers-reduced-motion is a setting of the operating system. Some people get
       dizziness or migraine from moving interfaces and switch it on.
       Why: without this rule the colour of the eye button keeps fading in and out for
       them, which is exactly the kind of small animation that setting asks us to drop. */
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

    /* Below 991px - phones and small tablets - the decorative panel is hidden and the form
       takes the whole width. 991px is the Bootstrap "lg" limit, the same one the template
       uses with the classes d-lg-none / d-flex, so both switch at the very same moment.
       Why !important: the panel is a flex item with its own display: flex, and Bootstrap
       utility classes would otherwise fight with this rule. Without this block the form
       would be squeezed into half of a phone screen, next to an image nobody can read. */
    @media (max-width: 991px) {
      .login-left  { display: none !important; }
      .login-right { width: 100%; }
    }
  `],
  // The HTML is written here, inside backticks, instead of a separate .html file.
  // Everything inside these backticks is HTML, so the ONLY legal comment there is the
  // arrow form. A // inside would be printed on the page as plain text.
  template: `
    <div class="login-wrap">

      <!-- ── Left: Branding ── -->
      <div class="login-left">
        <!-- Brand: the small logo square and the product name, top left.
             Note on the wording below: nothing is written in English or French in the
             template. The | transloco pipe takes a KEY, for example 'app.name', and asks
             Transloco for the text of the language currently chosen. Why: the same page
             serves French and English users, and the pipe re-reads the text when the
             language changes. Hard-coded words would stay in one language for ever.
             position-relative is needed because the dotted decoration of the panel is
             painted in an absolutely positioned layer; without it this block would slide
             under that layer. -->
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
          <!-- [attr.alt] with square brackets means the value is an Angular expression,
               not plain text. It has to be a binding here because the alternative text is
               translated. Why alt matters: a screen reader announces it, and it is what a
               user sees when the image cannot be loaded. -->
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

          <!-- Mobile logo. d-flex d-lg-none = shown by default, hidden from the Bootstrap
               "lg" width upwards. Why it exists: on a phone the whole left branding panel
               is hidden by the media query in the styles above, so without this line the
               user would face a bare form with no clue which application it belongs to. -->
          <div class="d-flex d-lg-none align-items-center justify-content-center gap-2 mb-5">
            <i class="bi bi-diagram-3-fill fs-4 text-brand"></i>
            <span style="font-size:16px;font-weight:700;color:var(--text-1)">{{ 'app.name' | transloco }} · ST2I</span>
          </div>

          <!-- Header -->
          <h2 class="login-title">{{ 'auth.login.welcome' | transloco }}</h2>
          <p class="login-sub">{{ 'auth.login.subtitle' | transloco }}</p>

          <!-- Session expired banner.
               @if is the Angular control flow block: the element below is created only
               when the condition is true, and really removed from the page otherwise.
               sessionExpired() with the parentheses READS the signal; Angular remembers
               that this part of the page depends on it and redraws just this block when
               it changes.
               When is it true: the interceptor could not renew the dead access token, so
               AuthService.expireSession() sent the browser to /login?expired=true, and
               ngOnInit below turned the signal on. Without this message the user would be
               thrown back to the login page in the middle of his work with no explanation,
               and would believe the application is broken. -->
          @if (sessionExpired()) {
            <div class="alert alert-warning d-flex align-items-center gap-2 mb-3 fs-12">
              <i class="bi bi-clock-history flex-shrink-0"></i>
              {{ 'auth.login.sessionExpired' | transloco }}
            </div>
          }

          <!-- Form.
               (ngSubmit) fires when the form is submitted, which includes pressing Enter
               inside a field, not only clicking the button. Why not (click) on the button:
               with (click) the keyboard user who types his password and presses Enter
               would reload the page instead of logging in.
               #f="ngForm" gives this form the local name f. FormsModule builds a small
               object that knows whether every required field is filled, and the submit
               button below reads f.valid. Without this name we would have to write the
               "is the form filled" test by hand in the class. -->
          <form (ngSubmit)="submit()" #f="ngForm">

            <div class="mb-3">
              <label class="form-label" for="loginEmail">{{ 'auth.login.email' | transloco }}</label>
              <div class="input-icon-wrap">
                <i class="bi bi-envelope icon"></i>
                <!-- [(ngModel)]="email" is two-way binding: what the user types goes into
                     the email field of the class, and a change made in the class comes
                     back into the box. name="email" is required by FormsModule to register
                     this box inside the #f form; drop it and Angular throws at runtime.
                     required is what makes f.valid false while the box is empty, which is
                     what keeps the submit button greyed out.
                     autocomplete="username" is the standard word browsers and password
                     managers look for. Without it the manager does not recognise the pair
                     and stops offering to fill in the saved account. -->
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
                <!-- [type] is bound, not fixed: 'password' hides the characters behind
                     dots, 'text' shows them. It is the eye button below that flips the
                     showPassword signal. Why bind the type instead of keeping two separate
                     input boxes: two boxes would hold two different values, and the one
                     the user typed in could easily be the one that is never sent.
                     autocomplete="current-password" tells the password manager this is the
                     password of an existing account, not a new one being chosen - with
                     "new-password" the browser would offer to invent a random password
                     right on the login screen. -->
                <input id="loginPassword"
                       [type]="showPassword() ? 'text' : 'password'"
                       class="form-control has-reveal"
                       name="password"
                       [(ngModel)]="password"
                       required
                       autocomplete="current-password"
                       placeholder="••••••••">
                <!-- type="button" matters: inside a form a bare <button> submits it,
                     so revealing the password would try to log you in.
                     showPassword.set(!showPassword()) simply flips the signal, and every
                     part of the page that reads it - the input type, the label, the icon -
                     is redrawn at once.
                     [attr.aria-label] and [attr.title] are translated on the fly and they
                     follow the state, because the button carries an icon and no text: a
                     blind user would otherwise hear only "button". aria-pressed tells the
                     screen reader whether the switch is currently on or off. -->
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

            <!-- "Remember me" is only a box bound to a boolean. It is sent to the server
                 inside the login request, and it is the SERVER that decides how long the
                 refresh cookie lives. Nothing is made longer-lasting here on the browser
                 side - that decision does not belong to code the user can edit. -->
            <div class="mb-4 form-check">
              <input type="checkbox" class="form-check-input" id="rememberMe"
                     name="rememberMe" [(ngModel)]="rememberMe">
              <label class="form-check-label" style="font-size:13px;color:var(--text-2)" for="rememberMe">
                {{ 'auth.login.rememberMe' | transloco }}
              </label>
            </div>

            <!-- Error banner. error() holds a translation KEY, never a finished sentence
                 (see submit() below), so it is passed through the | transloco pipe here.
                 An empty string is falsy, which is why "no error" simply means the block
                 is not drawn. -->
            @if (error()) {
              <div class="alert alert-danger d-flex align-items-center gap-2 mb-3 fs-12">
                <i class="bi bi-exclamation-triangle-fill flex-shrink-0"></i>{{ error() | transloco }}
              </div>
            }

            <!-- The button is locked for two different reasons.
                 loading() - a request is already travelling. Without it, an impatient user
                 clicking three times fires three login calls; the server counts three
                 attempts, and the brute-force protection can lock him out with a 429 while
                 his password was perfectly right.
                 !f.valid - a required field is still empty. Without it we would post an
                 empty e-mail and get a pointless error from the server.
                 The spinner is shown by the same loading() signal, so the user can see why
                 the button no longer answers. -->
            <button type="submit" class="btn-login mb-3" [disabled]="loading() || !f.valid">
              @if (loading()) {
                <span class="spinner-border spinner-border-sm"></span>
              }
              {{ 'auth.login.submit' | transloco }}
            </button>

          </form>

          <!-- Theme toggle + Language + Support.
               Why these two controls are on the login page at all: the normal place for
               them is the top bar, which only exists once you are inside. A user who reads
               French, or who needs the dark theme, must be able to set that BEFORE he owns
               a session. ThemeService is injected as a public field precisely so the
               template can call theme.toggle() and read theme.current() directly. -->
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
 * The class behind the screen above. It holds what the user typed, the state of the page
 * (busy, error, expired session), and the one action submit().
 *
 * "implements OnInit" means Angular will call ngOnInit() once, just after the component is
 * created and its inputs are set. Why not do that work in the constructor: the constructor
 * runs while the component is still being built, which is the wrong moment to start reading
 * the route or to touch anything outside the class.
 */
export class LoginComponent implements OnInit {
  // The three values of the form. They are plain fields, not signals, because [(ngModel)]
  // writes into them directly and nothing else on the page needs to react when they change.
  // The password is never stored anywhere: it lives in this field until submit() posts it,
  // and it dies with the page.
  email = '';
  password = '';
  rememberMe = false;
  // These three ARE signals, because the template must redraw when they change.
  // A signal is a value Angular watches: calling loading.set(true) is enough to grey out
  // the button and show the spinner, with no manual refresh and no subscription to clean up.
  // loading: a login request is on its way.
  loading      = signal(false);
  // error: the translation KEY of the message to show, or '' for no message.
  error        = signal('');
  // sessionExpired: set from the ?expired=true query parameter in ngOnInit().
  sessionExpired = signal(false);
  /** Reveals the password in clear text while the user checks what they typed. */
  showPassword = signal(false);

  // inject() asks Angular for the one shared ThemeService.
  // It is public (no private keyword) on purpose: the template calls theme.toggle() and
  // theme.current(), and a template can only reach public members. readonly stops the
  // field itself from being replaced by mistake; the service keeps its own state.
  readonly theme = inject(ThemeService);

  // The dependencies given through the constructor are private: they are used by the code
  // below, never by the template.
  //   auth   - does the real HTTP call and stores the session
  //   router - moves the user to the next page
  //   route  - lets us read the query parameters of the current URL
  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  /**
   * Runs once when the screen opens. It looks at the URL and decides whether to show the
   * "your session has expired" banner. Gives nothing back.
   *
   * Why read the URL instead of asking AuthService: when the token cannot be renewed, the
   * interceptor wipes the session and navigates to /login?expired=true. By then the
   * service has nothing left to tell us. The reason travels inside the URL, which survives
   * the wipe and even a reload of the page.
   */
  ngOnInit(): void {
    // queryParams is an Observable: a stream of values over time, not a single value.
    // subscribe() is what actually starts listening and runs the function on each value.
    // Why a stream and not a one-shot read: the router reuses the same component when only
    // the query string changes, so a second arrival with ?expired=true has to be seen too.
    this.route.queryParams.subscribe(params => {
      // The comparison is against the STRING 'true'. Everything in a URL is text, so
      // params['expired'] is "true", never the boolean true. Writing
      // if (params['expired']) would also be wrong the other way round: the URL
      // ?expired=false would light the banner up, because "false" is a non-empty string.
      if (params['expired'] === 'true') this.sessionExpired.set(true);
    });
  }

  /**
   * Called when the form is submitted. Sends the credentials through AuthService, then
   * either moves the user on to the next page or shows a message. Gives nothing back; the
   * result is seen through the signals and through the navigation.
   *
   * Why the whole thing goes through AuthService and not through HttpClient here: the
   * service is the single place that stores the access token and the identity. If this
   * screen called the API directly, the token would never be stored, the interceptor would
   * send requests without an Authorization header, and every page after the login would
   * answer 401.
   */
  submit(): void {
    // Lock the button and wipe the previous message before starting.
    // Why clear the error first: after a wrong password, a second correct attempt would
    // otherwise still show the old red banner while it succeeds, which looks like a bug.
    this.loading.set(true);
    this.error.set('');
    // subscribe() is what actually sends the request: an Observable does nothing until
    // somebody listens. Forget it and the login button would look alive but no HTTP call
    // would ever leave the browser.
    this.auth.login({ email: this.email, password: this.password, rememberMe: this.rememberMe }).subscribe({
      // next: the server accepted the credentials. AuthService has already stored the
      // access token and the permissions by this point, and the browser has kept the
      // refresh cookie, so the guards on the next route will let us through.
      next: res => {
        this.loading.set(false);
        // H-2: a brand-new account must change its temporary password before it can do
        // anything. The server marks it with firstLogin and its FirstLoginFilter refuses
        // every other endpoint with 403 until the password is changed. Sending the user
        // straight to /dashboard here would only show him a page full of failed calls.
        if (res.firstLogin) {
          this.router.navigate(['/change-password']);
        } else {
          this.router.navigate(['/dashboard']);
        }
      },
      // error: the server refused. The type is written as { status: number } because the
      // only thing read here is the HTTP status code.
      error: (e: { status: number }) => {
        this.loading.set(false);
        // We store the i18n KEY, the template translates it (so it stays correct after a
        // change of language).
        // Why not store the finished sentence: the user can switch language with the
        // selector at the bottom of this very page. A stored sentence would stay frozen in
        // the old language; a key is translated again at every redraw.
        // 403 = the account exists but has been disabled by an administrator.
        if (e.status === 403)       this.error.set('auth.login.errors.disabled');
        // 429 = too many attempts for this e-mail inside the server's 15-minute window.
        // It must have its own message: telling a locked-out user "wrong password" would
        // make him try again and again and keep the lock alive.
        else if (e.status === 429)  this.error.set('auth.login.errors.tooManyAttempts');
        // Anything else, 401 included, becomes the same vague "invalid credentials".
        // This is deliberate: a message that said "this e-mail does not exist" would let
        // a stranger discover which addresses have an account here.
        else                        this.error.set('auth.login.errors.invalid');
      }
    });
  }
}
