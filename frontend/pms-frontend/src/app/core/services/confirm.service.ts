import { Injectable, signal } from '@angular/core';

/**
 * M-11: Service-based confirmation dialog — replaces all window.confirm() calls.
 * Usage: `if (!await this.confirm.ask('Supprimer ?')) return;`
 * The ConfirmModalComponent (rendered in ShellComponent) listens to this service.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly pending  = signal(false);
  readonly message  = signal('');
  readonly title    = signal('Confirmation');

  private _resolve!: (ok: boolean) => void;

  ask(message: string, title = 'Confirmation'): Promise<boolean> {
    this.message.set(message);
    this.title.set(title);
    this.pending.set(true);
    return new Promise(resolve => { this._resolve = resolve; });
  }

  accept():  void { this.pending.set(false); this._resolve(true);  }
  decline(): void { this.pending.set(false); this._resolve(false); }
}
