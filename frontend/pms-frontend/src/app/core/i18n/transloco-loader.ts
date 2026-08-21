import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Translation, TranslocoLoader } from '@jsverse/transloco';

/**
 * Charge les catalogues de traduction depuis les fichiers statiques servis par Angular
 * (dossier `public/i18n/` → exposé à la racine `/i18n/`).
 *
 * Transloco appelle ce loader avec :
 *   - la langue seule pour le scope racine  → "fr"          → /i18n/fr.json
 *   - "<scope>/<langue>" pour un scope module → "project/fr" → /i18n/project/fr.json
 *
 * Ajouter une langue = déposer les fichiers `<langue>.json` correspondants,
 * sans aucune modification de code (ADR-025).
 */
@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(langOrScopedPath: string) {
    return this.http.get<Translation>(`/i18n/${langOrScopedPath}.json`);
  }
}
