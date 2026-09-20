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
 * Billing screen for one project: milestones (jalons), amendments (avenants), and the four
 * modals that create them, invoice a milestone, or record a payment.
 * Status flow PREVU -> FACTURE -> PAYE is decided by the server only; canManage() just hides
 * buttons (real enforcement is @PreAuthorize('MANAGE_BILLING') + ADR-021 project scope).
 */

// Which of the four windows is open, or null. One signal for all four so two can never be
// open at once.
type BillingModal = 'jalon' | 'avenant' | 'facturer' | 'paiement' | null;
// Shared sort direction; string union (not boolean) matches what aria-sort expects directly.
type SortDir = 'asc' | 'desc';
// Sortable milestone columns. A union (not plain string) so a typo like 'montnat' fails the
// build instead of silently breaking the sort. statut and dateFacture are not sortable.
type JalonSortCol = 'label' | 'pourcentage' | 'montant' | 'datePrevue';
// Sortable amendment columns (see core/models/billing.model.ts for the Avenant fields).
type AvenantSortCol = 'numero' | 'montant' | 'dateAvenant';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  // Scoped styles for the sortable-header look only; everything else comes from styles.scss.
  styles: [`
    /* Design-system colour var so the delete tint still reads correctly in dark theme. */
    .act-danger { color: var(--c-danger); }
    /* user-select:none stops repeated clicks (to flip sort) from highlighting the text. */
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    /* Reversed on numeric columns so the arrow stays next to the text, not the cell border. */
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    /* Caret keeps its space even when hidden (opacity, not display:none) to avoid text jump. */
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    /* focus-visible (not focus) so the ring shows for keyboard users only, not mouse clicks. */
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
  `],
  template: `
    <!-- @if removes the false branch entirely, so selected()!.code is never read on null. -->
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
      <!-- Shared picker reused per project rule: no plain dropdown for a large project list. -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-receipt-cutoff"
                            (projectSelected)="select($event)" />
      </div>

      <!-- Guards the tables from reading selected()!.name while no project is chosen. -->
      @if (selected()) {
        <div class="row g-4">
          <!-- MILESTONES TABLE (jalons) - the billing plan of the project. -->
          <div class="col-12">
            <div class="card">
              <div class="card-header justify-content-between">
                <span><i class="bi bi-list-check me-2"></i>{{ 'billing.milestones' | transloco }} — {{ selected()!.name }}</span>
                <div class="d-flex align-items-center gap-3">
                  <!-- jalonTotal() sums EVERY milestone, including ones still only planned. -->
                  <span class="text-muted small">{{ 'billing.totalInvoiced' | transloco }} {{ jalonTotal() | number:'1.0-0' }} TND</span>
                  <!-- Comfort only: hides the button for a read-only user; server enforces it. -->
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
                    <!-- aria-sort informs screen readers; tabindex+keydown make sort keyboard
                         accessible; preventDefault on Space stops the page from scrolling too. -->
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
                    <!-- track j.id: without it, reload()'s fresh array would make Angular
                         rebuild every row instead of diffing, losing scroll position. -->
                    @for (j of sortedJalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }} TND</td>
                        <!-- Both dates are optional on the model; ?? '—' avoids a blank cell. -->
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>{{ j.dateFacture ?? '—' }}</td>
                        <td>
                          <!-- Badge colour follows the server-sent status; never decided here. -->
                          @if (j.statut === 'PAYE') { <span class="badge-active">{{ 'milestoneStatus.PAYE' | transloco }}</span> }
                          @else if (j.statut === 'FACTURE') { <span class="badge-completed">{{ 'milestoneStatus.FACTURE' | transloco }}</span> }
                          @else { <span class="badge-draft">{{ 'milestoneStatus.PREVU' | transloco }}</span> }
                        </td>
                        <td class="text-end text-nowrap">
                          @if (canManage()) {
                            <!-- Only PREVU offers "invoice"; other statuses offer payment
                                 instead, so a user can't try to pay an unissued invoice. -->
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
                    <!-- @empty: without it, a project with no plan yet would look like a
                         failed load instead of an empty one. -->
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

          <!-- AMENDMENTS TABLE (avenants). Kept separate from the budget so the revised
               figure stays explainable line by line in front of the client. -->
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
                        <!-- An amendment CAN be negative (scope removed); colour marks that
                             so it doesn't read as a gain at a glance. -->
                        <td class="text-end"
                            [class.text-success]="a.montant >= 0"
                            [class.text-danger]="a.montant < 0">
                          {{ a.montant | number:'1.0-0' }}
                        </td>
                        <!-- 0 is treated as missing too (no extra day), not printed as "0.0". -->
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
         All four windows share the same skeleton: backdrop, click-outside closes it,
         stopPropagation on the dialog so an inside click doesn't close it too. Hand-written
         (not Bootstrap JS) so open/closed state stays an Angular-controlled signal. -->
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
              <!-- Plain object + [(ngModel)], not a reactive FormGroup: too few fields to
                   justify one, all checked in a single "if" in the save method. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.colLabel' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="jalonForm.label" [placeholder]="'billing.milestoneLabelPh' | transloco">
              </div>
              <!-- A PERCENTAGE is entered, never an amount; the server derives the money so
                   it can't drift from a revised budget. min/max are a hint only; server checks it. -->
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
              <!-- [disabled]="saving()" blocks a double click from creating the milestone
                   twice (same pattern in the other three windows). -->
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
              <!-- Optional: some amendments only change money or scope. step="0.5" since
                   days are sold in halves. -->
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

    <!-- WINDOW 3 of 4: invoice a milestone (PREVU -> FACTURE). Only the date is sent; the
         status move itself is the server's decision. -->
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
              <!-- ?. guards against currentJalon() being null: the window is driven by
                   modal(), not by currentJalon() itself. -->
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

    <!-- WINDOW 4 of 4: record money received. Lists past payments too, since a client may
         pay in instalments and the user needs to see what's already in to avoid duplicates. -->
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
              <!-- Skipped entirely when empty, so a first payment doesn't show a bare heading. -->
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
              <!-- May be smaller than the invoice. step="0.01" allows bank-transfer centimes. -->
              <div class="mb-3">
                <label class="form-label">{{ 'billing.amountReceived' | transloco }} <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="paiementForm.montantRecu" min="0" step="0.01">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'billing.paymentDate' | transloco }} <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="paiementForm.datePaiement">
              </div>
              <!-- Optional, but lets an auditor match this row to the bank statement later. -->
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
// Initial load happens in ngOnInit(), not the constructor, since services/route aren't
// fully ready at construction time and constructor HTTP calls are harder to test.
export class BillingComponent implements OnInit {
  // inject() instead of constructor args: adding a service needs no constructor rewrite,
  // and fields can be readonly here.
  private readonly projectSvc = inject(ProjectService);
  private readonly billingSvc = inject(BillingService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  // Tests a permission code, never a role name, so a new role can grant MANAGE_BILLING with
  // no code change. Hides buttons only; real enforcement is server-side (@PreAuthorize + ADR-021).
  canManage = () => this.auth.hasPermission('MANAGE_BILLING');

  // Signals (not plain fields) so the template redraws automatically when server data arrives.
  // Full project list, loaded once, used to resolve the "?p=12" URL param to a project.
  projects = signal<Project[]>([]);
  // The project being looked at, or null while the user is still choosing one.
  selected = signal<Project | null>(null);
  // Replaced whole by reload(); nothing here edits a row in place.
  jalons = signal<JalonFacturation[]>([]);
  avenants = signal<Avenant[]>([]);

  // Defaults to planned date, ascending: the next milestone to invoice reads first.
  jalonSortCol = signal<JalonSortCol>('datePrevue');
  jalonSortDir = signal<SortDir>('asc');
  // computed() re-sorts only when jalons()/jalonSortCol()/jalonSortDir() actually change,
  // instead of on every redraw. Copies the array first since Array.sort() mutates in place
  // and would change the signal's value behind Angular's back.
  readonly sortedJalons = computed(() => {
    const col = this.jalonSortCol(), dir = this.jalonSortDir() === 'asc' ? 1 : -1;
    return [...this.jalons()].sort((a, b) => {
      const av = a[col], bv = b[col];
      // Missing dates sort to the bottom regardless of direction, not as an empty string.
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      // localeCompare for text (locale-aware), subtraction for numbers.
      return (typeof av === 'string' ? av.localeCompare(bv as string) : (av as number) - (bv as number)) * dir;
    });
  });

  // Defaults to signature date, ascending, so the list reads like contract history.
  avenantSortCol = signal<AvenantSortCol>('dateAvenant');
  avenantSortDir = signal<SortDir>('asc');
  // Duplicated rather than shared with sortedJalons(): JalonSortCol and AvenantSortCol are
  // different types, and a generic helper would lose the compile-time column-name check.
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

  // One signal for all four windows, so two can never be open at once (see type above).
  modal = signal<BillingModal>(null);
  // Greys out the save button / shows the spinner; blocks a double click sending it twice.
  saving = signal(false);
  // Emptied on every window open so a stale error isn't shown for a new attempt.
  modalError = signal('');

  // The row the invoice/payment windows act on; modal() only says which window is open.
  currentJalon = signal<JalonFacturation | null>(null);
  // Loaded only when the payment window opens, not with the table (would be N+1 requests).
  paiements = signal<Paiement[]>([]);

  // Plain objects, not signals: [(ngModel)] writes directly and nothing needs to redraw
  // per keystroke.
  jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
  // workloadDays typed explicitly as optional so openModal() can set it to undefined.
  avenantForm: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string } = { numero: '', objet: '', montant: 0, dateAvenant: '' };
  // ISO date for <input type="date">. Note: toISOString() is UTC, so right after local
  // midnight this can pre-fill as the day before; the user can correct it before saving.
  facturerDate = new Date().toISOString().split('T')[0];
  paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };

  // Loads projects first, then reads the URL's "?p=" — nested on purpose, since the id
  // in the URL can't resolve to a project until the list has arrived. Watching the URL
  // (not just clicks) also makes the screen bookmarkable and makes Back work correctly.
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      // Emits now and again on every later URL change, including select()/clearSelection().
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        // No project in the URL: this is the picker page. Emptying selected() here is what
        // makes the "back" button of the breadcrumb work, since it only changes the URL.
        if (!pid) { this.selected.set(null); return; }
        // p.id is numeric, so it's stringified for the comparison against the URL's text.
        const project = list.find(p => String(p.id) === pid);
        // Skips reloading a project that's already displayed (avoids a duplicate reload
        // right after select() triggers this same callback via the URL change).
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.reload();
        }
      });
    });
  }

  // Shows the project immediately, writes it to the URL (bookmarkable), then loads its data.
  // replaceUrl: false keeps the previous project in history so Back returns to it.
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.reload();
  }

  // Only clears the URL; the queryParamMap watcher in ngOnInit empties selected() from that.
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  // Re-fetches rather than patching the array in place: the server computes the milestone
  // amount and may move its status, so a browser-built row could show a figure it never agreed to.
  reload(): void {
    const p = this.selected();
    // Guard: reload() can be called just after the user returns to the picker.
    if (!p) return;
    this.billingSvc.listJalons(p.id).subscribe(d => this.jalons.set(d));
    this.billingSvc.listAvenants(p.id).subscribe(d => this.avenants.set(d));
  }

  // Sums EVERY milestone regardless of status — the full billing plan, not just invoiced.
  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  // Clicking the active column flips direction; clicking another resets to ascending, since
  // the user has no way to know which direction the previous column was on.
  toggleJalonSort(col: JalonSortCol): void {
    if (this.jalonSortCol() === col) this.jalonSortDir.set(this.jalonSortDir() === 'asc' ? 'desc' : 'asc');
    else { this.jalonSortCol.set(col); this.jalonSortDir.set('asc'); }
  }
  // Screen-reader announcement of sort state; return type limited to the three HTML values.
  ariaJalonSort(col: JalonSortCol): 'ascending' | 'descending' | 'none' {
    if (this.jalonSortCol() !== col) return 'none';
    return this.jalonSortDir() === 'asc' ? 'ascending' : 'descending';
  }
  // Every header keeps a caret (invisible until hover) so users know it's clickable at all.
  caretJalon(col: JalonSortCol): string {
    if (this.jalonSortCol() !== col) return 'bi-chevron-expand';
    return this.jalonSortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

  // Same three helpers for the amendments table, kept separate so the two tables sort independently.
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

  // Resets both forms and the error even though only one window opens, so a cancelled edit
  // never resurfaces stale text later. openFacturer()/openPaiement() are separate — they need
  // the clicked row.
  openModal(type: BillingModal): void {
    this.jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
    this.avenantForm = { numero: '', objet: '', montant: 0, workloadDays: undefined, dateAvenant: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    // Set last, so the window is only drawn once the fields behind it are already clean.
    this.modal.set(type);
  }

  // The local check is comfort only; the real rules (100% cap, unique label) live server-side.
  saveJalon(): void {
    // !pourcentage also rejects 0, which is correct: a milestone worth nothing isn't one.
    if (!this.jalonForm.label || !this.jalonForm.pourcentage) {
      this.modalError.set(this.tr.translate('billing.errLabelPct'));
      return;
    }
    this.saving.set(true);
    this.modalError.set('');
    const body = {
      label: this.jalonForm.label,
      pourcentage: this.jalonForm.pourcentage,
      // || undefined turns an empty date input into "not sent" rather than an invalid "".
      datePrevue: this.jalonForm.datePrevue || undefined
    };
    // "!" is safe: this is only reachable from inside the @if (selected()) block.
    this.billingSvc.createJalon(this.selected()!.id, body).subscribe({
      // Reload rather than compute locally: the server owns the real amount.
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okMilestoneAdded')); },
      // Window stays open with the server's message so the user doesn't lose their input.
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // Same shape as saveJalon(). Amount is not required: an amendment can be worth zero money
  // and only add days or change scope.
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

  // await reads ConfirmService.ask()'s answer as a normal line; the delete is only sent if
  // the user confirms. Server also refuses deleting an already-invoiced/paid milestone.
  async deleteJalon(j: JalonFacturation): Promise<void> {
    if (!await this.confirm.ask(
      this.tr.translate('billing.confirmDeleteMilestone', { name: j.label }),
      this.tr.translate('billing.deleteMilestone'))) return;
    this.billingSvc.deleteJalon(this.selected()!.id, j.id).subscribe({
      next: () => { this.reload(); this.toast.success(this.tr.translate('billing.okMilestoneDeleted')); },
      // Toast, since no window is open here to show the error inline.
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Invoicing a milestone (PREVU -> FACTURE) ─────────────────────

  // Resets the date to today on every opening, otherwise a stale date from a previous
  // milestone could get saved unnoticed.
  openFacturer(j: JalonFacturation): void {
    this.currentJalon.set(j);
    this.facturerDate = new Date().toISOString().split('T')[0];
    this.modalError.set('');
    this.modal.set('facturer');
  }

  // Only the date is sent; the PREVU -> FACTURE -> PAYE order is enforced server-side.
  saveFacturer(): void {
    if (!this.facturerDate) { this.modalError.set('Date de facture requise.'); return; }
    this.saving.set(true);
    // Both "!" are safe: project via @if (selected()), milestone via openFacturer() above.
    this.billingSvc.facturer(this.selected()!.id, this.currentJalon()!.id, this.facturerDate).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okMilestoneInvoiced')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Recording money received ─────────────────────────────────────

  // Past payments are fetched only here (not with the table) to avoid an extra request per
  // row; needed because a client may pay in instalments and the user must see what's already in.
  openPaiement(j: JalonFacturation): void {
    this.currentJalon.set(j);
    this.paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };
    this.modalError.set('');
    this.billingSvc.listPaiements(this.selected()!.id, j.id).subscribe(d => this.paiements.set(d));
    this.modal.set('paiement');
  }

  // Full reload() rather than a local badge change: the server decides PAYE once payments
  // cover the amount, and a browser-computed status could disagree with a concurrent update.
  savePaiement(): void {
    // Rejects 0/empty and negative amounts, which would corrupt the server's running total.
    if (!this.paiementForm.montantRecu || this.paiementForm.montantRecu <= 0) {
      this.modalError.set(this.tr.translate('billing.errAmountPositive'));
      return;
    }
    if (!this.paiementForm.datePaiement) { this.modalError.set('Date du paiement requise.'); return; }
    this.saving.set(true);
    this.billingSvc.createPaiement(this.selected()!.id, this.currentJalon()!.id, this.paiementForm).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success(this.tr.translate('billing.okPaymentSaved')); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // Same confirm flow as deleteJalon(); the number is shown since amendments can look alike.
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
