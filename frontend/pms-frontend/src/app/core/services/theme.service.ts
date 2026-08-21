import { Injectable, signal, effect } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly current = signal<'light' | 'dark'>(this.detectInitial());

  constructor() {
    effect(() => {
      const t = this.current();
      document.documentElement.setAttribute('data-theme', t);
      localStorage.setItem('pms_theme', t);
    });
  }

  toggle(): void {
    this.current.update(t => t === 'light' ? 'dark' : 'light');
  }

  private detectInitial(): 'light' | 'dark' {
    const stored = localStorage.getItem('pms_theme') as 'light' | 'dark' | null;
    if (stored === 'light' || stored === 'dark') return stored;
    try {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    } catch {
      return 'light';
    }
  }
}
