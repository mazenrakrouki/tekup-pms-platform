import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { ConfirmService } from '../services/confirm.service';

/**
 * WHAT THIS FILE IS
 * The "you are about to lose what you typed" gate. Unlike the two other guards in this
 * folder, it runs when the user LEAVES a page, not when entering one, and it asks for
 * confirmation if the form still holds changes that were never saved.
 *
 * WHERE IT SITS IN THE FLOW
 * app.routes.ts puts 'canDeactivate: [unsavedChangesGuard]' on the project edit route
 * ('projects/:id/edit'). When the user navigates away, the router hands the guard the
 * live component instance. The guard asks the component whether it is dirty; if it is,
 * it asks ConfirmService (core/services/confirm.service.ts) to raise the application
 * modal and gives the router the promise that comes back. The modal itself is drawn by
 * ConfirmModalComponent, which lives inside ShellComponent, so it stays on screen while
 * the page being left is torn down.
 *
 * WHY IT EXISTS
 * Without it, one mis-click on a menu entry after ten minutes of filling in the project
 * form throws all that work away, with no warning at all.
 *
 * WHY THE COMPONENT DECIDES, NOT THE GUARD
 * The guard never looks inside the form. It cannot: each screen keeps its state in its
 * own way (a reactive form here, a plain object plus a signal there). The component
 * answers one yes/no question instead, so the same guard will protect any future screen
 * that implements the interface below.
 */

/**
 * The contract a routed component must fulfil to be protected by unsavedChangesGuard.
 *
 * isDirty is declared as a FUNCTION that returns a boolean, not as a plain boolean
 * property. That is on purpose: an Angular signal is itself a callable function, so a
 * component can write 'isDirty = signal(false)' and satisfy this interface with no glue
 * code at all. ProjectFormComponent does exactly that, and the very same signal also
 * drives the little "unsaved changes" dot in its template.
 * With a plain 'isDirty: boolean' the guard would read a value that was copied at some
 * earlier moment, instead of asking the component for its state right now.
 */
export interface HasUnsavedChanges {
  isDirty: () => boolean;
}

/**
 * Decides whether the router is allowed to leave the current page.
 *
 * Gives back 'true' straight away when nothing is pending, or a Promise<boolean> that
 * settles when the user answers the modal: true = leave and drop the changes,
 * false = stay on the page. The router knows how to wait for a promise, so the whole
 * navigation simply pauses until the user clicks.
 *
 * Why a promise and not a plain confirm(): see the comment on the ask() call below.
 *
 * The generic parameter <HasUnsavedChanges> is what gives 'component' its type. Without
 * it 'component' would be typed 'unknown' and 'component.isDirty()' would not compile.
 *
 * CanDeactivateFn is the type of an EXIT guard. It accepts the same answers as an entry
 * guard - true, false, a UrlTree - and on top of that a Promise or an Observable carrying
 * one of them, which is exactly what makes the asynchronous question below possible.
 * Answering 'false' is the right thing to do here, unlike in auth.guard.ts and
 * permission.guard.ts: the user must stay on the very form he is still filling in, and the
 * router puts the previous address back in the browser bar by itself, so nothing is lost.
 *
 * The router also calls this guard on the way out of a navigation that another guard would
 * have refused anyway. That costs nothing, because the clean-form case below answers
 * immediately without touching any service.
 */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => {
  // Fast path: a clean form must never raise a dialog. Leaving a page you only looked
  // at has to feel instant. Returning the literal `true` (not a promise) also keeps the
  // navigation synchronous in that very common case.
  if (!component.isDirty()) return true;
  // inject() is called here, in the middle of the guard body, and that is allowed
  // because the router runs guards inside an injection context. It is done lazily, only
  // on the dirty path, so a clean page does not even touch the service.
  // ConfirmService is the project-wide replacement for the browser's window.confirm()
  // (marker M-11, documented in confirm.service.ts). window.confirm() freezes the whole
  // browser tab, cannot be styled to match the application, and cannot be translated.
  // ask() sets the signals that ConfirmModalComponent watches and returns a Promise that
  // the modal's accept() / decline() buttons resolve with true or false.
  // The Promise is handed straight back to the router, with no await: the guard has nothing
  // to do with the answer itself, and awaiting it here would only wrap the same value in a
  // second promise.
  // The two arguments are the question and the heading of the modal. Note that they are
  // written here as plain French text, while the other confirmations of the application
  // pass a Transloco key instead (for example this.t.translate('agile.item.deleteConfirm')),
  // so this one sentence stays in French when the user switches the interface to English.
  return inject(ConfirmService).ask(
    'Des modifications non enregistrées seront perdues. Quitter quand même ?',
    'Modifications non enregistrées'
  );
};
