import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { ProjectService } from '../../../core/services/project.service';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsListStateService } from '../projects-list-state.service';
import { ProjectRequest, ProjectStatus } from '../../../core/models/project.model';
import { HasUnsavedChanges } from '../../../core/guards/unsaved-changes.guard';

// =============================================================================
// FILE: project-form.component.ts   ("the project identity sheet, create + edit")
// =============================================================================
// WHAT THIS FILE IS
//   One single screen used for two jobs: creating a new project, and editing an
//   existing one. It is the Angular copy of the "fiche d'identification" tab of
//   the company Excel file: code, name, client, dates, budget, currency, sold
//   workload. It is one standalone component: the HTML lives in `template` and
//   the few extra CSS rules live in `styles`, both inside this same file.
//
// WHERE IT SITS IN THE FLOW
//   WHO CALLS IT   app.routes.ts, on two addresses:
//                    /projects/new        -> no :id in the URL -> create mode
//                    /projects/:id/edit   -> an :id in the URL -> edit mode
//                  The same routes carry the permission guard and, on the edit
//                  route, unsavedChangesGuard (see core/guards/).
//   WHAT IT CALLS  ProjectService  -> GET /api/projects/{id}, POST /api/projects,
//                                     PUT /api/projects/{id}, GET /api/users/assignable
//                  AuthService     -> hasPermission(), to show or hide the
//                                     "project manager" dropdown
//                  ToastService    -> the small success message after a save
//                  TranslocoService-> French / English texts
//                  ProjectsListStateService -> the filters of the projects list,
//                                     reused in the "back to the list" link
//   AFTER A SAVE   it navigates to /projects/{id} (the detail page).
//
//   The screen never decides who is allowed to do anything. The real check is on
//   the SERVER, on the service methods of ProjectService (Java), with
//   @PreAuthorize("hasAuthority('...')"). What this file does with
//   auth.hasPermission() is only hiding a control the user cannot use, so the
//   screen is not confusing. A user who forges the HTTP call still gets a 403.
//
// WHY IT EXISTS (what breaks without it)
//   Without this screen there is no way to create a project at all, so every
//   other module of the application (DI, billing, KPI, agile board) has nothing
//   to hang on to. The whole application starts here.
//
// WHY ONE COMPONENT FOR CREATE AND EDIT, AND NOT TWO
//   The two screens would be 95 % the same HTML: the same 20 fields, the same
//   validation, the same computed boxes. Two files would mean every new field of
//   the identity sheet has to be added twice, and one day somebody adds it only
//   once. The differences are small and are all driven by the single flag
//   `isEdit`: the code field is locked, the draft auto-save is switched off, the
//   revised-budget notice appears, and the button says "Save" instead of
//   "Create".
//
// WHY TEMPLATE-DRIVEN FORMS ([(ngModel)]) AND NOT REACTIVE FORMS
//   The form is one flat object (`req`) with no dynamic field list and almost no
//   cross-field rules. [(ngModel)] binds each input straight onto that object, so
//   the object that is typed in is exactly the object that is sent to the server.
//   A reactive FormGroup would mean writing the same 20 field names a second time
//   in TypeScript and then copying values back and forth.
// =============================================================================

