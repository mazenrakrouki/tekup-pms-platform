import { Injectable, signal, effect } from '@angular/core';

/**
 * WHAT THIS FILE IS
 * The light / dark switch of the whole application. It holds the chosen theme, writes it on
 * the <html> tag so the CSS can react, and remembers it in the browser for the next visit.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls toggle()  : layout/sidebar/sidebar.component.ts and
 *                       features/auth/login/login.component.ts (the switch is on the login
 *                       page too, because there is no sidebar there yet).
 * Who reads current() : the same components, to draw the sun or the moon icon.
 * Who really uses the result: styles.scss, which declares its dark colours under the
 *                       selector [data-theme="dark"].
 * app.ts injects the service at start-up, which is what makes the saved theme apply before
 * anything is displayed.
 *
 * WHY IT EXISTS - AND WHY THE THEME IS AN ATTRIBUTE ON <html>
 * The alternative would be to pass a "dark" input into every component and add a CSS class
 * in each template. That means one line per component, so one forgotten component is one
 * white panel in the middle of a dark screen. Here one single attribute at the top of the
 * document switches every colour of the stylesheet at once, including the parts that Angular
 * does not own, such as the page background and the Bootstrap modals.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance. Why it matters here: the constructor below
// has a side effect on the document, and two instances would fight over the same attribute.
@Injectable({ providedIn: 'root' })
export class ThemeService {
  // A signal is a value that remembers who reads it: when it changes, Angular redraws
  // exactly the parts of the screen that used it, and the effect below runs again.
  // The type <'light' | 'dark'> is a union of two exact strings, not "string". Why: a typo
  // such as 'darK' then stops the compilation instead of producing an attribute no CSS rule
  // matches, which would show a half-broken screen at run time.
  // The starting value is not written here but computed by detectInitial() below, so the
  // application opens on the theme the user chose last time.
  // readonly protects the signal itself, not its content: nobody can replace the signal by
  // another one, while update() still works. Without it, a component could swap the signal
  // and every other reader would keep watching the old one and never redraw.
  readonly current = signal<'light' | 'dark'>(this.detectInitial());

  constructor() {
    // effect() runs a piece of code every time a signal it READS has changed - here
    // this.current(). It runs once immediately, which is exactly what applies the saved
    // theme when the application starts.
    // Why an effect and not two lines inside toggle(): the theme can also change from
    // somewhere else, for example a future "reset preferences" button. With the effect, the
    // rule "when the value changes, write it on the document and save it" is written once
    // and cannot be forgotten by the next person who sets the signal.
    effect(() => {
      // Reading the signal inside the effect is what subscribes the effect to it. Storing
      // the value in a local variable first makes sure the two lines below use the same one.
      const t = this.current();
      // document.documentElement is the <html> tag. Setting data-theme="dark" on it is what
      // switches the stylesheet: styles.scss declares its dark colours under
      // [data-theme="dark"]. Without this line the signal would change and the screen would
      // keep its light colours.
      document.documentElement.setAttribute('data-theme', t);
      // localStorage keeps a small text value in the browser, surviving a reload and a
      // closed window. Without it, the user would have to press the switch again at every
      // visit.
      localStorage.setItem('pms_theme', t);
    });
  }

  /**
   * Flips the theme: light becomes dark, dark becomes light. The effect above does the rest.
   *
   * Why update(t => ...) and not set(...) after reading the value: update receives the
   * current value and returns the next one in one single operation, so a fast double click
   * cannot write a decision taken from a value that has already changed.
   */
  toggle(): void {
    this.current.update(t => t === 'light' ? 'dark' : 'light');
  }

  /**
   * Chooses the theme to start with, in this order: the one saved in this browser, then the
   * preference of the operating system, then light as the last resort.
   *
   * Why that order: the explicit choice of the user must win over the system setting -
   * somebody who asked for light on a machine set to dark asked for it on purpose.
   */
  private detectInitial(): 'light' | 'dark' {
    // localStorage.getItem returns a string or null, so "as 'light' | 'dark' | null" tells
    // the compiler what we expect. It is a promise to the compiler, NOT a check: the value
    // really comes from the browser and could be anything if somebody edited it by hand,
    // which is why the real check is the line just below.
    const stored = localStorage.getItem('pms_theme') as 'light' | 'dark' | null;
    // The explicit comparison against the two accepted words handles null and any junk in
    // one line. Without it, a stored value of "blue" would be written as data-theme="blue",
    // no CSS rule would match, and the screen would open with no colours at all.
    if (stored === 'light' || stored === 'dark') return stored;
    try {
      // matchMedia asks the browser whether the operating system is set to dark mode.
      // .matches is true or false, so the ternary turns it into one of our two words.
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      // The try/catch is not decoration: window and matchMedia do not exist when the code
      // runs outside a browser - server-side rendering, or a unit test running in Node.
      // Without this catch the service would throw while being built, and because it is
      // built at start-up the whole application would fail to open.
      return 'light';
    }
  }
}
