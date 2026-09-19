import { Component, inject } from '@angular/core';
import { ToastService } from '../../core/services/toast.service';

/**
 * WHAT THIS FILE IS
 * The one component that draws the small messages ("Project saved", "Delete failed") in the
 * top-right corner of the screen. It owns the HTML and the CSS of those messages; it owns no
 * message and no timer.
 *
 * WHERE IT SITS IN THE FLOW
 * Who mounts it: ShellComponent (layout/shell/shell.component.ts), one single time, so the
 *                messages are drawn above every page of the application.
 * What it reads: ToastService (core/services/toast.service.ts). It reads the signal
 *                svc.toasts() to know what to draw, and calls svc.dismiss(id) when the user
 *                presses the close button.
 * It calls no server and holds no state of its own.
 *
 * WHY IT EXISTS
 * The service decides WHICH messages exist and for how long; this file decides HOW they look.
 * If this file were deleted, ToastService would keep filling and emptying its list correctly
 * and the user would see nothing at all: every confirmation and every error message in the
 * application would become invisible, and screens would have to go back to browser alert()
 * or to printing a message inside the page (which pushes the layout down).
 * It is kept apart from the service so that the same messages could be drawn differently
 * (for example at the bottom on a phone) without touching the logic of the timers.
 */

// @Component turns this class into a tag that Angular knows how to draw.
// Why the template and the styles are written here instead of in .html and .scss files:
// the whole component is about forty lines, and the project keeps such small pieces in one
// file. The styles stay scoped to this component only - Angular rewrites them, so
// .toast-item cannot repaint a class with the same name somewhere else in the application.
@Component({
  // The tag written in shell.component.ts: <app-toast-container></app-toast-container>.
  selector: 'app-toast-container',
  // standalone means the component declares what it needs by itself, with no NgModule.
  // Why it is needed: the whole frontend is built with standalone components, so
  // ShellComponent imports this class directly. Without it, Angular would refuse that import
  // and ask for a module that does not exist in this project.
  standalone: true,
  template: `
    <!-- position: fixed lives on this wrapper (see the styles below), so the stack floats -->
    <!-- above the page and never moves the content underneath. -->
    <!-- role="status" + aria-live="polite" tell a screen reader to read a message when it -->
    <!-- appears, WITHOUT interrupting what it is reading at that moment. -->
    <!-- Why it is needed: the message appears far from where the user is working, so a blind -->
    <!-- user would otherwise never know that a save failed. "polite" and not "assertive", -->
    <!-- because a save confirmation must not cut the sentence the user is listening to. -->
    <div class="toast-stack" role="status" aria-live="polite">
      <!-- @for is the Angular control-flow loop; svc.toasts() reads the signal, so this block -->
      <!-- is redrawn on its own every time a message is added or removed. -->
      <!-- track t.id reuses the DOM row of a message that is still in the list. -->
      <!-- Why it matters here: without it, closing the first of three messages would make -->
      <!-- Angular rebuild the rows below, the entry animation would play again on messages -->
      <!-- that were already on screen, and a click started on a close button could end on -->
      <!-- the wrong row. The id comes from the service counter, so it is never reused. -->
      @for (t of svc.toasts(); track t.id) {
        <!-- [class] adds one class built from the kind: toast-success, toast-error... -->
        <!-- That single class carries the colour of the left bar and of the icon (see the -->
        <!-- styles below). Written as a fixed class, all four kinds would look the same and -->
        <!-- an error would be as discreet as a confirmation. -->
        <div class="toast-item" [class]="'toast-' + t.kind">
          <!-- One <i> element, and the four [class.xxx] bindings switch the Bootstrap Icons -->
          <!-- glyph on or off depending on the kind. Exactly one test is true at a time. -->
          <!-- Why not four separate <i> elements: the icon must keep the same place in the -->
          <!-- row for the spacing rule below (.toast-item > .bi) to reach it, and one single -->
          <!-- element also avoids re-creating the DOM node. -->
          <i class="bi" [class.bi-check-circle-fill]="t.kind === 'success'"
                        [class.bi-exclamation-triangle-fill]="t.kind === 'warning'"
                        [class.bi-x-circle-fill]="t.kind === 'error'"
                        [class.bi-info-circle-fill]="t.kind === 'info'"></i>
          <!-- {{ }} prints the value as text, never as HTML. Messages often contain data -->
          <!-- coming from the server (a project name typed by a user), so a name holding -->
          <!-- <b> or a <script> tag is shown literally and cannot run. -->
          <span class="toast-msg">{{ t.message }}</span>
          <!-- The manual close button. It calls the service, not a local variable: the -->
          <!-- service is the only owner of the list, and dismiss() is safe to call even if -->
          <!-- the automatic timer has already removed this message (it then finds nothing). -->
          <!-- type="button" stops the browser from treating it as a submit button when the -->
          <!-- messages happen to be drawn over a page that contains a form. -->
          <button type="button" class="toast-close" (click)="svc.dismiss(t.id)" aria-label="Fermer">
            <!-- The button holds only an icon, so the aria-label above gives it a spoken -->
            <!-- name; without it a screen reader would only announce "button". -->
            <i class="bi bi-x-lg"></i>
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    /* The stack that holds every visible message. */
    .toast-stack {
      /* fixed = placed against the browser window, not against the page.
         Why: the messages stay in the corner while the user scrolls, and they take no room
         in the layout. With position: absolute they would scroll away with the page. */
      position: fixed;
      top: 1rem;
      right: 1rem;
      /* Above the Bootstrap modal backdrop (1050) and the modal itself (1055), so an error
         raised by a dialog stays readable while that dialog is open. */
      z-index: 1080;
      /* Column + gap: several messages pile up under each other instead of overlapping. */
      display: flex;
      flex-direction: column;
      gap: .5rem;
      /* min() keeps the box at 400px on a desktop, but never wider than the screen minus the
         2rem of margins on a phone. Without it, a long message would add a horizontal
         scrollbar to every page of the application. */
      max-width: min(400px, calc(100vw - 2rem));
      /* The wrapper covers a corner of the page even when it is empty. none lets the mouse
         pass through it, so a button sitting under that corner stays clickable. The messages
         take the clicks back with pointer-events: auto below. */
      pointer-events: none;
    }
    /* One message row. */
    .toast-item {
      /* Cancels the none above, for the visible message only: its close button must be
         clickable. Without this line the close button would simply not react. */
      pointer-events: auto;
      display: flex;
      /* flex-start, not center: on a message of three lines the icon must stay next to the
         first line instead of floating in the middle of the text. */
      align-items: flex-start;
      gap: .625rem;
      padding: .75rem .875rem;
      border-radius: 10px;
      /* var(--surface-1, #fff): the design-system colour, with white as a fallback if that
         variable is missing. The fallback keeps the text readable instead of showing it on
         a see-through background over the page. */
      background: var(--surface-1, #fff);
      border: 1px solid var(--border);
      box-shadow: 0 8px 24px rgba(0,0,0,.14);
      /* The neutral left bar; each kind repaints it below. It is declared here so the width
         (3px) is written once and the kind rules only change the colour. */
      border-left: 3px solid var(--text-3);
      animation: toast-in .18s ease-out;
      font-size: 13px;
      line-height: 1.4;
      color: var(--text-1);
    }
    /* The direct child icon only, so the small cross inside the close button is not resized
       by this rule. flex-shrink: 0 stops a long message from squashing the icon. */
    .toast-item > .bi { font-size: 15px; margin-top: 1px; flex-shrink: 0; }
    /* flex: 1 gives the text all the room left between the icon and the close button.
       break-word cuts a very long word (a file name, a URL) instead of letting it overflow. */
    .toast-msg { flex: 1; word-break: break-word; }
    .toast-close {
      background: none; border: 0; padding: 0; cursor: pointer;
      color: var(--text-3); font-size: 11px; line-height: 1; margin-top: 2px; flex-shrink: 0;
    }
    .toast-close:hover { color: var(--text-1); }
    /* The four kinds. Each one repaints the left bar and the icon, and nothing else: the
       background stays neutral, so the text keeps its contrast in dark mode too. */
    .toast-success { border-left-color: var(--c-success); }
    .toast-success > .bi { color: var(--c-success); }
    .toast-error   { border-left-color: var(--c-danger, #dc3545); }
    .toast-error   > .bi { color: var(--c-danger, #dc3545); }
    .toast-warning { border-left-color: var(--c-warning); }
    .toast-warning > .bi { color: var(--c-warning); }
    .toast-info    { border-left-color: var(--c-brand); }
    .toast-info    > .bi { color: var(--c-brand); }
    /* A short slide in from the right, so the eye catches a message that appears far from
       where the user is working. It runs once, on the row created by @for. */
    @keyframes toast-in { from { opacity: 0; transform: translateX(12px); } to { opacity: 1; transform: none; } }
    /* Respects the system setting "reduce motion". Why: moving elements can cause nausea or
       trigger symptoms for some users. Without this rule, such a user would get a sliding
       box every time any screen of the application saves something. */
    @media (prefers-reduced-motion: reduce) { .toast-item { animation: none; } }
  `]
})
/**
 * The component class. It is almost empty on purpose: it keeps no copy of the message list
 * and no timer, it only exposes the service to the template.
 * Why: the list must survive this component being redrawn, and the timers must keep running
 * even while nothing is displayed. A local copy here could drift apart from the service
 * list, and a message closed by the service could stay on screen for ever.
 */
export class ToastContainerComponent {
  /**
   * The shared ToastService instance (providedIn: 'root', so it is the very same object that
   * every screen pushes its messages into).
   * inject() is used instead of a constructor parameter: it is the style used across this
   * frontend, and it needs no constructor at all here.
   * public and readonly: public because the template reads svc.toasts() and calls
   * svc.dismiss() directly; readonly so the field can never be pointed at another service,
   * which would silently disconnect this container from the messages the screens send.
   */
  readonly svc = inject(ToastService);
}