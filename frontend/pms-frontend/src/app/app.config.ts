import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { provideI18n } from './core/i18n/transloco.providers';

// Start-up providers Angular installs before the first screen renders (routes,
// HTTP client, translations, locale). Standalone components mean there's no
// AppModule, so this is the only place this wiring lives.
export const appConfig: ApplicationConfig = {
  providers: [
    // Reports errors Angular doesn't own (e.g. a rejected promise's .then())
    // to Angular's error handler instead of only the browser console.
    provideBrowserGlobalErrorListeners(),
    // Router + route map. withComponentInputBinding() fills a route param
    // (e.g. 'projects/:id') straight into a matching @Input(), so detail
    // screens don't each need to inject ActivatedRoute and subscribe to paramMap.
    provideRouter(routes, withComponentInputBinding()),
    // HttpClient with authInterceptor: adds the JWT to every request's
    // Authorization header and, on 401, refreshes it via the refresh cookie
    // and replays the request — done once here instead of per screen.
    provideHttpClient(withInterceptors([authInterceptor])),
    // Transloco (FR/EN). Details (languages, fallback, loader) live in
    // core/i18n/transloco.providers.ts.
    provideI18n(),
    // Locale for Angular's date/number/currency pipes, read from localStorage
    // before first render so the first screen already formats correctly.
    // 'pms_lang' is the key LanguageService writes; anything but 'en' falls
    // back to 'fr'.
    { provide: LOCALE_ID, useFactory: () => localStorage.getItem('pms_lang') === 'en' ? 'en-US' : 'fr' }
  ]
};
