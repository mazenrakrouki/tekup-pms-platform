import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { GovernanceService } from '../../core/services/governance.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { Risk, Livrable, DemandeChangement, NiveauRisque } from '../../core/models/governance.model';
import { PartiePrenante } from '../../core/models/partie-prenante.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

/*
 * WHAT THIS FILE IS
 * The single screen for the "Governance" part of one project. It shows four registers in
 * four tabs: risks, deliverables (livrables), change requests (demandes de changement) and
 * stakeholders (parties prenantes).
 *
 * WHERE IT SITS IN THE FLOW
 * Who calls it: the Angular router. The route for /governance points at this component, and
 *   a permission guard already checked that the user may open the page at all.
 * What it calls next:
 *   - ProjectService.listAll()  -> to fill the project picker at the top
 *   - GovernanceService         -> every read and every write of the four registers; it talks
 *                                  to /api/projects/{id}/risks, /livrables,
 *                                  /demandes-changement and /parties-prenantes
 *   - TeamService.list(id)      -> the list of team members, used as the "requester" dropdown
 *                                  of a change request
 *   - AuthService.hasPermission -> to show or hide the write buttons
 *   - ConfirmService / ToastService -> the "are you sure?" dialog and the small success or
 *                                  error message in the corner
 *   - Router / ActivatedRoute   -> the selected project and the active tab are kept in the
 *                                  URL (?p=12&tab=risks)
 *
 * WHY IT EXISTS
 * Delete it and a project manager has no way to record a risk, follow a deliverable through
 * its life (waiting -> in progress -> delivered -> approved), approve or reject a change
 * request, or list the people around the project. The REST endpoints would still exist but
 * nothing in the application would call them.
 *
 * SECURITY NOTE (important for the defence)
 * canManage() below only decides what is DRAWN. Anybody can flip it with the browser
 * developer tools. The real refusal happens on the server: @PreAuthorize("hasAuthority(...)")
 * on the service methods, plus ProjectScopeInterceptor, which for every /api/projects/{id}/**
 * URL checks BOTH the permission AND that this user belongs to that project (ADR-021).
 * The front end never tests a role NAME, only the permission code MANAGE_GOVERNANCE.
 */

/*
 * The four possible tabs, written as a union of exact strings instead of a plain 'string'.
 * Why: the compiler then refuses a typo. Without it, setGovTab('risk') (missing the "s")
 * would compile, the @if in the template would never match, and the user would see an empty
 * page with no error anywhere.
 */
type GovTab = 'risks' | 'livrables' | 'changes' | 'parties';

/*
 * A standalone component: it declares its own dependencies in 'imports' instead of belonging
 * to an NgModule. Why: no shared module has to be edited to use this page, so a change here
 * cannot break another screen by accident.
 * - CommonModule            -> the built-in pipes and directives used by the template
 * - FormsModule             -> needed by every [(ngModel)] in the modals below; without it
 *                              Angular throws "Can't bind to 'ngModel'" at build time
 * - ProjectPickerComponent  -> the shared project chooser; we reuse it instead of a giant
 *                              <select>, which would be unusable with hundreds of projects
 * - TranslocoModule         -> gives the template the | transloco pipe, so every label can be
 *                              shown in French or English
 * The template is written inline, in backticks: one file holds the screen and its logic.
 * Inside those backticks only <!-- --> comments are legal; // or /* would be printed on the
 * page or break the parser.
 */
