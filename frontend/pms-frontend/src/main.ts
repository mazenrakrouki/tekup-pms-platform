import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import localeFrExtra from '@angular/common/locales/extra/fr';
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Entry point: index.html's bundle runs this, which loads French locale data then bootstraps
// App with appConfig (router, HttpClient, Transloco, LOCALE_ID all registered there).

// Angular only ships "en-US" locale data internally; anything else, including the French dates
// and number formats Transloco's LOCALE_ID switches to, must be registered by hand before
// bootstrap so the first screen already renders correctly.
registerLocaleData(localeFr, 'fr', localeFrExtra);

// .catch keeps a startup failure (e.g. a bad provider in appConfig) visible in the console
// instead of leaving a silent blank page.
bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
