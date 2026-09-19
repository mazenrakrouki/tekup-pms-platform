import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

/**
 * FILE HEADER - language.service.ts
 *
 * WHAT THIS FILE IS
 * The single source of truth for "which language is the interface in right now".
 *
 * WHERE IT SITS IN THE FLOW
 *   Called by:
 *     - 'transloco.providers.ts', through provideAppInitializer, which calls 'init()' once
 *       during application start-up, before the first screen is painted;
 *     - '<app-language-switcher>' (layout/language-switcher), which calls 'setLang()' when the
 *       user clicks FR or EN;
 *     - components that must re-compute labels built in TypeScript (dashboard, project-detail,
 *       sidebar) read the 'current' signal so their computed values re-run on a switch.
 *   Calls next:
 *     - 'TranslocoService.setActiveLang()', which makes Transloco ask 'TranslocoHttpLoader'
 *       for the catalogs of the new language and then re-render every '| transloco' in the app;
 *     - the browser's 'localStorage', to remember the choice for the next visit.
 *
 * WHY IT EXISTS
 * Without it, nothing would decide the starting language and nothing would remember the user's
 * choice: every reload would fall back to French, and each component would have to call
 * TranslocoService directly, so there would be no one place to also set
 * 'document.documentElement.lang' or to persist the choice.
 *
 * PERSISTENCE STRATEGY (ADR-025) - translated from the original French note
 * 'localStorage' first, then detection of the browser language on the very first visit.
 * It is a deliberate choice NOT to tie the language to the backend user profile, because
 * (1) it is a per-device / per-browser preference, (2) it must already work on the login
 * screen, before authentication, and (3) the backend stays strictly language-independent:
 * it returns stable codes such as 'ACTIVE' or 'CHEF_PROJET' and the frontend translates them.
 */

// A union type ("either 'fr' or 'en'", nothing else). Why a union and not plain `string`:
// TypeScript then refuses any other value at build time. Without it, a typo like
// `setLang('FR')` would compile, Transloco would look for /i18n/FR.json, get a 404, and the
// whole interface would silently stay in the previous language.
export type AppLang = 'fr' | 'en';

// One row of the language menu. It is deliberately NOT exported: only this service builds these
// objects, and the switcher component just iterates over `options`.
interface LangOption {
  // The short code that every other part of the system uses: it builds the catalog URL
  // (/i18n/fr.json), it is written into `<html lang>`, and it is the value saved in
  // localStorage. Typed `AppLang` and not `string` so that a row with a bad code, such as
  // { code: 'de', ... }, is refused at build time instead of producing a 404 at runtime.
  code: AppLang;
  // The name of the language written in that language itself, never translated: the French row
  // reads "Francais", the English row reads "English", in both interfaces.
  // Why: a user who only reads English must still recognise his
  // own language in the list. If this were translated, a French interface would offer
  // "Anglais", which an English-only user may not recognise.
  label: string;
  // Flag emoji. Note: the current `<app-language-switcher>` renders the two-letter code
  // (FR / EN) and does not display this field today.
  flag: string;
}

/**
 * The one entry point for the language of the interface (ADR-025).
 *
 * WHAT IT GIVES BACK: a reactive 'current' signal plus three ways to act on it
 * ('init', 'setLang', 'toggle') and the 'options' list the switcher draws.
 *
 * WHY WRITTEN THIS WAY rather than the obvious alternative: the obvious alternative is to let
 * each component call 'TranslocoService.setActiveLang()' directly. That would work for the text,
 * but then no component would own persistence, none would set the '<html lang>' attribute, and
 * the "which language do we start in?" rule would be copied in several places and drift apart.
 * Centralising here also means adding a third language touches only this file plus the config.
 */
// Same as in the loader: one shared instance for the whole app, created on first use.
// Why `providedIn: 'root'` and not a component-level provider: if the login page and the sidebar
// each got their own instance, clicking EN in one would not update the other, and the two copies
// of the `current` signal would disagree.
@Injectable({ providedIn: 'root' })
export class LanguageService {
  // `inject()` is the modern replacement for a constructor parameter. It only works while
  // Angular is building the class, which is guaranteed here because of @Injectable above.
  private readonly transloco = inject(TranslocoService);

