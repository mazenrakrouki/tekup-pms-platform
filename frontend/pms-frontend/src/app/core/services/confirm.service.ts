import { Injectable, signal } from '@angular/core';

// M-11: service-based confirmation dialog replacing window.confirm() (which freezes the tab,
// can't be styled or translated). Usage: 'if (!await this.confirm.ask("Delete this?")) return;'
// ConfirmModalComponent (mounted once in ShellComponent) watches the signals below and calls
// accept()/decline(), which settles the promise ask() handed back to the caller.
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  // Signal, not a plain boolean: the modal's @if needs to react when this flips.
  readonly pending  = signal(false);
  readonly message  = signal('');
  readonly title    = signal('Confirmation');

  // The resolve half of ask()'s promise, stored so a later click can settle it.
  private _resolve!: (ok: boolean) => void;

  /** Shows the modal and resolves true/false on confirm/cancel. A Promise, not an Observable,
   *  so callers can write "if (!await ...) return;" instead of a subscribe() callback. */
  ask(message: string, title = 'Confirmation'): Promise<boolean> {
    this.message.set(message);
    this.title.set(title);
    this.pending.set(true);
    return new Promise(resolve => { this._resolve = resolve; });
  }

  // Closes the modal before resolving, so it isn't left on screen while the caller's next
  // line (e.g. a delete request) runs.
  accept():  void { this.pending.set(false); this._resolve(true);  }
  decline(): void { this.pending.set(false); this._resolve(false); }
}
