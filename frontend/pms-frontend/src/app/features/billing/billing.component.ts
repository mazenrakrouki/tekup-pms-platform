import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { BillingService } from '../../core/services/billing.service';
import { AuthService } from '../../core/services/auth.service';
import { Project } from '../../core/models/project.model';
import { JalonFacturation, Avenant, Paiement } from '../../core/models/billing.model';

type BillingModal = 'jalon' | 'avenant' | 'facturer' | 'paiement' | null;

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold"><i class="bi bi-receipt me-2"></i>Facturation</h5>
    </div>
    <div class="p-4">
      <!-- Project selector -->
      <div class="card mb-4">
        <div class="card-body py-3">
          <div class="d-flex align-items-center gap-3 flex-wrap">
            <span class="fw-semibold text-muted small">PROJET :</span>
            @for (p of projects(); track p.id) {
              <button class="btn btn-sm"
                      [class]="selected()?.id === p.id ? 'btn-primary' : 'btn-outline-secondary'"
                      (click)="select(p)">
                {{ p.code }}
              </button>
            }
          </div>
        </div>
      </div>

      @if (selected()) {
        <div class="row g-4">
          <!-- Jalons -->
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
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
                  <thead class="table-light">
                    <tr><th>Libellé</th><th class="text-end">%</th><th class="text-end">Montant</th><th>Date prévue</th><th>Date facture</th><th>Statut</th><th></th></tr>
                  </thead>
                  <tbody>
                    @for (j of jalons(); track j.id) {
                      <tr>
                        <td class="fw-semibold">{{ j.label }}</td>
                        <td class="text-end">{{ j.pourcentage }}%</td>
                        <td class="text-end">{{ j.montant | number:'1.0-0' }} TND</td>
                        <td>{{ j.datePrevue ?? '—' }}</td>
                        <td>{{ j.dateFacture ?? '—' }}</td>
                        <td>
                          @if (j.statut === 'PAYE') { <span class="badge bg-success">Payé</span> }
                          @else if (j.statut === 'FACTURE') { <span class="badge bg-primary">Facturé</span> }
                          @else { <span class="badge bg-secondary">Prévu</span> }
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
                            <button class="btn btn-sm btn-outline-danger" (click)="deleteJalon(j)" title="Supprimer">
                              <i class="bi bi-trash"></i>
                            </button>
                          } @else { <span class="text-muted">—</span> }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="7" class="text-center py-4 text-muted">Aucun jalon</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          <!-- Avenants -->
          <div class="col-12">
            <div class="card">
              <div class="card-header bg-white fw-semibold py-3 d-flex justify-content-between align-items-center">
                <span><i class="bi bi-file-earmark-plus me-2"></i>Avenants — {{ selected()!.name }}</span>
                @if (canManage()) {
                  <button class="btn btn-primary btn-sm" (click)="openModal('avenant')">
                    <i class="bi bi-plus-lg me-1"></i>Ajouter un avenant
                  </button>
                }
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead class="table-light">
                    <tr><th>N°</th><th>Objet</th><th class="text-end">Montant</th><th class="text-end">Workload (JH)</th><th>Date</th><th></th></tr>
                  </thead>
                  <tbody>
                    @for (a of avenants(); track a.id) {
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
                            <button class="btn btn-sm btn-outline-danger" (click)="deleteAvenant(a)" title="Supprimer">
                              <i class="bi bi-trash"></i>
                            </button>
                          } @else { <span class="text-muted">—</span> }
                        </td>
                      </tr>
                    }
                    @empty {
                      <tr><td colspan="6" class="text-center py-4 text-muted">Aucun avenant</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      } @else {
        <div class="text-center py-5 text-muted">
          <i class="bi bi-receipt fs-1 d-block mb-3 opacity-25"></i>
          Sélectionnez un projet pour afficher sa facturation
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
                <label class="form-label fw-semibold">Libellé <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="jalonForm.label" placeholder="Ex: Livraison phase 1">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Pourcentage <span class="text-danger">*</span></label>
                <div class="input-group">
                  <input type="number" class="form-control" [(ngModel)]="jalonForm.pourcentage" min="1" max="100">
                  <span class="input-group-text">%</span>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date prévue</label>
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
                <label class="form-label fw-semibold">Numéro <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.numero" placeholder="Ex: AV-001">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Objet</label>
                <input type="text" class="form-control" [(ngModel)]="avenantForm.objet" placeholder="Description de l'avenant">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Montant <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.montant" placeholder="Positif = augmentation, négatif = réduction">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Impact charge vendue (JH)</label>
                <input type="number" class="form-control" [(ngModel)]="avenantForm.workloadDays" min="0" step="0.5" placeholder="Optionnel — workload avenant">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date <span class="text-danger">*</span></label>
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
                ({{ currentJalon()?.montant | number:'1.0-0' }} TND) → passe au statut <span class="badge bg-primary">Facturé</span>
              </p>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date de facture <span class="text-danger">*</span></label>
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
                <label class="form-label fw-semibold">Montant reçu (TND) <span class="text-danger">*</span></label>
                <input type="number" class="form-control" [(ngModel)]="paiementForm.montantRecu" min="0" step="0.01">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Date du paiement <span class="text-danger">*</span></label>
                <input type="date" class="form-control" [(ngModel)]="paiementForm.datePaiement">
              </div>
              <div class="mb-3">
                <label class="form-label fw-semibold">Référence</label>
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
  private readonly auth = inject(AuthService);

  canManage = () => this.auth.hasPermission('MANAGE_BILLING');

  projects = signal<Project[]>([]);
  selected = signal<Project | null>(null);
  jalons = signal<JalonFacturation[]>([]);
  avenants = signal<Avenant[]>([]);

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
    this.projectSvc.list().subscribe(list => this.projects.set(list));
  }

  select(p: Project): void {
    this.selected.set(p);
    this.reload();
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
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); },
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
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deleteJalon(j: JalonFacturation): void {
    if (!confirm(`Supprimer le jalon "${j.label}" ?`)) return;
    this.billingSvc.deleteJalon(this.selected()!.id, j.id).subscribe(() => this.reload());
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
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); },
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
      next: () => { this.reload(); this.modal.set(null); this.saving.set(false); },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  deleteAvenant(a: Avenant): void {
    if (!confirm(`Supprimer l'avenant "${a.numero}" ?`)) return;
    this.billingSvc.deleteAvenant(this.selected()!.id, a.id).subscribe(() => this.reload());
  }
}
