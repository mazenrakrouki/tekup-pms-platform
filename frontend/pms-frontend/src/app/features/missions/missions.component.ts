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

/**
 * ============================================================================
 * MISSIONS SCREEN
 * ============================================================================
 *
 * WHAT THIS FILE IS
 * The whole "Missions" page of the application, in one standalone Angular
 * component: pick a project, see the business trips (missions) booked on that
 * project, open a trip to see the cost lines it is made of (the "composantes"),
 * and create or delete both.
 *
 * WHERE IT SITS IN THE FLOW
 *   router (route "missions")
 *        -> MissionsComponent (this file)
 *             -> ProjectService.listAll()      : the list of projects to choose from
 *             -> <app-project-picker>          : the shared project chooser widget
 *             -> MissionService                : GET/POST/DELETE on
 *                                                /api/projects/{id}/missions[/{id}/composantes]
 *             -> TeamService.list(projectId)   : who is assigned to the project, so the
 *                                                "staff" dropdown only offers those people
 *             -> AuthService.hasPermission()   : show or hide the write buttons
 *             -> ConfirmService / ToastService : ask before deleting, tell after saving
 *             -> TranslocoService              : French / English texts
 *
 * WHY IT EXISTS
 * Without this file there is no user interface for missions at all. The REST
 * endpoints under /api/projects/{id}/missions would still exist on the server,
 * but nobody could declare a trip or attach its costs from the browser, so the
 * travel part of a project's real cost could never be entered.
 *
 * SECURITY NOTE - READ THIS BEFORE THE JURY ASKS
 * Everything this component does with canManage() is COSMETIC. Hiding a button
 * is not a security check. The real check happens twice on the server side:
 *   1. @PreAuthorize("hasAuthority('MANAGE_MISSION')") on the service method
 *      (the permission check - and it sits on the SERVICE, not the controller),
 *   2. ADR-021: ProjectScopeInterceptor, which for every URL of the shape
 *      /api/projects/{id}/** also checks that this user belongs to THIS project.
 * Example of why both are needed: a user who has MANAGE_MISSION on project 7
 * could hand-craft a POST to /api/projects/9/missions. The permission alone
 * would let it through; the project scope check is what refuses it.
 */
