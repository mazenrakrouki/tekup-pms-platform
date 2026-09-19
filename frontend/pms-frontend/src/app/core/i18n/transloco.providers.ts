import { EnvironmentProviders, Provider, inject, provideAppInitializer } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './transloco-loader';
import { LanguageService } from './language.service';

/**
 * FILE HEADER - transloco.providers.ts
 *
 * WHAT THIS FILE IS
 * The wiring of the whole translation system: one function, 'provideI18n()', that returns every
 * Angular provider the i18n layer needs.
 *
 * WHERE IT SITS IN THE FLOW
 *   Called by: 'src/app/app.config.ts', which simply lists 'provideI18n()' among the application
 *   providers. That is the only caller, and it is called once, at bootstrap.
 *   Calls next: 'provideTransloco(...)' with our configuration, registering
 *   'TranslocoHttpLoader' (transloco-loader.ts) as the class that downloads the JSON catalogs;
 *   then 'provideAppInitializer(...)', which calls 'LanguageService.init()' (language.service.ts)
 *   before the first screen is painted.
 *
 * SO THE THREE FILES OF THIS FOLDER FIT TOGETHER LIKE THIS
 *   app.config.ts -> provideI18n() [this file]
 *                      |-> configures Transloco + names the loader
 *                      |-> runs LanguageService.init() at start-up
 *                             |-> LanguageService picks fr/en and calls setActiveLang()
 *                                    |-> Transloco asks TranslocoHttpLoader for the catalog
 *                                           |-> HTTP GET /i18n/<lang>.json
 *
 * WHY IT EXISTS
 * Keeping this configuration in its own function, instead of inlining it in 'app.config.ts',
 * means the i18n decisions (which languages, which fallback, when the language is resolved)
 * live next to the two classes that implement them. Delete this file and Transloco is never
 * configured: no loader, no available languages, and 'LanguageService.init()' is never called,
 * so the application starts with no translations at all.
 *
 * ADR-025 is the decision record behind the choices below.
 */
/**
 * Builds the list of i18n providers for the application.
 *
 * Returns an array mixing two kinds of things, hence the '(Provider | EnvironmentProviders)[]'
 * return type: 'Provider' is a classic provider object, while 'EnvironmentProviders' is the
 * opaque bundle returned by Angular's modern 'provideXxx()' helpers.
 *
 * WHY a function and not an exported constant: 'provideAppInitializer(...)' and
 * 'provideTransloco(...)' build provider objects; creating them lazily, when the application
 * configuration is assembled, keeps module import order from mattering and keeps the unit tests
 * free to call 'provideI18n()' themselves.
 */
export function provideI18n(): (Provider | EnvironmentProviders)[] {
  return [
    // `provideTransloco` returns an ARRAY of providers, so the three dots (spread) unpack it into
    // this array instead of nesting an array inside an array. Without the spread, the value would
    // be `EnvironmentProviders[]` sitting inside a `(Provider | EnvironmentProviders)[]`, which
    // does not match the declared return type and fails the TypeScript build.
    ...provideTransloco({
      config: {
        // The languages the application really ships. Transloco refuses any language that is not
        // in this list, which protects against a bad value reaching `setActiveLang()`.
        // Adding a language means adding its code HERE and in `LanguageService.SUPPORTED` and
        // `LanguageService.options`, then dropping the matching `<lang>.json` files. No component
        // is touched (ADR-025): the switcher loops over `LanguageService.options`.
        availableLangs: ['fr', 'en'],
        // The language used when nothing else has been decided yet. French, because the company
        // and its clients are French-speaking.
        defaultLang: 'fr',
        // The language Transloco falls back to for a key that is missing in the active language.
        // Together with `useFallbackTranslation` below, this has a concrete effect: when the
        // interface is in English, Transloco downloads BOTH /i18n/en.json AND /i18n/fr.json, and
        // reads the French one whenever an English key is absent. When the interface is in French
        // only fr.json is downloaded, because the active language is already the fallback.
        // The same pairing applies to each scope: opening a project page in English fetches
        // /i18n/project/en.json and /i18n/project/fr.json together.
        // Careful, this is NOT the same thing as `defaultLang` above. `defaultLang` answers
        // "which language before anyone has chosen"; `fallbackLang` answers "which language for
        // this one key that has no text". They happen to be the same value here, French, so the
        // difference is invisible today - it would become visible the day the default becomes
        // English while French stays the complete, reference catalog.
        fallbackLang: 'fr',
        // Re-renders every translated part of the screen when the language changes.
        // WHY it is the core of the feature: this is what makes the switch instant, with no page
        // reload and without leaving the current page. With `false`, clicking EN would change
        // nothing visible until the user navigated to another route - and a half-filled form on
        // screen would have to be abandoned to see the new language.
        reRenderOnLangChange: true,
        // Declares to Transloco that this is not a production build.
        // Be precise about what it does in version 8 of the library: the missing-key warning is
        // in fact gated by Angular's own `ngDevMode`, so a production build stays silent whatever
        // this value is. The flag is part of the config contract and is stated explicitly here
        // rather than left to the library default.
        prodMode: false,
        // How a key with no text behaves. "Missing" covers two cases: the key is absent from the
        // catalog, or (because `allowEmpty` is false) its value is an empty string.
        missingHandler: {
          // Show the French text instead of the missing English one.
          // Without it, a key translated in fr.json but forgotten in en.json would be displayed
          // to the user as the raw key, for example `project.di.header.total` in the middle of a
          // screen. With it, the user sees the French wording - imperfect, but readable.
          useFallbackTranslation: true,
          // Write a red warning in the browser console for each missing key. This is the tool
          // that finds untranslated strings while developing; Angular's `ngDevMode` turns it off
          // automatically in a production build, so end users never see it.
          logMissingKey: true,
          // `false` means an empty value in a catalog is NOT accepted as a valid translation; it
          // is routed to the missing-key path above and therefore falls back to French.
          // Why that is the safer choice: a key left as "" in en.json usually means "not
          // translated yet". With `true`, the screen would show an empty label - an unlabelled
          // button that the user cannot understand - instead of the French wording.
          allowEmpty: false,
        },
      },
      // Names the class that actually fetches the JSON catalogs. This is the link to
      // `transloco-loader.ts`; Transloco creates it through dependency injection.
      loader: TranslocoHttpLoader,
    }),
    // Runs the given function during application start-up, before the first render.
    // `inject()` works inside this callback because Angular executes it in an injection context.
    // WHY resolve the language here and not in the root component: an initializer is guaranteed
    // to finish before anything is painted. Done later, an English user would see the interface
    // flash in French for one frame and then switch - the "flash of wrong language" this avoids.
    provideAppInitializer(() => {
      inject(LanguageService).init();
    }),
  ];
}
