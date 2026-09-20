import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { ConfirmService } from '../services/confirm.service';

// Exit guard (canDeactivate, unlike the entry guards elsewhere in this folder) that
// confirms before leaving a dirty form. Used on 'projects/:id/edit'; asks ConfirmService
// to raise the modal (drawn by ConfirmModalComponent inside ShellComponent) and hands the
// router the resulting promise.

/**
 * Contract for a component protected by unsavedChangesGuard. isDirty is a function (not a
 * plain boolean) so an Angular signal like 'isDirty = signal(false)' satisfies it directly.
 */
export interface HasUnsavedChanges {
  isDirty: () => boolean;
}

/**
 * Returns true immediately when clean, or a Promise<boolean> settled by the confirm modal
 * (true = leave and discard, false = stay). Unlike auth/permission guards, 'false' is the
 * right answer here: the router simply keeps the user on the form.
 */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => {
  // Fast path: a clean form must never raise a dialog.
  if (!component.isDirty()) return true;
  // ConfirmService replaces window.confirm() (M-11): it can't be styled or translated and
  // freezes the tab. These two strings stay hardcoded French rather than a Transloco key,
  // unlike other confirmations in the app.
  return inject(ConfirmService).ask(
    'Des modifications non enregistrées seront perdues. Quitter quand même ?',
    'Modifications non enregistrées'
  );
};
