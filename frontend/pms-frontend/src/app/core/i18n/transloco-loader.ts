import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Translation, TranslocoLoader } from '@jsverse/transloco';

// Fetches a translation catalog (a JSON key->text map) for Transloco. Registered as the
// 'loader' in transloco.providers.ts; Transloco calls getTranslation() itself at start-up,
// on language switch, and when a page declares a scope. Never talks to the Spring Boot
// backend - catalogs are static files under public/i18n/, served at /i18n/<lang>.json,
// which is why switching language works pre-login too. Adding a language only means
// dropping a new JSON file plus declaring the code elsewhere; this file never changes.
@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  // HttpClient (not fetch) because Transloco expects an Observable and tests can mock it.
  // Note: these requests also pass through the global authInterceptor and carry the
  // Authorization header when logged in - harmless since these are static files.
  private readonly http = inject(HttpClient);

  /**
   * Downloads one catalog. 'langOrScopedPath' is "fr" for the root catalog or "project/fr"
   * for a per-feature scope (only fetched when a page needing it opens).
   * Expect two requests in English (e.g. /i18n/en.json + /i18n/fr.json): fallbackLang: 'fr'
   * in transloco.providers.ts means the French catalog is always fetched alongside, so a
   * missing English key can fall back to French. That pairing is normal, not a bug.
   */
  getTranslation(langOrScopedPath: string) {
    // Leading "/" makes the URL absolute from the site root; a relative path would 404 from
    // any deep route (e.g. /projects/42/di).
    return this.http.get<Translation>(`/i18n/${langOrScopedPath}.json`);
  }
}
