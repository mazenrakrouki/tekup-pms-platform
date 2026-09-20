import { Component, OnInit, signal, inject } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { ProjectService } from '../../core/services/project.service';
import { MissionService } from '../../core/services/mission.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Project } from '../../core/models/project.model';
import { Mission, Composante } from '../../core/models/mission.model';
import { ProjectPickerComponent } from '../../shared/project-picker/project-picker.component';

// Missions screen: pick a project, see its trips (missions), open one for its cost lines
// (composantes), create/delete both. Calls MissionService (/api/projects/{id}/missions[/...]).
// canManage() is cosmetic; server enforces via @PreAuthorize + ProjectScopeInterceptor (ADR-021).
@Component({
  selector: 'app-missions',
  standalone: true,
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  styles: [`
    /* Design-token colors (not Bootstrap bg-light/bg-white) so the sub-row follows dark mode. */
    .sub-row > td { background: var(--surface-2); }
    .sub-table { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-sm); }
    .sub-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
  `],
  template: `
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-airplane" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        @if (selected()) {
          <button class="bc-back-btn" (click)="clearSelection()" [title]="'missions.backToPicker' | transloco">
            <i class="bi bi-arrow-left"></i> {{ 'nav.missions' | transloco }}
          </button>
          <span class="bc-sep">›</span>
          <span class="bc-curr">{{ selected()!.code }}</span>
        } @else {
          <span class="bc-curr">{{ 'nav.missions' | transloco }}</span>
        }
      </div>
    </div>
    <div class="page-body">
      <div class="page-header">
        <h1 class="page-title">{{ 'nav.missions' | transloco }}</h1>
      </div>
      <!-- Shared picker, not a plain <select>: project lists must be searchable/paged. -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-airplane"
                            (projectSelected)="select($event)" />
      </div>

      @if (selected()) {
        <div class="card">
          <div class="card-header justify-content-between">
            <span><i class="bi bi-airplane me-2"></i>{{ 'nav.missions' | transloco }} — {{ selected()!.name }}</span>
            @if (canManage()) {
              <button class="btn btn-primary btn-sm" (click)="openMissionModal()">
                <i class="bi bi-plus-lg me-1"></i>{{ 'missions.newMission' | transloco }}
              </button>
            }
          </div>
          <div class="table-responsive">
            <table class="table table-hover mb-0 align-middle">
              <thead>
                <tr>
                  <th></th><th>{{ 'missions.colStaff' | transloco }}</th><th>{{ 'missions.colSubject' | transloco }}</th><th>{{ 'missions.colPlace' | transloco }}</th><th>{{ 'projects.colStart' | transloco }}</th><th>{{ 'projects.colEnd' | transloco }}</th><th>{{ 'missions.colDuration' | transloco }}</th>
                  @if (canManage()) { <th class="text-end">{{ 'common.actions' | transloco }}</th> }
                </tr>
              </thead>
              <tbody>
                <!-- track m.id: without it, a reload's new array would rebuild every row. -->
                @for (m of missions(); track m.id) {
                  <tr>
                    <!-- Arrow points down only for the row matching expandedId(): one flag
                         instead of one per mission enforces a single open row. -->
                    <td>
                      <button class="btn btn-sm btn-link p-0" (click)="toggleComposantes(m)" [title]="'missions.costComponents' | transloco">
                        <i class="bi" [class.bi-chevron-right]="expandedId() !== m.id" [class.bi-chevron-down]="expandedId() === m.id"></i>
                      </button>
                    </td>
                    <td class="fw-semibold">{{ m.userFullName }}</td>
                    <td>{{ m.objet }}</td>
                    <td><i class="bi bi-geo-alt me-1 text-muted"></i>{{ m.lieu }}</td>
                    <td>{{ m.dateDebut }}</td>
                    <td>{{ m.dateFin }}</td>
                    <td class="text-muted small">{{ duration(m) }} j</td>
                    <td class="text-end">
                      @if (canManage()) {
                        <button class="btn btn-sm btn-outline-danger" (click)="deleteMission(m)"
                                [title]="'common.delete' | transloco" [attr.aria-label]="'missions.deleteMission' | transloco">
                          <i class="bi bi-trash"></i>
                        </button>
                      }
                    </td>
                  </tr>
                  <!-- Second <tr>, not a floating box, so the columns stay aligned. -->
                  @if (expandedId() === m.id) {
                    <tr class="sub-row">
                      <td></td>
                      <!-- colspan matches the header's actions column, which is manager-only. -->
                      <td [attr.colspan]="canManage() ? 7 : 6" class="py-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                          <span class="sub-title">{{ 'missions.costComponents' | transloco }}</span>
                          @if (canManage()) {
                            <button class="btn btn-sm btn-outline-primary" (click)="openComposanteModal(m)">
                              <i class="bi bi-plus-lg me-1"></i>{{ 'missions.addComponent' | transloco }}
                            </button>
                          }
                        </div>
                        <table class="table table-sm mb-0 sub-table align-middle">
                          <thead>
                            <tr><th>{{ 'missions.colType' | transloco }}</th><th>{{ 'common.description' | transloco }}</th><th class="text-end">{{ 'missions.colAmount' | transloco }}</th><th>{{ 'missions.colCurrency' | transloco }}</th><th></th></tr>
                          </thead>
                          <tbody>
                            <!-- One shared signal: only one mission's lines can be open at a time. -->
                            @for (c of composantes(); track c.id) {
                              <tr>
                                <td><span class="badge-draft">{{ 'componentType.' + c.typeComposante | transloco }}</span></td>
                                <td class="small">{{ c.description ?? '—' }}</td>
                                <td class="text-end fw-semibold">{{ c.montant | number:'1.0-2' }}</td>
                                <td>{{ c.devise }}</td>
                                <td class="text-end">
                                  @if (canManage()) {
                                    <button class="btn btn-sm btn-outline-danger" (click)="deleteComposante(m, c)"
                                            [title]="'common.delete' | transloco" [attr.aria-label]="'missions.deleteComponent' | transloco">
                                      <i class="bi bi-trash"></i>
                                    </button>
                                  }
                                </td>
                              </tr>
                            }
                            @empty {
                              <tr><td colspan="5" class="text-center py-2 text-muted small">{{ 'missions.noComponents' | transloco }}</td></tr>
                            }
                          </tbody>
                          <!-- Hidden when empty so the panel doesn't display "0". -->
                          @if (composantes().length) {
                            <tfoot>
                              <tr class="fw-semibold">
                                <td colspan="2" class="text-end">{{ 'common.total' | transloco }}</td>
                                <td class="text-end">{{ composanteTotal() | number:'1.0-2' }}</td>
                                <td colspan="2"></td>
                              </tr>
                            </tfoot>
                          }
                        </table>
                      </td>
                    </tr>
                  }
                }
                @empty {
                  <tr><td colspan="8">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-airplane"></i></div>
                      <div class="es-title">{{ 'missions.empty' | transloco }}</div>
                      <div class="es-desc">{{ 'missions.emptyDesc' | transloco }}</div>
                      @if (canManage()) { <button class="btn btn-primary btn-sm mt-3" (click)="openMissionModal()"><i class="bi bi-plus-lg me-1"></i>{{ 'missions.newMission' | transloco }}</button> }
                    </div>
                  </td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>

    <!-- Bootstrap markup driven by a signal, no Bootstrap JS: this app never loads it. -->
    @if (showMissionModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showMissionModal.set(false)">
        <!-- stopPropagation: a click inside the dialog must not close it. -->
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'missions.newMission' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showMissionModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colStaff' | transloco }} <span class="text-danger">*</span></label>
                <select class="form-select" [(ngModel)]="missionForm.userId">
                  <option [value]="0" disabled>{{ 'missions.select' | transloco }}</option>
                  <!-- From the project team, not the full directory — a guide, not a server
                       guarantee (the server only checks the user exists and is active). -->
                  @for (t of teamMembers(); track t.userId) {
                    <option [value]="t.userId">{{ t.userFullName }}</option>
                  }
                </select>
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colSubject' | transloco }} <span class="text-danger">*</span></label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.objet" [placeholder]="'missions.subjectPh' | transloco">
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colPlace' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="missionForm.lieu" [placeholder]="'missions.placePh' | transloco">
              </div>
              <div class="row g-3">
                <div class="col-6">
                  <label class="form-label">{{ 'missions.startDate' | transloco }} <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateDebut">
                </div>
                <div class="col-6">
                  <label class="form-label">{{ 'missions.endDate' | transloco }} <span class="text-danger">*</span></label>
                  <input type="date" class="form-control" [(ngModel)]="missionForm.dateFin">
                </div>
              </div>
              <!-- In-dialog, not a toast: the user must fix a field right in front of them. -->
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showMissionModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveMission()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Which mission this belongs to lives in the field composanteMissionId (not the HTML):
         the dialog is drawn once, outside the @for loop over missions. -->
    @if (showComposanteModal()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="showComposanteModal.set(false)">
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'missions.addComponentTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showComposanteModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colType' | transloco }} <span class="text-danger">*</span></label>
                <!-- Exact TypeComposante enum names (fixed, closed list); server rejects a typo. -->
                <select class="form-select" [(ngModel)]="composanteForm.typeComposante">
                  <option value="PERDIEM">{{ 'labels.missionItem.PERDIEM' | transloco }}</option>
                  <option value="BILLET">{{ 'labels.missionItem.BILLET' | transloco }}</option>
                  <option value="TRANSPORT">{{ 'labels.missionItem.TRANSPORT' | transloco }}</option>
                  <option value="SEJOUR">{{ 'labels.missionItem.SEJOUR' | transloco }}</option>
                  <option value="TIMBRE">{{ 'labels.missionItem.TIMBRE' | transloco }}</option>
                </select>
              </div>
              <div class="row g-3 mb-3">
                <div class="col-7">
                  <label class="form-label">{{ 'missions.colAmount' | transloco }} <span class="text-danger">*</span></label>
                  <!-- min/step are a browser convenience only; real validation is in saveComposante(). -->
                  <input type="number" class="form-control" [(ngModel)]="composanteForm.montant" min="0" step="0.01">
                </div>
                <div class="col-5">
                  <label class="form-label">{{ 'missions.colCurrency' | transloco }} <span class="text-danger">*</span></label>
                  <select class="form-select" [(ngModel)]="composanteForm.devise">
                    <option value="TND">TND</option>
                    <option value="EUR">EUR</option>
                    <option value="USD">USD</option>
                  </select>
                </div>
              </div>
              <div class="mb-3">
                <label class="form-label">{{ 'common.description' | transloco }}</label>
                <input type="text" class="form-control" [(ngModel)]="composanteForm.description" [placeholder]="'missions.optionalPh' | transloco">
              </div>
              @if (modalError()) { <div class="alert alert-danger py-2">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showComposanteModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <button class="btn btn-primary" (click)="saveComposante()" [disabled]="saving()">
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
// State kept here (not a dedicated service): this screen is self-contained, nothing else
// needs the mission list. implements OnInit so the first HTTP calls run after construction.
export class MissionsComponent implements OnInit {
  private readonly projectSvc = inject(ProjectService);
  private readonly missionSvc = inject(MissionService);
  private readonly teamSvc    = inject(TeamService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  // Display only: hides buttons, doesn't protect data. Server enforces via @PreAuthorize
  // plus project scope (ADR-021).
  canManage = () => this.auth.hasPermission('MANAGE_MISSION');

  projects = signal<Project[]>([]);
  // null is a real state: it's what makes the page show the picker instead of the table.
  selected = signal<Project | null>(null);
  missions = signal<Mission[]>([]);
  // Reduced to just what the staff dropdown needs.
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  // At most one id open, matching the single composantes list below.
  expandedId = signal<number | null>(null);
  // Fetched only when a row opens (not with the mission list): most trips are never opened.
  composantes = signal<Composante[]>([]);

  showMissionModal = signal(false);
  showComposanteModal = signal(false);
  saving = signal(false);
  modalError = signal('');
  // Plain field, not a signal: only saveComposante() reads it, no template binding.
  private composanteMissionId = 0;

  // Plain objects (ngModel writes directly); reassigned whole in open*Modal() to reset the form.
  missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
  composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };

  // URL (?p=12) drives selection so the page is bookmarkable and Back works; query param is
  // read inside the projects answer since matching "12" to an object needs the list.
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      // Stream, not one-shot: fires on every later navigation, which is what makes
      // clearSelection() (navigate-only) actually clear the screen.
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        if (!pid) { this.selected.set(null); return; }
        // String(p.id): URL params are always text.
        const project = list.find(p => String(p.id) === pid);
        // Skip re-fetching data we already have.
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          // The open panel belongs to the project being left.
          this.expandedId.set(null);
          this.missionSvc.list(project.id).subscribe(d => this.missions.set(d));
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
          );
        }
      });
    });
  }

  // Loads data itself (rather than just navigating) so it appears immediately; the ngOnInit
  // handler then sees the id unchanged and skips a duplicate fetch.
  select(p: Project): void {
    this.selected.set(p);
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.expandedId.set(null);
    this.missionSvc.list(p.id).subscribe(d => this.missions.set(d));
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  // Only navigates (doesn't also null selected()): URL is the single source of truth, so
  // dropping ?p is what fires ngOnInit's handler and clears the screen.
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  // Both days included, so 3rd-to-5th is 3 days. Math.round guards a DST crossing giving
  // 1.958 instead of 2; Math.max(1, ...) protects against equal/inverted dates.
  duration(m: Mission): number {
    const d = (new Date(m.dateFin).getTime() - new Date(m.dateDebut).getTime()) / 86_400_000;
    return Math.max(1, Math.round(d) + 1);
  }

  // Re-fetches every open (not cached): another user may have added a line meanwhile.
  // selected()! is safe: only reachable from a table row, which only renders when selected.
  toggleComposantes(m: Mission): void {
    if (this.expandedId() === m.id) { this.expandedId.set(null); return; }
    this.expandedId.set(m.id);
    this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d));
  }

  // Number(c.montant): server amounts can arrive as JSON strings; without it "100"+"50" would
  // concatenate to 10050. Note: sums regardless of currency, meaningful only within one currency.
  composanteTotal(): number {
    return this.composantes().reduce((s, c) => s + Number(c.montant), 0);
  }

  // ── Mission ──────────────────────────────────────────────────────

  openMissionModal(): void {
    this.missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
    this.modalError.set('');
    this.showMissionModal.set(true);
  }

  // Client-side checks are comfort only; real validation is Bean Validation on MissionRequest
  // plus the date rule in MissionService.
  saveMission(): void {
    const f = this.missionForm;
    // !f.userId also catches the disabled placeholder option (value 0).
    if (!f.userId || !f.objet || !f.dateDebut || !f.dateFin) {
      this.modalError.set(this.tr.translate('missions.errRequired'));
      return;
    }
    // Text comparison works because <input type="date"> always gives YYYY-MM-DD
    // (zero-padded, year-first); avoids 'new Date(...)' timezone slippage.
    if (f.dateFin < f.dateDebut) { this.modalError.set(this.tr.translate('missions.errDateOrder')); return; }
    this.saving.set(true);
    this.missionSvc.create(this.selected()!.id, f).subscribe({
      next: () => {
        // Re-read from the server (not a local push): picks up server-computed fields and
        // other users' changes.
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        this.showMissionModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('missions.okMissionCreated'));
      },
      // Dialog stays open so nothing typed is lost. ?. guards a body-less network failure.
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // ConfirmService (not window.confirm): styled, translated, and awaitable. Server does a
  // soft delete, so the row stays for the audit trail.
  async deleteMission(m: Mission): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteMission', { name: m.objet }), this.tr.translate('missions.deleteMission'))) return;
    this.missionSvc.delete(this.selected()!.id, m.id).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        // The deleted mission's cost panel, if open, would otherwise show stale lines.
        if (this.expandedId() === m.id) this.expandedId.set(null);
        this.toast.success(this.tr.translate('missions.okMissionDeleted'));
      },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Composante (one cost line of a trip) ─────────────────────────

  // Mission id stored in a field: the dialog is drawn once, outside the missions loop, so
  // there's no 'm' in scope at save time.
  openComposanteModal(m: Mission): void {
    this.composanteMissionId = m.id;
    this.composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };
    this.modalError.set('');
    this.showComposanteModal.set(true);
  }

  // Only the panel is reloaded, not the mission table: the mission row shows no amount.
  saveComposante(): void {
    // Matches @Positive on ComposanteRequest and the chk_comp_montant DB constraint.
    if (!this.composanteForm.montant || this.composanteForm.montant <= 0) {
      this.modalError.set(this.tr.translate('missions.errAmountPositive'));
      return;
    }
    this.saving.set(true);
    this.missionSvc.createComposante(this.selected()!.id, this.composanteMissionId, this.composanteForm).subscribe({
      next: () => {
        this.missionSvc.listComposantes(this.selected()!.id, this.composanteMissionId).subscribe(d => this.composantes.set(d));
        this.showComposanteModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('missions.okComponentAdded'));
      },
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  // Mission 'm' passed explicitly (not read from expandedId()): the endpoint needs all
  // three ids, and relying on expandedId() would break if two panels could ever be open.
  async deleteComposante(m: Mission, c: Composante): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteComponent'), this.tr.translate('missions.deleteComponent'))) return;
    this.missionSvc.deleteComposante(this.selected()!.id, m.id, c.id).subscribe({
      next: () => { this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d)); this.toast.success(this.tr.translate('missions.okComponentDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
