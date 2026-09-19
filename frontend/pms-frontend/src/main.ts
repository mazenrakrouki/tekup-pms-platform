import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import localeFrExtra from '@angular/common/locales/extra/fr';
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// ============================================================================
// FILE: main.ts — the very first TypeScript file the browser runs.
//
// WHAT IT IS: the entry point of the whole Angular front end. The build tool
// (Angular CLI) points the generated <script> tag at this file.
//
// WHERE IT SITS IN THE FLOW:
//   index.html loads the bundle -> this file runs -> it loads the French locale
//   data -> it calls bootstrapApplication(App, appConfig) -> Angular creates the
//   root component App and puts it inside the <app-root> tag of index.html.
//   appConfig (src/app/app.config.ts) is where the router, the HttpClient with
//   the auth interceptor, Transloco (i18n) and LOCALE_ID are registered, so
//   everything else in the app is started indirectly from here.
//
// WHY IT EXISTS: without this file nothing is ever created. index.html would
// show an empty <app-root> tag and the user would see a blank white page.
// ============================================================================

// Angular's date / number / currency pipes read LOCALE_ID, not Transloco.
// LOCALE_ID is set in app.config.ts ('fr' or 'en-US' depending on the saved
// language), but Angular only ships the "en-US" rules inside the framework.
// Any other locale must have its data loaded by hand, which is what this call
// does: it registers the French month names, day names and number rules.
// localeFrExtra adds the extra data (for example "matin"/"après-midi" day
// periods) that some date formats need.
// WHY here and not in a service: it must run BEFORE bootstrapApplication, so
// the very first screen is already formatted correctly.
// EXAMPLE of what breaks without it: Angular throws "Missing locale data for
// the locale fr", and dates fall back to en-US, so "15 juillet 2026" is shown
// as "July 15, 2026" and "1 234,50" as "1,234.50" — wrong for a Tunisian /
// French user reading amounts in a Devis Interne (DI, the internal quote).
registerLocaleData(localeFr, 'fr', localeFrExtra);

// Starts Angular in "standalone" mode: no NgModule, the root component App and
// the provider list appConfig are passed directly.
// bootstrapApplication returns a Promise. The .catch keeps a failure during
// start-up visible in the browser console instead of failing silently.
// EXAMPLE: if a provider in appConfig throws (a bad router configuration, for
// instance), without this .catch the page stays blank with no message, and you
// have no idea where to look.
bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
