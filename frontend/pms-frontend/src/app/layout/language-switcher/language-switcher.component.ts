import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { LanguageService } from '../../core/i18n/language.service';

/**
 * FILE HEADER - language-switcher.component.ts
 *
 * WHAT THIS FILE IS
 * The small FR | EN button pair that lets the user change the language of the interface
 * (ADR-025). It is a "segmented control": one button per language, side by side, with the
 * active one highlighted.
 *
 * WHERE IT SITS IN THE FLOW
 *   Used by: any template that writes the tag '<app-language-switcher>' - today the layout
 *   sidebar and the login page. It takes no input and emits no output, so it can be dropped
 *   anywhere without wiring.
 *   Calls next: 'LanguageService' (core/i18n/language.service.ts). It reads 'lang.options' to
 *   know which buttons to draw, reads the 'lang.current()' signal to know which one to
 *   highlight, and calls 'lang.setLang()' on a click. The service then tells Transloco to
 *   switch catalogs, saves the choice in localStorage and updates '<html lang>'.
 *
 * WHY IT EXISTS
 * Without it there would be no way for a user to change the language: the app would be stuck
 * on whatever 'LanguageService.init()' picked at start-up (saved choice, then browser
 * language, then French). It is also the only piece of UI that knows how a language choice
 * should look; keeping it in one component means the sidebar and the login page cannot drift
 * apart.
 *
 * ORIGINAL FRENCH NOTE, TRANSLATED
 * Global language selector (ADR-025) - segmented FR | EN control. The switch is instant, with
 * no page reload, and the user stays on the current page. Each available language is one
 * segment; the active segment is highlighted. Reusable everywhere through
 * '<app-language-switcher>' (sidebar, login page...). Adding a language adds one more segment
 * automatically, because the buttons are driven by 'lang.options'.
 */
