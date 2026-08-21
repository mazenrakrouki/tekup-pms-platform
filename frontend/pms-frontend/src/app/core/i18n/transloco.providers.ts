import { EnvironmentProviders, Provider, inject, provideAppInitializer } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './transloco-loader';
import { LanguageService } from './language.service';

/**
 * Fournisseurs i18n de la plateforme (ADR-025).
 *
 * - `availableLangs` : FR (défaut) + EN. Ajouter une langue = l'ajouter ici et déposer
 *   les fichiers `<langue>.json` ; aucun composant à modifier.
 * - `reRenderOnLangChange: true` : bascule instantanée, sans rechargement de page.
 * - `fallbackLang: 'fr'` + `useFallbackTranslation` : une clé manquante en EN retombe sur le FR.
 * - L'APP_INITIALIZER fixe la langue résolue (localStorage > navigateur > défaut) avant
 *   le premier rendu, pour éviter tout flash de langue.
 */
export function provideI18n(): (Provider | EnvironmentProviders)[] {
  return [
    ...provideTransloco({
      config: {
        availableLangs: ['fr', 'en'],
        defaultLang: 'fr',
        fallbackLang: 'fr',
        reRenderOnLangChange: true,
        prodMode: false,
        missingHandler: {
          useFallbackTranslation: true,
          logMissingKey: true,
          allowEmpty: false,
        },
      },
      loader: TranslocoHttpLoader,
    }),
    provideAppInitializer(() => {
      inject(LanguageService).init();
    }),
  ];
}
