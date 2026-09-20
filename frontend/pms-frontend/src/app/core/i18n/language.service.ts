import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

// Single source of truth for the interface language. transloco.providers.ts calls init() at
// start-up, '<app-language-switcher>' calls setLang() on click, and components that build
// labels in TypeScript read the 'current' signal to re-run on a switch. Centralised here
// (rather than each component calling TranslocoService directly) so persistence and
// '<html lang>' stay in one place. Persistence (ADR-025): localStorage first, then browser
// detection - not tied to the backend user profile, since it must work pre-login too.

// Union, not plain string, so TypeScript refuses an unsupported code like 'FR' at build time.
export type AppLang = 'fr' | 'en';

// One row of the language menu, not exported: only this service builds these.
interface LangOption {
  code: AppLang;
  // Written in the language itself, never translated, so a reader recognises their own row.
  label: string;
  // Flag emoji; the current switcher renders the FR/EN code instead and doesn't use this.
  flag: string;
}

// Single entry point for the interface language (ADR-025): a reactive 'current' signal plus
// init/setLang/toggle. providedIn: 'root' so every component shares the same signal.
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  // WARNING: this exact string is repeated in app.config.ts to pick LOCALE_ID at bootstrap;
  // renaming one without the other silently breaks date/number formatting.
  private static readonly STORAGE_KEY = 'pms_lang';
  // Validates whatever is read back from localStorage, which is user-editable.
  static readonly SUPPORTED: readonly AppLang[] = ['fr', 'en'];

  // Rows drawn by '<app-language-switcher>'; adding a language here adds a button automatically.
  readonly options: readonly LangOption[] = [
    { code: 'fr', label: 'Français', flag: '🇫🇷' },
    { code: 'en', label: 'English', flag: '🇬🇧' },
  ];

  // Signal, not a plain field: TypeScript-built labels (command palette, chart legends) read it
  // reactively. Starts at 'fr' as a safe default; init() overwrites it at start-up.
  readonly current = signal<AppLang>('fr');

  /** Resolves and applies the starting language. Called once by transloco.providers.ts, before
   *  first paint, so the interface never flashes in the wrong language. */
  init(): void {
    this.apply(this.resolveInitialLang());
  }

  /** Switches the interface to 'lang' and persists the choice. Called by the language switcher. */
  setLang(lang: AppLang): void {
    // Avoid re-rendering the whole screen (and resetting any open dropdown) for a no-op click.
    if (lang === this.current()) return;
    this.apply(lang);
    // localStorage can throw (Safari private mode, blocked site data); losing persistence must
    // not break the switch itself.
    try { localStorage.setItem(LanguageService.STORAGE_KEY, lang); } catch { /* storage unavailable - the switch still works, it is just not remembered */ }
  }

  /** Flips FR/EN. Delegates to setLang() so persistence and '<html lang>' stay in sync. */
  toggle(): void {
    this.setLang(this.current() === 'fr' ? 'en' : 'fr');
  }

  /** Full row (code + label + flag) of the active language. Nothing calls this today; kept as
   *  public API for a future display such as a header badge. */
  currentOption(): LangOption {
    return this.options.find(o => o.code === this.current()) ?? this.options[0];
  }

  /** The only place a language change actually happens; private so every change goes through
   *  init() or setLang(). */
  private apply(lang: AppLang): void {
    // Transloco fetches missing catalogs and re-renders every `| transloco` (reRenderOnLangChange).
    this.transloco.setActiveLang(lang);
    // Transloco doesn't update our own signal; publish the change to our reactive readers.
    this.current.set(lang);
    // Assistive tech and the browser rely on <html lang> for pronunciation/translation (WCAG).
    document.documentElement.lang = lang;
  }

  /** Priority (ADR-025): saved choice -> browser language -> French. A browser-detected
   *  language is not persisted here, only on the user's first explicit switch. */
  private resolveInitialLang(): AppLang {
    let stored: string | null = null;
    try { stored = localStorage.getItem(LanguageService.STORAGE_KEY); } catch { /* ignore - fall back to browser detection */ }
    // Cast needed because SUPPORTED is typed AppLang[] while stored is a plain string.
    if (stored && (LanguageService.SUPPORTED as readonly string[]).includes(stored)) {
      return stored as AppLang;
    }
    // navigator.language usually carries a region ("en-US"); keep only the language part.
    const browser = (navigator.language || 'fr').slice(0, 2).toLowerCase();
    // Only two catalogs are shipped, so anything but English falls back to French.
    return browser === 'en' ? 'en' : 'fr';
  }
}
