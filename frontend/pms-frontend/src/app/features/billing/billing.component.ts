import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { BillingService } from '../../core/services/billing.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { JalonFacturation, Avenant, Paiement } from '../../core/models/billing.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

/**
 * WHAT THIS FILE IS
 * The billing screen of one project: the list of billing milestones (jalons), the list of
 * contract amendments (avenants), and the four small windows that create them, invoice a
 * milestone, or record money received.
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it : the Angular router, on the /billing route, inside ShellComponent.
 *                The route itself is protected by core/guards/permission.guard.ts.
 * What it calls: ProjectService.listAll() to fill the project picker, then
 *                core/services/billing.service.ts for every read and every write, which
 *                talks to the Spring Boot BillingController over HTTP.
 *                It also uses ConfirmService (the "are you sure?" window), ToastService
 *                (the small message in the corner) and TranslocoService (translations).
 * Children     : shared/project-picker/project-picker.component.ts, which is how the user
 *                chooses the project. Firm project rule: no giant dropdown for a large
 *                list, always this picker.
 *
 * WHY IT EXISTS
 * Delete it and the money side of a project has no screen at all. Milestones could still
 * be created by calling the API by hand, but nobody could see the billing plan, mark a
 * milestone as invoiced, or record a payment - so a project would show work done and no
 * trace of what was billed or cashed.
 *
 * THE LIFE OF A MILESTONE (read this before the methods below)
 * PREVU (planned) -> FACTURE (invoiced) -> PAYE (paid). The screen never sets a status by
 * hand: openFacturer() asks the server to move PREVU -> FACTURE, and the server itself
 * moves FACTURE -> PAYE once the recorded payments cover the amount. A milestone amount is
 * never typed in either: the user gives a percentage of the project budget and the server
 * computes the money. This follows the project rule that computed amounts are derived when
 * read, never stored and never decided by the browser.
 *
 * SECURITY - WHAT canManage() REALLY DOES
 * canManage() only decides whether a button is DRAWN. Anybody can make that button appear
 * again with the browser developer tools. The real refusal happens on the server, with
 * @PreAuthorize("hasAuthority('MANAGE_BILLING')") on the SERVICE methods, plus
 * ProjectScopeInterceptor, which for every /api/projects/{id}/** URL checks the permission
 * AND that this user is allowed on THAT project (ADR-021). The screen tests a permission
 * code, never a role name, so an administrator can invent a new role without touching this
 * file.
 */

/**
 * Which of the four windows is open, or null for "none open".
 * Why one signal with four possible names instead of four booleans: with four booleans a
 * mistake could set two of them to true and the user would see two windows stacked, each
 * writing into the same "saving" flag.
 */
type BillingModal = 'jalon' | 'avenant' | 'facturer' | 'paiement' | null;
// Sort direction, shared by both tables. A string union instead of a boolean because
// "asc"/"desc" is also what the aria-sort attribute needs, so nothing has to be translated
// from true/false and the two tables can never disagree on which way true means.
type SortDir = 'asc' | 'desc';
/**
 * The milestone columns the user is allowed to sort on.
 * Why a union of exact field names and not plain "string": sortedJalons() below reads
 * a[col] on a JalonFacturation. With "string", a typo such as 'montnat' would compile and
 * the table would silently stop sorting; with this union the build fails on the typo.
 * Note which names are missing: statut and dateFacture are not sortable here.
 */
type JalonSortCol = 'label' | 'pourcentage' | 'montant' | 'datePrevue';
// Same idea for the amendments table: only these three fields may be sorted, and each one
// really exists on the Avenant model (see core/models/billing.model.ts).
type AvenantSortCol = 'numero' | 'montant' | 'dateAvenant';

/**
 * The component definition.
 *
 * standalone: true means the component declares its own dependencies in "imports" instead
 * of belonging to an NgModule. Why: the whole project is standalone, and the router can
 * then load this screen on its own. Without it, Angular would refuse to compile the lazy
 * route because the component would belong to no module.
 *
 * imports lists ONLY what the template really uses: CommonModule for the "number" pipe and
 * ngClass, FormsModule for [(ngModel)] in the four windows, ProjectPickerComponent for the
 * project chooser, TranslocoModule for the "| transloco" pipe. A missing entry here is not
 * a silent bug - the template simply fails to compile, which is the point.
 *
 * The template is written inline, in backticks, like every screen of this project. Inside
 * that HTML only the arrow style of comment may be used; a JavaScript-style comment there
 * would be printed on the page as ordinary text.
 */