@Component({
  // The HTML tag name of this component, <app-project-form>. It is never written
  // by hand here: the router creates the component from app.routes.ts. The name
  // still matters for error messages and for the Angular dev tools.
  selector: 'app-project-form',
  // standalone: true means this component declares its own dependencies below and
  // does not belong to any NgModule. Why: the whole application is built this way,
  // so a lazily loaded route can pull in this one file and nothing else.
  // Without it, the component would have to be declared in an NgModule and that
  // module imported everywhere it is used.
  standalone: true,
  // Loads the 'project' translation file (assets .../project/fr.json and en.json)
  // ONLY while this screen is alive, instead of putting all the texts of the
  // application in one huge global file.
  // Why it is in `providers` and not in `imports`: a Transloco scope is a value
  // injected into this component's own injector, not a class with a template.
  // Concrete effect: every key used below is written short, for example
  // 'project.form.code' resolves inside the project scope. Without this line all
  // those keys would be missing and the screen would print the raw keys.
  providers: [provideTranslocoScope('project')],
  // A standalone component must list every directive and pipe its template uses.
  //   CommonModule    -> the `number` pipe used for the budget boxes.
  //   FormsModule     -> ngModel, ngForm, ngValue. Without it [(ngModel)] is an
  //                      unknown attribute and the form binds to nothing.
  //   RouterLink      -> the [routerLink] of the breadcrumb links.
  //   TranslocoModule -> the `transloco` pipe used on every label.
  // Note: @if / @for need nothing here, they are built into the Angular compiler.
  imports: [CommonModule, FormsModule, RouterLink, TranslocoModule],
  // Component styles. Angular scopes them to this component only, so .field-ro
  // here cannot leak into another screen.
  // Inside this block only /* */ comments are legal: it is CSS, not TypeScript.
  styles: [`
    /* Read-only / computed fields. They use the design-system colour tokens
       (--surface-2, --text-2) instead of Bootstrap's .bg-light.
       Why: .bg-light is a fixed near-white. In dark mode it painted a white box
       with almost white text inside, which was unreadable. A token changes value
       with the theme, so the same rule works in both themes. */
    .field-ro { background: var(--surface-2); color: var(--text-2); }
    .field-ro.fw-semibold { color: var(--text-1); }

    /* Sticky action bar: the Save / Cancel row stays glued to the bottom of the
       window while the page scrolls (position: sticky + bottom: 0).
       Why: this form is four cards long. Without it the user has to scroll all
       the way down every time to find the Save button, and the "unsaved changes"
       dot at the right end of the bar would be off screen exactly when it matters.
       z-index: 10 keeps the bar drawn on top of the form fields sliding under it. */
    .form-actionbar { position: sticky; bottom: 0; z-index: 10; margin-top: 1.5rem;
      display: flex; align-items: center; gap: .5rem;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg);
      box-shadow: var(--sh-md); padding: .75rem 1rem; }
    .form-actionbar .fa-spacer { margin-left: auto; font-size: 12px; color: var(--text-3); }
  `],
  // The HTML of the screen, written inline instead of in a separate .html file.
  // Everything below is HTML, so ONLY <!-- --> comments are allowed inside it.
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <!-- Back to the projects list.
             [queryParams]="listState.query()" replays the search, filter, sort and
             page number the user had on the list (ProjectsListStateService keeps
             them in a signal). Why: without it the link would be a bare /projects
             and a user who had searched "BAD", filtered "Active" and gone to page 3
             would land back on page 1 of the full list and lose his search. -->
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <i class="bi bi-arrow-left"></i> {{ 'projects.title' | transloco }}
        </a>
        <span class="bc-sep">›</span>
        <!-- In edit mode the breadcrumb gets a middle step linking to the project
             detail page. req.code is tested too, because the code only exists after
             the GET has come back; without that test the breadcrumb would flash an
             empty link for a moment. -->
        @if (isEdit && req.code) {
          <a [routerLink]="['/projects', projectId]" style="color:var(--text-2);text-decoration:none;font-size:12px">{{ req.code }}</a>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ 'project.form.edit' | transloco }}</span>
        } @else {
          <span class="bc-curr">{{ (isEdit ? 'project.form.edit' : 'project.form.new') | transloco }}</span>
        }
      </div>
    </div>

    <div class="page-body">

      <!-- ── Draft banner ────────────────────────────────────────────
           Shown only in create mode, and only when a half-filled form was found
           in the browser's localStorage. The user chooses: put it back, or throw
           it away. Why it is a banner and not an automatic restore: silently
           refilling a form with data from three days ago would make the user save
           values he never checked. -->
      @if (showDraftBanner()) {
        <div class="alert alert-info d-flex align-items-center gap-3 mb-4">
          <i class="bi bi-floppy fs-5 flex-shrink-0"></i>
          <div class="flex-grow-1">
            <strong>{{ 'project.form.draftFound' | transloco }}</strong> — {{ 'project.form.draftFoundDesc' | transloco }}
          </div>
          <button type="button" class="btn btn-sm btn-primary" (click)="restoreDraft()">{{ 'project.form.restore' | transloco }}</button>
          <button type="button" class="btn btn-sm btn-outline-secondary" (click)="discardDraft()">{{ 'project.form.discard' | transloco }}</button>
        </div>
      }

      <!-- ── Loading spinner (edit mode only) ────────────────────────
           'loaded' is a signal. In create mode it is set to true at once, so this
           branch is skipped and the empty form appears immediately. In edit mode
           it only turns true when the GET /api/projects/{id} answers.
           Why hide the form while loading: the inputs are bound with [(ngModel)]
           onto 'req'. If the form were drawn first, the user could start typing in
           an empty field and the answer arriving one second later would wipe what
           he just typed. -->
      @if (!loaded()) {
        <div class="text-center py-5">
          <div class="spinner-border text-primary mb-2"></div>
          <p class="text-muted small mb-0">{{ 'project.form.loading' | transloco }}</p>
        </div>
      } @else {

      <!-- (ngSubmit) fires on the submit button AND on the Enter key, which a plain
           (click) on the button would miss.
           #f="ngForm" gives the template a handle on the form object that
           FormsModule builds from the ngModel fields; it is read further down as
           f.valid to grey out the Save button while a required field is empty.
           (input) catches typing, (change) catches the things typing does not:
           picking a value in a <select>, or choosing a day in a date picker. Both
           call onFormChange(), which raises the "unsaved changes" flag and starts
           the draft auto-save. Without the (change) half, changing only the status
           dropdown would leave the form looking clean and the exit guard would let
           the change be lost with no warning. -->
      <form (ngSubmit)="submit()" #f="ngForm"
            (input)="onFormChange()" (change)="onFormChange()">

        <!-- ══ SECTION 1: IDENTIFICATION ═══════════════════════════════
             Who the project is: its code, its name, its client, its funder, and
             the contract shape (alone or in a group, fixed price or time and
             means). These are the first lines of the Excel identity sheet. -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-card-heading me-2 text-primary"></i>{{ 'project.form.sectionIdentification' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">{{ 'project.form.code' | transloco }} *</label>
                <!-- The project code, the short business key everybody uses (EX-2024-001).
                     name="code" is required by FormsModule: without a name attribute
                     ngModel throws and the field is not registered in the form.
                     [(ngModel)] is two-way binding: typing writes into req.code, and
                     writing req.code in TypeScript refills the box.
                     'required' and maxlength="20" are checked in the browser, and
                     again on the server by the bean validation on ProjectRequest.
                     The browser check is only there to answer fast; the server one is
                     the one that protects the data.
                     [attr.readonly]="isEdit ? '' : null" adds the readonly attribute
                     in edit mode and REMOVES it otherwise. It must be written with
                     attr. and with null: readonly is a plain HTML attribute, and the
                     browser treats readonly="false" as still read-only, so a plain
                     [readonly]="isEdit" style binding on an attribute would lock the
                     field even when creating a project. null is what tells Angular to
                     drop the attribute completely.
                     Why lock it at all: the code is printed on the DI, on invoices and
                     on reports. Letting somebody rename EX-2024-001 into EX-2024-002
                     after six months of billing would break every paper trail. -->
                <input class="form-control text-uppercase" name="code" [(ngModel)]="req.code"
                       required maxlength="20" placeholder="EX-2024-001"
                       [attr.readonly]="isEdit ? '' : null"
                       [class.field-ro]="isEdit">
                @if (isEdit) {
                  <div class="form-text text-muted">{{ 'project.form.codeLocked' | transloco }}</div>
                }
              </div>

              <div class="col-md-9">
                <label class="form-label">{{ 'project.form.name' | transloco }} *</label>
                <input class="form-control" name="name" [(ngModel)]="req.name"
                       required maxlength="255" [placeholder]="'project.form.namePh' | transloco">
              </div>

              <div class="col-12">
                <label class="form-label">{{ 'common.description' | transloco }}</label>
                <textarea class="form-control" name="description" [(ngModel)]="req.description"
                          rows="3" [placeholder]="'project.form.descriptionPh' | transloco"></textarea>
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.contractRef' | transloco }}</label>
                <input class="form-control" name="contractId" [(ngModel)]="req.contractId"
                       [placeholder]="'project.form.contractRefPh' | transloco">
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.info.client' | transloco }}</label>
                <input class="form-control" name="client" [(ngModel)]="req.client">
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.funder' | transloco }}</label>
                <input class="form-control" name="funder" [(ngModel)]="req.funder"
                       [placeholder]="'project.form.funderPh' | transloco">
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.businessModel' | transloco }}</label>
                <!-- SEUL = the company delivers alone, GROUPEMENT = with partners.
                     The option values are the raw enum names, never the translated
                     words: they travel to the Java enum BusinessModel as they are.
                     Translating the value instead of the label would send "Alone" to
                     a server that only knows SEUL, and the save would fail with a 400. -->
                <select class="form-select" name="businessModel" [(ngModel)]="req.businessModel">
                  <!-- [ngValue]="null" (not value="null") binds the real null, not the
                       four-letter text "null". It is the "not decided yet" choice.
                       ngOnInit also normalises the loaded value with '?? null', so the
                       object identity matches and the dropdown reopens on the dash.
                       With value="null" the field would be saved as the string "null"
                       and the server would refuse an unknown enum value. -->
                  <option [ngValue]="null">—</option>
                  <option value="SEUL">{{ 'labels.businessModel.SEUL' | transloco }}</option>
                  <option value="GROUPEMENT">{{ 'labels.businessModel.GROUPEMENT' | transloco }}</option>
                </select>
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.engagementType' | transloco }}</label>
                <!-- FORFAIT = fixed price agreed once, REGIE = billed on time spent.
                     Same rule as above: the enum names go over the wire, the labels
                     are only what the user reads. -->
                <select class="form-select" name="engagementType" [(ngModel)]="req.engagementType">
                  <option [ngValue]="null">—</option>
                  <option value="FORFAIT">{{ 'labels.engagement.FORFAIT' | transloco }}</option>
                  <option value="REGIE">{{ 'labels.engagement.REGIE' | transloco }}</option>
                </select>
              </div>

              <!-- The "who runs this project" dropdown is only DRAWN for a user who
                   holds the ASSIGN_CHEF_PROJET permission.
                   Authorization here is permission-based and dynamic: the code asks
                   for a permission, never for a role name, so an administrator can
                   move that permission from one role to another without any code
                   change.
                   This is a display rule, not a security rule. The real check lives on
                   the server, on the service method, with
                   @PreAuthorize("hasAuthority('ASSIGN_CHEF_PROJET')"). Someone who
                   sends the chefProjetId by hand with curl is refused there.
                   Without this conditional block, a team member would see a dropdown that always
                   fails with a 403 when he saves. -->
              @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                <div class="col-md-4">
                  <label class="form-label">{{ 'project.info.manager' | transloco }}</label>
                  <select class="form-select" name="chefProjetId" [(ngModel)]="req.chefProjetId">
                    <!-- "not assigned yet" is a real business state, so it is a real
                         null and not a missing line in the list. -->
                    <option [ngValue]="null">{{ 'project.form.unassigned' | transloco }}</option>
                    <!-- chefs() reads the signal filled by listAssignableUsers().
                         track u.id tells Angular how to recognise a row between two
                         redraws, so it moves the existing <option> nodes instead of
                         destroying and rebuilding them. Without a stable track key the
                         selected option can be rebuilt under the user and the
                         selection is lost while the list refreshes. -->
                    @for (u of chefs(); track u.id) {
                      <option [ngValue]="u.id">{{ u.firstName }} {{ u.lastName }}</option>
                    }
                  </select>
                </div>
              }

            </div>
          </div>
        </div>

        <!-- ══ SECTION 2: PLANNING ═════════════════════════════════════
             Status of the project and the two contract dates. The contract length
             below is not typed: it is computed from the two dates. -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-calendar3 me-2 text-primary"></i>{{ 'project.form.sectionPlanning' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-4">
                <label class="form-label">{{ 'common.status' | transloco }} *</label>
                <!-- The five values are exactly the members of the ProjectStatus type
                     in project.model.ts and of the Java ProjectStatus enum. They are
                     hard-coded here rather than fetched: they are part of the business
                     rules, not data, and a sixth status would need code changes
                     everywhere anyway. Adding a value here that the Java enum does not
                     know would make every save fail with a 400. -->
                <select class="form-select" name="status" [(ngModel)]="req.status" required>
                  <option value="DRAFT">{{ 'status.DRAFT' | transloco }}</option>
                  <option value="ACTIVE">{{ 'status.ACTIVE' | transloco }}</option>
                  <option value="ON_HOLD">{{ 'status.ON_HOLD' | transloco }}</option>
                  <option value="COMPLETED">{{ 'status.COMPLETED' | transloco }}</option>
                  <option value="CANCELLED">{{ 'status.CANCELLED' | transloco }}</option>
                </select>
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.startDate' | transloco }}</label>
                <input type="date" class="form-control" name="startDate"
                       [(ngModel)]="req.startDate">
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.endDate' | transloco }}</label>
                <!-- type="date" gives the value as "YYYY-MM-DD", which is exactly the
                     shape Java's LocalDate reads on the server. No parsing, and no
                     05/03 that could mean March 5th or May 3rd.
                     [class.is-invalid] paints the red Bootstrap border as soon as the
                     end date is before the start date; dateRangeInvalid is a getter on
                     the class, so it is re-evaluated on every change detection pass.
                     Without this feedback the user would only learn about the mistake
                     when the disabled Save button refuses to do anything, with no
                     explanation of which field is wrong. -->
                <input type="date" class="form-control" name="endDate"
                       [(ngModel)]="req.endDate"
                       [class.is-invalid]="dateRangeInvalid">
                @if (dateRangeInvalid) {
                  <div class="invalid-feedback">
                    {{ 'project.form.dateOrder' | transloco }}
                  </div>
                }
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.contractDuration' | transloco }}</label>
                <div class="input-group">
                  <!-- Not an <input>: a plain <div> styled to look like one. The
                       contract length is DERIVED from the two dates, it is never
                       typed and never sent to the server (there is no durationDays
                       field in ProjectRequest). Drawing a real input would invite the
                       user to type a length that contradicts his own dates.
                       Same principle as the DI: a computed amount is recomputed when
                       it is read, never stored in its own column. -->
                  <div class="form-control field-ro"
                       [class.fw-semibold]="durationDays !== null">
                    {{ durationDays !== null ? durationDays : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">{{ 'project.form.days' | transloco }}</span>
                </div>
              </div>

            </div>
          </div>
        </div>

        <!-- ══ SECTION 3: FINANCIAL ════════════════════════════════════
             The money of the project. The user types the currency, the budget in
             that currency and the exchange rate; the converted budget and the PPR
             below are computed live so he can check the figures before saving. -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-cash-coin me-2 text-primary"></i>{{ 'project.form.sectionFinancial' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">{{ 'project.form.currency' | transloco }}</label>
                <!-- (ngModelChange) runs AFTER [(ngModel)] has written the new value
                     into req.currency. onCurrencyChange() forces the rate back to 1
                     when the user picks TND. Why here and not only in submit(): the
                     converted budget box below must show the right figure straight
                     away. Without it, a user who typed a rate of 3.2 for EUR and then
                     switched back to TND would keep seeing a budget multiplied by 3.2. -->
                <select class="form-select" name="currency" [(ngModel)]="req.currency"
                        (ngModelChange)="onCurrencyChange($event)">
                  <option value="TND">{{ 'project.form.currencyTnd' | transloco }}</option>
                  <option value="EUR">{{ 'project.form.currencyEur' | transloco }}</option>
                  <option value="USD">{{ 'project.form.currencyUsd' | transloco }}</option>
                  <option value="FCFA">{{ 'project.form.currencyFcfa' | transloco }}</option>
                </select>
              </div>

              <div class="col-md-5">
                <label class="form-label">
                  <!-- The transloco pipe takes a second argument: a bag of values that
                       fill the placeholders of the translation, here the placeholder
                       named "currency" inside the French and English texts. That is
                       how the label reads
                       "Initial budget (EUR)" and follows the dropdown above.
                       The "or TND" fallback covers the moment the field is still
                       undefined; without it the label would print "Initial budget ()". -->
                  {{ 'project.form.initialBudget' | transloco: { currency: (req.currency || 'TND') } }}
                </label>
                <input type="number" class="form-control" name="budget"
                       [(ngModel)]="req.initialBudget" min="0" step="0.01">
              </div>

              <div class="col-md-4">
                <label class="form-label">
                  {{ 'project.form.exchangeRate' | transloco }}
                  @if ((req.currency || 'TND') === 'TND') {
                    <span class="fw-normal text-muted">{{ 'project.form.notApplicable' | transloco }}</span>
                  }
                </label>
                <!-- The exchange rate towards TND. step="0.000001" allows six decimals:
                     FCFA is worth a tiny fraction of a dinar, and a rate rounded to
                     two decimals would move the converted budget of a large contract
                     by thousands of dinars.
                     The field is locked (and greyed with .field-ro) when the currency
                     is TND, because converting dinars into dinars is always 1. Same
                     [attr.readonly] with null trick as the code field above: a plain
                     readonly="false" would still lock the box for the other currencies. -->
                <input type="number" class="form-control" name="rate"
                       [(ngModel)]="req.exchangeRateToTnd" min="0" step="0.000001"
                       [attr.readonly]="(req.currency || 'TND') === 'TND' ? '' : null"
                       [class.field-ro]="(req.currency || 'TND') === 'TND'">
              </div>

              <!-- Converted budget: budget x rate, shown live in TND.
                   Read-only box again, because the figure is derived. The 'number'
                   pipe with '1.0-0' means: at least 1 digit before the decimal point,
                   and between 0 and 0 digits after it, so 1234567.89 reads 1 234 568.
                   Why no decimals here: this box is a sanity check on the order of
                   magnitude, not an accounting figure. The millimes would only make
                   a seven-digit number harder to read.
                   The '!== null' test keeps an empty budget showing a dash instead of
                   a 0, because "no budget agreed yet" and "a budget of zero" are two
                   different answers for a project manager. -->
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.convertedBudget' | transloco }}</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ budgetTnd !== null ? (budgetTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- PPR = "Provision Pour Risques", the money set aside for risk: 5 % of
                   the budget in TND, exactly as the Excel identity sheet computes it.
                   Preview only. The value that counts is recomputed on the server by
                   Project.getPprTnd(), so nothing here can be tampered with; this box
                   only lets the user check the figure before he saves.
                   Do not confuse it with "penalty provision" below, which is a number
                   typed by a user for late-delivery penalties. -->
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.pprLabel' | transloco }}</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ pprTnd !== null ? (pprTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- Revised budget: read-only NOTICE, never an input.
                   The revised budget is the budget after amendments ("avenants"). It
                   is written only by the billing module, which recomputes it from the
                   signed amendments. This form therefore shows it and never sends it:
                   there is no revisedBudget field in ProjectRequest at all.
                   Why: if this screen could overwrite it, a manual edit would silently
                   contradict the sum of the amendments, and the KPI module, which
                   values the project on the revised budget, would report a margin that
                   matches no document.
                   The '!== null' test hides the notice on a project that has never had
                   an amendment, where there is nothing to explain. -->
              @if (isEdit && revisedBudget !== null) {
                <div class="col-12">
                  <div class="alert alert-secondary py-2 small d-flex align-items-center gap-2 mb-0">
                    <i class="bi bi-info-circle flex-shrink-0"></i>
                    <span>
                      {{ 'project.form.revisedBudget' | transloco: { currency: (req.currency || 'TND') } }}
                      <!-- '1.2-2' forces exactly two decimals. Unlike the preview boxes
                           above, this is a contractual amount read off the signed
                           amendments, so 120000.5 must read 120 000.50 and not
                           120 000.5, which looks like a truncated figure. -->
                      <strong>{{ revisedBudget | number:'1.2-2' }}</strong>
                      {{ 'project.form.revisedBudgetVia' | transloco }} <strong>{{ 'project.form.billingAmendments' | transloco }}</strong>.
                    </span>
                  </div>
                </div>
              }

              <!-- Other financial fields. Both are plain numbers typed by the user and
                   stored as they are: the licence + subcontracting budget, and the
                   provision kept for late-delivery penalties. min="0" blocks a
                   negative provision, which would quietly improve the margin. -->
              <div class="col-md-6">
                <label class="form-label">
                  {{ 'project.form.licenseBudget' | transloco }}
                </label>
                <input type="number" class="form-control" name="licBudget"
                       [(ngModel)]="req.licenseSubcontractBudget" min="0" step="0.01">
              </div>

              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.penaltyProvision' | transloco }}</label>
                <input type="number" class="form-control" name="ppp"
                       [(ngModel)]="req.penaltyProvision" min="0" step="0.01">
              </div>

            </div>
          </div>
        </div>

        <!-- ══ SECTION 4: SOLD WORKLOAD ════════════════════════════════
             How many man-days were sold, and how many are kept for the warranty
             period. step="0.5" because half a day is the smallest unit the company
             sells; without it the browser would refuse 12.5 as an invalid number
             and the Save button would stay disabled with no visible reason.
             They come straight from the Excel identity sheet. -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-people me-2 text-primary"></i>{{ 'project.form.sectionSoldWorkload' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.soldWorkloadDays' | transloco }}</label>
                <input type="number" class="form-control" name="soldWl"
                       [(ngModel)]="req.soldWorkloadDays" min="0" step="0.5">
              </div>
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.warrantyWorkloadDays' | transloco }}</label>
                <input type="number" class="form-control" name="warrWl"
                       [(ngModel)]="req.warrantyWorkloadDays" min="0" step="0.5">
              </div>
            </div>
          </div>
        </div>

        <!-- ── Error banner and action bar ─────────────────────────────
             error() is a signal holding the message from the failed save (the
             server's own explanation when it sent one). It is placed right above
             the buttons, where the user is looking after he clicked Save. -->
        @if (error()) {
          <div class="alert alert-danger py-2 small d-flex align-items-center gap-2">
            <i class="bi bi-exclamation-circle-fill"></i>{{ error() }}
          </div>
        }

        <div class="form-actionbar">
          <!-- The Save button is switched off in three cases:
               loading()        -> a request is already on its way. Without it, a
                                   double click would send two POSTs and create the
                                   same project twice.
               !f.valid         -> a required field (code, name, status) is empty or
                                   too long, according to FormsModule.
               dateRangeInvalid -> the end date is before the start date. This one is
                                   a rule of our own, so the standard form validity
                                   does not know about it and it has to be added here.
               submit() checks the date rule a second time, because a disabled button
               is only a convenience, not a guarantee. -->
          <button type="submit" class="btn btn-primary px-4"
                  [disabled]="loading() || !f.valid || dateRangeInvalid">
            @if (loading()) {
              <span class="spinner-border spinner-border-sm me-2"></span>
            }
            {{ (isEdit ? 'project.form.saveChanges' : 'project.form.createProject') | transloco }}
          </button>
          <!-- type="button" is essential inside a <form>: the HTML default for a
               button is type="submit", so without it Cancel would SAVE the project
               and then navigate away. -->
          <button type="button" class="btn btn-outline-secondary" (click)="back()">
            {{ 'common.cancel' | transloco }}
          </button>
          <!-- The little orange dot with "unsaved changes". It reads the very same
               isDirty signal that unsavedChangesGuard asks for when the user leaves
               the page, so what he sees and what the guard decides can never differ. -->
          @if (isDirty()) {
            <span class="fa-spacer"><i class="bi bi-record-fill me-1" style="font-size:8px;color:var(--c-warning)"></i>{{ 'project.form.unsavedChanges' | transloco }}</span>
          }
        </div>

      </form>
      } <!-- end @else loaded -->

    </div>
  `
})
/**
 * The class behind the template above: it holds the form state, computes the preview
 * figures, keeps the draft, and sends the create or the update.
 *
 * The three interfaces it implements:
 *  - OnInit  : ngOnInit() runs once, after Angular has set up the component. The work
 *              is done there and not in the constructor, so that the route parameter
 *              is already available and the HTTP call can be tested on its own.
 *  - OnDestroy: ngOnDestroy() cancels the pending auto-save timer when the screen is
 *              closed.
 *  - HasUnsavedChanges: the contract of unsavedChangesGuard. It only asks for
 *              'isDirty: () => boolean', and an Angular signal IS a callable
 *              function, so 'isDirty = signal(false)' satisfies it with no glue code.
 */
export class ProjectFormComponent implements OnInit, OnDestroy, HasUnsavedChanges {
  // inject() is the modern form of constructor injection. It is used for the
  // dependencies that only the class needs, which keeps the constructor short; the
  // older constructor form is still used below for the four others. Both give the
  // exact same singleton instances.
  private readonly toast = inject(ToastService);
  // TranslocoService is the service form of the `transloco` pipe: it translates from
  // TypeScript, which the pipe cannot do (toast messages, error messages).
  private readonly tr = inject(TranslocoService);
  // Public (no `private`) because the TEMPLATE reads it in the back link. A private
  // field is not reachable from the template and the build would fail.
  readonly listState = inject(ProjectsListStateService);

  // true when the URL carried an :id. It is the single switch between the two modes
  // of this screen: locked code field, no draft, revised-budget notice, PUT instead
  // of POST, and the wording of the button.
  isEdit = false;
  // Signals. A signal is a reactive box: read it as loaded(), write it with
  // loaded.set(...). Angular knows which parts of the template read which signal and
  // redraws only those. A plain boolean would work with the default change detection
  // too, but signals are what lets this component be switched to zoneless later.
  loaded = signal(false);
  // true while a save request is on its way: it greys the button and spins the little
  // circle, which is what stops a second POST creating a duplicate project.
  loading = signal(false);
  // The message of the last failed save. Empty string means "nothing to show".
  error = signal('');
  // "the user has typed something that is not saved". Written by onFormChange(),
  // cleared after a successful save, and read both by the dot in the action bar and
  // by unsavedChangesGuard when the user tries to leave.
  isDirty = signal(false);
  // The id from the URL, kept for the PUT and for the breadcrumb link. null in create
  // mode, which is exactly what submit() tests before choosing update() or create().
  projectId: number | null = null;
  // The people who can be picked as project manager. It is a signal because it is
  // filled later, when GET /api/users/assignable answers, and the dropdown has to
  // redraw itself at that moment.
  // The inline type is written here instead of importing a User model on purpose: the
  // endpoint returns a deliberately small object (id, name, role), not a full user.
  chefs = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);

  /**
   * The budget after signed amendments, shown but never sent back.
   *
   * It is a plain field and not a signal because nothing in this screen ever changes
   * it after the project has been loaded.
   *
   * It is deliberately NOT part of 'req': ProjectRequest has no revisedBudget field,
   * so this form is physically unable to overwrite it. The billing module (avenants)
   * is the only writer. Without that separation, saving the identity sheet could
   * silently contradict the signed amendments and every margin computed from the
   * revised budget would be wrong.
   */
  revisedBudget: number | null = null;

  // The localStorage key under which the half-filled form is kept. It is a single
  // fixed key, so there is at most one draft project at a time, which is why the
  // banner can be a simple yes/no question.
  private readonly DRAFT_KEY = 'pms_draft_project';
  showDraftBanner = signal(false);
  // The draft read from localStorage at startup. It is held aside instead of being
  // poured straight into `req`, so the user can refuse it.
  private savedDraft: ProjectRequest | null = null;
  // The handle of the pending auto-save. `ReturnType<typeof setTimeout>` is used
  // instead of `number` because in TypeScript setTimeout may be typed as the browser
  // one (number) or the Node one (an object) depending on the types loaded; this form
  // is correct in both cases and does not need a cast.
  private autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

  // The object every input is bound to, and the exact object sent to the server.
  // It starts filled with sensible defaults rather than empty:
  //   status DRAFT  -> a new project is not active until somebody says so;
  //   currency TND + rate 1 -> the common case, and a rate of 1 means "no conversion",
  //                            so the converted budget box is right from the first
  //                            keystroke instead of showing 0 or nothing;
  //   the three nulls -> they make the "—" / "unassigned" option of the dropdowns the
  //                      selected one, because [ngValue]="null" matches a real null.
  //                      Leaving them undefined would show an empty dropdown with no
  //                      line selected at all.
  req: ProjectRequest = {
    code: '',
    name: '',
    // `as ProjectStatus` is a cast that tells TypeScript this plain text is one of the
    // five allowed values of the union type. Without it the compiler would see a wide
    // `string`, which does not fit the field type, and the build would fail.
    status: 'DRAFT' as ProjectStatus,
    currency: 'TND',
    exchangeRateToTnd: 1,
    businessModel: null,
    engagementType: null,
    chefProjetId: null
  };

  /**
   * Angular fills these four in by itself; nothing is built with 'new' here.
   *
   * 'auth' is declared 'readonly' and NOT 'private', unlike the three others: the
   * template calls auth.hasPermission('ASSIGN_CHEF_PROJET'), and a private field
   * cannot be read from a template. 'readonly' still forbids replacing the service.
   *
   * ActivatedRoute is what tells the component which project it is looking at, and
   * therefore whether it is in create or in edit mode.
   */
  constructor(
    private svc: ProjectService,
    readonly auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  /**
   * Prepares the screen: decides create or edit, loads the project in edit mode,
   * offers the saved draft in create mode, and fetches the list of possible project
   * managers when the user is allowed to assign one.
   *
   * Why here and not in the constructor: the constructor should only receive its
   * dependencies. Starting HTTP calls there makes the component impossible to create
   * in a test without a live backend, and Angular does not guarantee the inputs are
   * ready at that point.
   */
  ngOnInit(): void {
    // route.snapshot reads the URL ONCE, instead of subscribing to paramMap.
    // That is correct here because Angular destroys and rebuilds this component when
    // the user moves from /projects/4/edit to /projects/7/edit: the two routes do not
    // reuse the same instance. A subscription would only be needed if the id could
    // change while the component stays alive.
    // paramMap.get() gives back a string or null: null is exactly the "no :id in the
    // URL" case, which means create mode.
    const id = this.route.snapshot.paramMap.get('id');

    if (id) {
      // ── Edit mode: load the project before drawing the form ──
      this.isEdit = true;
      // The unary + turns the text "42" from the URL into the number 42. The service
      // and the API path expect a number; sending the string would still work in the
      // URL but would break the strict typing of ProjectService.get(id: number).
      this.projectId = +id;
      // The server answer is copied FIELD BY FIELD into a fresh ProjectRequest instead
      // of keeping the Project object it sent. Why: Project carries things this form
      // must never send back - id, revisedBudget, effectiveBudget, createdAt, the
      // computed durationDays / budgetTnd / pprTnd. Assigning the whole object would
      // put them in the body of the PUT, where they mean nothing at best and could be
      // taken for an attempt to overwrite a computed amount at worst.
      // Nothing is checked here. Reading a project is authorised on the server: the
      // service method carries the @PreAuthorize permission, and for the project-scoped
      // URLs ProjectScopeInterceptor (ADR-021) additionally checks that this very
      // project is inside the caller's scope. Holding the permission is not enough on
      // its own; the project must be one the user is allowed to see.
      this.svc.get(this.projectId).subscribe({
        next: p => {
          this.req = {
            code: p.code,
            name: p.name,
            description: p.description,
            status: p.status,
            startDate: p.startDate,
            endDate: p.endDate,
            initialBudget: p.initialBudget,
            directorId: p.directorId,
            chefProjetId: p.chefProjetId ?? null,
            contractId: p.contractId,
            client: p.client,
            funder: p.funder,
            // ?? is the "nullish coalescing" operator: it replaces the value only when
            // it is null or undefined, and keeps 0 or an empty string.
            // Here it turns a missing value into a real null, because the dropdowns
            // above use [ngValue]="null" and Angular compares by identity: undefined
            // does not match null, and the dropdown would open on no line at all
            // instead of on the "—" line.
            businessModel: p.businessModel ?? null,
            engagementType: p.engagementType ?? null,
            // A project saved before the currency fields existed comes back without
            // them. Falling back to TND and to a rate of 1 means "no conversion", so
            // the converted budget stays equal to the budget instead of becoming 0 or
            // NaN on the screen.
            currency: p.currency ?? 'TND',
            exchangeRateToTnd: p.exchangeRateToTnd ?? 1,
            licenseSubcontractBudget: p.licenseSubcontractBudget,
            soldWorkloadDays: p.soldWorkloadDays,
            warrantyWorkloadDays: p.warrantyWorkloadDays,
            penaltyProvision: p.penaltyProvision
          };
          // Display only. revisedBudget is kept OUTSIDE req on purpose, so it can never
          // travel back in the PUT body: AvenantService on the server is its only
          // writer.
          this.revisedBudget = p.revisedBudget ?? null;
          // Only now is the form allowed to appear, with the real values already in
          // place. See the @if (!loaded()) in the template.
          this.loaded.set(true);
        },
        error: () => {
          // loaded is set to true on the error branch as well. Without it the screen
          // would keep spinning for ever and the user would never see the message.
          // The message is the one French sentence left in this file; every other text
          // goes through Transloco.
          this.error.set('Impossible de charger le projet.');
          this.loaded.set(true);
        }
      });
    } else {
      // ── Create mode: is there a half-filled form left from last time? ──
      const saved = localStorage.getItem(this.DRAFT_KEY);
      if (saved) {
        // JSON.parse throws on anything that is not valid JSON, and localStorage can
        // hold rubbish: an old version of the app, a user who edited it by hand, a
        // write cut short by a crash. Without the try/catch that exception would stop
        // ngOnInit right there and the screen would stay stuck on the spinner, with no
        // way to create a project again until the browser storage is cleared by hand.
        try {
          this.savedDraft = JSON.parse(saved);
          this.showDraftBanner.set(true);
        } catch { /* draft is damaged: ignore it and open a clean form */ }
      }
      // Create mode has nothing to fetch, so the form is shown at once.
      this.loaded.set(true);
    }

    // The list of possible project managers is only fetched for a user who may assign
    // one. Two reasons: the dropdown is not drawn for the others anyway, and calling
    // the endpoint would simply come back 403 and log a useless error.
    // Again this is convenience, not security: /api/users/assignable is protected on
    // the server side.
    if (this.auth.hasPermission('ASSIGN_CHEF_PROJET')) {
      // This subscribe is not unsubscribed, and that is safe: Angular's HttpClient
      // completes the stream after one answer, which frees the subscription by itself.
      // A never-ending stream would need takeUntilDestroyed here.
      this.svc.listAssignableUsers().subscribe(users => {
        // The endpoint returns every assignable user, so the list is narrowed to the
        // ones whose role is CHEF_PROJET.
        const chefs = users.filter(u => u.roleName === 'CHEF_PROJET');
        // Fallback: if nobody carries that role name, show the whole list rather than
        // an empty dropdown. Why it matters: roles are data in this application and an
        // administrator may have renamed the role. An empty dropdown would block the
        // assignment completely with no explanation, while the full list still lets
        // the work go on.
        // Note that this is the one place where a ROLE NAME is read. It is used only
        // to sort the list for the user's comfort; no permission is granted or refused
        // from it anywhere in the application.
        this.chefs.set(chefs.length ? chefs : users);
      });
    }
  }

  /**
   * Runs when the user leaves the screen. It cancels the auto-save that may still be
   * waiting.
   *
   * Without it, a timer started 800 ms ago fires after the component is gone and
   * writes a draft that the user has just decided to abandon, so the banner would
   * offer it again the next time he opens the form.
   */
  ngOnDestroy(): void {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
  }

  // ── Computed values ───────────────────────────────────────────────
  // All four below are `get` accessors, not fields, and not signals. A getter is
  // re-run on every change detection pass, so the boxes follow the typing with no
  // event handler to wire and nothing that can fall out of date. They are cheap: a
  // subtraction and a multiplication.
  // They are PREVIEWS. The figures that count are recomputed on the server by
  // Project.getDurationDays(), getBudgetTnd() and getPprTnd(); none of them is sent
  // in the request, and none is stored in a column.

  /**
   * The rate to apply right now, protected against the two bad cases.
   *
   * TND gives 1 even if the field still holds an old rate typed for another currency,
   * so a dinar budget is never multiplied by 3.2.
   * A missing rate on a foreign currency gives 0, not 1: showing 0 makes it obvious
   * that the rate has not been filled in, while 1 would quietly display a budget in
   * euros as if it were already dinars, which looks perfectly plausible and is wrong
   * by a factor of three.
   */
  private get rate(): number {
    return (this.req.currency ?? 'TND') === 'TND' ? 1 : (this.req.exchangeRateToTnd ?? 0);
  }

  /**
   * Length of the contract in days, both ends counted, or null when a date is missing.
   *
   * null and not 0: "the dates are not agreed yet" and "a project of zero days" are
   * two different answers, and printing 0 days on a project that simply has no dates
   * would be read as a mistake.
   *
   * Same rule as the server (Project.getDurationDays), so the box and the detail page
   * always show the same number.
   */
  get durationDays(): number | null {
    if (!this.req.startDate || !this.req.endDate) return null;
    // The dates are "YYYY-MM-DD" text; new Date() turns them into a moment in time and
    // getTime() into milliseconds, so the difference can be divided.
    const ms = new Date(this.req.endDate).getTime() - new Date(this.req.startDate).getTime();
    // 86_400_000 is the number of milliseconds in a day; the underscores are only a
    // readability separator in TypeScript and change nothing.
    // Math.round, not a plain division: a daylight-saving change makes one day of the
    // year last 23 or 25 hours, so the raw division gives 30.958333 and truncating it
    // would report one day short.
    // The "+ 1" counts both ends, the way a contract does: from the 1st to the 3rd is
    // three days, not two. Without it every project would be reported one day short
    // and a one-day project would show 0.
    const days = Math.round(ms / 86_400_000) + 1;
    // A negative or zero result can only come from an end date placed before the start
    // date. Showing the dash rather than "-4 days" avoids a figure that looks like a
    // real measurement; the red border on the end-date field is what explains it.
    return days >= 1 ? days : null;
  }

  /**
   * true only when both dates are filled AND the end is before the start.
   *
   * A half-filled pair answers false, not true: a user who has typed the start date
   * and not yet the end one has made no mistake, and turning the field red while he
   * is still typing would be wrong. This getter drives the red border, the message
   * under the field, and the disabled Save button.
   *
   * Note that an end date EQUAL to the start date is accepted: a project can begin and
   * end on the same day, which is also why the database constraint allows it.
   */
  get dateRangeInvalid(): boolean {
    if (!this.req.startDate || !this.req.endDate) return false;
    // Two Date objects compared with < are compared on their underlying number of
    // milliseconds, which is what makes this work. (Note: == between two Dates would
    // NOT work, as it compares object identity.)
    return new Date(this.req.endDate) < new Date(this.req.startDate);
  }

  /**
   * Preview of the budget converted into dinars: budget x rate.
   *
   * '== null' with two equals, on purpose: it is true for null AND for undefined, and
   * false for 0. With '=== null' a budget left empty comes back as undefined, the test
   * would miss it, and the box would print NaN. With a falsy test (!this.req...) a real
   * budget of 0 would be hidden behind a dash instead of being shown.
   */
  get budgetTnd(): number | null {
    if (this.req.initialBudget == null) return null;
    // The cast tells TypeScript the value is a number here; the null case was already
    // handled on the line above. It changes nothing at run time.
    return (this.req.initialBudget as number) * this.rate;
  }

  /**
   * Preview of the PPR ("Provision Pour Risques", the money set aside for risk):
   * 5 % of the budget in TND, as the Excel identity sheet computes it.
   *
   * The same 5 % is applied on the server in Project.getPprTnd(). Both must stay
   * equal: if this one were changed alone, the user would validate a figure on screen
   * and the project would be saved with another one.
   */
  get pprTnd(): number | null {
    return this.budgetTnd != null ? this.budgetTnd * 0.05 : null;
  }

  // ── Handlers ─────────────────────────────────────────────────────

  /**
   * Puts the exchange rate back to 1 as soon as the currency becomes TND.
   *
   * Called from the template by (ngModelChange), which fires after the new currency
   * has been written into req.currency. The rate is corrected here and not only in
   * submit() so the converted budget shown on screen is right immediately.
   */
  onCurrencyChange(currency: string): void {
    if (currency === 'TND') this.req.exchangeRateToTnd = 1;
  }

  // ── Draft management ──────────────────────────────────────────────

  /**
   * Called on every keystroke and every dropdown change in the form.
   *
   * It does two things: it raises the "unsaved changes" flag, and, in create mode
   * only, it re-arms the auto-save of the draft.
   */
  onFormChange(): void {
    // Raised on the very first change and never lowered again until a successful save.
    // It does not compare with the loaded values: a user who types a letter and erases
    // it is still warned when he leaves. That is on purpose - a false warning costs one
    // click, a lost form costs ten minutes.
    this.isDirty.set(true);
    // No draft in edit mode. The project already exists in the database, so there is
    // nothing to rescue, and a draft of project 42 restored later on project 51 would
    // be a serious data mix-up.
    if (this.isEdit) return;
    // Debounce. Each change cancels the timer armed by the previous one, so the write
    // only happens 800 ms after the user STOPS typing.
    // Without it, typing a 40-character project name would serialise the whole form and
    // write to localStorage 40 times; localStorage is synchronous and blocks the page
    // while it writes, so the typing would feel sticky.
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
    // The arrow function matters: it keeps `this` pointing at the component. A plain
    // `setTimeout(this.persistDraft, 800)` would lose it and throw when the timer fires.
    this.autoSaveTimer = setTimeout(() => this.persistDraft(), 800);
  }

  /**
   * Writes the current form to the browser's localStorage, as text.
   *
   * localStorage and not sessionStorage: the point is exactly to survive a closed tab,
   * a browser crash or an accidental reload. sessionStorage dies with the tab, which
   * is the case this feature exists for.
   * It is only a convenience for a form being typed, so nothing confidential is meant
   * to be kept here, and submit() removes the entry as soon as the project is created.
   */
  private persistDraft(): void {
    localStorage.setItem(this.DRAFT_KEY, JSON.stringify(this.req));
  }

  /**
   * Puts the saved draft back into the form. Bound to the "Restore" button.
   */
  restoreDraft(): void {
    if (!this.savedDraft) return;
    // The spread merges the draft ON TOP of the current defaults rather than replacing
    // the object. Why: an old draft written before a new field existed does not contain
    // that field, and a plain assignment would leave it undefined instead of keeping
    // its default (for example currency TND and rate 1, which the converted-budget box
    // needs).
    this.req = { ...this.req, ...this.savedDraft };
    this.showDraftBanner.set(false);
    // Dropped so the banner cannot be brought back and the same draft applied twice.
    this.savedDraft = null;
  }

  /**
   * Throws the saved draft away for good. Bound to the "Discard" button.
   *
   * It erases the localStorage entry too, and not only the banner: otherwise the same
   * draft would be offered again the next time the form is opened, and the user would
   * have to refuse it every single time.
   */
  discardDraft(): void {
    localStorage.removeItem(this.DRAFT_KEY);
    this.showDraftBanner.set(false);
    this.savedDraft = null;
  }

  // ── Submission ────────────────────────────────────────────────────

  /**
   * Sends the form: a PUT in edit mode, a POST in create mode. On success it clears
   * the dirty flag and the draft, shows a small confirmation and goes to the project
   * detail page.
   *
   * One single method for the two cases, because everything around the call is
   * identical: the same checks before, and the same handling of the answer after.
   * Only the one line that chooses the observable differs.
   *
   * Nothing here decides whether the user is allowed to save. The permission is
   * checked on the server, on the service method, with @PreAuthorize, and for a
   * project-scoped URL ProjectScopeInterceptor (ADR-021) also checks that this project
   * belongs to the caller's scope.
   */
  submit(): void {
    // The date rule is checked a second time even though the button is disabled when
    // it is broken. A disabled button is only a piece of user interface: pressing Enter
    // in a field also submits the form, and the browser developer tools can remove the
    // attribute in one click. Never trust the button.
    if (this.dateRangeInvalid) {
      this.error.set(this.tr.translate('project.form.dateOrder'));
      // Returning here leaves `loading` untouched, so the button stays usable and the
      // user can correct the date and try again.
      return;
    }
    // From now on the button is greyed and spins: this is what stops a second POST
    // creating the same project twice while the first one is still travelling.
    this.loading.set(true);
    // The old error message is cleared, otherwise the banner of the previous failure
    // would stay under the form during the new attempt and look like a fresh refusal.
    this.error.set('');

    // Last guard on the rate, this time on what is actually SENT. onCurrencyChange()
    // already does it when the dropdown changes, but the form can reach this point with
    // an inconsistent pair: a draft restored from localStorage, or a project loaded in
    // euros whose currency was switched back to TND by other means. Storing a rate of
    // 3.2 on a project in dinars would multiply that project's budget by 3.2 in every
    // KPI of the application.
    if ((this.req.currency ?? 'TND') === 'TND') this.req.exchangeRateToTnd = 1;

    // The observable is only BUILT here; an Angular HttpClient observable is cold, so
    // no request leaves before the subscribe() below. Choosing it in a variable keeps
    // the answer handling written once instead of being copied into two branches.
    // `this.projectId` is tested as well as isEdit so TypeScript knows it is not null
    // when it is passed to update(id: number).
    const obs = this.isEdit && this.projectId
      ? this.svc.update(this.projectId, this.req)
      : this.svc.create(this.req);

    obs.subscribe({
      // `p` is the project as the server sends it back, with its id (the new one in
      // create mode) and its recomputed figures.
      next: p => {
        // Lowered FIRST, and before navigating: unsavedChangesGuard reads this same
        // signal when the router leaves the page just below. If it stayed true, saving
        // would pop the "you will lose your changes" question right after a successful
        // save.
        this.isDirty.set(false);
        if (!this.isEdit) {
          // The project now exists in the database, so the draft has no reason to live
          // and must not be offered again on the next new project.
          localStorage.removeItem(this.DRAFT_KEY);
          // And the timer armed by the last keystroke is cancelled, otherwise it would
          // fire a few hundred milliseconds later and write the draft back, just after
          // it was deleted.
          if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
        }
        this.toast.success(this.tr.translate(this.isEdit ? 'project.form.okSaved' : 'project.form.okCreated'));
        // Goes to the detail page of the project. p.id is used and not this.projectId,
        // because in create mode the id is only known from this answer.
        // `loading` is deliberately left true: the component is about to be destroyed,
        // and lowering it would make the button look clickable again for the last few
        // frames.
        this.router.navigate(['/projects', p.id]);
      },
      error: e => {
        // Here the button MUST be freed again: the user stays on the form and has to be
        // able to correct and retry.
        this.loading.set(false);
        // Three levels, best first:
        //   e.error.detail  - the field of a ProblemDetail (RFC 7807), the format the
        //                     backend uses for its business errors, for example
        //                     "This project code already exists".
        //   e.error.message - the older shape, still returned by some error paths.
        //   the translated generic sentence - for everything with no readable body at
        //                     all, typically a network failure or a 500.
        // Without the last level the banner would print "undefined" and the user would
        // have no idea whether his project was saved.
        this.error.set(e.error?.detail ?? e.error?.message ?? this.tr.translate('project.form.genericError'));
      }
    });
  }

  /**
   * The Cancel button: goes back to the projects list.
   *
   * It does not ask anything itself. The router runs unsavedChangesGuard on the way
   * out, and that guard raises the confirmation modal when the form is dirty, so the
   * question is asked in exactly one place for every way of leaving the page - this
   * button, the breadcrumb, the side menu or the browser's back arrow.
   */
  back(): void { this.router.navigate(['/projects']); }
}
