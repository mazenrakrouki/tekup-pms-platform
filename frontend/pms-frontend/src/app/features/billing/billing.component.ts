import { Component, OnInit, signal, computed, inject } from '@angular/core';
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

type BillingModal = 'jalon' | 'avenant' | 'facturer' | 'paiement' | null;
type SortDir = 'asc' | 'desc';
type JalonSortCol = 'label' | 'pourcentage' | 'montant' | 'datePrevue';
type AvenantSortCol = 'numero' | 'montant' | 'dateAvenant';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent],
  styles: [`
    .act-danger { color: var(--c-danger); }
    th.th-sort { cursor:pointer; user-select:none; transition:color var(--t); }
    th.th-sort:hover { color:var(--text-1); }
    th.th-sort .th-inner { display:inline-flex; align-items:center; gap:.3rem; }
    th.th-sort.text-end .th-inner { flex-direction:row-reverse; }
    th.th-sort .caret { font-size:11px; opacity:0; transition:opacity var(--t); }
    th.th-sort:hover .caret { opacity:.4; }
    th.th-sort.is-sorted { color:var(--c-brand); }
    th.th-sort.is-sorted .caret { opacity:1; }
    th.th-sort:focus-visible { outline:2px solid var(--c-brand); outline-offset:-2px; }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-receipt" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" title="Retour à la sélection de projet">
            <i class="bi bi-arrow-left"></i> Facturation
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">Facturation</span>
        }
      </div>
    </div>
    <div class="page-body">
      <!-- Project selector -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureTitle="Facturation"
                            featureIcon="bi-receipt-cutoff"
                            featureDescription="Gérez les jalons de facturation, avenants et paiements de vos projets."
                            (projectSelected)="select($event)" />
      </div>

      @if (selected()) {
        <div class="row g-4">
          <!-- Jalons -->
          <div class="col-12">
            <div class="card">
              <div class="card-header justify-content-between">
                <span><i class="bi bi-list-check me-2"></i>Jalons — {{ selected()!.name }}</span>
                <div class="d-flex align-items-center gap-3">
                  <span class="text-muted small">Total facturé : {{ jalonTotal() | number:'1.0-0' }} TND</span>
                  @if (canManage()) {
                    <button class="btn btn-primary btn-sm" (click)="openModal('jalon')">
                      <i class="bi bi-plus-lg me-1"></i>Ajouter un jalon
                    </button>
                  }
                </div>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <th class="th-sort" [class.is-sorted]="jalonSortCol()==='label'" [attr.aria-sort]="ariaJalonSort('label')"
                          tabindex="0" (click)="toggleJalonSort('label')" (keydown.enter)="toggleJalonSort('label')" (keydown.space)="toggleJalonSort('label'); $event.preventDefault()">
                        <span class="th-inner">Libellé <i class="bi caret" [ngClass]="caretJalon('label')"></i></span>
                      </th>
                      <th class="th-sort text-end" [class.is-sorted]="jalonSortCol()==='pourcentage'" [attr.aria-sort]="ariaJalonSort('pourcentage')"
                          tabindex="0" (click)="toggleJalonSort('pourcentage')" (keydown.enter)="toggleJalonSort('pourcentage')" (keydown.space)="toggleJalonSort('pourcentage'); $event.preventDefault()">
                        <span class="th-inner">% <i class="bi caret" [ngClass]="caretJalon('pourcentage')"></i></span>
                      </th>
                      <th class="th-sort text-end" [class.is-sorted]="jalonSortCol()==='montant'" [attr.aria-sort]="ariaJalonSort('montant')"
                          tabindex="0" (click)="toggleJalonSort('montant')" (keydown.enter)="toggleJalonSort('montant')" (keydown.space)="toggleJalonSort('montant'); $event.preventDefault()">
                        <span class="th-inner">Montant <i class="bi caret" [ngClass]="caretJalon('montant')"></i></span>
                      </th>
                      <th class="th-sort" [class.is-sorted]="jalonSortCol()==='datePrevue'" [attr.aria-sort]="ariaJalonSort('datePrevue')"
                          tabindex="0" (click)="toggleJalonSort('datePrevue')" (keydown.enter)="toggleJalonSort('datePrevue')" (keydown.space)="toggleJalonSort('datePrevue'); $event.preventDefault()">
                        <span class="th-inner">Date prévue <i class="bi caret" [ngClass]="caretJalon('datePrevue')"></i></span>
                      </th>
                      <th>Date facture</th><th>Statut</th><th></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (j of sortedJalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }} TND</td>
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>{{ j.dateFacture ?? '—' }}</td>
                        <td>
                          @if (j.statut === 'PAYE') { <span class="badge-active">Payé</span> }
                          @else if (j.statut === 'FACTURE') { <span class="badge-completed">Facturé</span> }
                          @else { <span class="badge-draft">Prévu</span> }
                        </td>
                        <td class="text-end text-nowrap">
                          @if (canManage()) {
                            @if (j.statut === 'PREVU') {
                              <button class="btn btn-sm btn-outline-primary me-1" (click)="openFacturer(j)" title="Facturer">
                                <i class="bi bi-receipt-cutoff me-1"></i>Facturer
                              </button>
                            } @else {
                              <button class="btn btn-sm btn-outline-success me-1" (click)="openPaiement(j)" title="Enregistrer un paiement">
                                <i class="bi bi-cash-coin me-1"></i>Paiement
                              </button>
                            }
                            <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="deleteJalon(j)"
                                    title="Supprimer" aria-label="Supprimer le jalon">
                              <i class="bi bi-trash"></i>
                            </button>
                          } @else { <span class="text-muted">—</span> }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="7">
                        <div class="empty-state">
                          <div class="es-icon"><i class="bi bi-list-check"></i></div>
                          <div class="es-title">Aucun jalon</div>
                          <div class="es-desc">Découpez le contrat en jalons de facturation.</div>
                          @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openModal('jalon')"><i class="bi bi-plus-lg me-1"></i>Ajouter un jalon</button> }
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Avenants -->
          <div class="col-12">
            <div class="card">
              <div class="card-header justify-content-between">
                <span><i class="bi bi-file-earmark-plus me-2"></i>Avenants — {{ selected()!.name }}</span>
                @if (canManage()) {
                  <button class="btn btn-primary btn-sm" (click)="openModal('avenant')">
                    <i class="bi bi-plus-lg me-1"></i>Ajouter un avenant
                  </button>
                }
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <th class="th-sort" [class.is-sorted]="avenantSortCol()==='numero'" [attr.aria-sort]="ariaAvenantSort('numero')"
                          tabindex="0" (click)="toggleAvenantSort('numero')" (keydown.enter)="toggleAvenantSort('numero')" (keydown.space)="toggleAvenantSort('numero'); $event.preventDefault()">
                        <span class="th-inner">N° <i class="bi caret" [ngClass]="caretAvenant('numero')"></i></span>
                      </th>
                      <th>Objet</th>
                      <th class="th-sort text-end" [class.is-sorted]="avenantSortCol()==='montant'" [attr.aria-sort]="ariaAvenantSort('montant')"
                          tabindex="0" (click)="toggleAvenantSort('montant')" (keydown.enter)="toggleAvenantSort('montant')" (keydown.space)="toggleAvenantSort('montant'); $event.preventDefault()">
                        <span class="th-inner">Montant <i class="bi caret" [ngClass]="caretAvenant('montant')"></i></span>
                      </th>
                      <th class="text-end">Workload (JH)</th>
                      <th class="th-sort" [class.is-sorted]="avenantSortCol()==='dateAvenant'" [attr.aria-sort]="ariaAvenantSort('dateAvenant')"
                          tabindex="0" (click)="toggleAvenantSort('dateAvenant')" (keydown.enter)="toggleAvenantSort('dateAvenant')" (keydown.space)="toggleAvenantSort('dateAvenant'); $event.preventDefault()">
                        <span class="th-inner">Date <i class="bi caret" [ngClass]="caretAvenant('dateAvenant')"></i></span>
                      </th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (a of sortedAvenants(); track a.id) {
                      <tr>
                        <td class="fw-semibold">{{ a.numero }}</td>
                        <td>{{ a.objet }}</td>
                        <td class="text-end"
                            [class.text-success]="a.montant >= 0"
                            [class.text-danger]="a.montant < 0">
                          {{ a.montant | number:'1.0-0' }}
                        </td>
                        <td class="text-end">{{ a.workloadDays ? (a.workloadDays | number:'1.0-1') : '—' }}</td>
                        <td>{{ a.dateAvenant }}</td>
                        <td class="text-end">
                          @if (canManage()) {
                            <button class="btn btn-ghost btn-icon btn-sm act-danger" (click)="deleteAvenant(a)"
                                    title="Supprimer" aria-label="Supprimer l'avenant">
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
                          <div class="es-title">Aucun avenant</div>
                          <div class="es-desc">Les avenants ajustent le budget et la charge vendue du projet.</div>
                          @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openModal('avenant')"><i class="bi bi-plus-lg me-1"></i>Ajouter un avenant</button> }
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

    <!-- Modal Jalon -->
    @if (modal() === 'jalon') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Ajouter un jalon</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">Libellé <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="jalonForm.label" placeholder="Ex: Livraison phase 1">
              </div>
              <div class="mb-3">
                <label class="form-label">Pourcentage <span class="text-danger">*</span></label>
                <div class="input-group">
                  <input type="number" class="form-control" [(ngModel)]="jalonForm.pourcentage" min="1" max="100">
                  <span class="input-group-text">%</span>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label">Date prévue</label>
                <input type="date" class="form-control" [(ngModel)]="jalonForm.datePrevue">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">Annuler</button>
              <button class="btn btn-primary" (click)="saveJalon()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Avenant -->
    @if (modal() === 'avenant') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Ajouter un avenant</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">Numéro <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.numero" placeholder="Ex: AV-001">
              </div>
              <div class="mb-3">
                <label class="form-label">Objet</label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.objet" placeholder="Description de l'avenant">
              </div>
              <div class="mb-3">
                <label class="form-label">Montant <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.montant" placeholder="Positif = augmentation, négatif = réduction">
              </div>
              <div class="mb-3">
                <label class="form-label">Impact charge vendue (JH)</label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.workloadDays" min="0" step="0.5" placeholder="Optionnel — workload avenant">
              </div>
              <div class="mb-3">
                <label class="form-label">Date <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="avenantForm.dateAvenant">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">Annuler</button>
              <button class="btn btn-primary" (click)="saveAvenant()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Facturer -->
    @if (modal() === 'facturer') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Facturer le jalon</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <p class="text-muted small mb-3">
                Jalon <strong>{{ currentJalon()?.label }}</strong>
                ({{ currentJalon()?.montant | number:'1.0-0' }} TND) → passe au statut <span class="badge-completed">Facturé</span>
              </p>
              <div class="mb-3">
                <label class="form-label">Date de facture <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="facturerDate">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">Annuler</button>
              <button class="btn btn-primary" (click)="saveFacturer()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Facturer
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal Paiement -->
    @if (modal() === 'paiement') {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="modal.set(null)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">Enregistrer un paiement</h5>
              <button type="button" class="btn-close" (click)="modal.set(null)"></button>
            </div>
            <div class="modal-body">
              <p class="text-muted small mb-3">
                Jalon <strong>{{ currentJalon()?.label }}</strong> — montant facturé {{ currentJalon()?.montant | number:'1.0-0' }} TND
              </p>
              @if (paiements().length) {
                <div class="mb-3">
                  <div class="fw-semibold small text-muted mb-1">Paiements déjà reçus</div>
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
              <div class="mb-3">
                <label class="form-label">Montant reçu (TND) <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="paiementForm.montantRecu" min="0" step="0.01">
              </div>
              <div class="mb-3">
                <label class="form-label">Date du paiement <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="paiementForm.datePaiement">
              </div>
              <div class="mb-3">
                <label class="form-label">Référence</label>
                <input type="text" class="form-control" [(ngModel)]="paiementForm.reference" placeholder="N° virement / chèque">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="modal.set(null)">Annuler</button>
              <button class="btn btn-success" (click)="savePaiement()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                Enregistrer le paiement
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `
})
export class BillingComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly billingSvc = inject(BillingService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  canManage = () => this.auth.hasPermission('MANAGE_BILLING');

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  jalons = signal<JalonFacturation[]>([]);
  avenants = signal<Avenant[]>([]);

  jalonSortCol = signal<JalonSortCol>('datePrevue');
  jalonSortDir = signal<SortDir>('asc');
  readonly sortedJalons = computed(() => {
    const col = this.jalonSortCol(), dir = this.jalonSortDir() === 'asc' ? 1 : -1;
    return [...this.jalons()].sort((a, b) => {
      const av = a[col], bv = b[col];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      return (typeof av === 'string' ? av.localeCompare(bv as string) : (av as number) - (bv as number)) * dir;
    });
  });

  avenantSortCol = signal<AvenantSortCol>('dateAvenant');
  avenantSortDir = signal<SortDir>('asc');
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

  modal = signal<BillingModal>(null);
  saving = signal(false);
  modalError = signal('');

  currentJalon = signal<JalonFacturation | null>(null);
  paiements = signal<Paiement[]>([]);

  jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
  avenantForm: { numero: string; objet: string; montant: number; workloadDays?: number; dateAvenant: string } = { numero: '', objet: '', montant: 0, dateAvenant: '' };
  facturerDate = new Date().toISOString().split('T')[0];
  paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };

  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        const project = list.find(p => String(p.id) === pid);
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          this.reload();
        }
      });
    });
  }

  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.reload();
  }

  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  reload(): void {
    const p = this.selected();
    if (!p) return;
    this.billingSvc.listJalons(p.id).subscribe(d => this.jalons.set(d));
    this.billingSvc.listAvenants(p.id).subscribe(d => this.avenants.set(d));
  }

  jalonTotal(): number {
    return this.jalons().reduce((s, j) => s + j.montant, 0);
  }

  toggleJalonSort(col: JalonSortCol): void {
    if (this.jalonSortCol() === col) this.jalonSortDir.set(this.jalonSortDir() === 'asc' ? 'desc' : 'asc');
    else { this.jalonSortCol.set(col); this.jalonSortDir.set('asc'); }
  }
  ariaJalonSort(col: JalonSortCol): 'ascending' | 'descending' | 'none' {
    if (this.jalonSortCol() !== col) return 'none';
    return this.jalonSortDir() === 'asc' ? 'ascending' : 'descending';
  }
  caretJalon(col: JalonSortCol): string {
    if (this.jalonSortCol() !== col) return 'bi-chevron-expand';
    return this.jalonSortDir() === 'asc' ? 'bi-chevron-up' : 'bi-chevron-down';
  }

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

  openModal(type: BillingModal): void {
    this.jalonForm = { label: '', pourcentage: 0, datePrevue: '' };
    this.avenantForm = { numero: '', objet: '', montant: 0, workloadDays: undefined, dateAvenant: new Date().toISOString().split('T')[0] };
    this.modalError.set('');
    this.modal.set(type);
  }

  saveJalon(): void {
    if (!this.jalonForm.label || !this.jalonForm.pourcentage) {
      this.modalError.set('Libellé et pourcentage sont requis.');
      return;
    }
    this.saving.set(true);
    this.modalError.set('');
    const body = {
      label: this.jalonForm.label,
      pourcentage: this.jalonForm.pourcentage,
      datePrevue: this.jalonForm.datePrevue || undefined
    };
    this.billingSvc.createJalon(this.selected()!.id, body).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success('Jalon ajouté.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  saveAvenant(): void {
    if (!this.avenantForm.numero || !this.avenantForm.dateAvenant) {
      this.modalError.set('Numéro et date sont requis.');
      return;
    }
    this.saving.set(true);
    this.modalError.set('');
    this.billingSvc.createAvenant(this.selected()!.id, this.avenantForm).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success('Avenant ajouté.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteJalon(j: JalonFacturation): Promise<void> {
    if (!await this.confirm.ask(`Supprimer le jalon « ${j.label} » ?`, 'Supprimer le jalon')) return;
    this.billingSvc.deleteJalon(this.selected()!.id, j.id).subscribe({
      next: () => { this.reload(); this.toast.success('Jalon supprimé.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Facturer ─────────────────────────────────────────────────────
  openFacturer(j: JalonFacturation): void {
    this.currentJalon.set(j);
    this.facturerDate = new Date().toISOString().split('T')[0];
    this.modalError.set('');
    this.modal.set('facturer');
  }

  saveFacturer(): void {
    if (!this.facturerDate) { this.modalError.set('Date de facture requise.'); return; }
    this.saving.set(true);
    this.billingSvc.facturer(this.selected()!.id, this.currentJalon()!.id, this.facturerDate).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success('Jalon facturé.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ── Paiement ─────────────────────────────────────────────────────
  openPaiement(j: JalonFacturation): void {
    this.currentJalon.set(j);
    this.paiementForm = { montantRecu: 0, datePaiement: new Date().toISOString().split('T')[0], reference: '' };
    this.modalError.set('');
    this.billingSvc.listPaiements(this.selected()!.id, j.id).subscribe(d => this.paiements.set(d));
    this.modal.set('paiement');
  }

  savePaiement(): void {
    if (!this.paiementForm.montantRecu || this.paiementForm.montantRecu <= 0) {
      this.modalError.set('Le montant reçu doit être positif.');
      return;
    }
    if (!this.paiementForm.datePaiement) { this.modalError.set('Date du paiement requise.'); return; }
    this.saving.set(true);
    this.billingSvc.createPaiement(this.selected()!.id, this.currentJalon()!.id, this.paiementForm).subscribe({
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); this.toast.success('Paiement enregistré.'); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  async deleteAvenant(a: Avenant): Promise<void> {
    if (!await this.confirm.ask(`Supprimer l'avenant « ${a.numero} » ?`, 'Supprimer l\'avenant')) return;
    this.billingSvc.deleteAvenant(this.selected()!.id, a.id).subscribe({
      next: () => { this.reload(); this.toast.success('Avenant supprimé.'); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
