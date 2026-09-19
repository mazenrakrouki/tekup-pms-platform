import { Injectable, signal } from '@angular/core';

/**
 * WHAT THIS FILE IS
 * The short messages that slide in at the corner of the screen ("Project saved", "Delete
 * failed"). This file holds the list of messages waiting to be shown and the timers that
 * remove them. It contains no HTML: the drawing is done by ToastContainerComponent.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls success/error/warning/info: almost every screen, after a call to the server
 *                                       succeeds or fails - admin, agile, billing,
 *                                       governance, missions, projects, resources, workload.
 * Who reads toasts() and calls dismiss(): only shared/toast-container/toast-container.
 *                                       component.ts, mounted once inside ShellComponent.
 * It calls nothing and talks to no server.
 *
 * WHY IT EXISTS
 * It replaces two older habits. The first was the browser alert(), which freezes the whole
 * page until the user clicks and cannot be styled. The second was a message printed inside
 * the page: it pushed the layout down, and every screen had to declare its own message
 * variable and its own place to show it. Here the rule is written once, the messages appear
 * above the page without moving anything, and a screen needs one line to use it.
 */

/**
 * The four kinds of message. It is a union of exact strings, not "string".
 * Why: the kind chooses the colour and the icon in the container. Written as a plain string,
 * a typo such as 'succes' would compile, and the message would appear with no colour and no
 * icon instead of failing at build time.
 */
export type ToastKind = 'success' | 'error' | 'warning' | 'info';

/**
 * One message in the list.
 * id is what makes each message addressable: the container uses it to draw the list without
 * redrawing everything, and dismiss(id) uses it to remove the right one. Without an id, two
 * identical messages could not be told apart, and closing one would close the other.
 */
export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/**
 * M-11: service-based notifications (toasts) - replaces the inline messages and the browser
 * alert() calls. Drawn by ToastContainerComponent, mounted in ShellComponent. They close by
 * themselves; error messages stay longer.
 */
// @Injectable lets Angular build this class and inject it.
// providedIn: 'root' creates ONE shared instance, and that is essential here: the twenty
// screens that push messages and the single container that displays them must share the
// same list. With one instance per component, a screen would push into its own list and the
// container would show nothing at all.
@Injectable({ providedIn: 'root' })
export class ToastService {
  // A signal is a value that remembers who reads it: when the array changes, Angular redraws
  // the container, with no subscription to write and nothing to unsubscribe from.
  // readonly protects the signal itself, not its content: nobody can replace it by another
  // signal, while update() below still works. Without it, a component could swap the signal
  // and the container would keep reading the old one, so the messages would stop appearing.
  readonly toasts = signal<Toast[]>([]);

  // The counter that gives each message its id. It only goes up, never back, so two messages
  // can never share an id even after the list has been emptied.
  // Why not the length of the array: after two messages are closed the length goes back down,
  // so a new message would reuse an id that a running timer is still about to remove - and
  // the wrong message would disappear.
  private seq = 0;

  // The four public doors of the service. Each one fixes the kind and a default lifetime,
  // so a screen writes this.toast.success('Saved') and nothing else.
  // Why the error lives longer (6000 ms) than a success (3500 ms): a success only confirms
  // what the user has just done, while an error has to be read and understood, sometimes on
  // a screen the user is no longer looking at. A failure that vanished in three seconds
  // would leave the user thinking the save had worked.
  success(message: string, duration = 3500): void { this.push('success', message, duration); }
  error(message: string,   duration = 6000): void { this.push('error', message, duration); }
  warning(message: string, duration = 5000): void { this.push('warning', message, duration); }
  info(message: string,    duration = 3500): void { this.push('info', message, duration); }

  /**
   * Removes one message, by the timer below or by the close button of the container.
   *
   * filter() builds a NEW array without that message instead of changing the current one.
   * Why it matters with signals: a signal compares the old and the new value to decide
   * whether to redraw. Removing the item in place would keep the same array object, the
   * signal would see no change, and the closed message would stay on the screen.
   * Calling it twice with the same id is harmless: the second call simply finds nothing to
   * remove. That is what makes the close button and the timer safe together.
   */
  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  /**
   * The single place where a message is added to the list and where its timer is armed.
   *
   * Private on purpose: the four doors above are the whole public surface. If screens could
   * call push() directly they would pass their own kind strings, and the colours would stop
   * being consistent across the application.
   */
  private push(kind: ToastKind, message: string, duration: number): void {
    // ++seq increments FIRST and then gives the value, so the first message has id 1 and
    // never 0. It matters because 0 is falsy in JavaScript: a test such as if (toast.id)
    // would treat the very first message as missing.
    const id = ++this.seq;
    // [...list, newItem] copies the current messages and adds the new one at the end - again
    // a new array, for the same reason as in dismiss() above. At the end, so the messages
    // are shown in the order they happened.
    this.toasts.update(list => [...list, { id, kind, message }]);
    // duration > 0 is the way to ask for a message that does NOT close by itself: a caller
    // passing 0 keeps it until the user closes it. Without this test, setTimeout(..., 0)
    // would fire on the next tick and the message would flash and disappear.
    if (duration > 0) {
      // setTimeout closes the message after the delay. It captures `id`, not the position in
      // the array, so the right message is removed even if others were closed meanwhile.
      // Why the timer is here and not in the container component: the container can be
      // redrawn, and a timer living in a redrawn component could be lost or armed twice.
      setTimeout(() => this.dismiss(id), duration);
    }
  }
}
