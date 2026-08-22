import { Component, OnInit, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DiService } from '../../core/services/di.service';
import { ProjectService } from '../../core/services/project.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { DevisInterneResponse, LigneDiRequest, LigneDiResponse, SectionDi, SECTION_DI_LABELS } from '../../core/models/di.model';
import { Project } from '../../core/models/project.model';

/**
 * Devis Interne (F-AFF-13 §3) — structure vide, accès MANAGE_DI.
 * Les montants et marges sont calculés par le serveur à la lecture ;
 * aucune valeur sensible n'est pré-remplie (BUSINESS_ANALYSIS.md §16).
 */
@Component({
  selector: 'app-devis-interne',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslocoModule],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <a [routerLink]="['/projects', projectId]" style="color:var(--text-2);text-decoration:none;font-size:12px">
          {{ project()?.code ?? 'Projet' }}
        </a>
        <span class="bc-sep">›</span>
        <span class="bc-curr"><i class="bi bi-file-earmark-lock2 me-1"></i>Devis Interne</span>
      </div>
      <div class="tb-right">
        <a [routerLink]="['/projects', projectId]" class="btn btn-outline-secondary btn-sm">
          <i class="bi bi-arrow-left"></i>Retour
        </a>
      </div>
    </div>

    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.internalQuote' | transloco }}</h1>
      </div>
      @if (di(); as d) {
        <!-- ── Totaux ── -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <div class="small text-muted">Total vendu ({{ d.currency }})</div>
              <div class="fs-5 fw-bold text-primary">{{ d.totalVenduDevise | number:'1.0-0' }}</div>
              <div class="small text-muted">{{ d.totalVenduTnd | number:'1.0-0' }} TND</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <div class="small text-muted">Coût total interne</div>
              <div class="fs-5 fw-bold text-warning">{{ d.totalCoutFinal | number:'1.0-0' }} TND</div>
              <div class="small text-muted">{{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <div class="small text-muted">Marge nette vendue</div>
              <div class="fs-5 fw-bold" [class.text-success]="d.margeNette >= 0" [class.text-danger]="d.margeNette < 0">
                {{ d.margeNette | number:'1.0-0' }} TND
              </div>
              <div class="small fw-semibold" [class.text-success]="(d.margePct ?? 0) >= 0" [class.text-danger]="(d.margePct ?? 0) < 0">
                {{ ((d.margePct ?? 0) * 100) | number:'1.1-2' }} %
              </div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <div class="small text-muted">Charge vendue</div>
              <div class="fs-5 fw-bold">{{ d.totalChargeVendueJh | number:'1.0-1' }} JH</div>
              <div class="small text-muted">vs {{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
        </div>

        <!-- ── Sections ── -->
        @for (section of sections; track section) {
          <div class="card mb-4">
            <div class="card-header justify-content-between">
              <span class="fw-semibold">{{ sectionLabel(section) }}</span>
              <button class="btn btn-sm btn-outline-primary" (click)="startAdd(section)">
                <i class="bi bi-plus-lg me-1"></i>Ajouter une ligne
              </button>
            </div>
            <div class="table-responsive">
              <table class="table table-sm table-hover mb-0 align-middle small">
                <thead>
                  <tr>
                    <th>Profil / libellé</th>
                    <th>Ressource retenue</th>
                    <th class="text-end">JH vendus</th>
                    <th class="text-end">PU ({{ d.currency }})</th>
                    <th class="text-end">Vendu TND</th>
                    <th class="text-end">JH internes</th>
                    <th class="text-end">TCC (TND/j)</th>
                    @if (section === 'AUTRES_FRAIS') { <th class="text-end">Taux %</th> }
                    <th class="text-end">Coût final</th>
                    <th class="text-end">Marge TND</th>
                    <th class="text-end">Marge %</th>
                    <th style="width:90px"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of bySection(section); track l.id) {
                    @if (editingId() === l.id) {
                      <tr class="table-active">
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.profilContractuel" placeholder="ex. PC-1 / TSR 5 %"></td>
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.ressourceRetenue"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.chargeVendueJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.prixVenteUnitaire" min="0"></td>
                        <td class="text-end text-muted">—</td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.quantiteInterneJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.coutUnitaireTcc" min="0"></td>
                        @if (section === 'AUTRES_FRAIS') {
                          <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.tauxPourcentage" min="0" max="1" step="0.01" placeholder="0.05"></td>
                        }
                        <td class="text-end text-muted" colspan="3">calculé après enregistrement</td>
                        <td class="text-end text-nowrap">
                          <button class="btn btn-sm btn-success me-1" (click)="save()" title="Enregistrer" aria-label="Enregistrer la ligne"><i class="bi bi-check-lg"></i></button>
                          <button class="btn btn-sm btn-outline-secondary" (click)="cancelEdit()" title="Annuler" aria-label="Annuler l'édition"><i class="bi bi-x-lg"></i></button>
                        </td>
                      </tr>
                    } @else {
                      <tr>
                        <td>{{ l.profilContractuel || '—' }}</td>
                        <td>{{ l.ressourceRetenue || '—' }}</td>
                        <td class="text-end">{{ l.chargeVendueJh != null ? (l.chargeVendueJh | number:'1.0-1') : '—' }}</td>
                        <td class="text-end">{{ l.prixVenteUnitaire != null ? (l.prixVenteUnitaire | number:'1.0-0') : '—' }}</td>
                        <td class="text-end">{{ l.montantTnd | number:'1.0-0' }}</td>
                        <td class="text-end">{{ l.quantiteInterneJh != null ? (l.quantiteInterneJh | number:'1.0-1') : '—' }}</td>
                        <td class="text-end">{{ l.coutUnitaireTcc != null ? (l.coutUnitaireTcc | number:'1.0-2') : '—' }}</td>
                        @if (section === 'AUTRES_FRAIS') {
                          <td class="text-end">{{ l.tauxPourcentage != null ? ((l.tauxPourcentage * 100) | number:'1.0-1') + ' %' : '—' }}</td>
                        }
                        <td class="text-end">{{ l.coutFinal | number:'1.0-0' }}</td>
                        <td class="text-end" [class.text-success]="(l.margeNette ?? 0) >= 0" [class.text-danger]="(l.margeNette ?? 0) < 0">
                          {{ l.margeNette | number:'1.0-0' }}
                        </td>
                        <td class="text-end">{{ l.margePct != null ? ((l.margePct * 100) | number:'1.1-1') + ' %' : '—' }}</td>
                        <td class="text-end text-nowrap">
                          <button class="btn btn-sm btn-outline-primary me-1" (click)="startEdit(l)" title="Modifier" aria-label="Modifier la ligne"><i class="bi bi-pencil"></i></button>
                          <button class="btn btn-sm btn-outline-danger" (click)="remove(l)" title="Supprimer" aria-label="Supprimer la ligne"><i class="bi bi-trash"></i></button>
                        </td>
                      </tr>
                    }
                  }
                  @if (addingSection() === section) {
                    <tr class="table-active">
                      <td><input class="form-control form-control-sm" [(ngModel)]="form.profilContractuel" placeholder="ex. PC-1 / TSR 5 %"></td>
                      <td><input class="form-control form-control-sm" [(ngModel)]="form.ressourceRetenue"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.chargeVendueJh" min="0"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.prixVenteUnitaire" min="0"></td>
                      <td class="text-end text-muted">—</td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.quantiteInterneJh" min="0"></td>
                      <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.coutUnitaireTcc" min="0"></td>
                      @if (section === 'AUTRES_FRAIS') {
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.tauxPourcentage" min="0" max="1" step="0.01" placeholder="0.05"></td>
                      }
                      <td class="text-end text-muted" colspan="3">calculé après enregistrement</td>
                      <td class="text-end text-nowrap">
                        <button class="btn btn-sm btn-success me-1" (click)="save()"><i class="bi bi-check-lg"></i></button>
                        <button class="btn btn-sm btn-outline-secondary" (click)="cancelEdit()"><i class="bi bi-x-lg"></i></button>
                      </td>
                    </tr>
                  }
                  @if (bySection(section).length === 0 && addingSection() !== section) {
                    <tr><td colspan="12">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-list-ul"></i></div>
                        <div class="es-title">Aucune ligne</div>
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      } @else {
        <div class="text-center py-5"><span class="spinner-border text-primary"></span></div>
      }

      @if (error()) {
        <div class="alert alert-danger py-2 small">{{ error() }}</div>
      }
    </div>
  `
})
export class DevisInterneComponent implements OnInit {
  private readonly diSvc = inject(DiService);
  private readonly projectSvc = inject(ProjectService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);

  projectId = 0;
  readonly sections: SectionDi[] = ['HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'];

  di = signal<DevisInterneResponse | null>(null);
  project = signal<Project | null>(null);
  editingId = signal<number | null>(null);
  addingSection = signal<SectionDi | null>(null);
  error = signal('');

  form: LigneDiRequest = { section: 'HONORAIRES' };

  ngOnInit(): void {
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    this.projectSvc.get(this.projectId).subscribe(p => this.project.set(p));
    this.load();
  }

  load(): void {
    this.diSvc.get(this.projectId).subscribe({
      next: d => this.di.set(d),
      error: () => this.error.set('Impossible de charger le Devis Interne.')
    });
  }

  bySection(section: SectionDi): LigneDiResponse[] {
    return (this.di()?.lignes ?? []).filter(l => l.section === section);
  }

  sectionLabel(s: SectionDi): string { return SECTION_DI_LABELS[s]; }

  startAdd(section: SectionDi): void {
    this.editingId.set(null);
    this.addingSection.set(section);
    this.form = { section };
  }

  startEdit(l: LigneDiResponse): void {
    this.addingSection.set(null);
    this.editingId.set(l.id);
    this.form = {
      section: l.section, ordre: l.ordre,
      profilContractuel: l.profilContractuel, ressourceProposee: l.ressourceProposee,
      ressourceRetenue: l.ressourceRetenue, unite: l.unite,
      chargeVendueJh: l.chargeVendueJh, prixVenteUnitaire: l.prixVenteUnitaire,
      quantiteInterneJh: l.quantiteInterneJh, coutUnitaireTcc: l.coutUnitaireTcc,
      fraisDivers: l.fraisDivers, fraisGeneraux: l.fraisGeneraux,
      coutImpots: l.coutImpots, tauxPourcentage: l.tauxPourcentage
    };
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.addingSection.set(null);
  }

  save(): void {
    this.error.set('');
    const done = (d: DevisInterneResponse) => { this.di.set(d); this.cancelEdit(); };
    const fail = () => this.error.set('Enregistrement impossible — vérifier les valeurs saisies.');

    const id = this.editingId();
    if (id != null) {
      this.diSvc.updateLigne(this.projectId, id, this.form).subscribe({ next: done, error: fail });
    } else {
      this.diSvc.addLigne(this.projectId, this.form).subscribe({ next: done, error: fail });
    }
  }

  async remove(l: LigneDiResponse): Promise<void> {
    const ok = await this.confirm.ask(
      `Supprimer la ligne « ${l.profilContractuel || 'sans libellé'} » du Devis Interne ?`,
      'Supprimer la ligne');
    if (!ok) return;
    this.diSvc.deleteLigne(this.projectId, l.id).subscribe(d => this.di.set(d));
  }
}
