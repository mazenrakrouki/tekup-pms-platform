import { Injectable, signal } from '@angular/core';

/**
 * WHAT THIS FILE IS
 * One single piece of screen state, shared by the whole application: is the left side menu
 * folded or open? Four lines, no HTTP, no business rule.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls toggle()          : layout/sidebar/sidebar.component.ts, on the fold button.
 * Who reads sidebarCollapsed(): the same sidebar (to draw itself narrow) and
 *                               layout/shell/shell.component.ts (to give the main area the
 *                               width the sidebar gives back).
 * It calls nothing.
 *
 * WHY IT EXISTS
 * Two components that are not parent and child need the same value. Without this service
 * the button would have to send the information upwards with an @Output, the shell would
 * have to hold it and pass it back down with an @Input, and any third component that needs
 * it tomorrow would mean changing all of them. A tiny service in the middle is the shortest
 * safe path.
 * Note what it does NOT do: it does not remember the choice after a page reload. This is a
 * deliberate difference with ThemeService, which writes to localStorage. Folding the menu is
 * a gesture of the moment; the theme is a preference.
 */
// @Injectable lets Angular build and inject this class.
// providedIn: 'root' is what makes the sharing work: ONE instance for the whole
// application. Why it matters here more than in an HTTP service: if each component received
// its own instance, the sidebar would fold its own copy of the value and the shell would
// never learn about it, so the menu would slide but the content next to it would not move.
@Injectable({ providedIn: 'root' })
export class LayoutService {
  // A signal is a value that remembers who reads it. When it changes, Angular redraws
  // exactly the parts of the screen that used it, with no manual subscription and nothing
  // to unsubscribe from.
  // readonly protects the signal itself, not its content: nobody can replace the signal by
  // another one, while set() and update() still work. Without readonly a component could
  // write this.layout.sidebarCollapsed = signal(true), and every other component would go on
  // reading the old signal and would never redraw.
  // false is the starting value: the menu is open when the application starts.
  readonly sidebarCollapsed = signal(false);

  /**
   * Flips the menu: open becomes folded, folded becomes open.
   *
   * Why update(v => !v) and not set(!this.sidebarCollapsed()): update receives the current
   * value and returns the next one in one single operation. Reading first and writing after
   * is two operations, and between the two the value can have changed, so a fast double
   * click can end up writing the state we had just read.
   */
  toggle(): void { this.sidebarCollapsed.update(v => !v); }
}