  // The key used inside the browser's localStorage. `private static` because it belongs to the
  // class, not to an instance, and nothing outside needs it.
  // WARNING for maintenance: the exact same string is repeated in `app.config.ts` to pick
  // LOCALE_ID at bootstrap. Renaming it here without renaming it there would silently break the
  // date and number formats (dates in US format while the interface is in French).
  // Be precise about how the two are linked, because they are not the same rule: `app.config.ts`
  // reads ONLY this stored value, while `resolveInitialLang()` below falls back to the browser
  // language when nothing is stored. Concrete effect on a very first visit from an English
  // browser: the text is English (browser detection) but the dates stay French, because nothing
  // has been written to localStorage yet. It lines up as soon as the user clicks the switcher
  // once, since `setLang()` persists the code and LOCALE_ID is read again on the next load.
  private static readonly STORAGE_KEY = 'pms_lang';
  // The list of accepted language codes, used to validate whatever is read back from
  // localStorage. `readonly AppLang[]` means the array cannot be modified after creation.
  // Why validate at all: localStorage is under the user's control. Someone can open the browser
  // console and set `pms_lang` to "de"; without this check the app would ask for /i18n/de.json,
  // receive a 404, and render raw keys everywhere.
  static readonly SUPPORTED: readonly AppLang[] = ['fr', 'en'];

  // The rows drawn by `<app-language-switcher>`. Public and `readonly`: the template reads it,
  // nobody replaces it. Because the switcher loops over this array, adding a language adds a
  // button on its own - no change to the component's HTML.
  readonly options: readonly LangOption[] = [
    { code: 'fr', label: 'Français', flag: '🇫🇷' },
    { code: 'en', label: 'English', flag: '🇬🇧' },
  ];

  /**
   * The language currently displayed. Read it with 'current()'.
   *
   * A "signal" is an Angular value that remembers who read it: when it changes, every template
   * and every 'computed()' that read it runs again, automatically.
   * WHY a signal and not a plain field: some labels are built in TypeScript, not in the HTML
   * (for example the command palette entries and some chart legends). A plain field would change
   * without telling anybody, so those labels would stay in the old language until the user
   * navigated away and came back.
   * It starts at 'fr' only as a safe default; 'init()' overwrites it during start-up.
   */
  readonly current = signal<AppLang>('fr');

  /**
   * Resolves the starting language and applies it. Returns nothing.
   *
   * Called exactly once, by 'provideAppInitializer(...)' in 'transloco.providers.ts'.
   * WHY at start-up and not inside a component: an initializer runs before Angular paints the
   * first screen. If this ran inside the root component instead, an English user would see the
   * interface appear in French and then blink to English one frame later.
   */
  init(): void {
    this.apply(this.resolveInitialLang());
  }

  /**
   * Switches the interface to 'lang' and remembers the choice for the next visit.
   * This is what '<app-language-switcher>' calls when the user clicks FR or EN.
   */
  setLang(lang: AppLang): void {
    // Do nothing if the user clicked the language that is already active.
    // Why: `apply()` re-renders every translated string in the application. Without this guard,
    // clicking "FR" while already in French would rebuild the whole screen for no reason, and
    // any open dropdown or in-progress animation would be reset under the user's fingers.
    if (lang === this.current()) return;
    this.apply(lang);
    // `localStorage` can throw, it is not only "full or not": in Safari private mode, or when
    // the browser is set to block site data, simply calling setItem raises a SecurityError.
    // The empty catch is deliberate - failing to remember the choice must not break the switch.
    // Without the try/catch, a user browsing in private mode would click EN and get an uncaught
    // error, even though the interface itself had already switched correctly.
    try { localStorage.setItem(LanguageService.STORAGE_KEY, lang); } catch { /* storage unavailable - the switch still works, it is just not remembered */ }
  }

  /**
   * Flips FR to EN and EN to FR. Returns nothing.
   *
   * It delegates to 'setLang()' on purpose, so the flip also persists the choice and updates
   * '<html lang>'; duplicating the body here would be a second place to keep in sync.
   * Note: the current '<app-language-switcher>' does not use this - it calls 'setLang()' with
   * an explicit code, because it draws one button per language rather than a single toggle.
   * It is kept as the two-language shortcut of the public API.
   */
  toggle(): void {
    this.setLang(this.current() === 'fr' ? 'en' : 'fr');
  }

