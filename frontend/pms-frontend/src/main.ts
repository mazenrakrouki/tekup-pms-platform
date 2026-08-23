import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import localeFrExtra from '@angular/common/locales/extra/fr';
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Les pipes date/number/currency d'Angular lisent LOCALE_ID, pas Transloco.
// Sans ces donnees, « 15 juillet 2026 » et l'espace insecable des milliers
// n'existent pas : tout serait rendu au format en-US.
registerLocaleData(localeFr, 'fr', localeFrExtra);

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
