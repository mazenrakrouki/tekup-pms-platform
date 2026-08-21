import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

export type AppLang = 'fr' | 'en';

interface LangOption {
  code: AppLang;
  label: string;   // libellé natif, non traduit (« Français », « English »)
  flag: string;    // emoji drapeau
}

/**
 * Point d'entrée unique pour la langue de l'interface.
 *
 * Stratégie de persistance (ADR-025) : **localStorage** en priorité, avec **détection
 * de la langue du navigateur** au tout premier accès. Choix délibéré de NE PAS lier la
 * langue au profil backend car (1) c'est une préférence d'appareil/navigateur, (2) elle
 * doit fonctionner sur l'écran de connexion avant authentification, (3) le backend reste
 * strictement indépendant de la langue.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  private static readonly STORAGE_KEY = 'pms_lang';
  static readonly SUPPORTED: readonly AppLang[] = ['fr', 'en'];

  readonly options: readonly LangOption[] = [
    { code: 'fr', label: 'Français', flag: '🇫🇷' },
    { code: 'en', label: 'English', flag: '🇬🇧' },
  ];

  /** Langue active — signal réactif consommé par l'en-tête (sélecteur) et les composants. */
  readonly current = signal<AppLang>('fr');

  /** Appelé une seule fois au démarrage (APP_INITIALIZER) : résout la langue initiale. */
  init(): void {
    this.apply(this.resolveInitialLang());
  }

  setLang(lang: AppLang): void {
    if (lang === this.current()) return;
    this.apply(lang);
    try { localStorage.setItem(LanguageService.STORAGE_KEY, lang); } catch { /* stockage indisponible */ }
  }

  /** Bascule FR ↔ EN (utilisé par le raccourci du sélecteur). */
  toggle(): void {
    this.setLang(this.current() === 'fr' ? 'en' : 'fr');
  }

  currentOption(): LangOption {
    return this.options.find(o => o.code === this.current()) ?? this.options[0];
  }

  private apply(lang: AppLang): void {
    this.transloco.setActiveLang(lang);
    this.current.set(lang);
    document.documentElement.lang = lang;
  }

  private resolveInitialLang(): AppLang {
    let stored: string | null = null;
    try { stored = localStorage.getItem(LanguageService.STORAGE_KEY); } catch { /* ignore */ }
    if (stored && (LanguageService.SUPPORTED as readonly string[]).includes(stored)) {
      return stored as AppLang;
    }
    const browser = (navigator.language || 'fr').slice(0, 2).toLowerCase();
    return browser === 'en' ? 'en' : 'fr';
  }
}
