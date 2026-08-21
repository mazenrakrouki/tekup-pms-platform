import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/**
 * Notifications transitoires (toasts) — remplace les messages inline et les alert() du navigateur.
 * Rendu par ToastContainerComponent (monté dans ShellComponent). Auto-fermeture ; erreurs plus longues.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private seq = 0;

  success(message: string, duration = 3500): void { this.push('success', message, duration); }
  error(message: string,   duration = 6000): void { this.push('error', message, duration); }
  warning(message: string, duration = 5000): void { this.push('warning', message, duration); }
  info(message: string,    duration = 3500): void { this.push('info', message, duration); }

  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  private push(kind: ToastKind, message: string, duration: number): void {
    const id = ++this.seq;
    this.toasts.update(list => [...list, { id, kind, message }]);
    if (duration > 0) {
      setTimeout(() => this.dismiss(id), duration);
    }
  }
}
