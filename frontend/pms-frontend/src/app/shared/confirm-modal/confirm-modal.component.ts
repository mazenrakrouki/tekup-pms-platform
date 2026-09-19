import { Component, inject } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { ConfirmService } from '../../core/services/confirm.service';

/**
 * WHAT THIS FILE IS
 * The one and only "are you sure?" window of the application. It is pure screen: it draws
 * the question, the Cancel button and the Confirm button, and nothing else.
 *
 * WHERE IT SITS IN THE FLOW
 * Who mounts it : ShellComponent, one single time, so the modal lives next to every page.
 * What it reads : the three signals of ConfirmService (pending, title, message). A signal
 *                 is a value Angular watches; when it changes, this template is redrawn.
 * What it calls : ConfirmService.accept() / ConfirmService.decline(), which finish the
 *                 Promise that the calling screen is awaiting.
 * So the full round trip is: some screen calls confirm.ask('Delete this?') and waits ->
 * the service flips pending to true -> this component appears -> the user clicks ->
 * accept()/decline() settles the promise -> the waiting screen continues, or stops.
 *
 * WHY IT EXISTS
 * Delete this file and ConfirmService.ask() would never get an answer: the promise would
 * stay parked for ever and every delete button in the application would simply do nothing.
 * It also exists so the question is drawn only once for the whole app. The alternative -
 * a modal copied into every screen that deletes something - would mean the same markup
 * repeated dozens of times, and one forgotten copy would silently drop back to the ugly,
 * untranslated browser window.confirm().
 */
@Component({
  selector: 'app-confirm-modal',
  // Standalone: the component declares its own dependencies and needs no NgModule.
  // Why: this project has no feature modules at all, so a module here would be an empty
  // file whose only job is to re-export this class.
  standalone: true,
  // TranslocoModule is what makes the "| transloco" pipe below exist. Without it the
  // template would not compile, because Angular would not know that pipe name.
  imports: [TranslocoModule],
  template: `
    <!-- @if is the switch of the whole modal: nothing at all is in the page until a caller
         asks a question. WHY: keeping the markup out of the DOM means no invisible backdrop
         can ever sit on top of the page and swallow clicks. WITHOUT IT we would have to
         hide the modal with CSS, and a wrong z-index or a leftover "show" class would make
         the application look frozen while the user clicks on nothing. -->
    @if (svc.pending()) {
      <!-- The grey sheet behind the window (Bootstrap 5). "show" is the class that makes it
           visible; we add it by hand because no Bootstrap JavaScript runs here - Angular
           alone decides when the modal exists. -->
      <div class="modal-backdrop fade show"></div>
      <!-- d-block replaces the display:none that Bootstrap puts on .modal by default.
           tabindex="-1" lets this container receive the keyboard, which is what makes the
           Escape binding below able to fire.
           aria-modal="true" tells a screen reader that the rest of the page is inactive;
           without it a blind user would keep reading the page behind the question.
           (keydown.escape) gives the usual escape hatch: pressing Esc answers "no".
           WITHOUT IT a user who opened the dialog by mistake would have no keyboard way
           out, and the awaited promise would never settle. -->
      <div class="modal fade show d-block" tabindex="-1" role="dialog" aria-modal="true"
           (keydown.escape)="svc.decline()">
        <div class="modal-dialog modal-dialog-centered">
          <div class="modal-content">
            <div class="modal-header">
              <!-- Title chosen by the caller; ConfirmService falls back to "Confirmation". -->
              <h5 class="modal-title">{{ svc.title() }}</h5>
              <!-- The small "x". It answers decline(), exactly like Cancel: closing the
                   window must never be read as an approval.
                   [attr.aria-label] writes a real HTML attribute (there is no DOM property
                   named aria-label to bind to), so the translated text reaches the
                   accessibility tree. Without it the button is announced as just "button"
                   and a screen reader user cannot tell what it does. -->
              <button type="button" class="btn-close" (click)="svc.decline()"
                      [attr.aria-label]="'common.cancel' | transloco"></button>
            </div>
            <!-- The question itself. {{ }} escapes its content, so a project name that
                 contains < or > is shown as text and can never be injected as HTML. -->
            <div class="modal-body">{{ svc.message() }}</div>
            <div class="modal-footer">
              <!-- type="button" on every button here. WHY: if this modal is ever rendered
                   inside a form, the browser default type="submit" would submit that form
                   on click and reload the page instead of answering the question. -->
              <button type="button" class="btn btn-secondary" (click)="svc.decline()">
                <!-- The transloco pipe swaps the key for the text of the chosen language
                     (FR/EN). Hard-coded words here would stay English for a French user. -->
                {{ 'common.cancel' | transloco }}
              </button>
              <!-- btn-danger, the red button: the confirmation is almost always a delete,
                   and the colour warns before the click. -->
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
/**
 * The class is deliberately almost empty: it holds no copy of the question and no boolean
 * of its own. Everything lives in ConfirmService.
 * WHY: the caller and this window must agree on one single state. If the component kept
 * its own copy, the two could drift - the window could close while the service still
 * believes a question is open, and the awaited promise would never finish.
 */
export class ConfirmModalComponent {
  // Public and readonly so the template can read the signals directly (a template can only
  // reach public members), while no other code can replace the service instance.
  // inject() is used instead of a constructor parameter: same result, less noise.
  readonly svc = inject(ConfirmService);
}
