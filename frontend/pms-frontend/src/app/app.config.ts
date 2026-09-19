import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { provideI18n } from './core/i18n/transloco.providers';

// =============================================================================
// app.config.ts - the start-up settings of the front-end application.
//
// WHAT IT IS: one object that lists the "providers" (the shared services and
// features) Angular must install before the first screen is drawn.
//
// WHERE IT SITS IN THE FLOW:
//   main.ts calls bootstrapApplication(App, appConfig)
//   -> this file switches on the router (with the map from app.routes.ts),
//      the HTTP client (with authInterceptor plugged in), the translations,
//      and fixes the locale used to format dates and numbers
//   -> then App (app.ts) is created and the router shows the first page.
//
// WHY IT EXISTS: this project uses standalone components, so there is no
// AppModule to hold this wiring. Delete this file and nothing works: no
// navigation, no HTTP calls, no token on the requests, no translated text.
// =============================================================================
export const appConfig: ApplicationConfig = {
  providers: [
    // Listens to errors that escape Angular: uncaught errors and rejected
    // promises at browser level, and reports them to Angular's error handler.
    // Why: without it, an error thrown inside a callback that Angular does not
    // own (for example a .then() of a promise) would only appear in the browser
    // console and Angular would never know about it.
    provideBrowserGlobalErrorListeners(),
    // Installs the Angular router and gives it the route map of app.routes.ts.
    // Why: this is what makes the URL drive the screen and what makes
    // <router-outlet> in app.ts able to display anything.
    //
    // withComponentInputBinding() sends route parameters straight into the
    // component's @Input() fields. With it, the route 'projects/:id' fills an
    // `id` input of ProjectDetailComponent by itself.
    // Why: it removes the need to inject ActivatedRoute and subscribe to
    // paramMap in every detail screen. Without it those @Input() fields would
    // simply stay undefined and the detail page would load nothing.
    provideRouter(routes, withComponentInputBinding()),
    // Installs HttpClient, the service every API call in the app goes through.
    //
    // withInterceptors([authInterceptor]) puts our own function in the middle of
    // every outgoing request. That interceptor adds the JWT access token
    // (the short-lived signed string that proves who the user is) in the
    // Authorization header, and on a 401 answer it asks the server for a new
    // token using the refresh cookie, then replays the request.
    // Why: doing it in one place means no screen has to remember it.
    // Without this line every request would leave with no token, the back end
    // would answer 401 everywhere, and the user would look logged out one
    // second after logging in.
    provideHttpClient(withInterceptors([authInterceptor])),
    // Installs Transloco, the translation engine (French and English catalogs).
    // The details (available languages, fallback, loader) live in
    // core/i18n/transloco.providers.ts so that this file stays readable.
    provideI18n(),
    // LOCALE_ID is the value Angular's date, number and currency pipes use to
    // decide the format ("19/09/2026" and "1 234,56" in French, "9/19/2026" and
    // "1,234.56" in English).
    // The chosen language is read from localStorage BEFORE the first render, so
    // dates and numbers are already right on the very first screen, without
    // waiting for a language change.
    // Why a useFactory and not a plain value: the answer depends on what this
    // user saved earlier, so it has to be computed at start-up.
    // 'pms_lang' is the same storage key LanguageService writes when the user
    // uses the language switcher; anything that is not 'en' falls back to 'fr',
    // which also protects against a junk value typed by hand in the browser.
    // Without this provider Angular would keep its built-in 'en-US' locale, and
    // a French user would see 12/31/2026 on a screen written in French.
    { provide: LOCALE_ID, useFactory: () => localStorage.getItem('pms_lang') === 'en' ? 'en-US' : 'fr' }
  ]
};
