import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { ToastService } from '../../../core/services/toast.service';
import { Permission, Role } from '../../../core/models/rbac.model';

/**
 * WHAT THIS FILE IS
 * The "Roles and permissions" administration screen. One standalone Angular component that
 * lists every role and opens a modal window where an administrator ticks the permissions a
 * role carries. This is the screen through which the authorization matrix of the whole
 * application is edited.
 *
 * WHERE IT SITS IN THE FLOW
 *   Who calls it: app.routes.ts, on the route 'admin/roles'. The route loads this file lazily
 *   (loadComponent), so its code is downloaded only when an administrator opens the page, and
 *   it is protected by permissionGuard with data: { permission: 'MANAGE_ROLES' }.
 *   What it calls next: RbacService (core/services/rbac.service.ts) for every HTTP call, on
 *   /api/admin/roles and /api/admin/permissions. On the server those URLs land in
 *   RoleController, then in RoleAdminService, whose methods carry
 *   @PreAuthorize("hasAuthority('MANAGE_ROLES')").
 *   It also uses ToastService (the short messages in the corner of the screen), ConfirmService
 *   (the "are you sure?" modal) and TranslocoService (the French and English texts).
 *   Data shapes: Role and Permission come from core/models/rbac.model.ts. They mirror the Java
 *   records RoleResponse and PermissionResponse, and the object sent by save() mirrors
 *   RoleRequest.
 *
 * WHY IT EXISTS
 * The authorization model of this project is dynamic: the code never tests a role name, it
 * tests a permission code, and the link between a role and its permissions is data in the
 * database. Remove this screen and that data can only be changed by hand in SQL, or by writing
 * a new migration and redeploying the application. The whole benefit of the dynamic model
 * would be lost.
 *
 * WHAT THIS SCREEN DOES NOT DO - the sentence to keep for the jury
 * Nothing here grants anything. The browser only sends what the administrator asked for. The
 * server decides: @PreAuthorize on the service methods checks the permission, and for any URL
 * shaped like /api/projects/{id} plus anything after it, the ProjectScopeInterceptor checks
 * BOTH the permission AND that this user is really attached to that project (ADR-021). Even
 * the route guard in front of this page is only comfort: it hides a screen, it does not
 * protect the data.
 *
 * SISTER SCREEN
 * features/admin/permissions/permission-list.component.ts SHOWS the catalogue of permissions
 * and is read only. This file is the one that CHANGES who holds what. Both screens are opened
 * with the same permission, MANAGE_ROLES.
 */

/**
 * One block of the permission editor: the name of a module ('PROJET', 'FACTURATION', ...) and
 * the permissions that belong to that module.
 *
 * Why this small local type instead of handing a Map straight to the template: the @for block
 * walks over an array and needs a stable value to track each item by. A Map gives neither, so
 * moduleGroups() below turns the map it builds into this flat, sorted list once.
 */
interface ModuleGroup { module: string; permissions: Permission[]; }

/**
 * The @Component decorator is what turns the plain class below into an Angular component: it
 * attaches the HTML, the styles and the list of building blocks the HTML is allowed to use.
 */
