import { Injectable, signal } from '@angular/core';

/**
 * M-11: Service-based confirmation dialog — replaces all window.confirm() calls.
 * Usage: 'if (!await this.confirm.ask('Delete this?')) return;'
 * The ConfirmModalComponent (rendered in ShellComponent) listens to this service.
 *
 * WHAT THIS FILE IS
 * The "are you sure?" of the whole application, written once. It holds no HTML: it only
 * holds the question being asked, and the promise that will carry the answer back to the
 * caller.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls ask()          : every screen before a destructive action - agile, billing,
 *                            governance, missions, project detail, the admin screens - and
 *                            core/guards/unsaved-changes.guard.ts before leaving a form
 *                            that still holds unsaved changes.
 * Who calls accept/decline : only shared/confirm-modal/confirm-modal.component.ts, the
 *                            modal window mounted once inside ShellComponent. It reads the
 *                            three signals below to draw itself.
 *
 * WHY IT EXISTS (why not window.confirm)
 * window.confirm() freezes the whole browser tab, it cannot be styled, it cannot be
 * translated by Transloco, and it cannot be driven by a test. Worse, the modal it opens
 * looks like a browser alert, not like the application. This service gives the same simple
 * code to write - one line, wait for true or false - with a modal that belongs to the
 * design system and speaks the language the user chose.
 *
 * HOW THE TWO HALVES MEET
 * ask() stores the text and raises a flag; the modal is drawn because it watches that
 * flag; the user clicks; the modal calls accept() or decline(); the promise handed back by
 * ask() finally settles. The caller wrote one single await and never saw the modal at all.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  // A signal is a value Angular watches: changing it redraws every template that reads it.
  // pending is the on/off switch of the modal - the component shows itself with @if.
  // Why a signal and not a plain boolean: with a plain field nothing would tell Angular to
  // draw the modal, so ask() would block for ever on a window nobody can see.
  readonly pending  = signal(false);
  // The question shown in the body of the modal.
  readonly message  = signal('');
  // The heading. It has a default so a caller can ask a question in one argument.
  readonly title    = signal('Confirmation');

  // The "resolve" half of the promise created in ask(). Keeping it here is what lets a
  // click that happens much later finish a promise created earlier.
  // The "!" is the definite assignment assertion: it tells TypeScript "trust me, this will
  // be set before it is read". It is true here because the modal is only drawn while
  // pending() is true, and pending only becomes true inside ask(), which sets this field.
  // Without the "!", TypeScript would refuse to compile a field that no constructor fills.
  private _resolve!: (ok: boolean) => void;

  /**
   * Asks the question and gives back a Promise<boolean>: true when the user confirms,
   * false when he cancels or presses Escape.
   *
   * Why a Promise and not an Observable, in a project that uses Observables everywhere:
   * the answer comes exactly once and cannot be cancelled or replayed, and a Promise lets
   * the caller write "if (!await ...) return;" - one readable line, with the rest of the
   * method simply not running. The same thing with an Observable would push every caller
   * into a subscribe() callback, and the guard could not return it directly.
   *
   * The promise is deliberately NOT resolved here. It is parked, and the resolve function
   * is stored in _resolve, to be called later by accept() or decline().
   */
  ask(message: string, title = 'Confirmation'): Promise<boolean> {
    this.message.set(message);
    this.title.set(title);
    // Raising this last is what makes the modal appear, once the text is already correct.
    this.pending.set(true);
    return new Promise(resolve => { this._resolve = resolve; });
  }

  // Called by the modal when the user confirms. The order matters: closing first means the
  // modal is already gone when the caller's next line runs, so the user does not see the
  // dialog hanging on screen while the delete request travels.
  accept():  void { this.pending.set(false); this._resolve(true);  }
  // Called by the modal on Cancel, on the close button and on the Escape key. Answering
  // false rather than leaving the promise unfinished is what stops the calling method:
  // without it the "are you sure?" would be decoration and the row would be deleted anyway.
  decline(): void { this.pending.set(false); this._resolve(false); }
}
