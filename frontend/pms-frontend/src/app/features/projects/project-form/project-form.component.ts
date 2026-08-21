import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { ProjectService } from '../../../core/services/project.service';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsListStateService } from '../projects-list-state.service';
import { ProjectRequest, ProjectStatus } from '../../../core/models/project.model';
import { HasUnsavedChanges } from '../../../core/guards/unsaved-changes.guard';

@Component({
  selector: 'app-project-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  styles: [`
    /* Read-only / computed fields — token-based so they adapt to dark mode (replaces .bg-light) */
    .field-ro { background: var(--surface-2); color: var(--text-2); }
    .field-ro.fw-semibold { color: var(--text-1); }

    /* Sticky action bar — always reachable on long forms */
    .form-actionbar { position: sticky; bottom: 0; z-index: 10; margin-top: 1.5rem;
      display: flex; align-items: center; gap: .5rem;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-lg);
      box-shadow: var(--sh-md); padding: .75rem 1rem; }
    .form-actionbar .fa-spacer { margin-left: auto; font-size: 12px; color: var(--text-3); }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <a [routerLink]="['/projects']" [queryParams]="listState.query()" class="bc-back-btn">
          <i class="bi bi-arrow-left"></i> Projets
        </a>
        <span class="bc-sep">›</span>
        @if (isEdit && req.code) {
          <a [routerLink]="['/projects', projectId]" style="color:var(--text-2);text-decoration:none;font-size:12px">{{ req.code }}</a>
          <span class="bc-sep">›</span>
          <span class="bc-curr">Modifier</span>
        } @else {
          <span class="bc-curr">{{ isEdit ? 'Modifier' : 'Nouveau projet' }}</span>
        }
      </div>
    </div>

    <div class="page-body">

      <!-- ── Bannière brouillon ──────────────────────────────────── -->
      @if (showDraftBanner()) {
        <div class="alert alert-info d-flex align-items-center gap-3 mb-4">
          <i class="bi bi-floppy fs-5 flex-shrink-0"></i>
          <div class="flex-grow-1">
            <strong>Brouillon sauvegardé</strong> — Un brouillon de ce formulaire a été trouvé.
            Souhaitez-vous le restaurer ?
          </div>
          <button type="button" class="btn btn-sm btn-primary" (click)="restoreDraft()">Restaurer</button>
          <button type="button" class="btn btn-sm btn-outline-secondary" (click)="discardDraft()">Ignorer</button>
        </div>
      }

      <!-- ── Chargement (mode édition uniquement) ────────────────── -->
      @if (!loaded()) {
        <div class="text-center py-5">
          <div class="spinner-border text-primary mb-2"></div>
          <p class="text-muted small mb-0">Chargement du projet…</p>
        </div>
      } @else {

      <form (ngSubmit)="submit()" #f="ngForm"
            (input)="onFormChange()" (change)="onFormChange()">

        <!-- ══ SECTION 1 : IDENTIFICATION ══════════════════════════ -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-card-heading me-2 text-primary"></i>Identification
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">Code projet *</label>
                <input class="form-control text-uppercase" name="code" [(ngModel)]="req.code"
                       required maxlength="20" placeholder="EX-2024-001"
                       [attr.readonly]="isEdit ? '' : null"
                       [class.field-ro]="isEdit">
                @if (isEdit) {
                  <div class="form-text text-muted">Non modifiable après création</div>
                }
              </div>

              <div class="col-md-9">
                <label class="form-label">Nom du projet *</label>
                <input class="form-control" name="name" [(ngModel)]="req.name"
                       required maxlength="255" placeholder="Intitulé complet du projet">
              </div>

              <div class="col-12">
                <label class="form-label">Description</label>
                <textarea class="form-control" name="description" [(ngModel)]="req.description"
                          rows="3" placeholder="Contexte, objectifs, périmètre…"></textarea>
              </div>

              <div class="col-md-4">
                <label class="form-label">Référence contrat</label>
                <input class="form-control" name="contractId" [(ngModel)]="req.contractId"
                       placeholder="Ex : CT-2024-0042">
              </div>

              <div class="col-md-4">
                <label class="form-label">Client</label>
                <input class="form-control" name="client" [(ngModel)]="req.client">
              </div>

              <div class="col-md-4">
                <label class="form-label">Bailleur de fonds</label>
                <input class="form-control" name="funder" [(ngModel)]="req.funder"
                       placeholder="Ex : Banque Mondiale (IDA)">
              </div>

              <div class="col-md-4">
                <label class="form-label">Modèle business</label>
                <select class="form-select" name="businessModel" [(ngModel)]="req.businessModel">
                  <option [ngValue]="null">—</option>
                  <option value="SEUL">Seul</option>
                  <option value="GROUPEMENT">En groupement</option>
                </select>
              </div>

              <div class="col-md-4">
                <label class="form-label">Type d'engagement</label>
                <select class="form-select" name="engagementType" [(ngModel)]="req.engagementType">
                  <option [ngValue]="null">—</option>
                  <option value="FORFAIT">Forfait (FP)</option>
                  <option value="REGIE">Régie (T&amp;M)</option>
                </select>
              </div>

              @if (auth.hasPermission('ASSIGN_CHEF_PROJET')) {
                <div class="col-md-4">
                  <label class="form-label">Chef de projet</label>
                  <select class="form-select" name="chefProjetId" [(ngModel)]="req.chefProjetId">
                    <option [ngValue]="null">— Non assigné —</option>
                    @for (u of chefs(); track u.id) {
                      <option [ngValue]="u.id">{{ u.firstName }} {{ u.lastName }}</option>
                    }
                  </select>
                </div>
              }

            </div>
          </div>
        </div>

        <!-- ══ SECTION 2 : PLANIFICATION ═══════════════════════════ -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-calendar3 me-2 text-primary"></i>Planification
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-4">
                <label class="form-label">Statut *</label>
                <select class="form-select" name="status" [(ngModel)]="req.status" required>
                  <option value="DRAFT">Brouillon</option>
                  <option value="ACTIVE">Actif</option>
                  <option value="ON_HOLD">En pause</option>
                  <option value="COMPLETED">Terminé</option>
                  <option value="CANCELLED">Annulé</option>
                </select>
              </div>

              <div class="col-md-4">
                <label class="form-label">Date de début</label>
                <input type="date" class="form-control" name="startDate"
                       [(ngModel)]="req.startDate">
              </div>

              <div class="col-md-4">
                <label class="form-label">Date de fin prévue</label>
                <input type="date" class="form-control" name="endDate"
                       [(ngModel)]="req.endDate"
                       [class.is-invalid]="dateRangeInvalid">
                @if (dateRangeInvalid) {
                  <div class="invalid-feedback">
                    La date de fin doit être postérieure ou égale à la date de début.
                  </div>
                }
              </div>

              <div class="col-md-4">
                <label class="form-label">Durée du contrat</label>
                <div class="input-group">
                  <div class="form-control field-ro"
                       [class.fw-semibold]="durationDays !== null">
                    {{ durationDays !== null ? durationDays : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">jours</span>
                </div>
              </div>

            </div>
          </div>
        </div>

        <!-- ══ SECTION 3 : FINANCIER ════════════════════════════════ -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-cash-coin me-2 text-primary"></i>Financier
          </div>
          <div class="card-body p-4">
            <div class="row g-3">

              <div class="col-md-3">
                <label class="form-label">Devise</label>
                <select class="form-select" name="currency" [(ngModel)]="req.currency"
                        (ngModelChange)="onCurrencyChange($event)">
                  <option value="TND">TND — Dinar Tunisien</option>
                  <option value="EUR">EUR — Euro</option>
                  <option value="USD">USD — Dollar US</option>
                  <option value="FCFA">FCFA — Franc CFA</option>
                </select>
              </div>

              <div class="col-md-5">
                <label class="form-label">
                  Budget initial ({{ req.currency || 'TND' }})
                </label>
                <input type="number" class="form-control" name="budget"
                       [(ngModel)]="req.initialBudget" min="0" step="0.01">
              </div>

              <div class="col-md-4">
                <label class="form-label">
                  Taux de change → TND
                  @if ((req.currency || 'TND') === 'TND') {
                    <span class="fw-normal text-muted">(N/A)</span>
                  }
                </label>
                <input type="number" class="form-control" name="rate"
                       [(ngModel)]="req.exchangeRateToTnd" min="0" step="0.000001"
                       [attr.readonly]="(req.currency || 'TND') === 'TND' ? '' : null"
                       [class.field-ro]="(req.currency || 'TND') === 'TND'">
              </div>

              <!-- Budget converti -->
              <div class="col-md-6">
                <label class="form-label">Budget initial converti (TND)</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ budgetTnd !== null ? (budgetTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- PPR -->
              <div class="col-md-6">
                <label class="form-label">PPR — Provision pour risques (5%)</label>
                <div class="input-group">
                  <div class="form-control field-ro fw-semibold">
                    {{ pprTnd !== null ? (pprTnd | number:'1.0-0') : '—' }}
                  </div>
                  <span class="input-group-text text-muted small">TND</span>
                </div>
              </div>

              <!-- Budget révisé (edit mode, piloté par les avenants) -->
              @if (isEdit && revisedBudget !== null) {
                <div class="col-12">
                  <div class="alert alert-secondary py-2 small d-flex align-items-center gap-2 mb-0">
                    <i class="bi bi-info-circle flex-shrink-0"></i>
                    <span>
                      Budget révisé ({{ req.currency || 'TND' }}) :
                      <strong>{{ revisedBudget | number:'1.2-2' }}</strong>
                      — Géré via <strong>Facturation → Avenants</strong>.
                    </span>
                  </div>
                </div>
              }

              <!-- Autres champs financiers -->
              <div class="col-md-6">
                <label class="form-label">
                  Budget licences &amp; sous-traitance (TND)
                </label>
                <input type="number" class="form-control" name="licBudget"
                       [(ngModel)]="req.licenseSubcontractBudget" min="0" step="0.01">
              </div>

              <div class="col-md-6">
                <label class="form-label">Provision pénalités (PPP, TND)</label>
                <input type="number" class="form-control" name="ppp"
                       [(ngModel)]="req.penaltyProvision" min="0" step="0.01">
              </div>

            </div>
          </div>
        </div>

        <!-- ══ SECTION 4 : CHARGE VENDUE ════════════════════════════ -->
        <div class="card mb-4">
          <div class="card-header">
            <i class="bi bi-people me-2 text-primary"></i>Charge vendue
          </div>
          <div class="card-body p-4">
            <div class="row g-3">
              <div class="col-md-6">
                <label class="form-label">Workload vendu (JH)</label>
                <input type="number" class="form-control" name="soldWl"
                       [(ngModel)]="req.soldWorkloadDays" min="0" step="0.5">
              </div>
              <div class="col-md-6">
                <label class="form-label">Workload garantie (JH)</label>
                <input type="number" class="form-control" name="warrWl"
                       [(ngModel)]="req.warrantyWorkloadDays" min="0" step="0.5">
              </div>
            </div>
          </div>
        </div>

        <!-- ── Erreur & boutons ─────────────────────────────────── -->
        @if (error()) {
          <div class="alert alert-danger py-2 small d-flex align-items-center gap-2">
            <i class="bi bi-exclamation-circle-fill"></i>{{ error() }}
          </div>
        }

        <div class="form-actionbar">
          <button type="submit" class="btn btn-primary px-4"
                  [disabled]="loading() || !f.valid || dateRangeInvalid">
            @if (loading()) {
              <span class="spinner-border spinner-border-sm me-2"></span>
            }
            {{ isEdit ? 'Enregistrer les modifications' : 'Créer le projet' }}
          </button>
          <button type="button" class="btn btn-outline-secondary" (click)="back()">
            Annuler
          </button>
          @if (isDirty()) {
            <span class="fa-spacer"><i class="bi bi-record-fill me-1" style="font-size:8px;color:var(--c-warning)"></i>Modifications non enregistrées</span>
          }
        </div>

      </form>
      } <!-- end @else loaded -->

    </div>
  `
})
export class ProjectFormComponent implements OnInit, OnDestroy, HasUnsavedChanges {
  private readonly toast = inject(ToastService);
  readonly listState = inject(ProjectsListStateService);

  isEdit = false;
  loaded = signal(false);
  loading = signal(false);
  error = signal('');
  isDirty = signal(false);
  projectId: number | null = null;
  chefs = signal<{ id: number; firstName: string; lastName: string; roleName: string }[]>([]);

  /** Read-only in edit mode — the revised budget is managed exclusively by Billing → Avenants. */
  revisedBudget: number | null = null;

  private readonly DRAFT_KEY = 'pms_draft_project';
  showDraftBanner = signal(false);
  private savedDraft: ProjectRequest | null = null;
  private autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

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

  constructor(
    private svc: ProjectService,
    readonly auth: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');

    if (id) {
      // ── Edit mode: load project data before rendering the form ──
      this.isEdit = true;
      this.projectId = +id;
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
            // null from backend means "not set"; [ngValue]="null" matches correctly
            businessModel: p.businessModel ?? null,
            engagementType: p.engagementType ?? null,
            currency: p.currency ?? 'TND',
            exchangeRateToTnd: p.exchangeRateToTnd ?? 1,
            licenseSubcontractBudget: p.licenseSubcontractBudget,
            soldWorkloadDays: p.soldWorkloadDays,
            warrantyWorkloadDays: p.warrantyWorkloadDays,
            penaltyProvision: p.penaltyProvision
          };
          // Display only — revisedBudget is written exclusively by AvenantService
          this.revisedBudget = p.revisedBudget ?? null;
          this.loaded.set(true);
        },
        error: () => {
          this.error.set('Impossible de charger le projet.');
          this.loaded.set(true);
        }
      });
    } else {
      // ── Create mode: check for saved draft ──
      const saved = localStorage.getItem(this.DRAFT_KEY);
      if (saved) {
        try {
          this.savedDraft = JSON.parse(saved);
          this.showDraftBanner.set(true);
        } catch { /* corrupt draft — silently ignore */ }
      }
      this.loaded.set(true);
    }

    if (this.auth.hasPermission('ASSIGN_CHEF_PROJET')) {
      this.svc.listAssignableUsers().subscribe(users => {
        const chefs = users.filter(u => u.roleName === 'CHEF_PROJET');
        this.chefs.set(chefs.length ? chefs : users);
      });
    }
  }

  ngOnDestroy(): void {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
  }

  // ── Computed values ───────────────────────────────────────────────

  private get rate(): number {
    return (this.req.currency ?? 'TND') === 'TND' ? 1 : (this.req.exchangeRateToTnd ?? 0);
  }

  get durationDays(): number | null {
    if (!this.req.startDate || !this.req.endDate) return null;
    const ms = new Date(this.req.endDate).getTime() - new Date(this.req.startDate).getTime();
    const days = Math.round(ms / 86_400_000) + 1;
    return days >= 1 ? days : null;
  }

  get dateRangeInvalid(): boolean {
    if (!this.req.startDate || !this.req.endDate) return false;
    return new Date(this.req.endDate) < new Date(this.req.startDate);
  }

  get budgetTnd(): number | null {
    if (this.req.initialBudget == null) return null;
    return (this.req.initialBudget as number) * this.rate;
  }

  get pprTnd(): number | null {
    return this.budgetTnd != null ? this.budgetTnd * 0.05 : null;
  }

  // ── Handlers ─────────────────────────────────────────────────────

  onCurrencyChange(currency: string): void {
    if (currency === 'TND') this.req.exchangeRateToTnd = 1;
  }

  // ── Draft management ──────────────────────────────────────────────

  onFormChange(): void {
    this.isDirty.set(true);
    if (this.isEdit) return;
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
    this.autoSaveTimer = setTimeout(() => this.persistDraft(), 800);
  }

  private persistDraft(): void {
    localStorage.setItem(this.DRAFT_KEY, JSON.stringify(this.req));
  }

  restoreDraft(): void {
    if (!this.savedDraft) return;
    this.req = { ...this.req, ...this.savedDraft };
    this.showDraftBanner.set(false);
    this.savedDraft = null;
  }

  discardDraft(): void {
    localStorage.removeItem(this.DRAFT_KEY);
    this.showDraftBanner.set(false);
    this.savedDraft = null;
  }

  // ── Submission ────────────────────────────────────────────────────

  submit(): void {
    if (this.dateRangeInvalid) {
      this.error.set('La date de fin doit être postérieure ou égale à la date de début.');
      return;
    }
    this.loading.set(true);
    this.error.set('');

    if ((this.req.currency ?? 'TND') === 'TND') this.req.exchangeRateToTnd = 1;

    const obs = this.isEdit && this.projectId
      ? this.svc.update(this.projectId, this.req)
      : this.svc.create(this.req);

    obs.subscribe({
      next: p => {
        this.isDirty.set(false);
        if (!this.isEdit) {
          localStorage.removeItem(this.DRAFT_KEY);
          if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
        }
        this.toast.success(this.isEdit ? 'Projet enregistré.' : 'Projet créé.');
        this.router.navigate(['/projects', p.id]);
      },
      error: e => {
        this.loading.set(false);
        this.error.set(e.error?.detail ?? e.error?.message ?? 'Une erreur est survenue.');
      }
    });
  }

  back(): void { this.router.navigate(['/projects']); }
}