@Component({
  selector: 'app-governance',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  template: `
    <!-- Top bar with the breadcrumb. When a project is selected the breadcrumb shows a
         back button that clears the selection, so the user can return to the picker
         without using the browser back button. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-shield-check" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <!-- selected() is a signal; calling it here subscribes this part of the template to
             it. Why: when select() or clearSelection() changes the signal, Angular redraws
             only this breadcrumb. Without signals the whole page would have to be checked. -->
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" [title]="'governance.backToPicker' | transloco">
            <i class="bi bi-arrow-left"></i> {{ 'nav.governance' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'nav.governance' | transloco }}</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.governance' | transloco }}</h1>
      </div>
      <!-- Project selector. We reuse the shared <app-project-picker> (search + pagination)
           instead of a <select>. Why: a company can have hundreds of projects, and a long
           dropdown is impossible to use. [selected] feeds the current choice back in so the
           picker shows it; (projectSelected) calls select() when the user picks one. -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-shield-check"
                            (projectSelected)="select($event)" />
      </div>

      <!-- Everything below only exists once a project is chosen. Why: every register is read
           from /api/projects/{id}/..., so with no id there is nothing to ask the server. -->
      @if (selected()) {
        <!-- Sub-tabs (design-system segmented control). Only one tab is in the DOM at a
             time (@if further down), but the counts in the labels come from signals that
             are all loaded together by reload(), so every count is right from the start. -->
        <div class="pms-tabs mb-3" style="width:fit-content;max-width:100%;overflow-x:auto">
          <button class="tab-item" [class.active]="govTab()==='risks'" (click)="setGovTab('risks')">
            <i class="bi bi-exclamation-triangle me-1"></i>{{ 'governance.tabRisks' | transloco }} ({{ risks().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='livrables'" (click)="setGovTab('livrables')">
            <i class="bi bi-check2-square me-1"></i>{{ 'governance.tabDeliverables' | transloco }} ({{ livrables().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='changes'" (click)="setGovTab('changes')">
            <i class="bi bi-arrow-repeat me-1"></i>{{ 'governance.tabChanges' | transloco }} ({{ changes().length }})
          </button>
          <button class="tab-item" [class.active]="govTab()==='parties'" (click)="setGovTab('parties')">
            <i class="bi bi-person-lines-fill me-1"></i>{{ 'governance.tabStakeholders' | transloco }} ({{ parties().length }})
          </button>
        </div>

        <!-- RISKS TAB (risques) -->
        @if (govTab() === 'risks') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>{{ 'governance.riskRegister' | transloco }} — {{ selected()!.name }}</span>
              <!-- canManage() hides the "add" button for a read-only user. This is comfort
                   only, not security: the POST is still refused by the server if the user
                   does not hold MANAGE_GOVERNANCE for this project (ADR-021). -->
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openRiskModal()">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'governance.addRisk' | transloco }}
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>{{ 'common.description' | transloco }}</th><th>{{ 'governance.colProbability' | transloco }}</th><th>{{ 'governance.colImpact' | transloco }}</th><th>{{ 'governance.colMitigation' | transloco }}</th><th>{{ 'common.status' | transloco }}</th><th></th></tr>
                </thead>
                <tbody>
                  <!-- track r.id tells Angular how to recognise a row between two redraws.
                       Why: after reload() the array is a brand new array of new objects.
                       Without track, Angular would destroy and rebuild every <tr>, which
                       loses focus and scroll position; with it, only the changed row moves. -->
                  @for (r of risks(); track r.id) {
                    <tr>
                      <td>{{ r.description }}</td>
                      <!-- The translation key is built from the enum value coming from the
                           server: 'riskLevel.' + 'ELEVE' gives 'riskLevel.ELEVE'. Why: one
                           line covers the three levels. Careful, this means every value of
                           the NiveauRisque enum must exist in fr.json and en.json; a value
                           added on the server without its key would be shown raw, as
                           "riskLevel.TRES_ELEVE". niveauBadge() picks the colour class. -->
                      <td><span [class]="niveauBadge(r.probabilite)">{{ 'riskLevel.' + r.probabilite | transloco }}</span></td>
                      <td><span [class]="niveauBadge(r.impact)">{{ 'riskLevel.' + r.impact | transloco }}</span></td>
                      <!-- ?? '—' prints a dash when the field is null or undefined. Why: the
                           mitigation plan is optional in the model. Without it the cell would
                           show the word "null" to the user. -->
                      <td class="text-muted small">{{ r.planMitigation ?? '—' }}</td>
                      <td>
                        @if (r.statut === 'FERME') { <span class="badge-active">{{ 'riskStatus.FERME' | transloco }}</span> }
                        @else if (r.statut === 'MITIGE') { <span class="badge-on-hold">{{ 'riskStatus.MITIGE' | transloco }}</span> }
                        @else { <span class="badge-cancelled">{{ 'riskStatus.OUVERT' | transloco }}</span> }
                      </td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteRisk(r)"
                                  [title]="'common.delete' | transloco" [attr.aria-label]="'governance.deleteRisk' | transloco">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  <!-- @empty runs when the list has zero items. Why: a table with only a
                       header looks broken. Instead the user gets an explanation and, if he
                       may write, the button to create the first risk. -->
                  @empty {
                    <tr><td colspan="6">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-exclamation-triangle"></i></div>
                        <div class="es-title">{{ 'governance.noRisks' | transloco }}</div>
                        <div class="es-desc">{{ 'governance.noRisksDesc' | transloco }}</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openRiskModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'governance.addRisk' | transloco }}</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- DELIVERABLES TAB (livrables). A deliverable walks through four states:
             EN_ATTENTE (waiting) -> EN_COURS (in progress) -> LIVRE (delivered) ->
             VALIDE (approved by the client). -->
        @if (govTab() === 'livrables') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>{{ 'governance.deliverables' | transloco }} — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openLivrableModal()">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'governance.addDeliverable' | transloco }}
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>{{ 'governance.colTitle' | transloco }}</th><th>{{ 'common.description' | transloco }}</th><th>{{ 'governance.colDueDate' | transloco }}</th><th>{{ 'common.status' | transloco }}</th><th>{{ 'common.actions' | transloco }}</th></tr>
                </thead>
                <tbody>
                  @for (l of livrables(); track l.id) {
                    <tr>
                      <td class="fw-semibold">{{ l.titre }}</td>
                      <td class="text-muted small">{{ l.description ?? '—' }}</td>
                      <td>{{ l.dateEcheance ?? '—' }}</td>
                      <td><span [class]="livrableBadge(l.statut)">{{ 'deliverableStatus.' + l.statut | transloco }}</span></td>
                      <td>
                        <!-- Only the ONE button that matches the current state is drawn.
                             Why: the states must be crossed in order. If all three buttons
                             were always visible, a user could click "approve" on a
                             deliverable that was never started, and the server would answer
                             with an error the user does not understand. Showing one button
                             makes the allowed next step obvious. The server enforces the same
                             order anyway; this is the friendly half of the rule. -->
                        @if (canManage()) {
                          @if (l.statut === 'EN_ATTENTE') {
                            <button class="btn btn-sm btn-outline-primary me-1" (click)="demarrerLivrable(l)" [title]="'governance.start' | transloco">
                              <i class="bi bi-play-fill"></i>
                            </button>
                          }
                          @if (l.statut === 'EN_COURS') {
                            <button class="btn btn-sm btn-outline-info me-1" (click)="livrerLivrable(l)" [title]="'governance.deliver' | transloco">
                              <i class="bi bi-box-arrow-up"></i>
                            </button>
                          }
                          @if (l.statut === 'LIVRE') {
                            <button class="btn btn-sm btn-outline-success me-1" (click)="validerLivrable(l)" [title]="'governance.approve' | transloco">
                              <i class="bi bi-check-lg"></i>
                            </button>
                          }
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteLivrable(l)"
                                  [title]="'common.delete' | transloco" [attr.aria-label]="'governance.deleteDeliverable' | transloco">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="5">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-check2-square"></i></div>
                        <div class="es-title">{{ 'governance.noDeliverables' | transloco }}</div>
                        <div class="es-desc">{{ 'governance.noDeliverablesDesc' | transloco }}</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openLivrableModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'governance.addDeliverable' | transloco }}</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- CHANGE REQUESTS TAB (demandes de changement). A request is created as
             EN_ATTENTE (waiting for a decision) and then becomes APPROUVE or REJETE. -->
        @if (govTab() === 'changes') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>{{ 'governance.changeRequests' | transloco }} — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openChangeModal()">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'governance.newRequest' | transloco }}
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>{{ 'governance.colTitle' | transloco }}</th><th>{{ 'governance.colRequester' | transloco }}</th><th>{{ 'governance.colPriority' | transloco }}</th><th>{{ 'common.date' | transloco }}</th><th>{{ 'common.status' | transloco }}</th><th>{{ 'common.actions' | transloco }}</th></tr>
                </thead>
                <tbody>
                  @for (dc of changes(); track dc.id) {
                    <tr>
                      <td class="fw-semibold">{{ dc.titre }}</td>
                      <td>{{ dc.demandeurFullName }}</td>
                      <td><span [class]="prioriteBadge(dc.priorite)">{{ 'changePriority.' + dc.priorite | transloco }}</span></td>
                      <td>{{ dc.dateDemande ?? '—' }}</td>
                      <td>
                        @if (dc.statut === 'APPROUVE') { <span class="badge-active">{{ 'changeStatus.APPROUVE' | transloco }}</span> }
                        @else if (dc.statut === 'REJETE') { <span class="badge-cancelled">{{ 'changeStatus.REJETE' | transloco }}</span> }
                        @else { <span class="badge-on-hold">{{ 'changeStatus.EN_ATTENTE' | transloco }}</span> }
                      </td>
                      <td>
                        @if (canManage()) {
                          <!-- Approve and reject are offered only while the request is still
                               waiting. Why: a decision is taken once. Without this test a
                               manager could reject a request that was already approved, and
                               the decision already recorded on the row would be overwritten. -->
                          @if (dc.statut === 'EN_ATTENTE') {
                            <button class="btn btn-sm btn-outline-success me-1" (click)="approuver(dc)" [title]="'governance.approve' | transloco">
                              <i class="bi bi-check-lg"></i>
                            </button>
                            <button class="btn btn-sm btn-outline-danger me-1" (click)="rejeter(dc)" [title]="'governance.reject' | transloco">
                              <i class="bi bi-x-lg"></i>
                            </button>
                          }
                          <button class="btn btn-sm btn-outline-danger" (click)="deleteChange(dc)"
                                  [title]="'common.delete' | transloco" [attr.aria-label]="'governance.deleteRequest' | transloco">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="6">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-arrow-repeat"></i></div>
                        <div class="es-title">{{ 'governance.noRequests' | transloco }}</div>
                        <div class="es-desc">{{ 'governance.noRequestsDesc' | transloco }}</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openChangeModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'governance.newRequest' | transloco }}</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        <!-- STAKEHOLDERS TAB (parties prenantes): the people around the project, each with
             an influence level and an interest level. Those two levels are the classic
             "power / interest" grid used to decide how often each person is informed. -->
        @if (govTab() === 'parties') {
          <div class="card">
            <div class="card-header justify-content-between">
              <span>{{ 'governance.stakeholders' | transloco }} — {{ selected()!.name }}</span>
              @if (canManage()) {
                <button class="btn btn-primary btn-sm" (click)="openPartieModal()">
                  <i class="bi bi-plus-lg me-1"></i>{{ 'governance.addStakeholder' | transloco }}
                </button>
              }
            </div>
            <div class="table-responsive">
              <table class="table table-hover mb-0 align-middle">
                <thead>
                  <tr><th>{{ 'common.name' | transloco }}</th><th>{{ 'governance.colFunction' | transloco }}</th><th>{{ 'common.email' | transloco }}</th><th>{{ 'governance.colPhone' | transloco }}</th><th>{{ 'governance.colInfluence' | transloco }}</th><th>{{ 'governance.colInterest' | transloco }}</th><th></th></tr>
                </thead>
                <tbody>
                  @for (pp of parties(); track pp.id) {
                    <tr>
                      <td class="fw-semibold">{{ pp.nom }}</td>
                      <td>{{ pp.fonction ?? '—' }}</td>
                      <td class="small">{{ pp.email ?? '—' }}</td>
                      <td class="small">{{ pp.telephone ?? '—' }}</td>
                      <td><span [class]="niveauBadge(pp.influence)">{{ 'riskLevel.' + pp.influence | transloco }}</span></td>
                      <td><span [class]="niveauBadge(pp.interet)">{{ 'riskLevel.' + pp.interet | transloco }}</span></td>
                      <td class="text-end">
                        @if (canManage()) {
                          <button class="btn btn-sm btn-outline-danger" (click)="deletePartie(pp)"
                                  [title]="'common.delete' | transloco" [attr.aria-label]="'governance.deleteStakeholder' | transloco">
                            <i class="bi bi-trash"></i>
                          </button>
                        } @else { <span class="text-muted">—</span> }
                      </td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="7">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-person-lines-fill"></i></div>
                        <div class="es-title">{{ 'governance.noStakeholders' | transloco }}</div>
                        <div class="es-desc">{{ 'governance.noStakeholdersDesc' | transloco }}</div>
                        @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openPartieModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'governance.addStakeholder' | transloco }}</button> }
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      }
    </div>

    <!-- Stakeholder creation modal. The four modals below all follow the same shape:
         a backdrop, a dialog, a body of fields bound with [(ngModel)], an error line fed by
         modalError(), and a save button disabled while saving() is true. -->
    @if (showPartieModal()) {
      <div class="modal-backdrop fade show"></div>
      <!-- Clicking the grey area closes the modal. (click) on the inner dialog calls
           $event.stopPropagation() so a click INSIDE the form does not bubble up to the
           outer handler. Without that line, typing in a field and releasing the mouse would
           close the modal and throw away what the user had entered. -->
      <div class="modal d-block" tabindex="-1" (click)="showPartieModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'governance.addStakeholder' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showPartieModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'common.name' | transloco }} <span class="text-danger">*</span></label>
                <!-- [(ngModel)] is two-way binding: what the user types goes straight into
                     partieForm.nom, and a change in partieForm.nom (openPartieModal resets
                     the object) clears the box. The placeholder is written as a BINDING,
                     [placeholder]="'...' | transloco", so the grey hint text is translated
                     too. Without the binding the hint would stay in one language while the
                     rest of the form follows the language chosen by the user. -->
                <input type="text" class="form-control" [(ngModel)]="partieForm.nom" [placeholder]="'governance.fullNamePh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.colFunction' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="partieForm.fonction" [placeholder]="'governance.functionPh' | transloco">
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'common.email' | transloco }}</label>
                  <input type="email" class="form-control" [(ngModel)]="partieForm.email" placeholder="email@example.com">
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colPhone' | transloco }}</label>
                  <input type="text" class="form-control" [(ngModel)]="partieForm.telephone" placeholder="+216 ...">
                </div>
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colInfluence' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="partieForm.influence">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colInterest' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="partieForm.interet">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
              </div>
              <!-- The error is shown inside the modal, next to the fields, not as a toast.
                   Why: the user must fix a field, so the message has to stay on screen
                   beside the form instead of fading away after three seconds. -->
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showPartieModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <!-- [disabled]="saving()" blocks a second click while the POST is travelling.
                   Without it, an impatient double click would create the same stakeholder
                   twice, and the register would show two identical rows. -->
              <button class="btn btn-primary" (click)="savePartie()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Risk creation modal. Probability, impact and status are <select> lists whose option
         values are exactly the enum values expected by the server (FAIBLE / MOYEN / ELEVE,
         OUVERT / MITIGE / FERME). Free text here would be rejected by the backend. -->
    @if (showRiskModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showRiskModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'governance.addRisk' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showRiskModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'common.description' | transloco }} <span class="text-danger">*</span></label>
                <textarea class="form-control" rows="2" [(ngModel)]="riskForm.description" [placeholder]="'governance.riskDescPh' | transloco"></textarea>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colProbability' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="riskForm.probabilite">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colImpact' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="riskForm.impact">
                    <option value="FAIBLE">{{ 'riskLevel.FAIBLE' | transloco }}</option>
                    <option value="MOYEN">{{ 'riskLevel.MOYEN' | transloco }}</option>
                    <option value="ELEVE">{{ 'riskLevel.ELEVE' | transloco }}</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.colMitigation' | transloco }}</label>
                <textarea class="form-control" rows="2" [(ngModel)]="riskForm.planMitigation" [placeholder]="'governance.mitigationPh' | transloco"></textarea>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'common.status' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="riskForm.statut">
                  <option value="OUVERT">{{ 'riskStatus.OUVERT' | transloco }}</option>
                  <option value="MITIGE">{{ 'riskStatus.MITIGE' | transloco }}</option>
                  <option value="FERME">{{ 'riskStatus.FERME' | transloco }}</option>
                </select>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showRiskModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveRisk()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Deliverable creation modal. There is no status field: a new deliverable always
         starts at EN_ATTENTE, and the state is changed afterwards with the three action
         buttons in the table. Why: this stops someone from creating a row that is already
         "approved" without anybody having done the work. -->
    @if (showLivrableModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showLivrableModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'governance.addDeliverable' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showLivrableModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.colTitle' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="livrableForm.titre" [placeholder]="'governance.deliverableTitlePh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'common.description' | transloco }}</label>
                <textarea class="form-control" rows="2" [(ngModel)]="livrableForm.description" [placeholder]="'governance.optionalDescPh' | transloco"></textarea>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.dueDateLabel' | transloco }}</label>
                <input type="date" class="form-control" [(ngModel)]="livrableForm.dateEcheance">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showLivrableModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveLivrable()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Change request creation modal. The requester is picked from the project TEAM, not
         from all users of the application. Why: only somebody who works on the project can
         ask for a change on it, and the list stays short enough for a dropdown. -->
    @if (showChangeModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showChangeModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'governance.newChangeRequest' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showChangeModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.colTitle' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="changeForm.titre" [placeholder]="'governance.requestTitlePh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'governance.colRequester' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="changeForm.demandeurId">
                  <!-- A first option marked disabled acts as the "please choose" hint. It
                       cannot be selected again once the user has picked a real member, so an
                       empty requester can never be sent back on purpose. -->
                  <option [value]="0" disabled>{{ 'governance.selectMember' | transloco }}</option>
                  <!-- track m.userId: the identity of a member row is its user id, so the
                       options are not rebuilt each time the list is reloaded. -->
                  @for (m of teamMembers(); track m.userId) {
                    <option [value]="m.userId">{{ m.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">{{ 'common.description' | transloco }}</label>
                <textarea class="form-control" rows="2" [(ngModel)]="changeForm.description" [placeholder]="'governance.optionalDescPh' | transloco"></textarea>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'governance.colPriority' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="changeForm.priorite">
                    <option value="FAIBLE">{{ 'changePriority.FAIBLE' | transloco }}</option>
                    <option value="NORMALE">{{ 'changePriority.NORMALE' | transloco }}</option>
                    <option value="ELEVEE">{{ 'changePriority.ELEVEE' | transloco }}</option>
                    <option value="CRITIQUE">{{ 'changePriority.CRITIQUE' | transloco }}</option>
                  </select>
                </div>
                <div class="col-6">
                  <label class="form-label fw-semibold">{{ 'common.date' | transloco }} <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="changeForm.dateDemande">
                </div>
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showChangeModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveChange()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
/*
 * The component class: it holds the state of the screen and every action the template can
 * fire. It implements OnInit so the first load happens after Angular has created the
 * component, not inside a constructor.
 */
export class GovernanceComponent implements OnInit {
  /*
   * Dependencies are taken with inject() instead of constructor parameters. Why: it reads
   * better with many services, and 'readonly' makes clear that none of them is ever
   * replaced later. Behaviour is exactly the same as a constructor injection.
   */
  private readonly projectSvc = inject(ProjectService);
  private readonly govSvc     = inject(GovernanceService);
  private readonly teamSvc    = inject(TeamService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  /*
   * True when the logged-in user holds the MANAGE_GOVERNANCE permission. Used all over the
   * template to show or hide the write buttons.
   * Why a permission CODE and not a role name: roles are created by an administrator at
   * runtime, so a role name in the code would break the day a new role is invented. Testing
   * the permission keeps the front end working whatever the roles are called.
   * Reminder: this is display only. The server refuses the call anyway with
   * @PreAuthorize("hasAuthority('MANAGE_GOVERNANCE')") on the service method plus the
   * project-scope check of ProjectScopeInterceptor (ADR-021).
   */
  canManage = () => this.auth.hasPermission('MANAGE_GOVERNANCE');

  /*
   * All the screen state is kept in signals. A signal is a value that tells Angular when it
   * changes, so only the parts of the template that read it are redrawn. Without signals the
   * whole page would be re-checked on every click, which is slow on four big tables.
   */
  projects = signal<Project[]>([]);
  // The project currently being looked at, or null when the picker is still showing.
  selected = signal<Project | null>(null);
  // Which of the four tabs is open. It is also written into the URL, see setGovTab().
  govTab = signal<GovTab>('risks');
  // The four registers. They are filled together by reload() so the counts in the tab
  // labels are correct even for a tab the user has not opened yet.
  risks = signal<Risk[]>([]);
  livrables = signal<Livrable[]>([]);
  changes = signal<DemandeChangement[]>([]);
  parties = signal<PartiePrenante[]>([]);
  // The project team, reduced to the two fields the requester dropdown needs. Why reduce it:
  // the template only shows a name, so keeping the whole member object would let a careless
  // change print data that has nothing to do with this screen.
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  // One flag per modal. Separate flags rather than one "which modal is open" value: two
  // modals are never opened together here, and separate booleans keep each @if simple.
  showRiskModal = signal(false);
  showLivrableModal = signal(false);
  showChangeModal = signal(false);
  showPartieModal = signal(false);
  // True while a POST is travelling. It disables the save button, so a double click cannot
  // create the same row twice.
  saving = signal(false);
  // The message shown inside the open modal, either a missing-field message written here or
  // the message sent back by the server.
  modalError = signal('');

  /*
   * The four form objects. They are plain objects, not signals: [(ngModel)] writes into them
   * directly, and nothing in the template has to react to a keystroke. Each open*Modal()
   * REPLACES the whole object with a fresh one, which is how the form is cleared between two
   * uses. Without that reset, opening the modal again would show what was typed last time.
   */
  riskForm = { description: '', probabilite: 'MOYEN', impact: 'MOYEN', planMitigation: '', statut: 'OUVERT' };
  livrableForm = { titre: '', description: '', dateEcheance: '' };
  // dateDemande defaults to today. new Date().toISOString() gives "2026-09-19T08:30:00.000Z"
  // and split('T')[0] keeps "2026-09-19", which is the exact format an <input type="date">
  // expects. Without the split the date box would stay empty, because it refuses the full
  // timestamp. Note this uses UTC, so very late in the evening the suggested day can be
  // tomorrow; the user can still change it.
  changeForm = { demandeurId: 0, titre: '', description: '', priorite: 'NORMALE', dateDemande: new Date().toISOString().split('T')[0] };
  partieForm = { nom: '', fonction: '', email: '', telephone: '', influence: 'MOYEN', interet: 'MOYEN' };

  /*
   * Runs once, when the screen is created. It loads the list of projects, then reads the URL
   * to know which project and which tab must be shown.
   * WHY IT IS WRITTEN THIS WAY: the URL is the source of truth, not a variable in memory.
   * The projects are fetched FIRST and the URL is read INSIDE that answer, because the id in
   * the URL has to be matched against a real project object. Reading both at the same time
   * would sometimes find an empty list and show the picker even though the URL named a
   * project. The result: pasting /governance?p=12&tab=changes in a new browser tab, or
   * reloading the page with F5, lands on exactly the same view.
   */
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      // queryParamMap is a stream: it fires now AND every later time the query string
      // changes, including when select() or setGovTab() navigates. That is what keeps the
      // screen and the browser back button in agreement.
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        // Anything unexpected in ?tab= falls back to 'risks'. The `as GovTab` only tells the
        // compiler to trust us; it checks nothing at runtime. A hand-typed ?tab=xyz would
        // simply show no tab content, never crash.
        const tab = (params.get('tab') as GovTab) || 'risks';
        this.govTab.set(tab);
        // No ?p= means "no project chosen": go back to the picker.
        if (!pid) { this.selected.set(null); return; }
        // The query parameter is always a string, the project id is a number, so the id is
        // turned into a string before comparing. Without String(), 12 === "12" is false and
        // no project would ever be found.
        const project = list.find(p => String(p.id) === pid);
        // The second half of the test stops useless work: if the URL names the project that
        // is already on screen (for example after setGovTab only changed the tab), the four
        // registers are not fetched again.
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.reload();
          // The team is fetched apart from the four registers because it is not a governance
          // register: it only feeds the "requester" dropdown of a change request. .map()
          // keeps just the id and the name.
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
          );
        }
      });
    });
  }

  /*
   * Called when the user picks a project in <app-project-picker>. It stores the project,
   * writes it into the URL, and loads the data.
   * Why the state is set here AND written to the URL: the signal makes the screen react at
   * once, the URL makes the choice survive a reload or a copy-paste of the link. replaceUrl
   * is false on purpose, so choosing a project adds a history entry and the browser back
   * button takes the user back to the picker.
   */
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id, tab: this.govTab() }, replaceUrl: false });
    this.reload();
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  /*
   * The back button of the breadcrumb: empties the query string, which brings the picker
   * back. Note that selected() is NOT set to null here. It does not need to be: emptying the
   * URL makes queryParamMap fire in ngOnInit, and that handler does the reset. Keeping a
   * single place that clears the state avoids the two ways of doing it drifting apart.
   */
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  /*
   * Switches tab and remembers the choice in the URL.
   * replaceUrl is TRUE here, unlike in select(). Why: changing tab replaces the current
   * history entry instead of adding one. Without it, a user who clicked through the four
   * tabs would have to press the back button four times to leave the page.
   */
  setGovTab(tab: GovTab): void {
    this.govTab.set(tab);
    if (this.selected()) {
      this.router.navigate([], { queryParams: { p: this.selected()!.id, tab }, replaceUrl: true });
    }
  }

  /*
   * Re-reads the four registers of the selected project. Every write action calls it once
   * the server has answered.
   * Why re-read everything instead of changing the local array by hand: the server may add
   * or compute fields (dates of decision, derived status), so re-reading is the only way to
   * be sure the screen shows what is really stored. The four calls are fired together, not
   * one after the other, so the browser runs them in parallel and the page is ready sooner.
   */
  reload(): void {
    const p = this.selected();
    // Guard: without a project there is no /api/projects/{id} to call. Without this line a
    // call to reload() just after clearSelection() would blow up on p.id being undefined.
    if (!p) return;
    this.govSvc.listRisks(p.id).subscribe(d => this.risks.set(d));
    this.govSvc.listLivrables(p.id).subscribe(d => this.livrables.set(d));
    this.govSvc.listChanges(p.id).subscribe(d => this.changes.set(d));
    this.govSvc.listParties(p.id).subscribe(d => this.parties.set(d));
  }

  // ── Stakeholders (parties prenantes) ─────────────────────────────

  /*
   * Opens the stakeholder modal on an empty form.
   * The form object is replaced, not emptied field by field, and modalError is cleared.
   * Without clearing the error, an old message such as "name required" would still be on
   * screen the next time the modal is opened, on a form that is in fact fine.
   */
  openPartieModal(): void {
    this.partieForm = { nom: '', fonction: '', email: '', telephone: '', influence: 'MOYEN', interet: 'MOYEN' };
    this.modalError.set('');
    this.showPartieModal.set(true);
  }

  /*
   * Sends the new stakeholder to POST /api/projects/{id}/parties-prenantes.
   * Steps: check the one mandatory field, lock the save button, call the server, and on
   * success re-read the register, close the modal and show a green toast.
   * The check on the name is only there to save a round trip and give an instant message;
   * the server validates again, because anybody can call the API without this screen.
   */
  savePartie(): void {
    if (!this.partieForm.nom) { this.modalError.set(this.tr.translate('governance.errNameRequired')); return; }
    this.saving.set(true);
    // selected()! : the "!" tells the compiler the project cannot be null here. It is true
    // because the modal can only be opened from inside the @if (selected()) block of the
    // template. It is an assertion for the compiler, not a runtime check.
    this.govSvc.createPartie(this.selected()!.id, this.partieForm).subscribe({
      next: () => { this.reload(); this.showPartieModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('governance.okStakeholderAdded')); },
      // e.error?.message : the "?." stops the code if e.error is missing, which happens when
      // the browser could not reach the server at all. Without it, a network failure would
      // throw "cannot read property message of undefined" and the save button would stay
      // locked for ever. ?? gives a fallback text when the server sent no message.
      // Note the modal stays OPEN on error, so the user does not lose what he typed.
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /*
   * Deletes one stakeholder, after an explicit confirmation.
   * 'async' + 'await' on confirm.ask(): the dialog returns a promise that resolves to true
   * or false, and the method simply stops when the user says no. Why a confirmation at all:
   * the deletion cannot be undone from this screen.
   * The second argument of ask() is the dialog title; the first is the question, built with
   * the row name so the user sees WHICH stakeholder he is about to remove.
   */
  async deletePartie(pp: PartiePrenante): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('governance.confirmDeleteStakeholder', { name: pp.nom }), this.tr.translate('governance.deleteStakeholder'))) return;
    this.govSvc.deletePartie(this.selected()!.id, pp.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okStakeholderDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Risks (risques) ──────────────────────────────────────────────

  // Opens the risk modal on a fresh form. The defaults (MOYEN / MOYEN / OUVERT) are the
  // most common case, so the user usually only has to type the description.
  openRiskModal(): void {
    this.riskForm = { description: '', probabilite: 'MOYEN', impact: 'MOYEN', planMitigation: '', statut: 'OUVERT' };
    this.modalError.set('');
    this.showRiskModal.set(true);
  }

  /*
   * Sends the new risk to POST /api/projects/{id}/risks, then refreshes the register.
   * Same shape as savePartie(): guard, lock, call, refresh, toast.
   */
  saveRisk(): void {
    // Only the description is mandatory; probability, impact and status always have a value
    // because they come from <select> lists that start on a default.
    if (!this.riskForm.description) { this.modalError.set('Description requise.'); return; }
    this.saving.set(true);
    this.govSvc.createRisk(this.selected()!.id, this.riskForm).subscribe({
      next: () => { this.reload(); this.showRiskModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('governance.okRiskSaved')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // Deletes one risk after confirmation. The question here does not name the risk, because a
  // risk has no short title, only a long description.
  async deleteRisk(r: Risk): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('governance.confirmDeleteRisk'), this.tr.translate('governance.deleteRisk'))) return;
    this.govSvc.deleteRisk(this.selected()!.id, r.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okRiskDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Deliverables (livrables) ─────────────────────────────────────

  // Opens the deliverable modal on a fresh form. No status field: see the modal comment.
  openLivrableModal(): void {
    this.livrableForm = { titre: '', description: '', dateEcheance: '' };
    this.modalError.set('');
    this.showLivrableModal.set(true);
  }

  /*
   * Creates a deliverable with POST /api/projects/{id}/livrables. The body carries only the
   * title, the description and the due date: no status is sent, so the starting status is
   * decided by the server. reload() right after brings back the row with that status.
   */
  saveLivrable(): void {
    if (!this.livrableForm.titre) { this.modalError.set('Titre requis.'); return; }
    this.saving.set(true);
    this.govSvc.createLivrable(this.selected()!.id, this.livrableForm).subscribe({
      next: () => { this.reload(); this.showLivrableModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('governance.okDeliverableCreated')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /*
   * The three state changes below. Each one is a PATCH on its own sub-URL
   * (/livrables/{id}/demarrer, /livrer, /valider) instead of a PUT that would send the whole
   * object with a new status.
   * Why: the step is named, so the server can check that the move is allowed and can record
   * who did it. With a plain "set status" call, a client could jump straight from EN_ATTENTE
   * to VALIDE and the history of the deliverable would be a lie.
   * No confirmation dialog here, unlike the deletes: these are ordinary day-to-day steps,
   * and asking "are you sure?" on every one of them would slow the user down for nothing.
   */
  // EN_ATTENTE -> EN_COURS: work on the deliverable has started.
  demarrerLivrable(l: Livrable): void {
    this.govSvc.demarrerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okDeliverableStarted')); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  // EN_COURS -> LIVRE: the deliverable has been handed over to the client.
  livrerLivrable(l: Livrable): void {
    this.govSvc.livrerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okDeliverableDelivered')); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  // LIVRE -> VALIDE: the client has accepted it. This is the last step.
  validerLivrable(l: Livrable): void {
    this.govSvc.validerLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okDeliverableApproved')); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  // Deletes one deliverable after confirmation. The question repeats the title, so the user
  // can see which row he is removing before he says yes.
  async deleteLivrable(l: Livrable): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('governance.confirmDeleteDeliverable', { name: l.titre }), this.tr.translate('governance.deleteDeliverable'))) return;
    this.govSvc.deleteLivrable(this.selected()!.id, l.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okDeliverableDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Change requests (demandes de changement) ──────────────────────────────────────────────────
  // Opens the change-request modal on a fresh form, with today's date pre-filled and no
  // requester chosen yet (demandeurId 0 means "nothing picked").
  openChangeModal(): void {
    this.changeForm = { demandeurId: 0, titre: '', description: '', priorite: 'NORMALE', dateDemande: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    this.showChangeModal.set(true);
  }

  /*
   * Creates a change request with POST /api/projects/{id}/demandes-changement.
   * Two fields are mandatory: the title and the requester. The requester matters because the
   * register has to show WHO asked for the change when the decision is discussed later.
   */
  saveChange(): void {
    // demandeurId is 0 while nothing is picked, and 0 is falsy, so this one test covers both
    // "never chosen" and "empty". Without it the server would receive a request with no
    // author and the table would show a blank requester column.
    if (!this.changeForm.titre || !this.changeForm.demandeurId) {
      this.modalError.set(this.tr.translate('governance.errTitleRequester'));
      return;
    }
    this.saving.set(true);
    this.govSvc.createChangement(this.selected()!.id, this.changeForm).subscribe({
      next: () => { this.reload(); this.showChangeModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('governance.okRequestCreated')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /*
   * Approves a change request: PATCH /demandes-changement/{id}/approuver.
   * Like the deliverable steps, the decision has its own named endpoint rather than a
   * generic status update, so the server can refuse a second decision and can store who
   * decided and when.
   */
  approuver(dc: DemandeChangement): void {
    this.govSvc.approuverChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okRequestApproved')); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  // Rejects a change request: PATCH /demandes-changement/{id}/rejeter. Mirror of approuver().
  rejeter(dc: DemandeChangement): void {
    this.govSvc.rejeterChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okRequestRejected')); },
      error: () => this.toast.error('Action impossible.')
    });
  }

  // Deletes a change request after confirmation, whatever its status.
  async deleteChange(dc: DemandeChangement): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('governance.confirmDeleteRequest', { name: dc.titre }), this.tr.translate('governance.deleteRequest'))) return;
    this.govSvc.deleteChangement(this.selected()!.id, dc.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('governance.okRequestDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Badge helpers: turn a server enum value into a CSS class ───────────────────────────────────────────────────────
  /*
   * Turns a risk level into the name of a CSS badge class, so the table shows a colour
   * instead of a bare word: ELEVE -> red, MOYEN -> orange, FAIBLE -> green.
   * Why a method and not a class in the template: the same three levels are used by the risk
   * probability, the risk impact, and the influence and interest of a stakeholder. One
   * method means the colours can never disagree between those four columns.
   * The parameter is typed NiveauRisque, not string, so the compiler refuses any value that
   * is not one of the three levels.
   */
  niveauBadge(n: NiveauRisque): string {
    return n === 'ELEVE' ? 'badge-cancelled' : n === 'MOYEN' ? 'badge-on-hold' : 'badge-active';
  }

  /*
   * Same idea for the deliverable status, but written as a lookup table.
   * Why a map instead of a chain of ternaries: there are four values here, and a map stays
   * readable if a fifth status is added later.
   * The ?? 'badge-draft' at the end is the safety net: if the server ever returns a status
   * this screen does not know, the cell shows a neutral grey badge instead of the row losing
   * its badge class and looking broken.
   */
  livrableBadge(s: string): string {
    const m: Record<string, string> = {
      EN_ATTENTE: 'badge-draft', EN_COURS: 'badge-active',
      LIVRE: 'badge-completed', VALIDE: 'badge-active'
    };
    return m[s] ?? 'badge-draft';
  }

  /*
   * The colour of a change-request priority, from green (FAIBLE) to red (CRITIQUE), with the
   * same fallback as livrableBadge() for an unknown value.
   */
  prioriteBadge(p: string): string {
    const m: Record<string, string> = {
      FAIBLE: 'badge-active', NORMALE: 'badge-draft',
      ELEVEE: 'badge-on-hold', CRITIQUE: 'badge-cancelled'
    };
    return m[p] ?? 'badge-draft';
  }
}
