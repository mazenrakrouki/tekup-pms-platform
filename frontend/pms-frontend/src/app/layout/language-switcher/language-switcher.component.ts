import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { LanguageService } from '../../core/i18n/language.service';

// FR | EN segmented control (ADR-025). No inputs/outputs; drop it anywhere. All real work
// (switching catalogs, persisting choice, updating <html lang>) lives in LanguageService.
@Component({
  selector: 'app-language-switcher',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <!-- role/aria-label give the pair a spoken name for screen readers. -->
    <div class="lang-seg" role="group" [attr.aria-label]="'lang.switch' | transloco">
      <!-- track o.code: without it, buttons rebuild on every change and lose keyboard focus. -->
      @for (o of lang.options; track o.code) {
        <!-- type="button": avoids submitting the login form when placed inside it. -->
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
    /* text-transform: uppercase lets the template print lowercase codes ('fr') as "FR". */
    .lang-seg-btn {
      border: none; background: transparent; cursor: pointer;
      padding: 0 .45rem; height: 28px;
      font-size: 11px; font-weight: 700; letter-spacing: .04em;
      text-transform: uppercase; color: var(--text-3);
      transition: background 140ms ease, color 140ms ease;
    }
    /* Adjacent-sibling selector: separator between segments only, not before the first one. */
    .lang-seg-btn + .lang-seg-btn { border-left: 1px solid var(--border); }
    .lang-seg-btn:hover { color: var(--text-1); }
    /* Only visual cue for the active language; aria-pressed covers it for screen readers. */
    .lang-seg-btn.active { background: var(--c-brand); color: #fff; }
  `]
})
// Exposes LanguageService to the template only; reading lang.current() directly (rather than a
// local copy) keeps one source of truth so the highlight can never go stale.
export class LanguageSwitcherComponent {
  readonly lang = inject(LanguageService);
}