@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  /*
   * Styles local to this screen only. Angular rewrites these rules so they cannot leak to
   * another component, which is why a class as common as ".caret" is safe here.
   * Only the sortable-header look lives here; everything else comes from the global
   * styles.scss design system. Inside this block, only comments are allowed.
   */
  styles: [`
    /* Red tint for the delete buttons. Uses the design-system variable, not a raw colour,
       so the red still reads correctly when the user switches to the dark theme. */
    .act-danger { color: var(--c-danger); }
    /* A clickable column header. user-select:none stops the browser from selecting the
       header text when the user clicks it several times in a row to flip the sort;
       without it, repeated clicks would highlight the title in blue. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    /* The title and its little arrow are kept on one line by this inner flex box. Without
       it the arrow could wrap under the title when the column is narrow. */
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* On a right-aligned (numeric) column the order is reversed so the arrow stays on the
       inside, next to the text, instead of being pushed against the cell border. */
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    /* The arrow is invisible at rest, faint on hover, full when the column is the one
       being sorted. It keeps its space even when hidden (opacity, not display:none), so
       the header text does not jump sideways when the mouse passes over it. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* Visible ring for the user who moves with the Tab key. focus-visible, not focus, so
       the ring is not drawn on a plain mouse click. Removing this would leave a keyboard
       user unable to see which header he is about to activate. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
  `],
  template: `
    <!-- Breadcrumb bar. It has two shapes: "Billing" alone while no project is chosen, and
         "Billing > CODE" once one is, where the first part becomes a button that goes back
         to the picker. @if is Angular's built-in condition; it REMOVES the branch that is
         false from the page instead of only hiding it, so selected()!.code below can never
         be read on a null project. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-receipt" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" [title]="'billing.backToPicker' | transloco">
            <i class="bi bi-arrow-left"></i> {{ 'billing.title' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'billing.title' | transloco }}</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.billing' | transloco }}</h1>
      </div>
      <!-- Project selector. The shared picker is reused here on purpose: the firm project
           rule is that a long list of projects is never put in a plain dropdown, because a
           dropdown with hundreds of lines cannot be searched and cannot be read.
           [selected] pushes the current project down so the picker can show it; the
           (projectSelected) event comes back up with the project the user clicked, and
           $event is that project, handed to select() below. -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-receipt-cutoff"
                            (projectSelected)="select($event)" />
      </div>

      <!-- Everything below exists only once a project is chosen. Without this guard the
           two tables would try to read selected()!.name on null and the screen would show
           an error instead of the picker. -->
      @if (selected()) {
        <div class="row g-4">
          <!-- MILESTONES TABLE (jalons) - the billing plan of the project. -->
          <div class="col-12">
            <div class="card">
              <div class="card-header justify-content-between">
                <span><i class="bi bi-list-check me-2"></i>{{ 'billing.milestones' | transloco }} — {{ selected()!.name }}</span>
                <div class="d-flex align-items-center gap-3">
                  <!-- The sum of the milestone amounts. The "number" pipe with '1.0-0'
                       means: at least one digit before the dot, and zero decimals, so
                       1234.56 is drawn as "1,235". Why no decimals: these are contract
                       figures read at a glance, and a column of long decimals is unreadable.
                       Careful when reading this line: jalonTotal() adds up EVERY milestone,
                       including the ones still only planned. -->
                  <span class="text-muted small">{{ 'billing.totalInvoiced' | transloco }} {{ jalonTotal() | number:'1.0-0' }} TND</span>
                  <!-- Write buttons are drawn only for a user holding MANAGE_BILLING. This
                       is comfort, not protection: the server refuses the call anyway
                       (@PreAuthorize on the service, plus the project scope check of
                       ADR-021). Without it a read-only user would see buttons that always
                       answer "forbidden". -->
                  @if (canManage()) {
                    <button class="btn btn-primary btn-sm" (click)="openModal('jalon')">
                      <i class="bi bi-plus-lg me-1"></i>{{ 'billing.addMilestone' | transloco }}
                    </button>
                  }
                </div>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <!-- Each sortable header repeats the same four things:
                         [class.is-sorted] paints it when it is the active column;
                         [attr.aria-sort] tells a screen reader which way the table is
                           sorted - without it a blind user hears the column name and never
                           learns that the rows were reordered;
                         tabindex="0" puts the header in the Tab path, because a <th> is
                           not focusable by itself and the sort would be mouse-only;
                         (keydown.enter) and (keydown.space) do with the keyboard what the
                           click does. $event.preventDefault() on Space is needed because
                           the browser scrolls the page down on Space by default, so without
                           it the table would sort AND jump. -->
                    <tr>
                      <th class="th-sort" [class.is-sorted]="jalonSortCol()==='label'" [attr.aria-sort]="ariaJalonSort('label')"
                          tabindex="0" (click)="toggleJalonSort('label')" (keydown.enter)="toggleJalonSort('label')" (keydown.space)="toggleJalonSort('label'); $event.preventDefault()">
                        <span class="th-inner">{{ 'billing.colLabel' | transloco }} <i class="bi caret" [ngClass]="caretJalon('label')"></i></span>
                      </th>
                      <th class="th-sort text-end" [class.is-sorted]="jalonSortCol()==='pourcentage'" [attr.aria-sort]="ariaJalonSort('pourcentage')"
                          tabindex="0" (click)="toggleJalonSort('pourcentage')" (keydown.enter)="toggleJalonSort('pourcentage')" (keydown.space)="toggleJalonSort('pourcentage'); $event.preventDefault()">
                        <span class="th-inner">% <i class="bi caret" [ngClass]="caretJalon('pourcentage')"></i></span>
                      </th>
                      <th class="th-sort text-end" [class.is-sorted]="jalonSortCol()==='montant'" [attr.aria-sort]="ariaJalonSort('montant')"
                          tabindex="0" (click)="toggleJalonSort('montant')" (keydown.enter)="toggleJalonSort('montant')" (keydown.space)="toggleJalonSort('montant'); $event.preventDefault()">
                        <span class="th-inner">{{ 'billing.colAmount' | transloco }} <i class="bi caret" [ngClass]="caretJalon('montant')"></i></span>
                      </th>
                      <th class="th-sort" [class.is-sorted]="jalonSortCol()==='datePrevue'" [attr.aria-sort]="ariaJalonSort('datePrevue')"
                          tabindex="0" (click)="toggleJalonSort('datePrevue')" (keydown.enter)="toggleJalonSort('datePrevue')" (keydown.space)="toggleJalonSort('datePrevue'); $event.preventDefault()">
                        <span class="th-inner">{{ 'billing.colDueDate' | transloco }} <i class="bi caret" [ngClass]="caretJalon('datePrevue')"></i></span>
                      </th>
                      <th>{{ 'billing.colInvoiceDate' | transloco }}</th><th>{{ 'common.status' | transloco }}</th><th></th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- track j.id tells Angular how to recognise a row it has already
                         drawn. Why it matters: after reload() the array is a brand new
                         array of brand new objects. Without the id, Angular would throw
                         every row away and rebuild it, losing the scroll position and
                         restarting the animations on rows that did not change. -->
                    @for (j of sortedJalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }} TND</td>
                        <!-- ?? '—' is the null-coalescing fallback: these two dates are
                             optional on the model. Without it an unplanned milestone would
                             show an empty cell and the row would look broken instead of
                             simply "not set yet". -->
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>{{ j.dateFacture ?? '—' }}</td>
                        <td>
                          <!-- The status badge. The colour is chosen from the status sent
                               by the server; the screen never decides a status itself. -->
                          @if (j.statut === 'PAYE') { <span class="badge-active">{{ 'milestoneStatus.PAYE' | transloco }}</span> }
                          @else if (j.statut === 'FACTURE') { <span class="badge-completed">{{ 'milestoneStatus.FACTURE' | transloco }}</span> }
                          @else { <span class="badge-draft">{{ 'milestoneStatus.PREVU' | transloco }}</span> }
                        </td>
                        <td class="text-end text-nowrap">
                          @if (canManage()) {
                            <!-- One single action button, chosen by the status: only a
                                 planned milestone offers "invoice"; every other status
                                 (FACTURE, and PAYE so that past payments can still be
                                 consulted) offers the payment window instead. Showing both
                                 at once would let a user try to pay an invoice that was
                                 never issued; the server refuses it, but the user would
                                 only get an error he cannot understand. -->
                            @if (j.statut === 'PREVU') {
                              <button class="btn btn-sm btn-outline-primary me-1" (click)="openFacturer(j)" [title]="'billing.invoice' | transloco">
                                <i class="bi bi-receipt-cutoff me-1"></i>{{ 'billing.invoice' | transloco }}
                              </button>
                            } @else {
                              <button class="btn btn-sm btn-outline-success me-1" (click)="openPaiement(j)" [title]="'billing.recordPayment' | transloco">
                                <i class="bi bi-cash-coin me-1"></i>{{ 'billing.payment' | transloco }}
                              </button>
                            }
                            <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="deleteJalon(j)"
                                    [title]="'common.delete' | transloco" [attr.aria-label]="'billing.deleteMilestone' | transloco">
                              <i class="bi bi-trash"></i>
                            </button>
                          } @else { <span class="text-muted">—</span> }
                        </td>
                      </tr>
                    }
                    <!-- @empty is drawn when the list above has no row at all. Without it
                         a project with no billing plan yet would show a table with a head
                         and nothing under it, and the user could not tell an empty project
                         from a screen that failed to load. -->
                    @empty {
                      <tr><td colspan="7">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-list-check"></i></div>
                          <div class="es-title">{{ 'billing.noMilestones' | transloco }}</div>
                          <div class="es-desc">{{ 'billing.noMilestonesDesc' | transloco }}</div>
                          @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openModal('jalon')"><i class="bi bi-plus-lg me-1"></i>{{ 'billing.addMilestone' | transloco }}</button> }
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- AMENDMENTS TABLE (avenants) - the signed changes to the contract. Kept as
               its own list, and not folded into the project budget, because the revised
               budget must stay explainable line by line in front of the client. -->
          <div class="col-12">
            <div class="card">
              <div class="card-header justify-content-between">
                <span><i class="bi bi-file-earmark-plus me-2"></i>{{ 'billing.amendments' | transloco }} — {{ selected()!.name }}</span>
                @if (canManage()) {
                  <button class="btn btn-primary btn-sm" (click)="openModal('avenant')">
                    <i class="bi bi-plus-lg me-1"></i>{{ 'billing.addAmendment' | transloco }}
                  </button>
                }
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <th class="th-sort" [class.is-sorted]="avenantSortCol()==='numero'" [attr.aria-sort]="ariaAvenantSort('numero')"
                          tabindex="0" (click)="toggleAvenantSort('numero')" (keydown.enter)="toggleAvenantSort('numero')" (keydown.space)="toggleAvenantSort('numero'); $event.preventDefault()">
                        <span class="th-inner">{{ 'billing.colNumber' | transloco }} <i class="bi caret" [ngClass]="caretAvenant('numero')"></i></span>
                      </th>
                      <th>{{ 'billing.colSubject' | transloco }}</th>
                      <th class="th-sort text-end" [class.is-sorted]="avenantSortCol()==='montant'" [attr.aria-sort]="ariaAvenantSort('montant')"
                          tabindex="0" (click)="toggleAvenantSort('montant')" (keydown.enter)="toggleAvenantSort('montant')" (keydown.space)="toggleAvenantSort('montant'); $event.preventDefault()">
                        <span class="th-inner">{{ 'billing.colAmount' | transloco }} <i class="bi caret" [ngClass]="caretAvenant('montant')"></i></span>
                      </th>
                      <th class="text-end">{{ 'billing.colWorkloadDays' | transloco }}</th>
                      <th class="th-sort" [class.is-sorted]="avenantSortCol()==='dateAvenant'" [attr.aria-sort]="ariaAvenantSort('dateAvenant')"
                          tabindex="0" (click)="toggleAvenantSort('dateAvenant')" (keydown.enter)="toggleAvenantSort('dateAvenant')" (keydown.space)="toggleAvenantSort('dateAvenant'); $event.preventDefault()">
                        <span class="th-inner">{{ 'common.date' | transloco }} <i class="bi caret" [ngClass]="caretAvenant('dateAvenant')"></i></span>
                      </th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (a of sortedAvenants(); track a.id) {
                      <tr>
                        <td class="fw-semibold">{{ a.numero }}</td>
                        <td>{{ a.objet }}</td>
                        <!-- Green when the amendment adds money, red when it takes some
                             away. An amendment CAN be negative (scope removed from the
                             contract), and a negative figure printed in the same colour as
                             a positive one is read as a gain at a glance - the worst kind
                             of mistake on a contract screen. -->
                        <td class="text-end"
                            [class.text-success]="a.montant >= 0"
                            [class.text-danger]="a.montant < 0">
                          {{ a.montant | number:'1.0-0' }}
                        </td>
                        <!-- workloadDays is optional, so the cell shows a dash when it is
                             missing. Careful: this test also treats 0 as missing, which is
                             wanted here - an amendment that sells no extra day should read
                             as "no day", not as "0.0". One decimal is kept because days
                             are sold in halves. -->
                        <td class="text-end">{{ a.workloadDays ? (a.workloadDays | number:'1.0-1') : '—' }}</td>
                        <td>{{ a.dateAvenant }}</td>
                        <td class="text-end">
                          @if (canManage()) {
                            <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="deleteAvenant(a)"
                                    [title]="'common.delete' | transloco" [attr.aria-label]="'billing.deleteAmendment' | transloco">
                              <i class="bi bi-trash"></i>
                            </button>
                          } @else { <span class="text-muted">—</span> }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="6">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-file-earmark-plus"></i></div>
                          <div class="es-title">{{ 'billing.noAmendments' | transloco }}</div>
                          <div class="es-desc">{{ 'billing.noAmendmentsDesc' | transloco }}</div>
                          @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openModal('avenant')"><i class="bi bi-plus-lg me-1"></i>{{ 'billing.addAmendment' | transloco }}</button> }
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      }
    </div>

    <!-- WINDOW 1 of 4: create a milestone.
         The four windows all follow the same skeleton:
           - a backdrop div that greys out the page;
           - a click on the outer .modal closes the window;
           - $event.stopPropagation() on the inner dialog stops that same click from
             travelling up when the user clicks INSIDE the form. Without it, clicking a
             text field would close the window and throw away what was typed.
         They are written by hand rather than with the Bootstrap JavaScript so that the
         open/closed state stays a signal that Angular controls. -->
    @if (modal() === 'jalon') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'billing.addMilestone' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <!-- [(ngModel)] is two-way binding: what the user types goes into the plain
                   object jalonForm, and a change made in the code shows up in the field.
                   The forms of this screen are plain objects and not a reactive FormGroup
                   because they are three or four fields checked in one "if" in the save
                   method; a FormGroup would add a lot of code for nothing here. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colLabel' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="jalonForm.label" [placeholder]="'billing.milestoneLabelPh' | transloco">
              </div>
              <!-- The user gives a PERCENTAGE, never an amount: the server multiplies it
                   by the project budget. If the browser sent the money, two screens could
                   disagree with the contract and a revised budget would leave every old
                   milestone showing a stale figure.
                   min and max are only a hint from the browser; the real check is on the
                   server, because the form can be sent from outside this page. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.percentage' | transloco }} <span class="text-danger">*</span></label>
                <div class="input-group">
                  <input type="number" class="form-control" [(ngModel)]="jalonForm.pourcentage" min="1" max="100">
                  <span class="input-group-text">%</span>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colDueDate' | transloco }}</label>
                <input type="date" class="form-control" [(ngModel)]="jalonForm.datePrevue">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">{{ 'common.cancel' | transloco }}</button>
              <!-- [disabled]="saving()" is the protection against the double click. The
                   request takes time; without it an impatient user clicking twice would
                   create the same milestone twice, and the billing plan would add up to
                   more than 100% of the budget. The spinner is what tells him to wait.
                   The same pattern is repeated in the three other windows below.
                   The red box just above shows the message sent back by the server (for
                   example "the total of the milestones exceeds 100%"); it is kept inside
                   the window so the user reads it next to the field he must correct. -->
              <button class="btn btn-primary" (click)="saveJalon()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- WINDOW 2 of 4: create an amendment. -->
    @if (modal() === 'avenant') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'billing.addAmendment' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colNumber' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.numero" [placeholder]="'billing.amendmentNumberPh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colSubject' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.objet" [placeholder]="'billing.amendmentSubjectPh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colAmount' | transloco }} <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.montant" [placeholder]="'billing.amountPh' | transloco">
              </div>
              <!-- Extra man-days sold by this amendment. Optional: some amendments only
                   change the money or the scope. step="0.5" because days are sold in
                   halves; with the default step of 1 the browser would mark "2.5" as
                   invalid and refuse the value the contract really says. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.soldWorkloadImpact' | transloco }}</label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.workloadDays" min="0" step="0.5" [placeholder]="'billing.workloadPh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'common.date' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="avenantForm.dateAvenant">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveAvenant()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- WINDOW 3 of 4: invoice a milestone (PREVU -> FACTURE).
         It asks for one thing only, the invoice date. The new status is NOT sent: the
         server moves it and refuses an illegal move. If the browser chose the status, a
         screen could push a milestone straight to PAYE with no invoice ever issued. -->
    @if (modal() === 'facturer') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'billing.invoiceMilestone' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <!-- A reminder of which milestone is about to be invoiced, and for how much.
                   The "?." is the safe-navigation operator: it reads the field only when
                   currentJalon() is not null. It is needed because this window is drawn
                   from the modal() signal, not from currentJalon(); without it a null
                   would crash the whole template instead of drawing an empty name. -->
              <p class="text-muted small mb-3">
                {{ 'billing.milestoneWord' | transloco }} <strong>{{ currentJalon()?.label }}</strong>
                ({{ currentJalon()?.montant | number:'1.0-0' }} TND) → {{ 'billing.movesToStatus' | transloco }} <span class="badge-completed">{{ 'milestoneStatus.FACTURE' | transloco }}</span>
              </p>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.invoiceDate' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="facturerDate">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveFacturer()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'billing.invoice' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- WINDOW 4 of 4: record money received against an invoiced milestone.
         It also lists the payments already received, because a client may pay in several
         instalments: without that list the user could not tell what is still owed and
         would enter the same instalment twice. -->
    @if (modal() === 'paiement') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'billing.recordPayment' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <p class="text-muted small mb-3">
                {{ 'billing.milestoneWord' | transloco }} <strong>{{ currentJalon()?.label }}</strong> — {{ 'billing.invoicedAmount' | transloco }} {{ currentJalon()?.montant | number:'1.0-0' }} TND
              </p>
              <!-- The block of past payments is skipped entirely when there is none, so a
                   first payment does not open on an empty "Payments received" heading. -->
              @if (paiements().length) {
                <div class="mb-3">
                  <div class="fw-semibold small text-muted mb-1">{{ 'billing.paymentsReceived' | transloco }}</div>
                  <ul class="list-group list-group-flush small">
                    @for (pmt of paiements(); track pmt.id) {
                      <li class="list-group-item px-0 py-1 d-flex justify-content-between">
                        <span>{{ pmt.datePaiement }}</span>
                        <span class="fw-semibold">{{ pmt.montantRecu | number:'1.0-0' }} TND</span>
                      </li>
                    }
                  </ul>
                </div>
              }
              <!-- The amount really received, which may be smaller than the invoice.
                   step="0.01" allows the centimes of a bank transfer; with the default
                   step the browser would refuse 1250.75 and the row could never match the
                   bank statement. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.amountReceived' | transloco }} <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="paiementForm.montantRecu" min="0" step="0.01">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.paymentDate' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="paiementForm.datePaiement">
              </div>
              <!-- The bank or accounting reference of the transfer, for example
                   "VIR-2026-0142". Optional here, but it is what lets an auditor match
                   this row with the bank statement later. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.reference' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="paiementForm.reference" [placeholder]="'billing.referencePh' | transloco">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-success" (click)="savePaiement()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'billing.savePayment' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
/**
 * The billing screen.
 *
 * "implements OnInit" is a promise to Angular that ngOnInit() exists. The first loading is
 * done there and not in a constructor, because at construction time the injected services
 * and the route are not fully ready, and a component that starts HTTP calls in its
 * constructor is also much harder to test.
 */
export class BillingComponent implements OnInit {
  /*
   * The services, taken with inject() instead of constructor arguments. Same result, but
   * adding one service does not mean rewriting the constructor line, and the fields can be
   * marked readonly right here so nothing can replace a service later by mistake.
   */
  private readonly projectSvc = inject(ProjectService);
  private readonly billingSvc = inject(BillingService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  /**
   * Says whether the write buttons should be drawn.
   *
   * It tests a PERMISSION CODE, never a role name. That is the rule of the whole project:
   * an administrator can create a new role and give it MANAGE_BILLING, and this line keeps
   * working with no change. Testing "is he a manager?" would need a new release every time
   * the company invents a job title.
   *
   * It is an arrow function field, so the template can call canManage() directly.
   * And to be clear in front of the jury: this hides buttons, it protects nothing. The
   * refusal that counts is @PreAuthorize("hasAuthority('MANAGE_BILLING')") on the server
   * service, plus the project scope check of ADR-021.
   */
  canManage = () => this.auth.hasPermission('MANAGE_BILLING');

  /*
   * A signal is a value Angular watches: when it changes, every part of the template that
   * reads it is redrawn, and nothing else is. Why signals rather than plain fields: with a
   * plain field nothing tells Angular that the table must be drawn again when the server
   * answers, and the rows would stay stale until some other click happened to refresh the
   * page.
   */
  // The full project list, loaded once, used to turn the "?p=12" of the URL back into a
  // real project object.
  projects = signal<Project[]>([]);
  // The project being looked at, or null while the user is still choosing one.
  selected = signal<Project | null>(null);
  // The two lists shown on the screen, exactly as the server sent them. They are replaced
  // whole by reload(); nothing in this file ever edits a row in place.
  jalons = signal<JalonFacturation[]>([]);
  avenants = signal<Avenant[]>([]);

  // Which milestone column is sorted, and in which direction. The default is the planned
  // date, rising: a billing plan is read as a calendar, so the next milestone to invoice
  // must be the first line without the user having to click anything.
  jalonSortCol = signal<JalonSortCol>('datePrevue');
  jalonSortDir = signal<SortDir>('asc');
  /**
   * The milestone rows in the order the table shows them.
   *
   * computed() builds a value out of other signals and remembers the result: it runs again
   * only when jalons(), jalonSortCol() or jalonSortDir() really changed. A plain method
   * would sort the whole list again at every single redraw of the page, even when a user
   * only hovered a button.
   *
   * Why the copy [...this.jalons()]: Array.sort() reorders the array IN PLACE. Sorting the
   * signal's own array would change a value behind Angular's back - the signal would not
   * notice, so the table would keep the old order, and reload() would later replace the
   * array anyway. The copy also keeps this computed free of side effects, which is what
   * computed() requires.
   */
  readonly sortedJalons = computed(() => {
    // dir is 1 or -1 and is multiplied into the result at the end. One multiplication
    // instead of writing the comparison twice, once per direction.
    const col = this.jalonSortCol(), dir = this.jalonSortDir() === 'asc' ? 1 : -1;
    return [...this.jalons()].sort((a, b) => {
      const av = a[col], bv = b[col];
      // datePrevue may be missing. "== null" with two equals signs is deliberate: it
      // catches both null and undefined in one test.
      // Missing values are pushed to the BOTTOM whatever the direction (return 1 / -1
      // before the dir multiplication). Why: a milestone with no planned date is not
      // "the earliest one", and letting it sort as an empty string would park it at the
      // top of the calendar, above the milestone due next week.
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      // Two kinds of column share this one comparison. Text is compared with
      // localeCompare, which knows the alphabet of the user's language, so "Étape" lands
      // next to "Etape" instead of after "Z". Numbers are subtracted, because comparing
      // them as text would put 100 before 20.
      // The "as" casts only reassure TypeScript: it cannot prove that a and b hold the
      // same kind of value under the same column name, but they do, since both come from
      // the same list.
      return (typeof av === 'string' ? av.localeCompare(bv as string) : (av as number) - (bv as number)) * dir;
    });
  });

  // Same pair for the amendments table, defaulting to the signature date, oldest first, so
  // the list reads like the history of the contract.
  avenantSortCol = signal<AvenantSortCol>('dateAvenant');
  avenantSortDir = signal<SortDir>('asc');
  /**
   * The amendment rows in display order. Same shape and same reasons as sortedJalons()
   * above: a copy before sorting, missing values last, text compared with localeCompare
   * and numbers subtracted.
   *
   * Why it is written twice instead of one shared helper: the two column unions are
   * different types (JalonSortCol and AvenantSortCol). A single generic helper would have
   * to accept any key name, and the compiler would stop catching a column name that does
   * not exist on the row - the very mistake these unions are here to prevent.
   */
  readonly sortedAvenants = computed(() => {
    const col = this.avenantSortCol(), dir = this.avenantSortDir() === 'asc' ? 1 : -1;
    return [...this.avenants()].sort((a, b) => {
      const av = a[col], bv = b[col];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      return (typeof av === 'string' ? av.localeCompare(bv as string) : (av as number) - (bv as number)) * dir;
    });
  });

  // Which of the four windows is open. One signal for all of them, so two can never be
  // open at the same time (see the type at the top of the file).
  modal = signal<BillingModal>(null);
  // True while a save request is travelling. It greys out the save button and shows the
  // spinner; without it a second click would send the same creation twice.
  saving = signal(false);
  // The error text shown inside the open window - either a local check, or the message
  // sent back by the server. It is emptied every time a window opens, otherwise the user
  // would reopen the window and find yesterday's error already written in it.
  modalError = signal('');

  // The milestone the invoice window and the payment window are working on. It is needed
  // because those two windows act on one row, while the modal() signal only says which
  // window is open.
  currentJalon = signal<JalonFacturation | null>(null);
  // The payments already received against currentJalon(), loaded when the payment window
  // opens - never for the whole table, because that would be one extra request per row.
  paiements = signal<Paiement[]>([]);

  /*
   * The four forms. They are plain objects, not signals: [(ngModel)] writes straight into
   * their fields, and nothing in the template has to be redrawn when a letter is typed, so
   * a signal would cost more than it gives.
   */
  jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
  // The type is written out here only because of workloadDays?: without it, TypeScript
  // would read the initial value (which has no workloadDays) and then refuse the line in
  // openModal() that sets it to undefined.
  avenantForm: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string } = { numero: '', objet: '', montant: 0, dateAvenant: '' };
  // Today's date as the ISO text "2026-07-14", which is exactly what <input type="date">
  // expects. toISOString() gives "2026-07-14T09:12:33.000Z" and split('T')[0] keeps the
  // day part. Pre-filling it saves the user a click, since an invoice is almost always
  // recorded on the day it is issued.
  // Careful: toISOString() gives the date in UTC, not in the user's own time zone. Tunisia
  // is one hour ahead of UTC, so just after midnight local time UTC is still on the day
  // before and the field is pre-filled one day late. The user can correct it in the field,
  // and the date he validates is the one that is sent.
  facturerDate = new Date().toISOString().split('T')[0];
  paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };

  /**
   * First loading of the screen: fetch the projects, then follow the "?p=" of the URL.
   *
   * The two subscriptions are nested on purpose, and the order matters. The URL only
   * carries an id; turning that id back into a project needs the list. Watching the URL
   * first would mean receiving "p=12" while the list is still empty, finding nothing, and
   * leaving the user on the picker although his link named a project.
   *
   * Watching the URL instead of only reacting to clicks is what makes the screen
   * bookmarkable: a link to a project opens straight on its billing plan, and the browser
   * Back button really goes back to the previous project.
   */
  ngOnInit(): void {
    // subscribe() is what actually sends the HTTP request: an Observable does nothing
    // until someone listens to it.
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      // queryParamMap emits once now, and again on every later change of the URL - which
      // includes the navigations done by select() and clearSelection() below.
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        // No project in the URL: this is the picker page. Emptying selected() here is what
        // makes the "back" button of the breadcrumb work, since it only changes the URL.
        if (!pid) { this.selected.set(null); return; }
        // The URL always gives text, so the numeric id is turned into text for the
        // comparison. With "==" instead, TypeScript would refuse the line; comparing
        // p.id directly to pid would never match and no project would ever be found.
        const project = list.find(p => String(p.id) === pid);
        // The id test avoids reloading a project that is already displayed. Without it,
        // select() would set the project and reload, then this callback would fire on the
        // URL change and reload the very same two lists a second time.
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.reload();
        }
      });
    });
  }

  /**
   * Called when the user picks a project in the shared picker.
   *
   * It does three things in this order: show the project at once, write it into the URL,
   * and load its data. The URL is updated so the page can be bookmarked and shared, and
   * replaceUrl: false keeps the previous project in the browser history, so Back returns
   * to it instead of leaving the screen altogether.
   */
  select(p: Project): void {
    this.selected.set(p);
    // navigate([]) means "same route, only change the query parameters"; the path is left
    // untouched.
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.reload();
  }

  /**
   * Goes back to the project picker.
   *
   * It only clears the URL and does not touch selected(): the queryParamMap watcher in
   * ngOnInit sees the change and empties it. Doing it in both places would be two ways of
   * reaching the same state, and they would drift apart the day one of them changes.
   */
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  /**
   * Reloads the two lists of the current project.
   *
   * It is called after every successful write instead of adding the new row to the array
   * by hand. Why: the server computes the milestone amount from the percentage and the
   * budget, and it may also move a status by itself (to PAYE once the payments cover the
   * amount). A row built in the browser would show a figure the server never agreed to.
   * The cost is one extra round trip, which is nothing next to a wrong amount on an
   * invoice.
   *
   * The two requests are sent side by side and each fills its own signal; they do not wait
   * for each other, so the slower one does not delay the faster table.
   */
  reload(): void {
    const p = this.selected();
    // Silent guard: reload() is called from many places, and one of them could run just
    // after the user went back to the picker. Without this line, p.id would crash.
    if (!p) return;
    this.billingSvc.listJalons(p.id).subscribe(d => this.jalons.set(d));
    this.billingSvc.listAvenants(p.id).subscribe(d => this.avenants.set(d));
  }

  /**
   * Adds up the amounts of the milestones and gives back the total shown in the card
   * header.
   *
   * reduce() walks the list with a running total: s is the total so far, j the row being
   * added, and 0 is the starting value. That 0 is what makes an empty list answer 0
   * instead of crashing.
   *
   * Read the figure carefully before defending it: this adds up EVERY milestone, whatever
   * its status, so it is the total value of the billing plan - not only the part already
   * invoiced.
   */
  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  /**
   * Handles a click on a milestone column header.
   *
   * Clicking the column that is already sorted flips the direction; clicking another
   * column moves the sort to it and starts again at "ascending".
   *
   * Why restart at ascending on a new column rather than keeping the current direction:
   * the user has no idea which way the previous column was going, and inheriting
   * "descending" would silently show him the LAST milestones when he asked for the label
   * order. Nothing is sorted here - only these two signals change, and sortedJalons()
   * recomputes by itself.
   */
  toggleJalonSort(col: JalonSortCol): void {
    if (this.jalonSortCol() === col) this.jalonSortDir.set(this.jalonSortDir() === 'asc' ? 'desc' : 'asc');
    else { this.jalonSortCol.set(col); this.jalonSortDir.set('asc'); }
  }
  /**
   * The value of the aria-sort attribute for one milestone column: "ascending",
   * "descending", or "none" for the columns that are not the sorted one.
   *
   * It is what a screen reader announces. Without it, a blind user would hear the column
   * title, activate it, and have no clue that the table was reordered, nor in which way.
   * The three words are imposed by the HTML standard, which is why the return type lists
   * exactly those three and a typo cannot compile.
   */
  ariaJalonSort(col: JalonSortCol): 'ascending' | 'descending' | 'none' {
    if (this.jalonSortCol() !== col) return 'none';
    return this.jalonSortDir() === 'asc' ? 'ascending' : 'descending';
  }
  /**
   * The name of the Bootstrap icon class to draw in one milestone header: a double arrow
   * on a column that could be sorted, an arrow up or down on the one that is.
   *
   * Every header keeps an icon, even the inactive ones (invisible until hover, see the
   * styles block). This is what tells the user the column is clickable at all; with an
   * icon only on the sorted column, nobody would guess the others can be sorted too.
   */
  caretJalon(col: JalonSortCol): string {
    if (this.jalonSortCol() !== col) return 'bi-chevron-expand';
    return this.jalonSortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  // The same three helpers for the amendments table. They are separate because the column
  // type is different (AvenantSortCol) and the two tables must keep their own sort: moving
  // the milestone table must not reorder the amendments underneath.
  toggleAvenantSort(col: AvenantSortCol): void {
    if (this.avenantSortCol() === col) this.avenantSortDir.set(this.avenantSortDir() === 'asc' ? 'desc' : 'asc');
    else { this.avenantSortCol.set(col); this.avenantSortDir.set('asc'); }
  }
  ariaAvenantSort(col: AvenantSortCol): 'ascending' | 'descending' | 'none' {
    if (this.avenantSortCol() !== col) return 'none';
    return this.avenantSortDir() === 'asc' ? 'ascending' : 'descending';
  }
  caretAvenant(col: AvenantSortCol): string {
    if (this.avenantSortCol() !== col) return 'bi-chevron-expand';
    return this.avenantSortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  /**
   * Opens the "new milestone" or "new amendment" window.
   *
   * It empties BOTH forms and the error message before showing anything. Why both, when
   * only one window is about to open: it costs nothing and it removes a whole family of
   * bugs. Without this reset, a user who types an amendment, cancels, and opens the window
   * again finds his old text still there and may save it without noticing. The amendment
   * date is pre-filled with today, since an amendment is normally recorded the day it is
   * signed.
   *
   * Note that the two windows of the milestone workflow are NOT opened here: openFacturer()
   * and openPaiement() need the row that was clicked.
   */
  openModal(type: BillingModal): void {
    this.jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
    this.avenantForm = { numero: '', objet: '', montant: 0, workloadDays: undefined, dateAvenant: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    // Set last, so the window is only drawn once the fields behind it are already clean.
    this.modal.set(type);
  }

  /**
   * Creates one milestone from the window.
   *
   * The local check is only about comfort: it answers instantly and avoids a useless round
   * trip. The real rules - the percentages of a project must not exceed 100, the label
   * must be unique - live on the server, which is the only place that sees all the other
   * milestones. Trusting this "if" alone would let anyone create an illegal milestone with
   * a hand-made HTTP call.
   *
   * It returns nothing: the answer arrives later, in the callbacks below.
   */
  saveJalon(): void {
    // "!this.jalonForm.pourcentage" also rejects 0, which is what is wanted: a milestone
    // worth nothing is not a milestone. The message is read from Transloco so it is shown
    // in the user's own language, French or English.
    if (!this.jalonForm.label || !this.jalonForm.pourcentage) {
      this.modalError.set(this.tr.translate('billing.errLabelPct'));
      return;
    }
    this.saving.set(true);
    // Clear the previous error before asking again, otherwise an old message would stay on
    // screen while the new request travels and the user would think it just failed again.
    this.modalError.set('');
    const body = {
      label: this.jalonForm.label,
      pourcentage: this.jalonForm.pourcentage,
      // "|| undefined" turns the empty field into a missing field. A date input that was
      // never filled holds "", and an empty string is not a date: the server would answer
      // 400. Sending nothing at all is what lets it store "no planned date yet".
      datePrevue: this.jalonForm.datePrevue || undefined
    };
    // The "!" says "selected() is certainly not null here". It is true because this method
    // can only be reached from a button that lives inside the @if (selected()) block.
    this.billingSvc.createJalon(this.selected()!.id, body).subscribe({
      // On success: reload from the server (the amount is computed there), close the
      // window, release the button, and confirm with a toast. The reload is what brings
      // back the real amount, which this screen never computes itself.
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okMilestoneAdded')); },
      // On failure: the window stays OPEN with the message from the server, so the user
      // does not lose what he typed. "?." then "??" means: take the server message if
      // there is one, otherwise a default - a network failure has no body at all, and
      // without the fallback the box would appear empty.
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /**
   * Creates one amendment.
   *
   * Same shape as saveJalon(). Two fields only are required here: the reference written on
   * the signed paper and the signature date - they are what makes the row an amendment and
   * not a wish. The amount is deliberately NOT required, because an amendment can be worth
   * zero money and only add days or change the scope.
   *
   * The form object is sent as it stands, with no rebuilding, because its fields already
   * match what the service expects exactly.
   */
  saveAvenant(): void {
    if (!this.avenantForm.numero || !this.avenantForm.dateAvenant) {
      this.modalError.set(this.tr.translate('billing.errNumberDate'));
      return;
    }
    this.saving.set(true);
    this.modalError.set('');
    this.billingSvc.createAvenant(this.selected()!.id, this.avenantForm).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okAmendmentAdded')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /**
   * Deletes one milestone, after asking the user to confirm.
   *
   * "async" and "await" are used only to read the answer of the confirmation window as if
   * it were a normal line. ConfirmService.ask() hands back a promise that settles when the
   * user clicks; without the await, the delete would be sent immediately and the question
   * would be pure decoration.
   *
   * The name of the milestone is placed inside the question ({ name: ... } fills the
   * placeholder of the translated sentence), so the user reads WHICH row he is about to
   * remove. A generic "Are you sure?" is exactly how the wrong line gets deleted.
   *
   * The server only marks the row as deleted, and it refuses to delete a milestone that is
   * already invoiced or paid: billing data is accounting history that an audit may need.
   */
  async deleteJalon(j: JalonFacturation): Promise<void> {
    // Nothing happens at all if the user cancels - the method simply stops here.
    if (!await this.confirm.ask(
      this.tr.translate('billing.confirmDeleteMilestone', { name: j.label }),
      this.tr.translate('billing.deleteMilestone'))) return;
    this.billingSvc.deleteJalon(this.selected()!.id, j.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('billing.okMilestoneDeleted')); },
      // No window is open here, so the failure is shown as a toast. Without this branch a
      // refused delete would be completely silent and the user would think it worked until
      // he noticed the row was still there.
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Invoicing a milestone (PREVU -> FACTURE) ─────────────────────

  /**
   * Opens the invoice window for the milestone that was clicked.
   *
   * It remembers the row in currentJalon(), because the window itself only knows that it
   * is open. The date is reset to today at every opening, otherwise the date chosen for
   * the previous milestone would still be sitting in the field and would be saved without
   * anyone looking at it - an invoice dated on the wrong day.
   */
  openFacturer(j: JalonFacturation): void {
    this.currentJalon.set(j);
    this.facturerDate = new Date().toISOString().split('T')[0];
    this.modalError.set('');
    this.modal.set('facturer');
  }

  /**
   * Asks the server to mark the milestone as invoiced.
   *
   * Only the date is sent. The status change is the server's decision, which is also where
   * the order PREVU -> FACTURE -> PAYE is enforced; a browser able to choose the status
   * could mark a milestone paid with no invoice ever issued.
   */
  saveFacturer(): void {
    // The invoice date is required: without it a milestone would sit in status FACTURE
    // with no date, and nobody could match it with the paper invoice during an audit.
    if (!this.facturerDate) { this.modalError.set('Date de facture requise.'); return; }
    this.saving.set(true);
    // Two "!" here: the project (the button lives inside @if (selected())) and the
    // milestone (openFacturer set it just before this window was drawn).
    this.billingSvc.facturer(this.selected()!.id, this.currentJalon()!.id, this.facturerDate).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okMilestoneInvoiced')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Recording money received ─────────────────────────────────────

  /**
   * Opens the payment window for one milestone and fetches the payments already recorded
   * against it.
   *
   * Those past payments are loaded only now, and not with the table, because that would be
   * one extra request per row for information almost nobody looks at. They matter here:
   * a client may pay in several instalments, and without the list the user could not tell
   * what is still owed and might enter the same instalment twice.
   *
   * The window is opened on the last line, before the answer arrives. The list simply
   * appears when it does, instead of the user waiting in front of a frozen screen.
   */
  openPaiement(j: JalonFacturation): void {
    this.currentJalon.set(j);
    // Fresh form at every opening, so an amount typed for another milestone can never be
    // saved against this one.
    this.paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };
    this.modalError.set('');
    this.billingSvc.listPaiements(this.selected()!.id, j.id).subscribe(d => this.paiements.set(d));
    this.modal.set('paiement');
  }

  /**
   * Records one payment received.
   *
   * The screen does not decide whether the milestone becomes PAYE: the server adds up the
   * payments and moves the status once they cover the amount. That is why the answer is
   * followed by a full reload() instead of changing the badge here - a status computed in
   * the browser would disagree with the database the moment a second user paid at the same
   * time.
   */
  savePaiement(): void {
    // A payment of zero, or a negative one, would silently corrupt the total the server
    // compares against the invoice - and a negative amount would even move a paid
    // milestone back into debt. The first test also covers the empty field, which reads as
    // 0 here.
    if (!this.paiementForm.montantRecu || this.paiementForm.montantRecu <= 0) {
      this.modalError.set(this.tr.translate('billing.errAmountPositive'));
      return;
    }
    // The date is what lets an accountant place the payment in the right month; a payment
    // with no date cannot be reconciled with a bank statement.
    if (!this.paiementForm.datePaiement) { this.modalError.set('Date du paiement requise.'); return; }
    this.saving.set(true);
    this.billingSvc.createPaiement(this.selected()!.id, this.currentJalon()!.id, this.paiementForm).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okPaymentSaved')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /**
   * Deletes one amendment, after the same confirmation as deleteJalon().
   *
   * The reference of the amendment is put inside the question, because two amendments of
   * the same project often look alike in the table and only the number tells them apart.
   * Here too the server only marks the row deleted: an amendment is a signed document, and
   * the revised budget of the project must stay explainable line by line afterwards.
   */
  async deleteAvenant(a: Avenant): Promise<void> {
    if (!await this.confirm.ask(
      this.tr.translate('billing.confirmDeleteAmendment', { name: a.numero }),
      this.tr.translate('billing.deleteAmendment'))) return;
    this.billingSvc.deleteAvenant(this.selected()!.id, a.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('billing.okAmendmentDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
