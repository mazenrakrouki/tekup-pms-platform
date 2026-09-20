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

// "Devis Interne" (internal quote) screen: sold vs. internal cost for one project, via
// DiService (/api/projects/{id}/devis-interne, ADR-021 + MANAGE_DI scoped).
// Every amount is server-computed; every write replaces the di signal with the server's full
// fresh response, so figures can never drift between concurrent editors or go stale.
@Component({
  selector: 'app-devis-interne',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslocoModule],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <!-- ?./?? handle project() still being null on first paint. -->
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
      <!-- @if (...; as d): waits for the quote and reads the signal once for the block;
           @else below shows a spinner meanwhile. -->
      @if (di(); as d) {
        <!-- Totals: four summary cards, server-computed, browser only formats. -->
        <div class="row g-3 mb-4">
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!-- Shown in sale currency, then converted to TND. -->
              <div class="small text-muted">Total vendu ({{ d.currency }})</div>
              <div class="fs-5 fw-bold text-primary">{{ d.totalVenduDevise | number:'1.0-0' }}</div>
              <div class="small text-muted">{{ d.totalVenduTnd | number:'1.0-0' }} TND</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!-- JH = jour-homme (man-day); 1 decimal since half-days are common. -->
              <div class="small text-muted">Coût total interne</div>
              <div class="fs-5 fw-bold text-warning">{{ d.totalCoutFinal | number:'1.0-0' }} TND</div>
              <div class="small text-muted">{{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!-- Colored so a loss is never mistaken for a profit at a glance. -->
              <div class="small text-muted">Marge nette vendue</div>
              <div class="fs-5 fw-bold" [class.text-success]="d.margeNette >= 0" [class.text-danger]="d.margeNette < 0">
                {{ d.margeNette | number:'1.0-0' }} TND
              </div>
              <!-- margePct is a ratio, so *100 for display; ?? 0 avoids "NaN %" when nothing sold yet. -->
              <div class="small fw-semibold" [class.text-success]="(d.margePct ?? 0) >= 0" [class.text-danger]="(d.margePct ?? 0) < 0">
                {{ ((d.margePct ?? 0) * 100) | number:'1.1-2' }} %
              </div>
            </div></div>
          </div>
          <div class="col-sm-6 col-xl-3">
            <div class="card h-100"><div class="card-body py-3">
              <!-- Sold vs. internal workload side by side: how a project quietly loses money. -->
              <div class="small text-muted">Charge vendue</div>
              <div class="fs-5 fw-bold">{{ d.totalChargeVendueJh | number:'1.0-1' }} JH</div>
              <div class="small text-muted">vs {{ d.totalQuantiteInterneJh | number:'1.0-1' }} JH internes</div>
            </div></div>
          </div>
        </div>

        <!-- Sections: one card/table per part of the quote (fixed order, HONORAIRES/FRAIS/AUTRES_FRAIS). -->
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
                    <!-- Rate column only for AUTRES_FRAIS; repeated on every row to keep column counts equal. -->
                    @if (section === 'AUTRES_FRAIS') { <th class="text-end">Taux %</th> }
                    <th class="text-end">Coût final</th>
                    <th class="text-end">Marge TND</th>
                    <th class="text-end">Marge %</th>
                    <th style="width:90px"></th>
                  </tr>
                </thead>
                <tbody>
                  <!-- track l.id (db id) keeps rows stable across re-sort/reload. -->
                  @for (l of bySection(section); track l.id) {
                    <!-- editingId() holds at most one id, so only one row can be in edit mode. -->
                    @if (editingId() === l.id) {
                      <!-- Only user-enterable fields are inputs; computed ones stay text-only.
                           min="0" is a friendly browser check — the server enforces it too. -->
                      <tr class="table-active">
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.profilContractuel" placeholder="ex. PC-1 / TSR 5 %"></td>
                        <td><input class="form-control form-control-sm" [(ngModel)]="form.ressourceRetenue"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.chargeVendueJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.prixVenteUnitaire" min="0"></td>
                        <td class="text-end text-muted">—</td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.quantiteInterneJh" min="0"></td>
                        <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.coutUnitaireTcc" min="0"></td>
                        @if (section === 'AUTRES_FRAIS') {
                          <!-- Stored/typed as a ratio (server computes with it), not a percentage. -->
                          <td><input type="number" class="form-control form-control-sm text-end" [(ngModel)]="form.tauxPourcentage" min="0" max="1" step="0.01" placeholder="0.05"></td>
                        }
                        <!-- Computed cells replaced by one placeholder while editing: never show a stale preview. -->
                        <td class="text-end text-muted" colspan="3">calculé après enregistrement</td>
                        <td class="text-end text-nowrap">
                          <!-- Icon-only buttons: aria-label is what a screen reader announces. -->
                          <button class="btn btn-sm btn-success me-1" (click)="save()" title="Enregistrer" aria-label="Enregistrer la ligne"><i class="bi bi-check-lg"></i></button>
                          <button class="btn btn-sm btn-outline-secondary" (click)="cancelEdit()" title="Annuler" aria-label="Annuler l'édition"><i class="bi bi-x-lg"></i></button>
                        </td>
                      </tr>
                    } @else {
                      <!-- Text fields fall back on falsy (dash for ''); numbers test != null so a
                           real 0 still displays instead of being read as "unfilled". -->
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
                        <!-- coutFinal/margeNette/margePct: server-derived, no DB column, printed raw. -->
                        <td class="text-end">{{ l.coutFinal | number:'1.0-0' }}</td>
                        <td class="text-end" [class.text-success]="(l.margeNette ?? 0) >= 0" [class.text-danger]="(l.margeNette ?? 0) < 0">
                          {{ l.margeNette | number:'1.0-0' }}
                        </td>
                        <td class="text-end">{{ l.margePct != null ? ((l.margePct * 100) | number:'1.1-1') + ' %' : '—' }}</td>
                        <td class="text-end text-nowrap">
                          <button class="btn btn-sm btn-outline-primary me-1" (click)="startEdit(l)" title="Modifier" aria-label="Modifier la ligne"><i class="bi bi-pencil"></i></button>
                          <!-- remove(l) confirms first (see method below) — deleting changes the margin, no undo. -->
                          <button class="btn btn-sm btn-outline-danger" (click)="remove(l)" title="Supprimer" aria-label="Supprimer la ligne"><i class="bi bi-trash"></i></button>
                        </td>
                      </tr>
                    }
                  }
                  <!-- New-line row; compared to 'section' (not a boolean) since all three
                       tables share this template and only one should open a form. -->
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
                  <!-- addingSection() !== section: don't show "empty" under the form being filled. -->
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
        <!-- di() stays null on any failure (incl. permission/scope refusal): never shows half a quote. -->
        <div class="text-center py-5"><span class="spinner-border text-primary"></span></div>
      }

      <!-- Outside the @if above: a save can fail while the quote is already displayed. -->
      @if (error()) {
        <div class="alert alert-danger py-2 small">{{ error() }}</div>
      }
    </div>
  `
})
// No business rules of its own: keeps only inline-editor state (which line is open, what's
// being typed) and forwards every write to DiService. One plain form object + one signal
// holding the server's last quote is enough since the server always answers a write with
// the whole recomputed quote — that keeps the single source of truth server-side.
export class DevisInterneComponent implements OnInit {
  // The only door to the quote's reads/writes.
  private readonly diSvc = inject(DiService);
  // Used once, for the breadcrumb's project code only.
  private readonly projectSvc = inject(ProjectService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);

  // Plain number, not a signal: read once in ngOnInit, never changes while the screen is open.
  projectId = 0;
  // Fixed order per the Excel method (F-AFF-13 §3), not server data.
  readonly sections: SectionDi[] = ['HONORAIRES', 'FRAIS', 'AUTRES_FRAIS'];

  // Starts null so the template can tell "not loaded yet" from "loaded and empty".
  di = signal<DevisInterneResponse | null>(null);
  project = signal<Project | null>(null);
  // At most one id, guaranteeing a single open editor (see template).
  editingId = signal<number | null>(null);
  // Separate from editingId: a new line has no id yet, and the two must never both be open.
  addingSection = signal<SectionDi | null>(null);
  error = signal('');

  // Plain object (not a signal): ngModel writes into it directly, reused for add and edit.
  // Typed LigneDiRequest, never LigneDiResponse, so computed fields (coutFinal, margeNette,
  // margePct) can't be sent back to the server by accident.
  form: LigneDiRequest = { section: 'HONORAIRES' };

  // Constructor would run before route data is guaranteed bound, and makes HTTP-in-constructor
  // hard to test without a network stub.
  ngOnInit(): void {
    // snapshot is enough: leaving this project means leaving the screen, so the id is fixed.
    this.projectId = +this.route.snapshot.paramMap.get('id')!;
    // Breadcrumb only; failure isn't fatal, it just falls back to "Projet".
    this.projectSvc.get(this.projectId).subscribe(p => this.project.set(p));
    this.load();
  }

  // Own method (not inlined in ngOnInit) so the screen can be reloaded from scratch after an error.
  load(): void {
    this.diSvc.get(this.projectId).subscribe({
      next: d => this.di.set(d),
      // Deliberately vague: naming "no permission" on an internal-costs page already leaks
      // that the quote exists and has a value.
      error: () => this.error.set('Impossible de charger le Devis Interne.')
    });
  }

  // Filters in the browser (not 3 separate requests) so all three tables read one consistent quote.
  bySection(section: SectionDi): LigneDiResponse[] {
    return (this.di()?.lignes ?? []).filter(l => l.section === section);
  }

  /** Section code to its card-header label; map lives in the model file, typed to force full coverage. */
  sectionLabel(s: SectionDi): string { return SECTION_DI_LABELS[s]; }

  // Closes edit mode FIRST: add/edit share one 'form' object, so a stale edit would leak its
  // values into a new line.
  startAdd(section: SectionDi): void {
    this.editingId.set(null);
    this.addingSection.set(section);
    // Nothing pre-filled beyond section: a suggested rate could become real cost unreviewed.
    this.form = { section };
  }

  // Closes add mode first, same shared-form reason as startAdd.
  startEdit(l: LigneDiResponse): void {
    this.addingSection.set(null);
    this.editingId.set(l.id);
    // Fields copied one by one (not spread): l carries server-computed amounts and the id,
    // which must never be posted back as if they were user input.
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

  // Clears both signals unconditionally: simpler than branching on which mode was active.
  cancelEdit(): void {
    this.editingId.set(null);
    this.addingSection.set(null);
  }

  // One method for add and update: they differ only by HTTP verb / presence of the line id.
  save(): void {
    this.error.set('');
    // Stores the WHOLE returned quote, not just the saved line: section/global totals and
    // margin all shift, and those are server-computed.
    const done = (d: DevisInterneResponse) => { this.di.set(d); this.cancelEdit(); };
    // Form stays open on failure so the user can fix a value instead of retyping the row.
    const fail = () => this.error.set('Enregistrement impossible — vérifier les valeurs saisies.');

    const id = this.editingId();
    // != null (not a truthiness check) so an id of 0 would still count as "editing".
    if (id != null) {
      this.diSvc.updateLigne(this.projectId, id, this.form).subscribe({ next: done, error: fail });
    } else {
      this.diSvc.addLigne(this.projectId, this.form).subscribe({ next: done, error: fail });
    }
  }

  // async/await reads top-to-bottom instead of hiding the delete inside a Promise callback.
  async remove(l: LigneDiResponse): Promise<void> {
    // Repeats the line's label so a misclick on the wrong row is caught before it's confirmed.
    const ok = await this.confirm.ask(
      `Supprimer la ligne « ${l.profilContractuel || 'sans libellé'} » du Devis Interne ?`,
      'Supprimer la ligne');
    if (!ok) return;
    // Like save(), applies the server's whole recomputed quote so totals stay in step.
    this.diSvc.deleteLigne(this.projectId, l.id).subscribe(d => this.di.set(d));
  }
}
