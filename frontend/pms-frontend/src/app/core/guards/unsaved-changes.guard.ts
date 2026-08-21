import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { ConfirmService } from '../services/confirm.service';

export interface HasUnsavedChanges {
  isDirty: () => boolean;
}

export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => {
  if (!component.isDirty()) return true;
  return inject(ConfirmService).ask(
    'Des modifications non enregistrées seront perdues. Quitter quand même ?',
    'Modifications non enregistrées'
  );
};