@Component({
  // The HTML tag this component answers to. The router creates the component itself, so the
  // tag is never written anywhere; the selector is still required and is what any future
  // template would use to embed this screen.
  selector: 'app-role-list',
  // standalone: true means the component declares its own dependencies in "imports" below
  // instead of belonging to an NgModule. Why it matters here: a standalone component can be
  // lazy loaded on its own with loadComponent, which is exactly what app.routes.ts does. An
  // NgModule based component would drag its whole module into the bundle.
  standalone: true,
  // Loads the "admin" translation catalogue, that is the file public/i18n/admin/<lang>.json,
  // the first time this screen is opened, and merges it under the prefix "admin." - which is
  // why the template asks for 'admin.roles.title' while the JSON file itself only holds
  // "roles.title". Declaring it here rather than in the root catalogue keeps the admin texts
  // out of the download for a user who never opens an admin page, for example on the login
  // screen.
  providers: [provideTranslocoScope('admin')],
  // The building blocks the template is allowed to use: CommonModule for the Angular pipes,
  // FormsModule for ngModel (the two way binding on the search box and the form fields), and
  // TranslocoModule for the "| transloco" pipe. Forget one of them and the build fails with
  // "can't bind to ngModel" or the pipe is simply not found.
  imports: [CommonModule, FormsModule, TranslocoModule],
  // INLINE TEMPLATE. The HTML lives inside these backticks instead of a separate .html file,
  // which is the convention followed by every screen of this project: one file to read, and
  // the template stays next to the methods it calls.
  // Warning for anyone editing below: inside this block only HTML comments work.
  template: `
    <!-- Top bar of the page: breadcrumb on the left, actions on the right. The .topbar,
         .page-body and .card classes come from the global styles.scss design system, so every
         screen of the application lines up the same way. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-shield-lock" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'admin.breadcrumb.roles' | transloco }}</span>
      </div>
      <div class="tb-right">
        <!-- Opens the modal in "create" mode. openCreate() empties the form first, otherwise
             the screen would reopen with whatever the previous edit left in it. -->
        <button class="btn btn-primary btn-sm" (click)="openCreate()">
          <i class="bi bi-plus-lg"></i>{{ 'admin.roles.new' | transloco }}
        </button>
      </div>
    </div>

    <div class="page-body">
      <div class="card">
        <div class="card-header justify-content-between">
          <span>{{ 'admin.roles.title' | transloco }}</span>
          <div class="input-wrap" style="width:260px;max-width:100%">
            <i class="bi bi-search input-icon"></i>
            <!-- Search box. The binding is split in two on purpose: [ngModel] reads the value
                 out of the search signal, and (ngModelChange) writes the new text back into it
                 with search.set(). The signal stays the single source of truth, and the
                 filtered() list below recomputes by itself on every keystroke. Filtering is
                 done in the browser on the roles already loaded, so no request is sent while
                 the user types. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.roles.search' | transloco"
                   [ngModel]="search()" (ngModelChange)="search.set($event)">
          </div>
        </div>

        <div class="table-responsive">
          <table class="table table-hover mb-0 align-middle">
            <thead>
              <tr>
                <th>{{ 'admin.roles.table.role' | transloco }}</th>
                <!-- d-none d-md-table-cell: the description column disappears below the medium
                     Bootstrap breakpoint. On a phone the five columns would otherwise squeeze
                     the action buttons out of the screen. -->
                <th class="d-none d-md-table-cell">{{ 'common.description' | transloco }}</th>
                <th class="text-center">{{ 'common.permissions' | transloco }}</th>
                <th class="text-center">{{ 'admin.roles.table.users' | transloco }}</th>
                <th class="text-end">{{ 'admin.roles.table.actions' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              <!-- While the list is being fetched, four grey animated rows are shown instead of
                   an empty table. Why: an empty table for half a second reads as "there is no
                   role", which is wrong and worrying on a security screen.
                   track i is required by @for; here the items are the fixed numbers 1 to 4, so
                   the number itself is the stable identity. -->
              @if (loading()) {
                @for (i of [1,2,3,4]; track i) {
                  <tr><td colspan="5"><div class="skeleton-row"></div></td></tr>
                }
              } @else {
                <!-- One row per role kept by the search. track r.id tells Angular which DOM row
                     belongs to which role: when the list is reloaded after a save, it reuses
                     the rows instead of destroying and rebuilding all of them. Without a
                     correct track, the whole table would blink on every save. -->
                @for (r of filtered(); track r.id) {
                  <tr>
                    <td>
                      <div class="d-flex align-items-center gap-2">
                        <span class="fw-semibold">{{ r.name }}</span>
                        <!-- The padlock badge marks the four built in roles (ADMIN, DIRECTEUR,
                             CHEF_PROJET, DEVELOPPEUR). The server refuses to rename or delete
                             them, so the badge tells the administrator why the buttons below
                             behave differently on those rows. Their permissions stay editable,
                             and that is the point of the dynamic matrix: the row is protected,
                             not its content. -->
                        @if (r.system) {
                          <span class="badge-draft" style="font-size:10px" [title]="'admin.roles.systemTitle' | transloco">
                            <i class="bi bi-lock-fill me-1"></i>{{ 'admin.roles.system' | transloco }}
                          </span>
                        }
                      </div>
                    </td>
                    <!-- describeRole() is a method call, not a stored field: the description
                         shown is the translated one when the catalogue has it, and the text
                         stored in the database otherwise. -->
                    <td class="d-none d-md-table-cell cell-desc">{{ describeRole(r) }}</td>
                    <td class="text-center"><span class="badge-active">{{ r.permissions.length }}</span></td>
                    <!-- userCount is computed by the server with a COUNT query and is the same
                         number the server checks before allowing a delete. -->
                    <td class="text-center cell-muted">{{ r.userCount }}</td>
                    <td class="text-end">
                      <button class="btn btn-ghost btn-icon btn-sm" (click)="openEdit(r)" [title]="'admin.roles.editAction' | transloco">
                        <i class="bi bi-pencil"></i>
                      </button>
                      <!-- The delete button is greyed out for the two cases the server refuses:
                           a built in role, or a role still carried by at least one user.
                           Blocking it here is only politeness, remove() checks the same two
                           conditions again and RoleAdminService.delete() answers HTTP 422 if
                           someone calls the API directly. deleteHint() puts the reason in the
                           tooltip, otherwise the user faces a dead button with no explanation. -->
                      <button class="btn btn-ghost btn-icon btn-sm"
                              [disabled]="r.system || r.userCount > 0"
                              [title]="deleteHint(r)"
                              (click)="remove(r)"
                              style="color:var(--c-danger,#dc3545)">
                        <i class="bi bi-trash"></i>
                      </button>
                    </td>
                  </tr>
                }
                <!-- @empty runs when the @for above produced no row at all, that is when the
                     search matches nothing. It saves writing a second @if with the opposite
                     condition, which could drift away from the real content of the list. -->
                @empty {
                  <tr><td colspan="5">
                    <div class="empty-state">
                      <div class="es-icon"><i class="bi bi-shield-lock"></i></div>
                      <div class="es-title">{{ 'admin.roles.empty' | transloco }}</div>
                    </div>
                  </td></tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Create / Edit modal -->
    <!-- The modal is written inside this same template and is created only while showModal()
         is true. Why an @if and not a CSS "hidden" class: when it is closed the form does not
         exist at all in the page, so nothing of a previous edit can survive and be sent by
         mistake on the next save. -->
    @if (showModal()) {
      <!-- The grey layer behind the dialog. Bootstrap styles it; it carries no behaviour. -->
      <div class="modal-backdrop fade show"></div>
      <!-- Click anywhere outside the white dialog closes the modal. -->
      <div class="modal d-block" tabindex="-1" (click)="showModal.set(false)">
        <!-- stopPropagation() keeps a click inside the dialog from travelling up to the parent
             above and closing the window. Without this line, ticking a single checkbox would
             shut the modal and lose everything the administrator had selected. -->
        <div class="modal-dialog modal-lg" (click)="$event.stopPropagation()">
          <div class="modal-content">
            <div class="modal-header">
              <!-- One modal serves both cases. editing() holds the role being changed, or null
                   for a creation, and the title follows it. -->
              <h5 class="modal-title">
                {{ (editing() ? 'admin.roles.form.editTitle' : 'admin.roles.form.newTitle') | transloco }}
              </h5>
              <button type="button" class="btn-close" (click)="showModal.set(false)"></button>
            </div>
            <div class="modal-body">
              <div class="row g-3 mb-3">
                <div class="col-md-5">
                  <label class="form-label">{{ 'common.name' | transloco }} <span class="text-danger">*</span></label>
                  <!-- Three things happen on this one input:
                       1. [(ngModel)] keeps form.name in step with what is typed.
                       2. [disabled] locks the field when a built in role is being edited,
                          because the server refuses to rename one (HTTP 422). The "?? false"
                          is there because editing() can be null, and editing()?.system would
                          then be undefined; [disabled] wants a real true or false.
                       3. (ngModelChange) forces the text to uppercase as it is typed, so the
                          value always matches the rule the server applies,
                          ^[A-Z][A-Z0-9_]*$. Without it a user typing "auditeur" would fill the
                          whole form, press Save and only then be told the name is wrong. -->
                  <input type="text" class="form-control" [(ngModel)]="form.name"
                         placeholder="EX_NOUVEAU_ROLE" [disabled]="editing()?.system ?? false"
                         (ngModelChange)="form.name = $event.toUpperCase()">
                  <!-- Small grey line that explains the locked field above. A disabled input
                       with no explanation looks like a bug. -->
                  @if (editing()?.system) {
                    <div style="font-size:11px;color:var(--text-3);margin-top:.25rem">
                      {{ 'admin.roles.form.nameLocked' | transloco }}
                    </div>
                  }
                </div>
                <div class="col-md-7">
                  <label class="form-label">{{ 'common.description' | transloco }}</label>
                  <input type="text" class="form-control" [(ngModel)]="form.description"
                         [placeholder]="'admin.roles.form.descriptionPlaceholder' | transloco">
                </div>
              </div>

              <div class="d-flex align-items-center justify-content-between mb-2">
                <label class="form-label mb-0">{{ 'common.permissions' | transloco }}</label>
                <!-- The counter is translated with a parameter: the catalogue holds
                     "{{count}} selectionnee(s)" and transloco puts the number in place. Why a
                     parameter and not two glued pieces of text: the number does not sit at the
                     same place in every language. -->
                <span class="sel-count">{{ 'admin.roles.form.selected' | transloco: { count: selectedIds().size } }}</span>
              </div>

              <!-- The permission editor: one box per module, each box holding its checkboxes.
                   Why grouped and not one long list: the catalogue holds dozens of permissions,
                   and a flat list would make it impossible to see that, for example, every
                   right on billing has been given by mistake. -->
              <div class="perm-groups">
                @for (g of moduleGroups(); track g.module) {
                  <div class="perm-group">
                    <div class="perm-group-head">
                      <!-- The module code is translated through the key 'admin.modules.PROJET',
                           'admin.modules.FACTURATION' and so on, so the box shows "Projets"
                           and not the raw database value. -->
                      <span class="perm-group-title">{{ 'admin.modules.' + g.module | transloco }}</span>
                      <!-- One button that ticks or unticks the whole module. Its label follows
                           allSelected(g), so the same button says "tick all" and then "untick
                           all" once everything is on. -->
                      <button type="button" class="btn btn-ghost btn-sm perm-toggle"
                              (click)="toggleModule(g)">
                        {{ (allSelected(g) ? 'admin.roles.form.deselectAll' : 'admin.roles.form.selectAll') | transloco }}
                      </button>
                    </div>
                    <div class="perm-grid">
                      <!-- track p.id: the permission id is the stable identity of a checkbox.
                           With a wrong track, Angular could reuse the DOM of one checkbox for
                           another permission and show a tick on the wrong line. -->
                      @for (p of g.permissions; track p.id) {
                        <!-- The whole line is a <label> wrapping the checkbox, so clicking
                             anywhere on the line toggles it: a much larger target than the
                             small square itself.
                             [class.perm-on] paints the line when the permission is selected,
                             which makes what a role carries readable at a glance. -->
                        <label class="perm-item" [class.perm-on]="selectedIds().has(p.id)">
                          <!-- [checked] is read from the signal and (change) calls togglePerm.
                               The checkbox is deliberately NOT bound with ngModel: the truth
                               lives in the single selectedIds set, which is also what
                               toggleModule and save() work on. Two sources of truth would drift
                               apart the first time "tick all" is used. -->
                          <input type="checkbox" [checked]="selectedIds().has(p.id)"
                                 (change)="togglePerm(p.id)">
                          <!-- The permission code itself is shown, never a role name: this code
                               is exactly the string the server tests in
                               hasAuthority('...'). Seeing MANAGE_DI here and MANAGE_DI in the
                               Java annotation is what makes the matrix auditable. -->
                          <span class="perm-code">{{ p.code }}</span>
                          <!-- The human sentence, shown only when there is one. describePerm()
                               gives back an empty string when neither the catalogue nor the
                               database holds a description, and an empty grey line would leave
                               a hole in the grid. -->
                          @if (describePerm(p)) { <span class="perm-desc">{{ describePerm(p) }}</span> }
                        </label>
                      }
                    </div>
                  </div>
                }
              </div>
            </div>
            <div class="modal-footer">
              <button class="btn btn-secondary" (click)="showModal.set(false)">{{ 'common.cancel' | transloco }}</button>
              <!-- [disabled]="saving()" blocks a second click while the request is in flight.
                   Without it, an impatient double click would send two POST requests and create
                   the same role twice, or at least make the second one fail with "a role
                   already has this name". -->
              <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
                @if (saving()) { <span class="spinner-border spinner-border-sm me-1"></span> }
                {{ 'common.save' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  // INLINE STYLES. They are scoped to this component by Angular, so .perm-item here cannot
  // affect any other screen. Only the rules that exist nowhere else are written here; colours,
  // buttons and cards come from the global styles.scss.
  // Warning for anyone editing below: inside this block only /* */ comments work.
  styles: [`
    /* The grey animated placeholder row shown while the roles are being fetched. The moving
       gradient is what makes it read as "loading" rather than as a broken empty row. */
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    /* Accessibility: a user who asked the operating system for less motion gets the same grey
       row without the sliding animation. Some people get dizzy or lose focus with looping
       movement, and this media query is the standard way to respect that setting. */
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    .cell-desc { color: var(--text-2); font-size: 12px; max-width: 340px; }
    .cell-muted { color: var(--text-2); }
    .sel-count { font-size: 12px; color: var(--text-3); }
    .perm-toggle { font-size: 11px; color: var(--c-brand); }
    /* Keep the modal within the viewport and scroll the body, so the footer (Save) stays
       reachable. Why it is needed here more than on other screens: the permission editor grows
       with the number of permissions, and without these two rules the Save button ends up
       pushed below the bottom of the window with no way to reach it. */
    .modal-content { max-height: calc(100vh - 3.5rem); }
    .modal-body { overflow-y: auto; }
    .perm-groups { display: flex; flex-direction: column; gap: .75rem; padding-right: .25rem; }
    .perm-group { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
    .perm-group-head { display: flex; align-items: center; justify-content: space-between;
      padding: .4rem .75rem; background: var(--surface-2, rgba(0,0,0,.03)); border-bottom: 1px solid var(--border); }
    .perm-group-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    /* auto-fill with minmax(230px, 1fr): as many columns of at least 230px as the width allows.
       On a wide screen the permissions of a module show on three or four columns, on a phone on
       one, without writing a single media query. */
    .perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: .25rem; padding: .5rem; }
    .perm-item { display: flex; align-items: baseline; gap: .5rem; padding: .4rem .5rem; border-radius: 8px;
      cursor: pointer; border: 1px solid transparent; }
    .perm-item:hover { background: var(--surface-2, rgba(0,0,0,.03)); }
    /* The selected state. The border is transparent in the rule above and coloured here, so the
       line does not move by one pixel when it becomes selected. */
    .perm-item.perm-on { background: var(--c-brand-dim); border-color: var(--c-brand); }
    .perm-item input { margin-top: 2px; flex-shrink: 0; }
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    /* flex-basis: 100% pushes the description onto its own line under the code, inside the same
       flex line box. The padding lines it up with the code above it. */
    .perm-desc { font-size: 11px; color: var(--text-3); flex-basis: 100%; padding-left: 1.4rem; }
  `]
})
/**
 * The screen itself: it holds the data that is displayed, the state of the modal, and the eight
 * methods the template calls.
 *
 * "implements OnInit" is the contract for the ngOnInit() method below. Why the first load is
 * done there and not in the constructor: at construction time the component is not attached to
 * the page yet, and a request started there is harder to reason about and to test. ngOnInit
 * runs once, right after Angular has set up the component.
 */
export class RoleListComponent implements OnInit {
  // inject() is the modern form of constructor injection: Angular gives the one shared instance
  // of each service. readonly says these references are never reassigned afterwards.
  // rbac     -> the HTTP calls to /api/admin/roles and /api/admin/permissions
  // confirm  -> the shared "are you sure?" modal (M-11: it replaced window.confirm)
  // toast    -> the short success and error messages in the corner
  // t        -> Transloco, used from TypeScript for the texts that are not in the template
  private readonly rbac = inject(RbacService);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly t     = inject(TranslocoService);

  // ── The data shown on the page ────────────────────────────────
  // A signal is a value that tells Angular, by itself, which parts of the screen must be
  // redrawn when it changes. Calling roles() reads it, roles.set(...) replaces it.
  // Why signals rather than plain fields: filtered() and moduleGroups() below are derived from
  // them and stay correct without a single manual refresh call.

  // Every role returned by the server, with its permissions and its user count.
  roles = signal<Role[]>([]);
  // The full catalogue of permissions, used to build the checkboxes of the modal. It is loaded
  // once in ngOnInit, because the catalogue does not change while the page is open.
  allPermissions = signal<Permission[]>([]);
  // Starts at true so the very first paint already shows the grey skeleton rows instead of an
  // empty table that would read as "there is no role".
  loading = signal(true);
  // What is typed in the search box.
  search = signal('');

  // ── The state of the create / edit modal ──────────────────────
  // Whether the modal is on screen.
  showModal = signal(false);
  // The role being edited, or null when a new one is being created. This single value is what
  // makes one modal serve both cases: the title, and the choice between PUT and POST in save().
  editing = signal<Role | null>(null);
  // True while the save request is in flight, which greys out the Save button and shows the
  // spinner. Without it a double click would send the request twice.
  saving = signal(false);
  // The ids of the ticked permissions. A Set and not an array because the question asked on
  // every checkbox is "is this id in there?": Set.has() answers immediately whatever the size,
  // while array.includes() would walk the list again for each of the dozens of checkboxes on
  // every redraw.
  selectedIds = signal<Set<number>>(new Set());
  // The two text fields of the modal. A plain object and not a signal because ngModel writes
  // into it directly; it is reset by openCreate() and openEdit(), and read once by save().
  form: { name: string; description: string } = { name: '', description: '' };

  /**
   * The roles actually displayed: all of them, or those matching the search box.
   *
   * computed() builds a value derived from other signals. It recomputes only when roles() or
   * search() really change, and it caches the result in between.
   * Why compute instead of filtering in the template: an expression written directly in @for
   * would be re-evaluated on every single redraw of the page, even when nothing changed.
   *
   * The search is done in the browser, on the list already downloaded. The roles table holds a
   * handful of rows, so there is nothing to gain from asking the server again at every
   * keystroke.
   */
  readonly filtered = computed(() => {
    // toLowerCase + trim: the comparison ignores the case and the spaces around the text, so
    // typing " chef " still finds CHEF_PROJET. Without it, the user would have to type the
    // exact uppercase name.
    const s = this.search().toLowerCase().trim();
    const list = this.roles();
    // Empty search: give back the original array as it is, without building a copy for nothing.
    if (!s) return list;
    // The match is tried on the name AND on the description, because an administrator often
    // remembers what a role does ("facturation") rather than its exact code.
    return list.filter(r =>
      r.name.toLowerCase().includes(s) || this.describeRole(r).toLowerCase().includes(s));
  });

  /**
   * Role and permission descriptions are seeded in French by the migrations. The
   * catalogue carries a translation per code; the stored text stays the fallback so
   * a role or permission created later still shows something.
   *
   * Gives back the translated text when the key exists, the text stored in the database
   * otherwise, and an empty string when there is neither.
   *
   * How the test works: Transloco returns the key itself when it finds no translation for it,
   * so "the answer is different from the key I asked for" means "a real translation was found".
   * EXAMPLE of what this protects against: an administrator creates a role AUDITEUR today. No
   * catalogue has a key admin.roleDesc.AUDITEUR. Without this fallback the table would display
   * the raw string "admin.roleDesc.AUDITEUR" to the user instead of the description he just
   * typed.
   */
  private translated(key: string, stored: string | undefined): string {
    const value = this.t.translate(key);
    if (value && value !== key) return value;
    // "stored || ''" also turns undefined into an empty string, because the description field
    // is optional in the API answer.
    return stored || '';
  }

  /**
   * The description shown in the table for one role.
   *
   * Builds the translation key from the role name, for example admin.roleDesc.CHEF_PROJET.
   * The trailing "|| '—'" puts a dash in the cell when there is no text at all: an empty table
   * cell looks like a display bug, a dash reads as "nothing to say here".
   */
  describeRole(r: Role): string {
    return this.translated('admin.roleDesc.' + r.name, r.description) || '—';
  }

  /**
   * The description shown under one permission code in the modal.
   *
   * Same mechanism as describeRole, but with no dash fallback: the template only prints this
   * line when the answer is not empty, so an unexplained permission simply takes no space.
   */
  describePerm(p: Permission): string {
    return this.translated('admin.permissionDesc.' + p.code, p.description);
  }

  /**
   * The catalogue of permissions, cut into one block per module and sorted by module name.
   *
   * Gives back an array of ModuleGroup, rebuilt only when allPermissions() changes.
   * Why group them: the catalogue holds dozens of permissions. A flat list of checkboxes would
   * make it impossible to check at a glance that a role was given every right on billing.
   */
  readonly moduleGroups = computed<ModuleGroup[]>(() => {
    // A Map keyed by module name: looking up "does this module already have a list?" is
    // immediate, while searching an array for each permission would restart from the beginning
    // every time.
    const groups = new Map<string, Permission[]>();
    for (const p of this.allPermissions()) {
      // Read left to right: take the list of this module; if there is none yet (?? handles the
      // undefined returned by get), create it with set(), which gives back the Map, then read
      // the freshly created list from it. The "!" only tells TypeScript that this get cannot be
      // undefined, since the line above has just written it.
      // Without the ?? half, the first permission of each module would crash with
      // "cannot read properties of undefined (reading 'push')".
      (groups.get(p.module) ?? groups.set(p.module, []).get(p.module)!).push(p);
    }
    // [...map.entries()] copies the pairs [module, permissions] into a real array, because a
    // Map cannot be sorted and cannot be walked by the @for block of the template.
    return [...groups.entries()]
      // localeCompare sorts the module names the way a human reads them. Without an explicit
      // sort, the blocks would appear in the order the server happened to send the permissions,
      // and could move around from one load to the next.
      .sort((a, b) => a[0].localeCompare(b[0]))
      // Turns each pair into the named object the template uses, so the HTML reads g.module and
      // g.permissions instead of g[0] and g[1].
      .map(([module, permissions]) => ({ module, permissions }));
  });

  /**
   * Runs once when the screen opens: fetches the roles and the permission catalogue.
   *
   * The two calls are started one after the other without waiting for each other, because
   * neither needs the answer of the other. The table appears as soon as the roles arrive, even
   * if the catalogue is still travelling; the catalogue is only needed when the modal opens.
   */
  ngOnInit(): void {
    this.load();
    // subscribe() is what actually sends the HTTP request: an Angular HttpClient observable
    // does nothing until someone subscribes to it. Without this call the request would never
    // leave the browser and the modal would show no checkbox at all.
    this.rbac.listPermissions().subscribe(p => this.allPermissions.set(p));
  }

  /**
   * Fetches the list of roles and puts it in the roles signal.
   *
   * Called by ngOnInit and again after every successful save or delete. Why reload the whole
   * list instead of patching the row that changed: the server also recomputes userCount and
   * sorts the permissions, so a reload is the only way to show exactly what was stored.
   */
  load(): void {
    this.loading.set(true);
    this.rbac.listRoles().subscribe({
      next: r => { this.roles.set(r); this.loading.set(false); },
      // The error branch must also clear the loading flag. Without it, a failed request would
      // leave the grey skeleton rows spinning for ever and the user would wait for a list that
      // is never coming.
      error: () => { this.loading.set(false); this.toast.error(this.t.translate('admin.roles.msg.loadFailed')); }
    });
  }

  /**
   * The tooltip of the delete button.
   *
   * Gives back the reason the button is disabled, or the plain "Delete the role" title when it
   * is usable. Why bother: a greyed out button with no explanation is read as a broken screen.
   * The two reasons are exactly the two rules RoleAdminService.delete() enforces on the server.
   */
  deleteHint(r: Role): string {
    if (r.system) return this.t.translate('admin.roles.msg.cannotDeleteSystem');
    if (r.userCount > 0) return this.t.translate('admin.roles.msg.cannotDeleteAssigned');
    return this.t.translate('admin.roles.msg.deleteTitle');
  }

  /**
   * Opens the modal to create a new role.
   *
   * Every piece of state is reset explicitly. Why it cannot be skipped: the modal is shared
   * with the edit mode, so without these three lines, clicking "New role" right after editing
   * CHEF_PROJET would open a form pre-filled with its name and its ticked permissions, and the
   * administrator could create a copy of it without noticing.
   */
  openCreate(): void {
    this.editing.set(null);
    this.form = { name: '', description: '' };
    this.selectedIds.set(new Set());
    this.showModal.set(true);
  }

  /**
   * Opens the modal on an existing role, pre-filled with its current values.
   *
   * new Set(r.permissions.map(p => p.id)) builds a working copy of the ticked ids. Working on a
   * copy is what makes Cancel work: whatever is ticked or unticked in the modal never touches
   * the role object held in the roles() list, so closing without saving leaves the table
   * exactly as it was.
   * "r.description ?? ''" replaces a missing description by an empty string, because ngModel on
   * a text input expects a string and would otherwise print "null" in the field.
   */
  openEdit(r: Role): void {
    this.editing.set(r);
    this.form = { name: r.name, description: r.description ?? '' };
    this.selectedIds.set(new Set(r.permissions.map(p => p.id)));
    this.showModal.set(true);
  }

  /**
   * Ticks or unticks one permission.
   *
   * update() reads the current value and returns the next one. The key point is the copy on the
   * second line: a signal compares the old and the new value by reference, so adding an id to
   * the existing Set and returning that same Set would change nothing in Angular's eyes and the
   * checkbox would not move on screen. Building a new Set is what makes the screen react.
   */
  togglePerm(id: number): void {
    this.selectedIds.update(s => {
      const n = new Set(s);
      // Written as a conditional expression rather than an if: if the id is already there,
      // remove it, otherwise add it.
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  }

  /**
   * True when every permission of a module is already ticked.
   *
   * Used twice: to choose the label of the module button ("tick all" or "untick all") and to
   * decide what toggleModule() must do. Having one single method answer the question keeps the
   * label and the action from disagreeing.
   */
  allSelected(g: ModuleGroup): boolean {
    return g.permissions.every(p => this.selectedIds().has(p.id));
  }

  /**
   * Ticks every permission of a module, or unticks them all when they were all ticked.
   *
   * The decision is read once, before the loop. Why it matters: if the test were made inside
   * the loop, the first permission would flip, the following ones would then see a different
   * state, and the module would end up half ticked.
   * Same copy of the Set as in togglePerm, for the same reason.
   */
  toggleModule(g: ModuleGroup): void {
    const on = this.allSelected(g);
    this.selectedIds.update(s => {
      const n = new Set(s);
      for (const p of g.permissions) { on ? n.delete(p.id) : n.add(p.id); }
      return n;
    });
  }

  /**
   * Saves the modal: creates a new role, or updates the one being edited.
   *
   * Returns nothing; the result reaches the user as a toast, and the table is reloaded so it
   * shows what the server really stored.
   *
   * Why the same method handles both cases: the body sent is identical (name, description and
   * the complete list of permission ids), only the HTTP verb and the URL differ. Two separate
   * methods would duplicate the validation and the error handling.
   */
  save(): void {
    const name = this.form.name.trim();
    // Guard 1: a name is required. Checked here so the user gets an immediate message instead
    // of waiting for a round trip; the server checks it again with @NotBlank on RoleRequest.
    if (!name) { this.toast.error(this.t.translate('admin.roles.msg.nameRequired')); return; }
    // Guard 2, the regular expression, read left to right:
    //   ^           start of the text, nothing may come before
    //   [A-Z]       the first character must be an uppercase letter A to Z
    //   [A-Z0-9_]*  then any number of uppercase letters, digits or underscores
    //   $           end of the text, nothing may come after
    // So CHEF_PROJET and AUDIT2 pass, while "chef projet", "Chef_Projet" and "CHEF-PROJET" are
    // refused. It is the exact copy of the @Pattern written on RoleRequest on the server, and
    // it is duplicated on purpose: here it gives a clear message in the modal, there it is the
    // real guarantee for anyone calling the API directly.
    // Why role names are kept in this shape: they are looked up as plain strings by the
    // start-up seeders and by the SQL migrations. Note this is about seeding and display only,
    // no authorization decision anywhere tests a role name; access is always decided on
    // permission codes.
    if (!/^[A-Z][A-Z0-9_]*$/.test(name)) {
      this.toast.error(this.t.translate('admin.roles.msg.nameFormat'));
      return;
    }
    this.saving.set(true);
    const req = {
      name,
      // An empty description is sent as undefined, which means the field is simply absent from
      // the JSON, so the column holds null rather than an empty string. Without this, half the
      // roles would hold null and the other half "" for the very same thing.
      description: this.form.description.trim() || undefined,
      // [...set] turns the Set of ticked ids into the array the JSON body needs. This is the
      // COMPLETE list: the server replaces the permissions of the role with it, it does not
      // merge. That is what makes unticking a box actually remove a permission.
      permissionIds: [...this.selectedIds()],
    };
    // Read into a local variable so the same value is used for the choice below and inside the
    // callbacks, which run later.
    const editing = this.editing();
    // One observable, chosen from the mode: PUT /api/admin/roles/{id} to update, POST
    // /api/admin/roles to create. Nothing is sent yet; subscribe() on the next line is what
    // fires the request.
    const obs = editing ? this.rbac.updateRole(editing.id, req) : this.rbac.createRole(req);
    obs.subscribe({
      next: () => {
        this.saving.set(false);
        this.showModal.set(false);
        this.toast.success(this.t.translate(editing ? 'admin.roles.msg.updated' : 'admin.roles.msg.created'));
        // Reload the table. Beyond showing the new row, this is what brings back the values
        // only the server knows: the generated id, and the permissions sorted by module.
        this.load();
      },
      error: (e) => {
        // The saving flag must be cleared here too, otherwise the Save button would stay greyed
        // out and the administrator could not retry after correcting the name.
        this.saving.set(false);
        // e.error?.detail is the message of the ProblemDetail body the Spring error handler
        // sends back, for example "a role already has this name" (HTTP 409) or "a system role
        // cannot be renamed" (HTTP 422). Showing it is what tells the user which rule was
        // broken. The ?. and the ?? cover the case where the answer has no body at all, for
        // instance when the network is down, and a generic message is shown instead.
        // Note: that last fallback text is written in French straight in the code, while every
        // other message on this screen goes through the translation catalogue. An English user
        // who loses the network therefore reads a French sentence here.
        this.toast.error(e.error?.detail ?? 'Erreur lors de l\'enregistrement.');
      }
    });
  }

  /**
   * Deletes a role, after asking the user to confirm.
   *
   * Declared async only to be able to await the answer of the confirmation modal:
   * ConfirmService.ask() returns a Promise that is resolved when the user clicks Yes or No
   * (M-11, which removed every window.confirm from the project). Without the await, the code
   * would carry on immediately and delete the role before anyone answered.
   */
  async remove(r: Role): Promise<void> {
    // The same two rules as the server, checked again here because the button being disabled is
    // not a guarantee: the template could change, or the userCount could have grown since the
    // list was loaded. RoleAdminService.delete() is the real gate and answers HTTP 422.
    if (r.system || r.userCount > 0) return;
    // Deleting a role is not undoable, so the user confirms first. The role name is passed as a
    // parameter of the message so the modal says which role is about to disappear.
    if (!await this.confirm.ask(
      this.t.translate('admin.roles.msg.deleteConfirm', { name: r.name }),
      this.t.translate('admin.roles.msg.deleteTitle'))) return;
    this.rbac.deleteRole(r.id).subscribe({
      // Reloading is the honest way to refresh: removing the row locally would show a deleted
      // role as gone even if the server had refused it for a reason the browser does not know.
      next: () => { this.toast.success(this.t.translate('admin.roles.msg.deleted')); this.load(); },
      // Same ProblemDetail reading as in save(): the server message explains which of the two
      // rules blocked the delete, for example "this role is assigned to 3 user(s)". The French
      // fallback below is used only when the answer carries no body, for example when the
      // network is down; like the one in save() it is not translated.
      error: (e) => this.toast.error(e.error?.detail ?? 'Suppression impossible.')
    });
  }
}
