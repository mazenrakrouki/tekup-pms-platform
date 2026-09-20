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

// Project identity sheet, create+edit in one component (driven by 'isEdit'): the two screens
// are ~95% identical, so splitting them would mean adding every new field twice. Template-driven
// forms, not Reactive Forms: 'req' is one flat object, so [(ngModel)] needs no duplicate FormGroup.

@Component({
  selector: 'app-project-form',
  standalone: true,
  providers: [provideTranslocoScope('project')],
  imports: [CommonModule, FormsModule, RouterLink, TranslocoModule],
  styles: [`
    /* Design-system tokens (not Bootstrap .bg-light) so this stays readable in dark mode. */
    .field-ro { background: var(--surface-2); color: var(--text-2); }
    .field-ro.fw-semibold { color: var(--text-1); }

    /* Sticky so Save/Cancel stay reachable on this four-card-long form. */
    .form-actionbar { position: sticky; bottom: 0; z-index: 10; margin-top: 1.5rem;
      display: flex; align-items: center; gap: .5rem;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg);
      box-shadow: var(--sh-md); padding: .75rem 1rem; }
    .form-actionbar .fa-spacer { margin-left: auto; font-size: 12px; color: var(--text-3); }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <!-- Replays the list's search/filter/sort/page so Back doesn't lose it. -->
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <i class="bi bi-arrow-left"></i> {{ 'projects.title' | transloco }}
        </a>
        <span class="bc-sep">›</span>
        <!-- req.code tested too: it's empty until the GET returns, avoiding a flash of an empty link. -->
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

      <!-- Create mode only, when a half-filled form was found in localStorage. Not an
           automatic restore: silently refilling with old data risks saving unreviewed values. -->
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

      <!-- Form hidden while loading: otherwise a user could type into an empty field and
           the GET answer, arriving a moment later, would wipe it. -->
      @if (!loaded()) {
        <div class="text-center py-5">
          <div class="spinner-border text-primary mb-2"></div>
          <p class="text-muted small mb-0">{{ 'project.form.loading' | transloco }}</p>
        </div>
      } @else {

      <!-- (ngSubmit): fires on Enter too, unlike a plain button (click). #f="ngForm" is
           read below as f.valid. (input) covers typing, (change) covers selects/date pickers
           — both needed or a status-only change would slip past the unsaved-changes guard. -->
      <form (ngSubmit)="submit()" #f="ngForm"
            (input)="onFormChange()" (change)="onFormChange()">

        <!-- SECTION 1: IDENTIFICATION -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-card-heading me-2 text-primary"></i>{{ 'project.form.sectionIdentification' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">{{ 'project.form.code' | transloco }} *</label>
                <!-- [attr.readonly] + null (not [readonly]): a bound readonly="false" would
                     still lock the field. Locked in edit mode: the code is printed on the DI,
                     invoices and reports, so it must stay stable after creation. -->
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
                <!-- Option values are raw Java enum names, not translated text, or the save
                     would 400 on an unknown value. -->
                <select class="form-select" name="businessModel" [(ngModel)]="req.businessModel">
                  <!-- [ngValue]="null" (not value="null"): binds a real null, not the string "null". -->
                  <option [ngValue]="null">—</option>
                  <option value="SEUL">{{ 'labels.businessModel.SEUL' | transloco }}</option>
                  <option value="GROUPEMENT">{{ 'labels.businessModel.GROUPEMENT' | transloco }}</option>
                </select>
              </div>

              <div class="col-md-4">
                <label class="form-label">{{ 'project.form.engagementType' | transloco }}</label>
                <!-- Same rule: raw enum values on the wire. -->
                <select class="form-select" name="engagementType" [(ngModel)]="req.engagementType">
                  <option [ngValue]="null">—</option>
                  <option value="FORFAIT">{{ 'labels.engagement.FORFAIT' | transloco }}</option>
                  <option value="REGIE">{{ 'labels.engagement.REGIE' | transloco }}</option>
                </select>
              </div>

              <!-- Permission-gated (ASSIGN_CHEF_PROJET), display only — server enforces via
                   @PreAuthorize. Without this @if, a team member would see a dropdown that
                   always 403s on save. -->
              @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                <div class="col-md-4">
                  <label class="form-label">{{ 'project.info.manager' | transloco }}</label>
                  <select class="form-select" name="chefProjetId" [(ngModel)]="req.chefProjetId">
                    <!-- "Not assigned yet" is a real business state: a real null, not a missing row. -->
                    <option [ngValue]="null">{{ 'project.form.unassigned' | transloco }}</option>
                    @for (u of chefs(); track u.id) {
                      <option [ngValue]="u.id">{{ u.firstName }} {{ u.lastName }}</option>
                    }
                  </select>
                </div>
              }

            </div>
          </div>
        </div>

        <!-- SECTION 2: PLANNING -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-calendar3 me-2 text-primary"></i>{{ 'project.form.sectionPlanning' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-4">
                <label class="form-label">{{ 'common.status' | transloco }} *</label>
                <!-- Hard-coded to match the Java ProjectStatus enum exactly: business rules, not data. -->
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
                <!-- type="date" gives "YYYY-MM-DD", exactly what Java's LocalDate reads, no
                     ambiguous 05/03. dateRangeInvalid getter re-evaluates every change detection. -->
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
                  <!-- <div>, not an <input>: derived from the dates, never typed or sent
                       (no durationDays in ProjectRequest) — same rule as the DI. -->
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

        <!-- SECTION 3: FINANCIAL -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-cash-coin me-2 text-primary"></i>{{ 'project.form.sectionFinancial' | transloco }}
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">{{ 'project.form.currency' | transloco }}</label>
                <!-- Resets rate to 1 on TND here (not just in submit()), so the converted-budget
                     box below is correct immediately. -->
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
                  <!-- 'or TND' fallback: currency can still be undefined, else the label
                       would print "Initial budget ()". -->
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
                <!-- step="0.000001": FCFA is a tiny fraction of a dinar, 2-decimal rounding
                     would shift a large contract's converted budget by thousands. Locked on
                     TND (converting dinars to dinars is always 1). -->
                <input type="number" class="form-control" name="rate"
                       [(ngModel)]="req.exchangeRateToTnd" min="0" step="0.000001"
                       [attr.readonly]="(req.currency || 'TND') === 'TND' ? '' : null"
                       [class.field-ro]="(req.currency || 'TND') === 'TND'">
              </div>

              <!-- Converted budget = budget x rate, live in TND. No decimals: a sanity check
                   on order of magnitude, not an accounting figure. -->
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.convertedBudget' | transloco }}</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ budgetTnd !== null ? (budgetTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- PPR (Provision Pour Risques) = 5% of budget in TND, as the Excel sheet does it.
                   Preview only, recomputed server-side by Project.getPprTnd(). Not the
                   "penalty provision" field below, which is user-typed. -->
              <div class="col-md-6">
                <label class="form-label">{{ 'project.form.pprLabel' | transloco }}</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ pprTnd !== null ? (pprTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- Notice only, never an input: revisedBudget is written solely by the billing
                   module (no such field in ProjectRequest), so this form can never contradict
                   the signed amendments. -->
              @if (isEdit && revisedBudget !== null) {
                <div class="col-12">
                  <div class="alert alert-secondary py-2 small d-flex align-items-center gap-2 mb-0">
                    <i class="bi bi-info-circle flex-shrink-0"></i>
                    <span>
                      {{ 'project.form.revisedBudget' | transloco: { currency: (req.currency || 'TND') } }}
                      <!-- Exactly 2 decimals: a contractual amount, unlike the preview boxes above. -->
                      <strong>{{ revisedBudget | number:'1.2-2' }}</strong>
                      {{ 'project.form.revisedBudgetVia' | transloco }} <strong>{{ 'project.form.billingAmendments' | transloco }}</strong>.
                    </span>
                  </div>
                </div>
              }

              <!-- min="0" blocks a negative provision, which would quietly inflate the margin. -->
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

        <!-- SECTION 4: SOLD WORKLOAD; step="0.5" since a half-day is the smallest unit sold. -->
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

        <!-- Right above the buttons, where the user looks right after clicking Save. -->
        @if (error()) {
          <div class="alert alert-danger py-2 small d-flex align-items-center gap-2">
            <i class="bi bi-exclamation-circle-fill"></i>{{ error() }}
          </div>
        }

        <div class="form-actionbar">
          <!-- Disabled while saving (avoids a duplicate POST), when required fields are
               invalid, or when dates are out of order (submit() re-checks this last one too). -->
          <button type="submit" class="btn btn-primary px-4"
                  [disabled]="loading() || !f.valid || dateRangeInvalid">
            @if (loading()) {
              <span class="spinner-border spinner-border-sm me-2"></span>
            }
            {{ (isEdit ? 'project.form.saveChanges' : 'project.form.createProject') | transloco }}
          </button>
          <!-- type="button": the HTML default (submit) would save the project before navigating away. -->
          <button type="button" class="btn btn-outline-secondary" (click)="back()">
            {{ 'common.cancel' | transloco }}
          </button>
          <!-- Same isDirty signal unsavedChangesGuard reads, so the two can never disagree. -->
          @if (isDirty()) {
            <span class="fa-spacer"><i class="bi bi-record-fill me-1" style="font-size:8px;color:var(--c-warning)"></i>{{ 'project.form.unsavedChanges' | transloco }}</span>
          }
        </div>

      </form>
      } <!-- end @else loaded -->

    </div>
  `
})
// Holds form state, computes preview figures, keeps the draft, sends create/update.
// HasUnsavedChanges is satisfied for free: the guard just needs isDirty: () => boolean, and
// a signal already is one.
export class ProjectFormComponent implements OnInit, OnDestroy, HasUnsavedChanges {
  private readonly toast = inject(ToastService);
  // Service form of the transloco pipe: translates from TypeScript (toasts, errors).
  private readonly tr = inject(TranslocoService);
  // Public: the template reads it in the back link.
  readonly listState = inject(ProjectsListStateService);

  // Single switch between the two modes: locked code field, no draft, revised-budget
  // notice, PUT vs POST, button wording.
  isEdit = false;
  loaded = signal(false);
  // Greys the button and spins, so a double-click can't fire a duplicate POST.
  loading = signal(false);
  error = signal('');
  // Read by both the action-bar dot and unsavedChangesGuard.
  isDirty = signal(false);
  // null in create mode; that's exactly what submit() tests to choose update() vs create().
  projectId: number | null = null;
  // Deliberately a small inline type (id/name/role), not the full User model — the
  // endpoint returns only that.
  chefs = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);

  // Plain field, not a signal: never changes after load. Deliberately NOT part of 'req'
  // (ProjectRequest has no revisedBudget field) so this form can never overwrite it — only
  // the billing module (avenants) writes it.
  revisedBudget: number | null = null;

  // Single fixed key: at most one draft project at a time, so the banner is a simple yes/no.
  private readonly DRAFT_KEY = 'pms_draft_project';
  showDraftBanner = signal(false);
  // Held aside (not poured into 'req') so the user can refuse it.
  private savedDraft: ProjectRequest | null = null;
  private autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

  // Sensible defaults, not empty: DRAFT until someone activates it; TND + rate 1 so the
  // converted-budget box is correct from the first keystroke; nulls match [ngValue]="null"
  // so the dropdowns open on their "—"/unassigned option instead of nothing selected.
  req: ProjectRequest = {
    code: '',
    name: '',
    status: 'DRAFT' as ProjectStatus,
    currency: 'TND',
    exchangeRateToTnd: 1,
    businessModel: null,
    engagementType: null,
    chefProjetId: null
  };

  // auth is readonly (not private): the template calls auth.hasPermission(...).
  constructor(
    private svc: ProjectService,
    readonly auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  // Constructor should only receive dependencies; HTTP calls here would make the
  // component hard to test and Angular doesn't guarantee inputs are ready yet.
  ngOnInit(): void {
    // snapshot, not paramMap subscription: Angular recreates this component when moving
    // between /projects/4/edit and /projects/7/edit, so the id can't change under it.
    const id = this.route.snapshot.paramMap.get('id');

    if (id) {
      // ── Edit mode: load the project before drawing the form ──
      this.isEdit = true;
      this.projectId = +id;
      // Copied field by field into a fresh ProjectRequest (not the whole Project object):
      // Project carries id/revisedBudget/effectiveBudget/computed fields this form must
      // never send back in the PUT body.
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
            // ?? null: undefined doesn't match [ngValue]="null" by identity, so without
            // this the dropdown would open on no line at all instead of the "—" option.
            businessModel: p.businessModel ?? null,
            engagementType: p.engagementType ?? null,
            // A project saved before currency fields existed comes back without them;
            // TND + rate 1 means "no conversion" so the converted budget isn't 0/NaN.
            currency: p.currency ?? 'TND',
            exchangeRateToTnd: p.exchangeRateToTnd ?? 1,
            licenseSubcontractBudget: p.licenseSubcontractBudget,
            soldWorkloadDays: p.soldWorkloadDays,
            warrantyWorkloadDays: p.warrantyWorkloadDays,
            penaltyProvision: p.penaltyProvision
          };
          // Display only, kept outside req: AvenantService is the only writer.
          this.revisedBudget = p.revisedBudget ?? null;
          this.loaded.set(true);
        },
        error: () => {
          // Set true on error too, or the screen would spin forever with no message.
          this.error.set('Impossible de charger le projet.');
          this.loaded.set(true);
        }
      });
    } else {
      // ── Create mode: is there a half-filled form left from last time? ──
      const saved = localStorage.getItem(this.DRAFT_KEY);
      if (saved) {
        // localStorage can hold rubbish (old app version, hand-edited, crash mid-write);
        // without try/catch this would stop ngOnInit and strand the screen on the spinner.
        try {
          this.savedDraft = JSON.parse(saved);
          this.showDraftBanner.set(true);
        } catch { /* draft is damaged: ignore it and open a clean form */ }
      }
      this.loaded.set(true);
    }

    // Only fetched if the user may assign a manager: the dropdown isn't drawn for
    // others, and the call would just 403. Comfort only; the server enforces it too.
    if (this.auth.hasPermission('ASSIGN_CHEF_PROJET')) {
      // Not unsubscribed: HttpClient completes after one answer and frees itself.
      this.svc.listAssignableUsers().subscribe(users => {
        const chefs = users.filter(u => u.roleName === 'CHEF_PROJET');
        // Falls back to the full list if nobody carries that role name (an admin may
        // have renamed it) — better than an empty, blocked dropdown. Sole place a role
        // name is read; it's for sorting comfort only, never a permission decision.
        this.chefs.set(chefs.length ? chefs : users);
      });
    }
  }

  // Cancels the auto-save so a timer fired after the user navigates away doesn't write
  // back a draft they just abandoned.
  ngOnDestroy(): void {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
  }

  // ── Computed values ───────────────────────────────────────────────
  // get accessors (not signals): re-run every change-detection pass, cheap (subtraction/
  // multiplication). All PREVIEWS — server recomputes these, none is sent or stored.

  // TND always gives 1, even with a stale foreign rate still in the field. Missing rate on
  // a foreign currency gives 0 (not 1): 0 is visibly wrong, 1 would look plausible but be off.
  private get rate(): number {
    return (this.req.currency ?? 'TND') === 'TND' ? 1 : (this.req.exchangeRateToTnd ?? 0);
  }

  // null (not 0) when a date is missing: distinct from "a project of zero days".
  // Matches Project.getDurationDays() server-side.
  get durationDays(): number | null {
    if (!this.req.startDate || !this.req.endDate) return null;
    const ms = new Date(this.req.endDate).getTime() - new Date(this.req.startDate).getTime();
    // Math.round guards a DST crossing; +1 counts both ends (1st to 3rd = 3 days).
    const days = Math.round(ms / 86_400_000) + 1;
    // Negative/zero only from an inverted date pair; the dash avoids a fake "-4 days".
    return days >= 1 ? days : null;
  }

  // False (not true) on a half-filled pair: a user mid-typing the end date hasn't made
  // a mistake yet. Equal dates are accepted (a project can start and end the same day).
  get dateRangeInvalid(): boolean {
    if (!this.req.startDate || !this.req.endDate) return false;
    return new Date(this.req.endDate) < new Date(this.req.startDate);
  }

  // == null (not ===): also catches undefined, without a falsy test hiding a real budget of 0.
  get budgetTnd(): number | null {
    if (this.req.initialBudget == null) return null;
    return (this.req.initialBudget as number) * this.rate;
  }

  // Mirrors Project.getPprTnd()'s 5% server-side; both must stay in sync.
  get pprTnd(): number | null {
    return this.budgetTnd != null ? this.budgetTnd * 0.05 : null;
  }

  // ── Handlers ─────────────────────────────────────────────────────

  // Fixed here (not only in submit()) so the converted-budget box updates immediately.
  onCurrencyChange(currency: string): void {
    if (currency === 'TND') this.req.exchangeRateToTnd = 1;
  }

  // ── Draft management ──────────────────────────────────────────────

  onFormChange(): void {
    // Never lowered until a successful save, even if the user undoes their own edit:
    // a false warning costs one click, a lost form costs ten minutes.
    this.isDirty.set(true);
    // No draft in edit mode: the project already exists, and restoring a project-42 draft
    // onto project 51 later would be a serious mix-up.
    if (this.isEdit) return;
    // Debounced 800ms after typing stops; localStorage writes are synchronous and would
    // otherwise make typing feel sticky.
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
    // Arrow function: keeps 'this' bound, unlike passing this.persistDraft directly.
    this.autoSaveTimer = setTimeout(() => this.persistDraft(), 800);
  }

  // localStorage (not sessionStorage): must survive a closed tab or crash.
  private persistDraft(): void {
    localStorage.setItem(this.DRAFT_KEY, JSON.stringify(this.req));
  }

  restoreDraft(): void {
    if (!this.savedDraft) return;
    // Spread merges ON TOP of current defaults: an old draft predating a new field would
    // otherwise leave that field undefined instead of its default.
    this.req = { ...this.req, ...this.savedDraft };
    this.showDraftBanner.set(false);
    // Dropped so the same draft can't be applied twice.
    this.savedDraft = null;
  }

  // Also clears localStorage, else the same draft would be offered again next time.
  discardDraft(): void {
    localStorage.removeItem(this.DRAFT_KEY);
    this.showDraftBanner.set(false);
    this.savedDraft = null;
  }

  // ── Submission ────────────────────────────────────────────────────

  // One method for create/update: the checks and answer handling are identical, only
  // the chosen observable differs. Server re-checks the permission and project scope
  // (ADR-021) independently of anything here.
  submit(): void {
    // Re-checked even though the button is disabled: a disabled attribute is UI only,
    // Enter still submits, and dev tools can remove it.
    if (this.dateRangeInvalid) {
      this.error.set(this.tr.translate('project.form.dateOrder'));
      return;
    }
    this.loading.set(true);
    this.error.set('');

    // Last guard on the rate actually sent: onCurrencyChange() covers the dropdown, but
    // a restored draft or an indirectly-switched currency could still reach here inconsistent.
    if ((this.req.currency ?? 'TND') === 'TND') this.req.exchangeRateToTnd = 1;

    // this.projectId tested alongside isEdit so TypeScript knows it's non-null for update().
    const obs = this.isEdit && this.projectId
      ? this.svc.update(this.projectId, this.req)
      : this.svc.create(this.req);

    obs.subscribe({
      next: p => {
        // Cleared before navigating: unsavedChangesGuard reads this same signal, and a
        // stale true would pop the "lose your changes?" question right after a good save.
        this.isDirty.set(false);
        if (!this.isEdit) {
          localStorage.removeItem(this.DRAFT_KEY);
          // Cancels a still-pending auto-save that would otherwise write the draft back
          // right after it was just deleted.
          if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
        }
        this.toast.success(this.tr.translate(this.isEdit ? 'project.form.okSaved' : 'project.form.okCreated'));
        // p.id, not this.projectId: in create mode the id is only known from this answer.
        // loading deliberately left true — the component is about to be destroyed.
        this.router.navigate(['/projects', p.id]);
      },
      error: e => {
        this.loading.set(false);
        // detail (RFC 7807 ProblemDetail) first, message as an older fallback, else a
        // generic sentence — never leaves the banner printing "undefined".
        this.error.set(e.error?.detail ?? e.error?.message ?? this.tr.translate('project.form.genericError'));
      }
    });
  }

  // Doesn't ask anything itself: unsavedChangesGuard runs on every way out (this button,
  // breadcrumb, menu, browser back), asking the question in exactly one place.
  back(): void { this.router.navigate(['/projects']); }
}
