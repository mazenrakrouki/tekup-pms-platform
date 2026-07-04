import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../../core/services/project.service';
import { AuthService } from '../../../core/services/auth.service';
import { ProjectRequest, ProjectStatus } from '../../../core/models/project.model';

@Component({
  selector: 'app-project-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="topbar">
      <h5 class="mb-0 fw-semibold">{{ isEdit ? 'Modifier le projet' : 'Nouveau projet' }}</h5>
    </div>
    <div class="p-4" style="max-width:880px">
      <form (ngSubmit)="submit()" #f="ngForm">

        <!-- ── Identification ─────────────────────────────── -->
        <div class="card mb-3">
          <div class="card-header bg-white fw-semibold py-3"><i class="bi bi-card-heading me-2"></i>Identification</div>
          <div class="card-body p-4">
            <div class="row g-3">
              <div class="col-md-4">
                <label class="form-label small fw-semibold">Code *</label>
                <input class="form-control" name="code" [(ngModel)]="req.code" required [disabled]="isEdit">
              </div>
              <div class="col-md-8">
                <label class="form-label small fw-semibold">Nom *</label>
                <input class="form-control" name="name" [(ngModel)]="req.name" required>
              </div>
              <div class="col-12">
                <label class="form-label small fw-semibold">Description</label>
                <textarea class="form-control" name="description" [(ngModel)]="req.description" rows="2"></textarea>
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Référence contrat</label>
                <input class="form-control" name="contractId" [(ngModel)]="req.contractId" placeholder="Contract ID">
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Client</label>
                <input class="form-control" name="client" [(ngModel)]="req.client">
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Bailleur de fonds</label>
                <input class="form-control" name="funder" [(ngModel)]="req.funder" placeholder="Ex: Banque Mondiale (IDA)">
              </div>
              <div class="col-md-3">
                <label class="form-label small fw-semibold">Modèle business</label>
                <select class="form-select" name="businessModel" [(ngModel)]="req.businessModel">
                  <option [ngValue]="undefined">—</option>
                  <option value="SEUL">Seul</option>
                  <option value="GROUPEMENT">En groupement</option>
                </select>
              </div>
              <div class="col-md-3">
                <label class="form-label small fw-semibold">Type d'engagement</label>
                <select class="form-select" name="engagementType" [(ngModel)]="req.engagementType">
                  <option [ngValue]="undefined">—</option>
                  <option value="FORFAIT">Forfait (FP)</option>
                  <option value="REGIE">Régie (T&amp;M)</option>
                </select>
              </div>
              @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                <div class="col-md-6">
                  <label class="form-label small fw-semibold">Chef de projet</label>
                  <select class="form-select" name="chefProjetId" [(ngModel)]="req.chefProjetId">
                    <option [ngValue]="undefined">— Non assigné —</option>
                    @for (u of chefs(); track u.id) {
                      <option [ngValue]="u.id">{{ u.firstName }} {{ u.lastName }} ({{ u.roleName }})</option>
                    }
                  </select>
                </div>
              }
            </div>
          </div>
        </div>

        <!-- ── Contractuel & financier ───────────────────── -->
        <div class="card mb-3">
          <div class="card-header bg-white fw-semibold py-3"><i class="bi bi-cash-coin me-2"></i>Contractuel &amp; financier</div>
          <div class="card-body p-4">
            <div class="row g-3">
              <div class="col-md-4">
                <label class="form-label small fw-semibold">Statut *</label>
                <select class="form-select" name="status" [(ngModel)]="req.status" required>
                  <option value="DRAFT">Brouillon</option>
                  <option value="ACTIVE">Actif</option>
                  <option value="ON_HOLD">En pause</option>
                  <option value="COMPLETED">Terminé</option>
                  <option value="CANCELLED">Annulé</option>
                </select>
              </div>
              <div class="col-md-4">
                <label class="form-label small fw-semibold">Date début</label>
                <input type="date" class="form-control" name="startDate" [(ngModel)]="req.startDate">
              </div>
              <div class="col-md-4">
                <label class="form-label small fw-semibold">Date fin prévue</label>
                <input type="date" class="form-control" name="endDate" [(ngModel)]="req.endDate">
              </div>
              <div class="col-md-3">
                <label class="form-label small fw-semibold">Devise</label>
                <select class="form-select" name="currency" [(ngModel)]="req.currency">
                  <option value="TND">TND</option>
                  <option value="FCFA">FCFA</option>
                  <option value="EUR">EUR</option>
                  <option value="USD">USD</option>
                </select>
              </div>
              <div class="col-md-5">
                <label class="form-label small fw-semibold">Budget initial ({{ req.currency || 'TND' }})</label>
                <input type="number" class="form-control" name="budget" [(ngModel)]="req.initialBudget" min="0">
              </div>
              <div class="col-md-4">
                <label class="form-label small fw-semibold">Taux → TND</label>
                <input type="number" class="form-control" name="rate" [(ngModel)]="req.exchangeRateToTnd" min="0" step="0.000001"
                       [disabled]="(req.currency || 'TND') === 'TND'">
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Budget licences &amp; sous-traitance</label>
                <input type="number" class="form-control" name="licBudget" [(ngModel)]="req.licenseSubcontractBudget" min="0">
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Provision pénalités (PPP)</label>
                <input type="number" class="form-control" name="ppp" [(ngModel)]="req.penaltyProvision" min="0">
              </div>
              @if (budgetTndPreview() !== null) {
                <div class="col-12">
                  <div class="alert alert-light border py-2 small mb-0">
                    <i class="bi bi-calculator me-1"></i>
                    Budget converti ≈ <strong>{{ budgetTndPreview() | number:'1.0-0' }} TND</strong>
                    · PPR (5%) ≈ <strong>{{ pprPreview() | number:'1.0-0' }} TND</strong>
                    @if (durationPreview() !== null) { · Durée ≈ <strong>{{ durationPreview() }} j</strong> }
                  </div>
                </div>
              }
            </div>
          </div>
        </div>

        <!-- ── Workload ──────────────────────────────────── -->
        <div class="card mb-3">
          <div class="card-header bg-white fw-semibold py-3"><i class="bi bi-people me-2"></i>Charge vendue</div>
          <div class="card-body p-4">
            <div class="row g-3">
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Workload vendu (JH)</label>
                <input type="number" class="form-control" name="soldWl" [(ngModel)]="req.soldWorkloadDays" min="0" step="0.5">
              </div>
              <div class="col-md-6">
                <label class="form-label small fw-semibold">Workload garantie (JH)</label>
                <input type="number" class="form-control" name="warrWl" [(ngModel)]="req.warrantyWorkloadDays" min="0" step="0.5">
              </div>
            </div>
          </div>
        </div>

        @if (error()) {
          <div class="alert alert-danger py-2 small">{{ error() }}</div>
        }

        <div class="d-flex gap-2">
          <button type="submit" class="btn btn-primary" [disabled]="loading() || !f.valid">
            @if (loading()) { <span class="spinner-border spinner-border-sm me-2"></span> }
            {{ isEdit ? 'Enregistrer' : 'Créer' }}
          </button>
          <button type="button" class="btn btn-outline-secondary" (click)="back()">Annuler</button>
        </div>
      </form>
    </div>
  `
})
export class ProjectFormComponent implements OnInit {
  isEdit = false;
  loading = signal(false);
  error = signal('');
  projectId: number | null = null;
  chefs = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);

  req: ProjectRequest = {
    code: '', name: '', status: 'DRAFT' as ProjectStatus, currency: 'TND', exchangeRateToTnd: 1
  };

  constructor(
    private svc: ProjectService,
    readonly auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    if (this.auth.hasPermission('ASSIGN_CHEF_PROJET')) {
      this.svc.listAssignableUsers().subscribe(u =>
        this.chefs.set(u.filter(x => x.roleName === 'CHEF_PROJET').length ? u.filter(x => x.roleName === 'CHEF_PROJET') : u)
      );
    }
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEdit = true;
      this.projectId = +id;
      this.svc.get(this.projectId).subscribe(p => {
        this.req = {
          code: p.code, name: p.name, description: p.description,
          status: p.status, startDate: p.startDate, endDate: p.endDate,
          initialBudget: p.initialBudget, directorId: p.directorId, chefProjetId: p.chefProjetId,
          contractId: p.contractId, client: p.client, funder: p.funder,
          businessModel: p.businessModel, engagementType: p.engagementType,
          currency: p.currency ?? 'TND', exchangeRateToTnd: p.exchangeRateToTnd ?? 1,
          licenseSubcontractBudget: p.licenseSubcontractBudget,
          soldWorkloadDays: p.soldWorkloadDays, warrantyWorkloadDays: p.warrantyWorkloadDays,
          penaltyProvision: p.penaltyProvision
        };
      });
    }
  }

  private rate(): number {
    return (this.req.currency || 'TND') === 'TND' ? 1 : (this.req.exchangeRateToTnd || 0);
  }
  budgetTndPreview(): number | null {
    if (!this.req.initialBudget) return null;
    return this.req.initialBudget * this.rate();
  }
  pprPreview(): number | null {
    const t = this.budgetTndPreview();
    return t !== null ? t * 0.05 : null;
  }
  durationPreview(): number | null {
    if (!this.req.startDate || !this.req.endDate) return null;
    const d = (new Date(this.req.endDate).getTime() - new Date(this.req.startDate).getTime()) / 86_400_000;
    return d >= 0 ? Math.round(d) + 1 : null;
  }

  submit(): void {
    this.loading.set(true);
    this.error.set('');
    if ((this.req.currency || 'TND') === 'TND') this.req.exchangeRateToTnd = 1;
    const obs = this.isEdit && this.projectId
      ? this.svc.update(this.projectId, this.req)
      : this.svc.create(this.req);
    obs.subscribe({
      next: p => this.router.navigate(['/projects', p.id]),
      error: e => { this.loading.set(false); this.error.set(e.error?.detail ?? e.error?.message ?? 'Erreur'); }
    });
  }

  back(): void { this.router.navigate(['/projects']); }
}
