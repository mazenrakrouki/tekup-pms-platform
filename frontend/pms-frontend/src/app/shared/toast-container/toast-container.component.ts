import { Component, inject } from '@angular/core';
import { ToastService } from '../../core/services/toast.service';

/**
 * Draws the toast messages ("Project saved", "Delete failed") in the top-right corner.
 * Mounted once by ShellComponent so toasts float above every page. Kept apart from
 * ToastService on purpose: the service owns which messages exist and for how long, this file
 * only owns how they look.
 */
@Component({
  selector: 'app-toast-container',
  standalone: true,
  template: `
    <!-- role="status" + aria-live="polite": screen readers announce new toasts without cutting off what they're already reading. -->
    <div class="toast-stack" role="status" aria-live="polite">
      <!-- track t.id (a service counter, never reused) keeps rows stable so closing one toast doesn't replay the entry animation on the others. -->
      @for (t of svc.toasts(); track t.id) {
        <div class="toast-item" [class]="'toast-' + t.kind">
          <i class="bi" [class.bi-check-circle-fill]="t.kind === 'success'"
                        [class.bi-exclamation-triangle-fill]="t.kind === 'warning'"
                        [class.bi-x-circle-fill]="t.kind === 'error'"
                        [class.bi-info-circle-fill]="t.kind === 'info'"></i>
          <!-- {{ }} escapes the text, so a project name containing markup can't render as HTML. -->
          <span class="toast-msg">{{ t.message }}</span>
          <button type="button" class="toast-close" (click)="svc.dismiss(t.id)" aria-label="Fermer">
            <i class="bi bi-x-lg"></i>
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-stack {
      /* fixed against the window (not the page), so toasts stay put while the page scrolls. */
      position: fixed;
      top: 1rem;
      right: 1rem;
      /* Above the Bootstrap modal backdrop (1050) and modal (1055). */
      z-index: 1080;
      display: flex;
      flex-direction: column;
      gap: .5rem;
      /* min() caps at 400px but never exceeds the viewport minus margins on a phone. */
      max-width: min(400px, calc(100vw - 2rem));
      /* Lets clicks pass through the empty corner; toasts re-enable them below. */
      pointer-events: none;
    }
    .toast-item {
      pointer-events: auto;
      display: flex;
      align-items: flex-start;
      gap: .625rem;
      padding: .75rem .875rem;
      border-radius: 10px;
      background: var(--surface);
      border: 1px solid var(--border);
      box-shadow: 0 8px 24px rgba(0,0,0,.14);
      border-left: 3px solid var(--text-3);
      animation: toast-in .18s ease-out;
      font-size: 13px;
      line-height: 1.4;
      color: var(--text-1);
    }
    .toast-item > .bi { font-size: 15px; margin-top: 1px; flex-shrink: 0; }
    .toast-msg { flex: 1; word-break: break-word; }
    .toast-close {
      background: none; border: 0; padding: 0; cursor: pointer;
      color: var(--text-3); font-size: 11px; line-height: 1; margin-top: 2px; flex-shrink: 0;
    }
    .toast-close:hover { color: var(--text-1); }
    .toast-success { border-left-color: var(--c-success); }
    .toast-success > .bi { color: var(--c-success); }
    .toast-error   { border-left-color: var(--c-danger, #dc3545); }
    .toast-error   > .bi { color: var(--c-danger, #dc3545); }
    .toast-warning { border-left-color: var(--c-warning); }
    .toast-warning > .bi { color: var(--c-warning); }
    .toast-info    { border-left-color: var(--c-brand); }
    .toast-info    > .bi { color: var(--c-brand); }
    @keyframes toast-in { from { opacity: 0; transform: translateX(12px); } to { opacity: 1; transform: none; } }
    /* Respects "reduce motion" so the slide-in doesn't trigger motion sensitivity. */
    @media (prefers-reduced-motion: reduce) { .toast-item { animation: none; } }
  `]
})
/** Holds no message list or timer of its own — those must outlive this component being redrawn. */
export class ToastContainerComponent {
  readonly svc = inject(ToastService);
}