  /**
   * Gives back the full row (code + native label + flag) of the active language, for a caller
   * that wants to display more than the bare code.
   *
   * Honest note for anyone reading the class: nothing calls this today. The switcher loops over
   * 'options' and compares each row against 'current()' itself, so it never needs the single
   * active row. It is part of the public API of the service, ready for a place that shows the
   * active language on its own, such as a header badge reading "English" instead of "EN".
   */
  currentOption(): LangOption {
    // `find` gives back `undefined` when nothing matches, so TypeScript would force every caller
    // to handle a possibly-missing value. `?? this.options[0]` ("if the left side is null or
    // undefined, take the right side") removes that burden: the method always returns a real row.
    // In practice `current` is always one of the codes in `options`, so the fallback is a safety
    // net, not the normal path - it only matters if someone adds a code to `AppLang` and forgets
    // to add the matching row to `options`.
    return this.options.find(o => o.code === this.current()) ?? this.options[0];
  }

  /**
   * The one place that actually performs a language change. Private so that every change goes
   * through 'init()' or 'setLang()' and the three steps below can never be done half-way.
   */
  private apply(lang: AppLang): void {
    // Tells the library which language is active. Transloco then asks `TranslocoHttpLoader` for
    // the catalogs it does not hold yet and, because `reRenderOnLangChange: true` is set in
    // `transloco.providers.ts`, re-renders every `| transloco` in the application with no reload.
    this.transloco.setActiveLang(lang);
    // Publishes the change to our own reactive readers (labels built in TypeScript, and the
    // active-button highlight in the switcher). Transloco does not update our signal by itself.
    this.current.set(lang);
    // Writes the code into the `<html lang="...">` attribute of the page.
    // Why: assistive technology chooses its pronunciation from this attribute, and the browser
    // uses it to offer translation and to hyphenate text. Without this line a screen reader
    // would keep reading the English interface with a French voice, which is close to unusable,
    // and it would also be an accessibility defect (WCAG "Language of Page").
    document.documentElement.lang = lang;
  }

  /**
   * Decides which language to start in. Returns the code, without applying it.
   *
   * Priority, in this order (ADR-025): saved choice -> browser language -> French.
   * WHY this order: an explicit past choice by the user must always beat a guess, and the guess
   * must beat a hard-coded default. Note that a language only detected from the browser is not
   * written to localStorage here; it is saved the first time the user clicks the switcher.
   * That detail has one visible consequence, described in full above 'STORAGE_KEY': on a first
   * visit from an English browser the text is English while the date format is still French.
   */
  private resolveInitialLang(): AppLang {
    let stored: string | null = null;
    // Reading storage can throw for exactly the same reasons as writing it (private mode,
    // blocked site data). Starting the application must never depend on that, so a failure is
    // swallowed and `stored` simply stays null, which sends us to browser detection below.
    try { stored = localStorage.getItem(LanguageService.STORAGE_KEY); } catch { /* ignore - fall back to browser detection */ }
    // Two checks in one: `stored &&` rejects null and the empty string, and `.includes(...)`
    // rejects any value that is not a language we really ship.
    // The `as readonly string[]` cast is needed because `SUPPORTED` is typed as `AppLang[]`, so
    // its `includes()` only accepts 'fr' or 'en', while `stored` is a plain string - without the
    // cast the code would not compile. It widens the array's type for the comparison only; it
    // does not weaken the check, because the result is still compared against the real contents.
    if (stored && (LanguageService.SUPPORTED as readonly string[]).includes(stored)) {
      // `as AppLang` is safe here: the line above has just proved the string is one of the
      // supported codes. TypeScript cannot deduce that by itself after a runtime `includes`.
      return stored as AppLang;
    }
    // `navigator.language` is the browser's preferred language, and it usually carries a region:
    // "en-US", "fr-FR", "fr-CA". `.slice(0, 2)` keeps only the language part, `.toLowerCase()`
    // normalises values such as "EN-us". The `|| 'fr'` guards the rare case where the property is
    // empty or undefined (some embedded browsers), which would otherwise crash on `.slice`.
    const browser = (navigator.language || 'fr').slice(0, 2).toLowerCase();
    // Anything that is not English starts in French, the default language of the platform.
    // Why not map every browser language: we only ship two catalogs, so a German browser gets
    // French rather than a screen full of missing keys.
    return browser === 'en' ? 'en' : 'fr';
  }
}
