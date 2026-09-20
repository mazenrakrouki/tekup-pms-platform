import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { ConfirmService } from '../../core/services/confirm.service';

/**
 * The app's single "are you sure?" dialog. Mounted once by ShellComponent; reads
 * ConfirmService's signals and calls accept()/decline() to settle the caller's awaited promise.
 */
@Component({
  selector: 'app-confirm-modal',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <!-- @if keeps the modal out of the DOM entirely when idle, so no invisible backdrop can
         swallow clicks. -->
    @if (svc.pending()) {
      <!-- Bootstrap classes applied by hand: no Bootstrap JS runs here, Angular owns visibility. -->
      <div class="modal-backdrop fade show"></div>
      <!-- aria-modal + keydown.escape: keeps the dialog accessible and give a keyboard way out. -->
      <div class="modal fade show d-block" tabindex="-1" role="dialog" aria-modal="true"
           (keydown.escape)="svc.decline()">
        <div class="modal-dialog modal-dialog-centered">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ svc.title() }}</h5>
              <!-- The "x" declines too: closing the window must never read as approval. -->
              <button type="button" class="btn-close" (click)="svc.decline()"
                      [attr.aria-label]="'common.cancel' | transloco"></button>
            </div>
            <div class="modal-body">{{ svc.message() }}</div>
            <div class="modal-footer">
              <!-- type="button": prevents an implicit form submit if this ever renders inside a form. -->
              <button type="button" class="btn btn-secondary" (click)="svc.decline()">
                {{ 'common.cancel' | transloco }}
              </button>
              <button type="button" class="btn btn-danger" (click)="svc.accept()" #confirmBtn>
                {{ 'common.confirm' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
// No local state on purpose: caller and dialog must share one source of truth (ConfirmService).
export class ConfirmModalComponent {
  readonly svc = inject(ConfirmService);
}
