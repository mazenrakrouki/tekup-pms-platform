import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { provideI18n } from './core/i18n/transloco.providers';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideI18n(),
    // La langue choisie est lue avant le premier rendu : les dates et les nombres
    // sont donc corrects des l'affichage initial, sans attendre un changement de langue.
    { provide: LOCALE_ID, useFactory: () => localStorage.getItem('pms_lang') === 'en' ? 'en-US' : 'fr' }
  ]
};
