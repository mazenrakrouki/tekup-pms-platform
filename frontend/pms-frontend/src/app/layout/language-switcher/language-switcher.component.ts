import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { LanguageService } from '../../core/i18n/language.service';

/**
 * Sélecteur de langue global (ADR-025) — contrôle segmenté FR | EN.
 *
 * Bascule instantanée, sans rechargement, en conservant la page courante.
 * Chaque langue disponible est un segment ; le segment actif est mis en évidence.
 * Réutilisable partout via `<app-language-switcher>` (barre latérale, page de connexion…).
 * Ajouter une langue = un segment de plus, automatiquement (piloté par `lang.options`).
 */
@Component({
  selector: 'app-language-switcher',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <div class="lang-seg" role="group" [attr.aria-label]="'lang.switch' | transloco">
      @for (o of lang.options; track o.code) {
        <button type="button" class="lang-seg-btn"
                [class.active]="o.code === lang.current()"
                (click)="lang.setLang(o.code)"
                [title]="o.label"
                [attr.aria-pressed]="o.code === lang.current()">
          {{ o.code }}
        </button>
      }
    </div>
  `,
  styles: [`
    .lang-seg {
      display: inline-flex; align-items: center;
      border: 1px solid var(--border); border-radius: var(--r-sm, 6px);
      overflow: hidden; background: transparent;
    }
    .lang-seg-btn {
      border: none; background: transparent; cursor: pointer;
      padding: 0 .45rem; height: 28px;
      font-size: 11px; font-weight: 700; letter-spacing: .04em;
      text-transform: uppercase; color: var(--text-3);
      transition: background 140ms ease, color 140ms ease;
    }
    .lang-seg-btn + .lang-seg-btn { border-left: 1px solid var(--border); }
    .lang-seg-btn:hover { color: var(--text-1); }
    .lang-seg-btn.active { background: var(--c-brand); color: #fff; }
  `]
})
export class LanguageSwitcherComponent {
  readonly lang = inject(LanguageService);
}
