import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { ConfirmService } from '../../core/services/confirm.service';

@Component({
  selector: 'app-confirm-modal',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    @if (svc.pending()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal fade show d-block" tabindex="-1" role="dialog" aria-modal="true"
           (keydown.escape)="svc.decline()">
        <div class="modal-dialog modal-dialog-centered">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ svc.title() }}</h5>
              <button type="button" class="btn-close" (click)="svc.decline()"
                      [attr.aria-label]="'common.cancel' | transloco"></button>
            </div>
            <div class="modal-body">{{ svc.message() }}</div>
            <div class="modal-footer">
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
export class ConfirmModalComponent {
  readonly svc = inject(ConfirmService);
}
