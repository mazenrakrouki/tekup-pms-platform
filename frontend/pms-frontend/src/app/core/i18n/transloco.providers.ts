import { EnvironmentProviders, Provider, inject, provideAppInitializer } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './transloco-loader';
import { LanguageService } from './language.service';

// Wires up the whole translation system: provideI18n() returns every Angular provider the
// i18n layer needs, called once from app.config.ts. Configures Transloco with
// TranslocoHttpLoader as the catalog loader, then runs LanguageService.init() before first
// paint. Kept in its own function (rather than inlined in app.config.ts) so the i18n
// decisions live next to the classes that implement them. See ADR-025 for the choices below.

/** Builds the i18n providers. A function, not a constant, so provideTransloco()/
 *  provideAppInitializer() build their provider objects lazily when app config is assembled. */
export function provideI18n(): (Provider | EnvironmentProviders)[] {
  return [
    // provideTransloco returns an array; spread it flat into this array.
    ...provideTransloco({
      config: {
        // Languages actually shipped; adding one also means updating LanguageService.SUPPORTED
        // and .options plus dropping the matching <lang>.json files.
        availableLangs: ['fr', 'en'],
        // Starting language before any choice is made; French since the company is French-speaking.
        defaultLang: 'fr',
        // Language used for a key missing in the active language. Same effect as defaultLang
        // today (both 'fr'), but a different question: this is about one key, not the start-up
        // language, and the two would diverge if the default ever became English.
        fallbackLang: 'fr',
        // Re-renders every translated string in place on a language change, with no page reload.
        reRenderOnLangChange: true,
        // Config contract field; in Transloco 8 the missing-key warning is actually gated by
        // Angular's ngDevMode, so this flag alone doesn't silence it in production.
        prodMode: false,
        missingHandler: {
          // Show French instead of a raw key when an English translation is missing.
          useFallbackTranslation: true,
          // Console warning per missing key; silenced in production by ngDevMode.
          logMissingKey: true,
          // An empty string in a catalog counts as missing too, so it falls back to French
          // instead of rendering an unlabelled control.
          allowEmpty: false,
        },
      },
      loader: TranslocoHttpLoader,
    }),
    // Runs before first paint so the interface never flashes in the wrong language.
    provideAppInitializer(() => {
      inject(LanguageService).init();
    }),
  ];
}