// @Component turns this class into a page element that Angular can draw.
// Why here and not a plain class: Angular only knows how to create, render and
// destroy things that carry this decorator.
@Component({
  // The HTML tag name. The router creates <app-missions> when the missions
  // route is activated.
  selector: 'app-missions',
  // standalone: true means this component declares its own dependencies below
  // instead of being registered inside an NgModule.
  // Why: the whole front end uses the standalone style, so there is no
  // MissionsModule anywhere. Without this flag Angular would look for a module
  // that declares the component and the build would fail.
  standalone: true,
  // Everything the inline template below is allowed to use. If a name is
  // missing here, the template silently does nothing or fails to compile.
  // CommonModule       -> the `number` pipe used on amounts.
  // FormsModule        -> [(ngModel)] two-way binding in the two modals.
  // ProjectPickerComponent -> the <app-project-picker> tag.
  // TranslocoModule    -> the `transloco` translation pipe.
  // Example of what breaks: drop TranslocoModule and every {{ 'x' | transloco }}
  // throws "The pipe 'transloco' could not be found".
  imports: [CommonModule, FormsModule, ProjectPickerComponent, TranslocoModule],
  styles: [`
    /* Styles written here are scoped to this component only: Angular adds a
       generated attribute to these selectors, so .sub-table cannot leak out and
       restyle a table on another page. */
    /* Expanded composantes sub-row - the colours come from CSS variables (design
       tokens) instead of Bootstrap's bg-light/bg-white.
       Why: the tokens are redefined in dark mode, so the row follows the theme.
       Without them the sub-row would stay white-on-white text in dark mode. */
    .sub-row > td { background: var(--surface-2); }
    .sub-table { background: var(--surface); border: 1px solid var(--border); border-radius: var(--r-sm); }
    .sub-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
  `],
  template: `
    <!-- INLINE TEMPLATE. Only HTML comments are legal from here to the closing
         backtick: // or /* */ would be printed on the page as plain text. -->
    <!-- Top bar with the breadcrumb. When a project is selected the breadcrumb
         gains a "back" button, because the project picker is then hidden behind
         the data table and the user needs a way out. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-airplane" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <!-- selected() is a signal. Reading it inside the template subscribes
             this piece of HTML to it: when select() writes a new project, only
             this branch is redrawn, not the whole page. -->
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
      <!-- Project selector. This is the shared <app-project-picker> widget and
           NOT a plain <select>: the firm rule on this project is that a long
           list of projects must be searchable and paged, never a giant
           dropdown. [selected] is an input (we push the current project in),
           (projectSelected) is an output (the widget pushes the chosen project
           back out and select() runs). -->
      <div class="mb-4">
        <app-project-picker [selected]="selected()"
                            featureIcon="bi-airplane"
                            (projectSelected)="select($event)" />
      </div>

      <!-- The whole mission table only exists once a project is chosen.
           Why: every mission URL is /api/projects/{id}/missions, so without a
           project id there is simply nothing to ask the server for. -->
      @if (selected()) {
        <div class="card">
          <div class="card-header justify-content-between">
            <span><i class="bi bi-airplane me-2"></i>{{ 'nav.missions' | transloco }} — {{ selected()!.name }}</span>
            <!-- canManage() asks AuthService whether this user holds the
                 MANAGE_MISSION permission. It hides the button; it does NOT
                 protect anything. The server refuses the POST anyway. -->
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
                <!-- track m.id tells Angular how to recognise a row it has
                     already drawn. Why it matters: after a reload the array is
                     a brand new array of brand new objects; without a track key
                     Angular would throw every row away and rebuild it, losing
                     the scroll position and making the open sub-row blink. -->
                @for (m of missions(); track m.id) {
                  <tr>
                    <!-- The chevron that opens the cost lines. The arrow only
                         points down for the ONE row whose id equals
                         expandedId(), which is how a single open row at a time
                         is enforced without any extra flag per mission. -->
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
                  <!-- The expanded panel is a SECOND <tr> right after the mission
                       row, not a box floating over it. Why: a real table row keeps
                       the columns aligned and keeps the markup valid HTML. -->
                  @if (expandedId() === m.id) {
                    <tr class="sub-row">
                      <td></td>
                      <!-- colspan makes this single cell cover all the remaining
                           columns of the row. It is computed because the header
                           only shows the "actions" column to a manager.
                           Without a colspan the panel would sit inside the
                           narrow "staff" column instead of the full width. -->
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
                            <!-- composantes() holds the lines of the ONE open
                                 mission, refilled each time a row is opened.
                                 One shared signal is enough because only one
                                 row can be open at a time. -->
                            @for (c of composantes(); track c.id) {
                              <tr>
                                <!-- The translation key is built from the enum
                                     value, e.g. PERDIEM -> 'componentType.PERDIEM'.
                                     Why: one key per type instead of a switch in
                                     the template, so adding a type is a change in
                                     the translation files only. -->
                                <td><span class="badge-draft">{{ 'componentType.' + c.typeComposante | transloco }}</span></td>
                                <!-- ?? is the null-coalescing operator: show a
                                     dash when description is null or undefined.
                                     The description is optional on the server,
                                     so without this the cell would print the
                                     word "null" or stay empty. -->
                                <td class="small">{{ c.description ?? '—' }}</td>
                                <!-- number:'1.0-2' = at least 1 digit before the
                                     decimal point, 0 to 2 after. Why: money must
                                     not be shown as 1200.3333333333 nor as a raw
                                     1200 when the real value is 1200.5. -->
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
                            <!-- @empty is the branch Angular draws when the list
                                 above produced no row. Without it an opened
                                 mission with no cost line would show an empty
                                 frame and the user could not tell the data from
                                 a loading failure. -->
                            @empty {
                              <tr><td colspan="5" class="text-center py-2 text-muted small">{{ 'missions.noComponents' | transloco }}</td></tr>
                            }
                          </tbody>
                          <!-- The total line is hidden when there is nothing to
                               add up, so an empty panel does not display "0". -->
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
                <!-- Empty state for the whole project: a chosen project with no
                     mission yet. It repeats the "new mission" button so the user
                     does not have to look back up at the card header. -->
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

    <!-- Modal: create a mission.
         It is plain Bootstrap markup driven by a signal, with no Bootstrap
         JavaScript. Why: the rest of the app never loads bootstrap.bundle.js, so
         a signal is the only thing that decides whether the dialog exists. -->
    @if (showMissionModal()) {
      <!-- The grey backdrop is a separate element, exactly as Bootstrap expects.
           Without it the page behind would stay at full brightness and the
           dialog would look like it is floating for no reason. -->
      <div class="modal-backdrop fade show"></div>
      <!-- Clicking the dark area closes the dialog. -->
      <div class="modal d-block" tabindex="-1" (click)="showMissionModal.set(false)">
        <!-- stopPropagation keeps a click INSIDE the white box from bubbling up
             to the handler above. Without it, clicking a text field or the Save
             button would also close the dialog and lose what was typed. -->
        <div class="modal-dialog" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">{{ 'missions.newMission' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="showMissionModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="mb-3">
                <label class="form-label">{{ 'missions.colStaff' | transloco }} <span class="text-danger">*</span></label>
                <!-- [(ngModel)] is two-way binding: typing in the control writes
                     into missionForm, and writing into missionForm updates the
                     control. openMissionModal() relies on the second direction to
                     blank the form between two uses. -->
                <select class="form-select" [(ngModel)]="missionForm.userId">
                  <!-- A disabled placeholder option, so the dropdown opens on
                       "choose..." and 0 can never be sent as a real user id. -->
                  <option [value]="0" disabled>{{ 'missions.select' | transloco }}</option>
                  <!-- The choices come from the project team (TeamService), not
                       from the full user directory. Why: a trip is paid by the
                       project, so it should be booked for someone who works on
                       it. A short, relevant list is also far easier to use than
                       every account in the company.
                       Note for honesty: the server checks that the user exists
                       and is still active, it does not itself require team
                       membership. This list is a guide, not a guarantee. -->
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
              <!-- The error is shown INSIDE the dialog, not as a toast. Why: the
                   user must fix a field that is right in front of him, and a
                   toast disappears after a few seconds. -->
              @if (modalError()) { <div class="alert alert-danger py-2 mt-3">{{ modalError() }}</div> }
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showMissionModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <!-- [disabled]="saving()" blocks a second click while the POST is
                   still in the air. Without it an impatient double click would
                   create the same trip twice, and both rows would then show in
                   the table. -->
              <button class="btn btn-primary" (click)="saveMission()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }

    <!-- Modal: add one cost line to a mission.
         Which mission it belongs to is NOT kept in this HTML; it is remembered
         in the private field composanteMissionId, written by
         openComposanteModal(). Why: the dialog is drawn once at the bottom of
         the page, outside the @for loop over the missions. -->
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
                <!-- The five values are the exact names of the TypeComposante
                     enum on the server (PERDIEM = daily allowance, BILLET =
                     ticket, TRANSPORT, SEJOUR = accommodation, TIMBRE = stamp
                     or fiscal fee). They are written by hand here because the
                     list is fixed and closed. A typo would be refused by the
                     server when it converts the text into the enum. -->
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
                  <!-- min and step are only a help for the browser widget:
                       step="0.01" lets the user type cents, min="0" stops the
                       little arrows at zero. They are not a validation: the real
                       "must be greater than zero" test is in saveComposante(),
                       and the server checks it again. -->
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
/**
 * The page controller: it holds the state of the screen in signals and calls the
 * HTTP services.
 *
 * What it gives back: nothing directly. It exposes signals that the template
 * above reads, and methods that the template calls on a click.
 *
 * Why written with signals and not with plain fields: a signal tells Angular
 * exactly which part of the template depends on it, so setting missions() only
 * redraws the table body. It is also why this component needs no manual change
 * detection call after an HTTP answer arrives.
 *
 * Why the state lives here and not in a dedicated state service: this screen is
 * self-contained, nothing else in the application needs the current mission
 * list, so a service would only add indirection.
 *
 * implements OnInit: Angular calls ngOnInit() once, after the inputs are set.
 * The first HTTP calls are made there and not in a constructor, so the component
 * is fully built before any answer can come back.
 */
export class MissionsComponent implements OnInit {
  // inject() is the modern form of constructor injection. Everything is
  // `private readonly` so no other class can reach in and swap a service, and
  // nothing inside can reassign one by accident.
  private readonly projectSvc = inject(ProjectService);
  private readonly missionSvc = inject(MissionService);
  private readonly teamSvc    = inject(TeamService);
  private readonly auth       = inject(AuthService);
  private readonly confirm    = inject(ConfirmService);
  private readonly toast      = inject(ToastService);
  private readonly tr         = inject(TranslocoService);
  private readonly router     = inject(Router);
  private readonly route      = inject(ActivatedRoute);

  /**
   * True when the logged-in user holds the MANAGE_MISSION permission.
   *
   * What it gives back: a boolean used by the template to show or hide the
   * create and delete buttons.
   *
   * Why a permission code and never a role name: authorization in this project
   * is dynamic, so an administrator can move MANAGE_MISSION from one role to
   * another at runtime. Testing 'role === 'CHEF_PROJET'' would freeze that
   * decision in the compiled JavaScript and break the day the roles change.
   *
   * Why an arrow function and not a computed(): it just forwards to the auth
   * service, there is nothing to cache.
   *
   * IMPORTANT: this hides buttons, it does not protect data. The server checks
   * the same permission with @PreAuthorize on the service method, plus the
   * project scope (ADR-021).
   */
  canManage = () => this.auth.hasPermission('MANAGE_MISSION');

  // Every project the user is allowed to see, loaded once in ngOnInit and then
  // used to turn the ?p=12 query parameter back into a real Project object.
  projects = signal<Project[]>([]);
  // The project currently being looked at, or null while none is chosen.
  // null is a real state here, not a missing value: it is what makes the page
  // show the picker instead of the table.
  selected = signal<Project | null>(null);
  // The missions of the selected project, as returned by the server.
  missions = signal<Mission[]>([]);
  // The people assigned to the selected project, reduced to the two fields the
  // "staff" dropdown needs. Why reduce instead of keeping the full
  // TeamAssignment objects: the dropdown only needs an id and a name, and a
  // small shape makes it obvious that nothing else is used.
  teamMembers = signal<{ userId: number; userFullName: string }[]>([]);

  // Id of the mission whose cost lines are open, or null when all rows are
  // closed. One id instead of one boolean per row is what guarantees that at
  // most one panel is open, and it matches the single `composantes` list below.
  expandedId = signal<number | null>(null);
  // The cost lines of the open mission only. They are fetched when the row is
  // opened, not with the mission list. Why: a project can hold many trips and
  // most of them are never opened, so loading every cost line up front would be
  // a large response for data nobody looks at.
  composantes = signal<Composante[]>([]);

  // Visibility of the two dialogs. The dialog markup is created and destroyed
  // with these flags, so a closed dialog is not merely hidden: it is not in the
  // page at all and cannot be reached with the Tab key.
  showMissionModal = signal(false);
  showComposanteModal = signal(false);
  // True while an HTTP save is running; it disables the Save button and shows
  // the spinner, which is what prevents a double submission.
  saving = signal(false);
  // The error text shown inside the open dialog. An empty string means "no
  // error", which is why the template can simply test its truthiness.
  modalError = signal('');
  // Which mission the composante dialog is adding a line to. It is a plain
  // field and not a signal because no part of the template reads it; only
  // saveComposante() does.
  private composanteMissionId = 0;

  // The two dialog forms. They are plain objects, not signals, because
  // [(ngModel)] writes into them directly; and not Reactive Forms, because the
  // fields are few and the rules are simple. They are reassigned whole in
  // openMissionModal() / openComposanteModal(), which is how the form is reset
  // between two uses.
  missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
  composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };

  /**
   * Runs once when the page opens: loads the projects, then follows the URL.
   *
   * What it gives back: nothing; it fills the signals.
   *
   * Why the URL drives the selection instead of a simple field: the chosen
   * project is written in the address as ?p=12 (see select()). That makes the
   * page bookmarkable and makes the browser Back button work. If the selection
   * lived only in memory, pressing Back would change the address bar while the
   * screen kept showing the old project.
   *
   * Why the query parameters are read INSIDE the projects answer: turning "12"
   * back into a Project object needs the list. Reading the URL first would give
   * an id with no object to match it against.
   */
  ngOnInit(): void {
    this.projectSvc.listAll().subscribe(list => {
      this.projects.set(list);
      // queryParamMap is a stream, not a one-shot read: it fires again on every
      // later navigation. That is what makes clearSelection() work - it only
      // navigates, and this handler is what actually empties the screen.
      this.route.queryParamMap.subscribe(params => {
        const pid = params.get('p');
        // No ?p in the URL means "no project chosen": go back to the picker.
        if (!pid) { this.selected.set(null); return; }
        // The id in a URL is always text, so it is compared as text. Without
        // String(p.id) the comparison 12 === "12" would be false and a
        // bookmarked link would never restore its project.
        const project = list.find(p => String(p.id) === pid);
        // The second half of the test stops a reload of data we already have.
        // Without it, select() would set the signal, navigate, and this handler
        // would fire and fetch the same missions a second time.
        if (project && this.selected()?.id !== project.id) {
          this.selected.set(project);
          // Close any open cost panel: expandedId holds a mission id, and that
          // id belongs to the project we are leaving.
          this.expandedId.set(null);
          this.missionSvc.list(project.id).subscribe(d => this.missions.set(d));
          this.teamSvc.list(project.id).subscribe(members =>
            this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
          );
        }
      });
    });
  }

  /**
   * Called when the user picks a project in <app-project-picker>.
   *
   * What it gives back: nothing; it selects the project, writes it in the URL
   * and loads the two lists the screen needs.
   *
   * Why it loads the data itself instead of only navigating and letting the
   * ngOnInit handler react: the data appears immediately, without waiting for
   * the router. The handler then sees that the id has not changed and does
   * nothing, so nothing is fetched twice.
   */
  select(p: Project): void {
    this.selected.set(p);
    // navigate([]) keeps the current route and only changes the query string.
    // replaceUrl: false adds a new entry in the browser history, so Back
    // returns to the picker instead of leaving the page altogether.
    this.router.navigate([], { queryParams: { p: p.id }, replaceUrl: false });
    this.expandedId.set(null);
    this.missionSvc.list(p.id).subscribe(d => this.missions.set(d));
    this.teamSvc.list(p.id).subscribe(members =>
      this.teamMembers.set(members.map(m => ({ userId: m.userId, userFullName: m.userFullName })))
    );
  }

  /**
   * Goes back to the project picker (the "back" button in the breadcrumb).
   *
   * What it gives back: nothing.
   *
   * Why it only navigates and does not call selected.set(null) as well: the URL
   * is the single source of truth. Dropping ?p makes the subscription in
   * ngOnInit fire with no id, and THAT is what clears the screen. Doing both
   * would mean two places decide the same thing, and they could disagree.
   */
  clearSelection(): void {
    this.router.navigate([], { queryParams: {} });
  }

  /**
   * Length of a trip in days, both days included.
   *
   * What it gives back: a whole number, never less than 1.
   *
   * How: the difference of the two dates in milliseconds, divided by the number
   * of milliseconds in a day (86_400_000; the underscores are only there to
   * make the number readable). Then +1 because a trip from the 3rd to the 5th
   * lasts three days, not two.
   *
   * Why Math.round before the +1: a period that crosses a daylight-saving
   * change is one hour short or long, so the division gives 1.958 instead of 2.
   * Without the rounding the page would display 2.958 days.
   *
   * Why Math.max(1, ...): it protects the display if the two dates were ever
   * equal or inverted, so the column can never show 0 or a negative duration.
   *
   * Why it is computed here and not sent by the server: it is pure display; the
   * database stores the two dates, which are the facts.
   */
  duration(m: Mission): number {
    const d = (new Date(m.dateFin).getTime() - new Date(m.dateDebut).getTime()) / 86_400_000;
    return Math.max(1, Math.round(d) + 1);
  }

  /**
   * Opens or closes the cost-lines panel of one mission.
   *
   * What it gives back: nothing; it moves expandedId and refills composantes().
   *
   * Why it fetches every time the row is opened instead of keeping what was
   * read before: the figures are money and another user may have added a line
   * in the meantime. A cached panel could show a total that is no longer true.
   *
   * The non-null assertion on selected()! is safe here: this method can only be
   * reached from a button inside the table, and the table is only drawn when a
   * project is selected.
   */
  toggleComposantes(m: Mission): void {
    // Clicking the row that is already open closes it. The early return is what
    // turns one button into a toggle.
    if (this.expandedId() === m.id) { this.expandedId.set(null); return; }
    this.expandedId.set(m.id);
    this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d));
  }

  /**
   * Sum of the amounts shown in the open cost panel.
   *
   * What it gives back: a number, displayed in the table footer.
   *
   * Number(c.montant) is defensive: the server sends NUMERIC(15,2), and JSON
   * amounts can arrive as a string. Without the conversion, + would join the
   * texts and "100" + "50" would display 10050 instead of 150.
   *
   * WARNING the jury may ask about: this adds the amounts whatever their
   * currency. The total is only meaningful when every line uses the same
   * currency; mixing TND and EUR in one trip gives a number with no meaning.
   */
  composanteTotal(): number {
    return this.composantes().reduce((s, c) => s + Number(c.montant), 0);
  }

  // ── Mission ──────────────────────────────────────────────────────

  /**
   * Opens the "new mission" dialog on a clean form.
   *
   * What it gives back: nothing.
   *
   * Why the form object is replaced with a brand new one instead of having its
   * fields blanked one by one: one line, and no field can be forgotten. Without
   * this reset, a cancelled attempt would leave the old subject and dates in
   * place the next time the dialog opens.
   * The error message is cleared too, otherwise yesterday's red alert would
   * greet the user on a fresh form.
   */
  openMissionModal(): void {
    this.missionForm = { userId: 0, objet: '', lieu: '', dateDebut: '', dateFin: '' };
    this.modalError.set('');
    this.showMissionModal.set(true);
  }

  /**
   * Checks the form, then asks the server to create the mission.
   *
   * What it gives back: nothing; on success it closes the dialog, reloads the
   * table and shows a toast.
   *
   * Why the checks are repeated here when the server checks too: this is only
   * about comfort. Catching an empty subject locally answers instantly and
   * avoids a pointless round trip. The rules that really protect the data are
   * the Bean Validation annotations on MissionRequest and the date rule in the
   * backend MissionService.
   */
  saveMission(): void {
    const f = this.missionForm;
    // !f.userId also catches the placeholder option, because its value is 0 and
    // 0 is falsy. So "no staff chosen" is refused with the same test as an
    // empty subject.
    if (!f.userId || !f.objet || !f.dateDebut || !f.dateFin) {
      this.modalError.set(this.tr.translate('missions.errRequired'));
      return;
    }
    // Both dates are text in the shape YYYY-MM-DD, which an <input type="date">
    // always produces. In that exact shape, comparing the texts gives the same
    // answer as comparing the real dates, because the year comes first and the
    // parts are zero-padded. Example: "2026-03-02" < "2026-03-10".
    // Why not `new Date(...)`: no time zone is involved, so a date typed late in
    // the evening cannot slip to the previous day.
    if (f.dateFin < f.dateDebut) { this.modalError.set(this.tr.translate('missions.errDateOrder')); return; }
    this.saving.set(true);
    this.missionSvc.create(this.selected()!.id, f).subscribe({
      next: () => {
        // The list is read again from the server instead of pushing the created
        // mission into the array by hand. Why: the row the server returns also
        // carries values it computed itself, and a fresh read also picks up what
        // other users changed meanwhile. The cost is one small extra GET.
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        this.showMissionModal.set(false); this.saving.set(false); this.toast.success(this.tr.translate('missions.okMissionCreated'));
      },
      // The error branch keeps the dialog OPEN and puts the message in it, so
      // the user does not lose what he typed. saving is released here too;
      // without that the Save button would stay disabled for ever after the
      // first failure.
      // e.error?.message reads the message field of the server's error body.
      // The ?. guards the case where there is no body at all (a network cut, or
      // a gateway error returning HTML); without it this handler would itself
      // throw and the user would see nothing.
      error: (e) => { this.modalError.set(e.error?.message ?? 'Erreur.'); this.saving.set(false); }
    });
  }

  /**
   * Asks for confirmation, then deletes one mission.
   *
   * What it gives back: a Promise that finishes once the question has been
   * answered. The method is 'async' only so the confirmation can be awaited;
   * the template ignores the returned promise.
   *
   * Why ConfirmService and not the browser's window.confirm: window.confirm
   * freezes the whole browser tab and cannot be styled or translated. The
   * service shows the application's own modal and resolves a promise with the
   * answer, which is what lets this method read like straight-line code.
   *
   * Note: the server does a soft delete (it sets a flag), so the row stays in
   * the database for the audit trail.
   */
  async deleteMission(m: Mission): Promise<void> {
    // Nothing happens until the user says yes. The second argument is the title
    // of the modal; { name: m.objet } fills a placeholder in the translated
    // sentence, so the question names the trip and the user cannot delete the
    // wrong one by mistake.
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteMission', { name: m.objet }), this.tr.translate('missions.deleteMission'))) return;
    this.missionSvc.delete(this.selected()!.id, m.id).subscribe({
      next: () => {
        this.missionSvc.list(this.selected()!.id).subscribe(d => this.missions.set(d));
        // If the deleted mission was the one whose costs were open, close the
        // panel. Without this, composantes() would keep showing the cost lines
        // of a trip that no longer exists in the table.
        if (this.expandedId() === m.id) this.expandedId.set(null);
        this.toast.success(this.tr.translate('missions.okMissionDeleted'));
      },
      error: () => this.toast.error('Suppression impossible.')
    });
  }

  // ── Composante (one cost line of a trip) ─────────────────────────

  /**
   * Opens the "add a cost line" dialog for one mission.
   *
   * What it gives back: nothing.
   *
   * Why the mission id is stored in a field first: the dialog is drawn once,
   * outside the loop over the missions, so at save time there is no 'm' in
   * scope any more. This field is the link between the clicked row and the
   * dialog. Without it, saveComposante() could not tell which trip to bill.
   *
   * The defaults PERDIEM and TND are the most common case in this company, so
   * most lines need only an amount typed in.
   */
  openComposanteModal(m: Mission): void {
    this.composanteMissionId = m.id;
    this.composanteForm = { typeComposante: 'PERDIEM', montant: 0, devise: 'TND', description: '' };
    this.modalError.set('');
    this.showComposanteModal.set(true);
  }

  /**
   * Checks the amount, then asks the server to add the cost line.
   *
   * What it gives back: nothing; on success it closes the dialog, reloads the
   * open panel and shows a toast.
   *
   * Why only the panel is reloaded and not the mission table: the mission row
   * itself does not display any amount, so refreshing it would change nothing
   * on screen.
   */
  saveComposante(): void {
    // Two tests on purpose. The first catches an empty field, which ngModel
    // turns into null on a number input, and null <= 0 would be true but
    // reading it as a number later would be wrong anyway. The second refuses 0
    // and negative amounts. This matches @Positive on the server's
    // ComposanteRequest and the database CHECK constraint chk_comp_montant.
    // Without them a line worth 0 would sit in the trip costing nothing, and a
    // negative line would quietly lower the real cost of the project.
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

  /**
   * Asks for confirmation, then deletes one cost line of a mission.
   *
   * What it gives back: a Promise, for the same reason as deleteMission().
   *
   * Why the mission 'm' is passed in as well as the line 'c': the URL of the
   * endpoint needs all three ids (project, mission, line). Relying on
   * expandedId() instead would work today but would silently break the day two
   * panels can be open at once.
   *
   * After the delete, only the cost lines are read again; the new total in the
   * table footer follows on its own, because composanteTotal() recomputes from
   * the signal that has just changed.
   */
  async deleteComposante(m: Mission, c: Composante): Promise<void> {
    if (!await this.confirm.ask(this.tr.translate('missions.confirmDeleteComponent'), this.tr.translate('missions.deleteComponent'))) return;
    this.missionSvc.deleteComposante(this.selected()!.id, m.id, c.id).subscribe({
      next: () => { this.missionSvc.listComposantes(this.selected()!.id, m.id).subscribe(d => this.composantes.set(d)); this.toast.success(this.tr.translate('missions.okComponentDeleted')); },
      error: () => this.toast.error('Suppression impossible.')
    });
  }
}
