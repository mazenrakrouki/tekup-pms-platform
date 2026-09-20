import { Injectable, signal } from '@angular/core';

// Holds the queue of corner "toast" messages ("Project saved", "Delete failed") and their
// auto-dismiss timers. No HTML here — ToastContainerComponent does the drawing. Replaces the
// old browser alert() (blocking, unstyled) and inline per-screen message variables.

// Union, not plain string, so a typo like 'succes' fails at compile time instead of
// producing a toast with no matching color/icon.
export type ToastKind = 'success' | 'error' | 'warning' | 'info';

// id lets the container track/remove one specific message without redrawing the whole list.
export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

// M-11: service-based notifications, shared singleton so every screen's push() reaches the
// one container mounted in ShellComponent.
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);

  // Monotonic counter, not array length — length shrinks as toasts close, which could reissue
  // an id a pending timer still expects to remove.
  private seq = 0;

  // Error toasts stay longer (6000ms vs 3500ms) since they need to be read and understood,
  // not just glanced at.
  success(message: string, duration = 3500): void { this.push('success', message, duration); }
  error(message: string,   duration = 6000): void { this.push('error', message, duration); }
  warning(message: string, duration = 5000): void { this.push('warning', message, duration); }
  info(message: string,    duration = 3500): void { this.push('info', message, duration); }

  // Rebuilds the array (not in-place removal) so the signal detects the change. Safe to call
  // twice with the same id — the close button and the timer can race harmlessly.
  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  // Private: the four methods above are the whole public surface, keeping toast colors consistent.
  private push(kind: ToastKind, message: string, duration: number): void {
    const id = ++this.seq; // pre-increment so the first id is 1, never the falsy 0
    this.toasts.update(list => [...list, { id, kind, message }]);
    // duration 0 means "stays until closed manually" — setTimeout(...,0) would otherwise flash it away.
    if (duration > 0) {
      setTimeout(() => this.dismiss(id), duration);
    }
  }
}