// @Component turns this class into an Angular component: a class plus the HTML it draws.
// Why: without this decorator the class would just be an ordinary object, and the tag
// `<app-language-switcher>` would stay unknown, so Angular would fail to compile the sidebar.
@Component({
  // The tag name to write in other templates. Everything is reached through this one name.
  selector: 'app-language-switcher',
  // "standalone" means this component declares its own dependencies below and does not need to
  // be listed in an NgModule. Why: the whole project is built on standalone components, so a
  // module-based component here could not be imported by the standalone sidebar.
  standalone: true,
  // Brings in the `| transloco` pipe used in the template below.
  // Why: a standalone component only sees what it imports. Without this line the build fails
  // with "the pipe transloco could not be found", and the button group would have no
  // accessible name.
  imports: [TranslocoModule],
  // The template is written inline, inside backticks, instead of a separate .html file.
  // Why: the markup is a few lines long and it is meaningless without the class next to it.
  // IMPORTANT for anyone editing inside these backticks: only <!-- --> comments are allowed
  // here. A // or /* */ comment would be printed on the screen as plain text.
  template: `
    <!-- role="group" tells assistive technology that these buttons belong together, and
         aria-label gives that group a spoken name, translated through the 'transloco' pipe
         ('lang.switch' is the key looked up in fr.json / en.json).
         Without them a screen reader would announce two loose buttons called "FR" and "EN"
         with no hint that they are a language choice. -->
    <div class="lang-seg" role="group" [attr.aria-label]="'lang.switch' | transloco">
      <!-- @for is Angular's loop. It draws one button per entry of 'lang.options'
           (today: fr, en), so adding a third language to the service adds a third button here
           with no change to this file.
           'track o.code' tells Angular how to recognise a row it has already drawn. Without a
           track key Angular would throw away and rebuild the buttons on every change, which
           loses keyboard focus: a user tabbing to "EN" would be dropped back to the top of the
           page the moment the list re-renders. -->
      @for (o of lang.options; track o.code) {
        <!-- One language button.
             - type="button" stops the browser from treating it as a submit button. Without it,
               the switcher placed inside the login <form> would submit that form and try to
               log the user in when he only wanted to change the language.
             - [class.active] adds the .active class only to the language in use. It reads the
               'current()' signal, so Angular re-highlights the right button by itself right
               after the click, with no manual DOM work.
             - (click) is the whole behaviour of this component: it hands the chosen code to the
               service, which switches the catalogs, saves the choice and updates <html lang>.
               The service ignores a click on the language already active.
             - [title] shows the full native name on hover ("Francais", "English"), because the
               visible text is only the two-letter code.
             - aria-pressed tells a screen reader which of the two is currently on. Without it a
               blind user would hear two identical buttons and could not tell which language is
               active. -->
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
  // Styles written inline too. Angular scopes them to this component, so `.lang-seg-btn` here
  // cannot leak out and repaint a button somewhere else in the app.
  // Only /* */ comments are allowed inside this block.
  styles: [`
    /* The frame around the two buttons. inline-flex keeps the control as small as its content
       and lets it sit on one line next to other items in the sidebar; overflow: hidden makes
       the two square buttons respect the rounded corners of the frame, otherwise the coloured
       active button would spill over the rounded edge. Colours come from CSS variables
       (--border, --text-1...) defined in styles.scss, which is what makes the control follow
       the light and dark themes without any extra code here. */
    .lang-seg {
      display: inline-flex; align-items: center;
      border: 1px solid var(--border); border-radius: var(--r-sm, 6px);
      overflow: hidden; background: transparent;
    }
    /* One segment. The buttons are stripped of their native border and background so the group
       reads as a single control; 'text-transform: uppercase' is why the template can print the
       lowercase code 'fr' and the user still sees "FR". */
    .lang-seg-btn {
      border: none; background: transparent; cursor: pointer;
      padding: 0 .45rem; height: 28px;
      font-size: 11px; font-weight: 700; letter-spacing: .04em;
      text-transform: uppercase; color: var(--text-3);
      transition: background 140ms ease, color 140ms ease;
    }
    /* "+" is the adjacent sibling selector: it matches every button that directly follows
       another one, so the separator line is drawn between the segments and NOT before the
       first one. Written as a plain 'border-left' on all buttons, the group would show a stray
       vertical line glued to its left edge. It also keeps working on its own when a third
       language is added. */
    .lang-seg-btn + .lang-seg-btn { border-left: 1px solid var(--border); }
    /* Hover only brightens the text, it does not move or resize anything: the control sits in
       the sidebar and any size change would nudge the items around it. */
    .lang-seg-btn:hover { color: var(--text-1); }
    /* The class added by [class.active] in the template: the language in use is filled with the
       brand colour. This is the only visual difference between the two segments, which is why
       aria-pressed is also set - colour alone says nothing to a screen reader. */
    .lang-seg-btn.active { background: var(--c-brand); color: #fff; }
  `]
})
/**
 * The class behind '<app-language-switcher>'.
 *
 * WHAT IT DOES / GIVES BACK: almost nothing by itself. It only exposes the LanguageService to
 * its own template; all the real work (switching catalogs, saving the choice, setting
 * '<html lang>') happens in that service.
 *
 * WHY WRITTEN THIS WAY rather than the obvious alternative: the obvious version would keep a
 * local field such as 'selected = 'fr'' and call TranslocoService directly. It would then hold
 * a second copy of the truth, so a language changed anywhere else (start-up detection, another
 * instance of the switcher on another screen) would leave this button group highlighting the
 * wrong segment. Reading 'lang.current()' straight from the shared service means there is only
 * one source of truth and the highlight can never be stale.
 */
export class LanguageSwitcherComponent {
  // `inject()` asks Angular for the application-wide LanguageService instance (it is provided
  // with `providedIn: 'root'`, so every screen gets the same object).
  // It is NOT private on purpose: the inline template above reads `lang.options` and
  // `lang.current()`, and a private field cannot be reached from the template in strict
  // template type-checking. `readonly` stops anyone from replacing the service at runtime.
  readonly lang = inject(LanguageService);
}
