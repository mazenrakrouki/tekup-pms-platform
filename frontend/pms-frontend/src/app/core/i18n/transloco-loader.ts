import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Translation, TranslocoLoader } from '@jsverse/transloco';

/**
 * FILE HEADER - transloco-loader.ts
 *
 * WHAT THIS FILE IS
 * The single class that goes and fetches a translation catalog. A "catalog" is just a JSON
 * file that maps a key to the text shown on screen, for example { "common.save": "Save" }.
 *
 * WHERE IT SITS IN THE FLOW
 *   'transloco.providers.ts' registers this class as the Transloco 'loader'.
 *   Transloco (the i18n library, package '@jsverse/transloco') calls 'getTranslation()'
 *   by itself - we never call it from our own code. It calls it:
 *     1. once at start-up, for the language chosen by 'language.service.ts';
 *     2. again every time 'LanguageService.setLang()' switches the language;
 *     3. again the first time a page declares a scope, e.g. 'provideTranslocoScope('project')'.
 *   This class then calls Angular's 'HttpClient', which downloads a plain static file from
 *   'public/i18n/'. Angular 21 serves everything in 'public/' at the site root, so
 *   'public/i18n/fr.json' is reachable at the URL '/i18n/fr.json'.
 *   Important: this never talks to the Spring Boot backend. Translations are frontend-only
 *   assets, which is why the language switch keeps working on the login screen, before the
 *   user is authenticated.
 *
 * WHY IT EXISTS
 * Transloco ships with no default loader; it only defines the 'TranslocoLoader' interface and
 * expects the application to implement it. Delete this file and Transloco can never obtain a
 * single line of text: every '{{ 'common.save' | transloco }}' in every template would print
 * the raw key 'common.save' on the page instead of "Save" / "Enregistrer".
 *
 * ADDING A LANGUAGE (ADR-025)
 * This file never changes. Adding Spanish means dropping 'public/i18n/es.json' (plus one
 * 'es.json' per scope folder) and declaring 'es' in 'transloco.providers.ts' and in
 * 'LanguageService'. The URL is built from the language code, so the loader already handles it.
 */
// @Injectable marks the class as something Angular's dependency injection can build.
// `providedIn: 'root'` means: one single shared instance for the whole application, created
// the first time it is needed. Why root and not a component provider: Transloco itself asks
// the injector for this loader, long before any component exists.
// Without @Injectable, `inject(HttpClient)` below has no injection context and Angular throws
// "NG0203: inject() must be called from an injection context" at start-up.
@Injectable({ providedIn: 'root' })
// `implements TranslocoLoader` is the contract with the library: it forces this class to have
// a `getTranslation(lang)` method returning an Observable (or Promise) of a Translation object.
// Why it is useful: if someone renamed the method below, TypeScript would fail the build here,
// instead of the app compiling fine and then showing raw keys on every screen at runtime.
export class TranslocoHttpLoader implements TranslocoLoader {
  // `inject()` is the modern way to ask Angular for a dependency, instead of declaring it as a
  // constructor parameter. It is legal here because the class carries @Injectable above, so
  // Angular is the one building it.
  // Why Angular's HttpClient and not the browser's `fetch`: the Transloco contract expects an
  // Observable, which HttpClient returns directly, and Angular's testing tools can then replace
  // the network in unit tests without touching this class.
  // One consequence to know: HttpClient goes through `authInterceptor`, registered globally in
  // `app.config.ts`, which has no URL filter. So once the user is logged in these catalog
  // requests also carry the Authorization header. That is harmless - they are static files
  // served by the frontend itself, which ignores the header - and it does not stop the language
  // from being switched on the login screen, because the interceptor sends the request
  // untouched when there is no token yet.
  private readonly http = inject(HttpClient);

  /**
   * Downloads one catalog and gives back an Observable that emits the parsed JSON object.
   *
   * 'langOrScopedPath' is passed by Transloco, and it carries two different shapes:
   *   - "fr"         -> root catalog          -> GET /i18n/fr.json
   *   - "project/fr" -> catalog of a scope    -> GET /i18n/project/fr.json
   * A "scope" is a per-feature catalog (auth, admin, agile, dashboard, project, resources) that
   * is only downloaded when a page that needs it is opened. Why split them instead of one big
   * file: the login screen would otherwise have to download the whole application's text,
   * including admin screens the user may never open.
   *
   * EXPECT TWO CALLS PER LANGUAGE IN ENGLISH: because 'useFallbackTranslation: true' and
   * 'fallbackLang: 'fr'' are set in 'transloco.providers.ts', Transloco loads the active
   * catalog AND the French one together, so a key missing in English can be shown in French
   * instead of as a raw key. Switching to English therefore produces GET /i18n/en.json and
   * GET /i18n/fr.json; opening a scoped page produces /i18n/project/en.json and
   * /i18n/project/fr.json. In French there is only one call, since the active language is
   * already the fallback. Seeing the pair in the Network tab is normal, not a double request.
   *
   * WHY ONE METHOD AND NO CACHE HERE: Transloco already keeps every catalog it has received in
   * memory, in a Map indexed by this same path, and never asks twice for the same one. Adding
   * our own cache here would duplicate that and risk serving stale text after a rebuild.
   */
  getTranslation(langOrScopedPath: string) {
    // The generic `<Translation>` tells TypeScript the response body is a translation object
    // (a plain key -> text map), so the returned value is Observable<Translation>, exactly what
    // the TranslocoLoader contract requires. Without it, HttpClient returns Observable<Object>
    // and the `implements TranslocoLoader` line above fails to compile.
    // The leading "/" makes the URL absolute from the site root, matching how Angular serves
    // the `public/` folder. A relative path would break as soon as the user is on a deep route:
    // from /projects/42/di the browser would ask for /projects/42/i18n/fr.json and get a 404,
    // leaving the whole page showing raw keys.
    return this.http.get<Translation>(`/i18n/${langOrScopedPath}.json`);
  }
}
