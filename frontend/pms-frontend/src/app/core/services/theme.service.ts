import { Injectable, signal, effect } from '@angular/core';

// Light/dark switch for the whole app: holds the chosen theme, writes it to the <html> tag
// so styles.scss ([data-theme="dark"]) can react, and persists it in localStorage.
// One attribute on <html> instead of a "dark" input threaded through every component — no
// forgotten component ends up with the wrong colors, including parts Angular doesn't own
// (page background, Bootstrap modals).
@Injectable({ providedIn: 'root' })
export class ThemeService {
  // Union type, not plain string, so a typo like 'darK' fails at compile time instead of
  // silently producing an attribute no CSS rule matches.
  readonly current = signal<'light' | 'dark'>(this.detectInitial());

  constructor() {
    // effect() re-runs whenever `current` changes, including once immediately at startup —
    // one place to keep "apply + persist the theme" in sync, instead of duplicating it in toggle().
    effect(() => {
      const t = this.current();
      document.documentElement.setAttribute('data-theme', t);
      localStorage.setItem('pms_theme', t);
    });
  }

  // update() (read+write in one step) avoids a race where a fast double click could undo itself.
  toggle(): void {
    this.current.update(t => t === 'light' ? 'dark' : 'light');
  }

  // Priority: saved choice > OS preference > light. The user's explicit past choice wins
  // over whatever the OS happens to be set to.
  private detectInitial(): 'light' | 'dark' {
    const stored = localStorage.getItem('pms_theme') as 'light' | 'dark' | null;
    if (stored === 'light' || stored === 'dark') return stored;
    try {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      // window/matchMedia don't exist outside a browser (SSR, Node-based unit tests); without
      // this catch the app would fail to start.
      return 'light';
    }
  }
}
