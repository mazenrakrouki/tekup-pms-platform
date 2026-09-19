import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { TranslocoModule, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RbacService } from '../../../core/services/rbac.service';
import { ToastService } from '../../../core/services/toast.service';
import { PermissionWithRoles } from '../../../core/models/rbac.model';

//// ============================================================================
// FILE: permission-list.component.ts
//
// WHAT THIS FILE IS
//   The read-only "Permissions" page of the administration area. It shows the
//   whole permission catalogue of the application, grouped by module, and for
//   each permission the names of the roles that hold it today. It is the answer
//   to the question an auditor asks first: "who is allowed to do this?".
//
// WHERE IT SITS IN THE FLOW
//   app.routes.ts, route 'admin/permissions'
//     -> permissionGuard (core/guurds/permission.guard.ts), declared on that
//        route with data: { permission: 'MANAGE_ROLES' }. A user who does not
//        hold MANAGE_ROLES never reaches this class: the guard rewrites the
//        navigation to /dashboard.
//     -> THIS COMPONENT. The route uses loadComponent, so this file is
//        downloaded only the first time somebody opens the page.
//     -> RbacService.listPermissionsWithRoles() (core/services/rbac.service.ts)
//     -> GET /api/admin/permissions?withRoles=true, with the JWT access token
//        added by authInterceptor (core/interceptors/auth.interceptor.ts)
//     -> PermissionController -> PermissionAdminService.findAllWithRoles(),
//        which is guarded again on the server by
//        @PreAuthorize("hasAuthority('MANAGE_ROLES')")
//     -> comes back as PermissionWithRolesResponse (Java record) and is read
//        here through the PermissionWithRoles interface
//        (core/models/rbac.model.ts): id, code, module, description, roleNames.
//   ToastService is used for one thing only: telling the user when the load
//   failed.
//
//   Sister page, same folder level: features/admin/roles/role-list.component.ts.
//   That one WRITES (it attaches permissions to roles); this one only READS.
//   The two share RbacService, the 'admin' translation scope and the same
//   ModuleGroup shape.
//
// WHY IT EXISTS
//   Delete it and nobody can see the security matrix from one place. To learn
//   who holds MANAGE_DI before moving it, an administrator would have to open
//   every role one by one, or read the Flyway migrations. This page also makes
//   visible the permissions held by NO role, which are security rules that
//   block everybody and that somebody probably forgot to assign.
//
// WHY THERE IS NO CREATE / EDIT / DELETE HERE - EXPECT THIS QUESTION
//   A permission code only does something when some Java @PreAuthorize names
//   it. A code invented from a screen would look meaningful, could be ticked on
//   a role, and would guard nothing at all - the worst kind of security
//   illusion. So the catalogue is owned by the code and by the migrations, and
//   administering RBAC means attaching EXISTING codes to roles, which is the
//   roles page. The missing write actions are a decision, not a gap.
//
// WHY THIS PAGE TESTS NO PERMISSION IN ITS OWN CODE
//   The route guard already refused the navigation, and the server checks again
//   on the service method. That server check is the only one that decides:
//   anybody can edit the JavaScript running in his own browser. The test is on
//   a permission name and never on a role name (ADR-001, dynamic RBAC), so an
//   administrator can move MANAGE_ROLES to another role without a new build.
//   ADR-021 (the project scope check) does not apply here: it only guards URLs
//   shaped like /api/projects/{id}/**, and this page calls /api/admin/**.
// ============================================================================

// One module and the permissions that belong to it - for example module
// "PROJET" with CREATE_PROJECT, EDIT_PROJECT and DELETE_PROJECT. This is
// exactly what the template draws: one block, one title, one table.
// WHY a named type rather than an inline one: the computed below is declared as
// computed<ModuleGroup[]>, so a mistake in the grouping code is caught by the
// TypeScript compiler instead of showing an empty table to the user.
// WHY the grouping is built in TypeScript and not in the HTML: an Angular
// template has no "group by" instruction, and a getter doing the work would
// redo it on every change detection pass - here it is done once and cached.
interface ModuleGroup { module: string; permissions: PermissionWithRoles[]; }

/**
 * Read-only view of the RBAC permission catalogue (ADR-001), grouped by module
 * and searchable.
 *
 * <p>Why everything lives in one file: the page has no form, no dialog and no
 * write action. Splitting it into a .ts, a .html and a .scss would spread about
 * a hundred lines over three files for no gain, and the project uses inline
 * templates for its screens of this size.
 */
@Component({
  // The HTML tag name Angular would answer to. It is never written anywhere in
  // the application: this component is reached only through the route
  // 'admin/permissions', which builds it inside the <router-outlet>. The
  // selector is kept because every component of the project declares one, and
  // because it is what a unit test would use to mount the page.
  selector: 'app-permission-list',
  // Standalone means the component declares its own dependencies in `imports`
  // below instead of being listed inside an NgModule. Without it, opening this
  // page would require an AdminModule declaring it, and the lazy loadComponent
  // used in app.routes.ts would not be possible.
  standalone: true,
  // Loads the 'admin' translation catalogue (public/i18n/admin/fr.json and
  // en.json) and makes its keys reachable under the prefix 'admin.'.
  // WHY a scope and not one huge global file: the catalogue is fetched only
  // when this page is opened, so a user who never visits the administration
  // area never downloads the wording of its screens.
  // WITHOUT IT: every key below would be missing, and Transloco would print the
  // raw key, for example 'admin.permissions.title', in the middle of the page.
  providers: [provideTranslocoScope('admin')],
  // What the template is allowed to use.
  //   FormsModule      -> the [ngModel] binding on the search box.
  //   TranslocoModule  -> the | transloco pipe.
  //   CommonModule     -> the common directives and pipes (ngClass, date,
  //                       async...). The if and for blocks used below are built
  //                       into the Angular compiler and do NOT come from it, so
  //                       nothing in this template depends on CommonModule
  //                       today; the neighbouring admin screens list it the
  //                       same way.
  imports: [CommonModule, FormsModule, TranslocoModule],
  template: `
    <!-- Page layout, same on every screen of the application: a thin topbar
         with the breadcrumb, then a page-body holding one card. The classes
         topbar, page-body, card, input-wrap, table, empty-state and
         role-badge-light are defined once in src/styles.scss, so all pages stay
         identical and only the few classes specific to this page are written in
         the styles block further down. -->
    <div class="topbar">
      <div class="tb-breadcrumb">
        <i class="bi bi-key" style="font-size:13px;color:var(--text-3)"></i>
        <span class="bc-sep">›</span>
        <span class="bc-curr">{{ 'common.permissions' | transloco }}</span>
      </div>
    </div>

    <div class="page-body">
      <div class="card">
        <div class="card-header justify-content-between">
          <span>{{ 'admin.permissions.title' | transloco }}</span>
          <div class="input-wrap" style="width:260px;max-width:100%">
            <i class="bi bi-search input-icon"></i>
            <!-- The search box is bound in two separate halves on purpose:
                 [ngModel] pushes the current value of the signal into the
                 input, and (ngModelChange) writes every keystroke back into it
                 with search.set(...).
                 WHY not the usual two-way [(ngModel)]: a signal is read as
                 search() and written as search.set(v); the two-way form would
                 try to assign to the property itself and replace the signal by
                 a plain string, and the computed below would stop reacting.
                 type="search" gives the small clear cross in the browser, which
                 fires the same ngModelChange with an empty value. -->
            <input type="search" class="form-control form-control-sm"
                   [placeholder]="'admin.permissions.search' | transloco"
                   [ngModel]="search()" (ngModelChange)="search.set($event)">
          </div>
        </div>

        <!-- Two states, never both: while the HTTP call is in flight we draw
             grey placeholder rows, afterwards the real table. -->
        @if (loading()) {
          <div class="table-responsive">
            <table class="table mb-0"><tbody>
              <!-- Five fake rows. The numbers are only a way to repeat the row
                   five times; nothing reads them.
                   WHY a skeleton rather than a spinner or nothing: the card
                   already has its final height while the data travels, so the
                   page does not jump under the mouse when the rows arrive.
                   track i tells Angular how to recognise each repeated row; it
                   is required by the for block. -->
              @for (i of [1,2,3,4,5]; track i) {
                <tr><td><div class="skeleton-row"></div></td></tr>
              }
            </tbody></table>
          </div>
        } @else {
          <!-- One block per module, already sorted and filtered by the computed
               below.
               track g.module: the module code is unique in the list and never
               changes, so Angular keeps the existing block when the list is
               recomputed instead of destroying and rebuilding it. Without a
               stable track, every keystroke in the search box would throw away
               the whole table and build it again. -->
          @for (g of filteredGroups(); track g.module) {
            <div class="module-block">
              <div class="module-head">
                <!-- The translation key is built at runtime from the module
                     code sent by the server: module "PROJET" is shown through
                     the key 'admin.modules.PROJET', which reads "Projets" in
                     French and "Projects" in English.
                     WHY not display g.module directly: the user would read the
                     raw technical code in capital letters. If a future
                     migration adds a module that has no key yet, Transloco
                     prints the key and the missing wording is immediately
                     visible in development. -->
                <span class="module-name">{{ 'admin.modules.' + g.module | transloco }}</span>
                <span class="module-count">{{ g.permissions.length }}</span>
              </div>
              <div class="table-responsive">
                <table class="table table-hover mb-0 align-middle">
                  <thead>
                    <tr>
                      <!-- Each module block draws its OWN <table>, and a table
                           sizes its columns from its own content only. Without
                           this fixed width the "Code" column would be narrow in
                           a module holding VIEW_KPI and wide in one holding
                           MANAGE_GOVERNANCE, so the descriptions of the blocks
                           would not line up down the page.
                           The word Code is not translated: it is the same word
                           in French and in English and it names a technical
                           value, so no key was created for it. -->
                      <th style="width:230px">Code</th>
                      <th>{{ 'common.description' | transloco }}</th>
                      <th>{{ 'admin.permissions.roles' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- track p.id uses the database identifier, which is the
                         only value guaranteed to be unique and stable here. -->
                    @for (p of g.permissions; track p.id) {
                      <tr>
                        <td><span class="perm-code">{{ p.code }}</span></td>
                        <!-- describe() picks the translated sentence when there
                             is one and falls back to the French text stored in
                             the database; see the method further down. -->
                        <td class="cell-desc">{{ describe(p) }}</td>
                        <td>
                          <!-- roleNames is the reverse view computed by the
                               server: the roles that hold this permission. An
                               empty list is NOT a detail - it means the
                               permission blocks everybody, so it gets its own
                               visible wording instead of an empty cell that
                               would look like a display bug. -->
                          @if (p.roleNames.length) {
                            <div class="d-flex flex-wrap gap-1">
                              <!-- Role names are unique inside this list, so
                                   the name itself is a safe track value. -->
                              @for (rn of p.roleNames; track rn) {
                                <span class="role-badge-light">{{ rn }}</span>
                              }
                            </div>
                          } @else {
                            <span class="no-role"><i class="bi bi-dash-circle me-1"></i>{{ 'admin.permissions.noRole' | transloco }}</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }
          <!-- Nothing matched the search text, or the catalogue came back
               empty. Without this message the card would simply be blank and
               the user could not tell the difference between "no result" and
               "the page is broken". -->
          @if (filteredGroups().length === 0) {
            <div class="empty-state">
              <div class="es-icon"><i class="bi bi-key"></i></div>
              <div class="es-title">{{ 'admin.permissions.empty' | transloco }}</div>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: [`
    /* Only the few rules specific to this page live here. Angular scopes them
       to this component, so .module-head cannot leak onto another screen. The
       scoping works one way only: the global classes of styles.scss still apply
       to the elements above. */

    /* The grey placeholder bar shown while the data is loading. The background
       is a gradient four times wider than the bar, and the animation slides it,
       which reads as a light sweeping across the row.
       WHY a local class and not the global .skeleton of styles.scss: this one
       needs its own height and radius to line up inside a table cell. */
    .skeleton-row { height: 20px; border-radius: 6px;
      background: linear-gradient(90deg, var(--surface-2,#eee) 25%, var(--surface-3,#f5f5f5) 37%, var(--surface-2,#eee) 63%);
      background-size: 400% 100%; animation: skl 1.2s ease infinite; }
    /* The movement itself: the gradient travels from right to left, for ever,
       until the rows are replaced by the real table. */
    @keyframes skl { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
    /* Accessibility, and it is a real need, not a detail: a user who asked his
       operating system to reduce animations often did so because movement makes
       him dizzy or distracts him. This rule switches the sweep off for him
       while keeping the grey bar, so the page still says "loading". */
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; } }
    /* A thin line between two module blocks. The second rule removes it above
       the first block, which already has the card header just on top of it;
       without that removal the two borders would sit next to each other and
       look like a double line. */
    .module-block { border-top: 1px solid var(--border); }
    .module-block:first-of-type { border-top: 0; }
    /* The module title bar. var(--surface-2, ...) reads the colour token
       defined in styles.scss, which has a light value and a dark value, so this
       page follows the dark theme without a single extra rule here. The colour
       written after the comma is only used if the token were ever removed. */
    .module-head { display: flex; align-items: center; gap: .5rem; padding: .5rem 1rem;
      background: var(--surface-2, rgba(0,0,0,.03)); }
    .module-name { font-size: 11px; font-weight: 700; letter-spacing: .05em; color: var(--text-2); }
    .module-count { font-size: 11px; color: var(--text-3); background: var(--surface-3, rgba(0,0,0,.06));
      border-radius: 20px; padding: 0 .5rem; }
    /* The permission code is shown in a fixed-width font so that codes of
       different lengths stay easy to compare from one row to the next.
       --font-mono is not defined in styles.scss today, so what actually applies
       is the generic monospace family written after the comma; the token is
       named first so that defining it later would restyle every code of the
       application at once. */
    .perm-code { font-size: 12px; font-weight: 600; color: var(--text-1); font-family: var(--font-mono, monospace); }
    .cell-desc { color: var(--text-2); font-size: 12px; }
    .no-role { font-size: 11px; color: var(--text-3); }
  `]
})
// implements OnInit is a TypeScript promise to the compiler that the method
// ngOnInit() below exists and has the right signature. Angular would call the
// method anyway, but without this the day the method is renamed by mistake
// nothing would complain and the page would simply stay empty for ever.
export class PermissionListComponent implements OnInit {
  // inject() replaces the constructor parameters. All three are singletons
  // provided at application level, so this component receives the same
  // instances as every other screen. readonly says they are never reassigned.
  private readonly rbac = inject(RbacService);
  private readonly toast = inject(ToastService);
  // TranslocoService is injected because one translation is needed from
  // TypeScript code and not from the template: the key of a permission
  // description is built at runtime from its code, so the | transloco pipe
  // cannot do it alone and the filter below has to compare translated text.
  private readonly tr = inject(TranslocoService);

  /**
   * Gives back the sentence shown in the "Description" column for one
   * permission, in the language currently active.
   *
   * Three sources, in this order:
   *   1. the translation catalogue, key 'admin.permissionDesc.' + the code,
   *      for example 'admin.permissionDesc.CREATE_PROJECT' - the wording that
   *      exists in French and in English in public/i18n/admin/;
   *   2. the description stored in the permissions table, which migration V25
   *      filled in French;
   *   3. an em dash, so a cell is never completely empty.
   *
   * WHY the stored text is only a fallback: the database column holds one
   * single language, French, so an English user would read French sentences in
   * the middle of an English page.
   * WHY the stored text is kept at all: a permission added by a later migration
   * would otherwise show its raw key on screen until somebody remembers to edit
   * the two JSON files. The page keeps working, in French, on its own.
   *
   * HOW A MISSING KEY IS DETECTED - the point of the second test:
   * when Transloco finds nothing, neither in the active language nor in the
   * French fallback, it returns the key itself as the text. Comparing the
   * answer with the key is therefore the way to know that the translation was
   * missing. Without that test the user would read
   * 'admin.permissionDesc.MANAGE_ROLES' inside the table.
   *
   * The method is called from two places: the template, for every visible row,
   * and the filter below, so that typing a word of the description finds the
   * permission.
   */
  describe(p: PermissionWithRoles): string {
    const key = 'admin.permissionDesc.' + p.code;
    const translated = this.tr.translate(key);
    if (translated && translated !== key) return translated;
    return p.description || '—';
  }

  // The three pieces of state of this page. A signal is a value holder that
  // tells Angular, and any computed built on it, that the value changed.
  // WHY signals rather than plain fields: filteredGroups below is a computed
  // that rebuilds itself only when one of these actually changes, so typing in
  // the search box is the only thing that re-groups the list.
  // permissions() is what the server sent, kept untouched: the filtering never
  // destroys it, so clearing the search box shows the full list again without
  // a second HTTP call.
  permissions = signal<PermissionWithRoles[]>([]);
  // Starts at true, before any request has even been sent, so the very first
  // frame drawn is the skeleton. Starting at false would show the empty-state
  // message "no permission matches" for a fraction of a second, which would
  // look like an error.
  loading = signal(true);
  // What the user typed in the search box.
  search = signal('');

  /**
   * The list the template draws: the permissions kept by the search, grouped by
   * module and sorted by module name.
   *
   * <p>computed() runs this function the first time its value is read, keeps
   * the answer, and runs it again only after permissions() or search() changed.
   * WHY that matters here: the template reads filteredGroups() several times
   * per render pass. A plain method, or a getter, would redo the whole filter,
   * the grouping and the sort on every one of those reads and on every change
   * detection pass of the application.
   *
   * <p>It is readonly because a computed value is derived and can never be
   * assigned from outside; the only way to change what it holds is to change
   * the signals it reads.
   */
  readonly filteredGroups = computed<ModuleGroup[]>(() => {
    // toLowerCase + trim are applied once, here, and not inside the loop:
    // the comparisons below are all lower case, so searching "PROJET",
    // "projet" or " projet " gives the same result. Without trim, a space left
    // by a copy and paste would match nothing at all.
    const s = this.search().toLowerCase().trim();
    // An empty search means "keep everything". The test is written this way so
    // that the untouched page does not run a filter over the whole catalogue
    // for nothing.
    const list = s
      // The search looks at the four things the user can actually see: the
      // code, the description as it is displayed (translated, so searching an
      // English word works when the page is in English), the module code, and
      // the role names.
      // some() on roleNames answers "does at least one role name contain the
      // text": typing "DIRECTEUR" lists every permission that role holds.
      // Without the roleNames line, the fastest way to audit a role from this
      // page would not exist.
      ? this.permissions().filter(p =>
          p.code.toLowerCase().includes(s) ||
          this.describe(p).toLowerCase().includes(s) ||
          p.module.toLowerCase().includes(s) ||
          p.roleNames.some(rn => rn.toLowerCase().includes(s)))
      : this.permissions();

    // Grouping by module. A Map is used rather than a plain object because it
    // keeps the module codes exactly as they are, whereas an object would turn
    // every key into a property name and could collide with names inherited
    // from Object, for example a module called "constructor".
    const groups = new Map<string, PermissionWithRoles[]>();
    for (const p of list) {
      const arr = groups.get(p.module);
      // First permission seen for this module: create the array. The test is
      // needed because get() gives back undefined for an unknown key, and
      // push() on undefined would crash the whole page.
      if (arr) arr.push(p); else groups.set(p.module, [p]);
    }
    // [...map.entries()] copies the pairs into a real array, because a Map
    // cannot be sorted in place and has no map() method.
    return [...groups.entries()]
      // Sorted on the module code with localeCompare, which orders text the way
      // a human expects for the current language, including accented letters -
      // a plain a < b comparison sorts on character codes and puts accented
      // letters after Z. Without any sort, the order would depend on the order
      // the rows arrived in, and two loads of the same page could show the
      // blocks in a different order.
      .sort((a, b) => a[0].localeCompare(b[0]))
      // Turns each pair [key, value] into the ModuleGroup object the template
      // expects. The square brackets on the left unpack the pair into two
      // named variables.
      .map(([module, permissions]) => ({ module, permissions }));
  });

  /**
   * Loads the catalogue once, when the component is created.
   *
   * <p>WHY here and not in the constructor: at constructor time the component
   * is not fully built yet. ngOnInit is the Angular step that means "the
   * component is ready", and putting the HTTP call there also keeps the
   * constructor free of side effects, which makes the class easy to create in a
   * test.
   */
  ngOnInit(): void {
    // An Angular HTTP call is a cold observable: nothing is sent to the server
    // until subscribe() is called. Forgetting subscribe() here would send no
    // request at all and leave the skeleton on screen for ever.
    this.rbac.listPermissionsWithRoles().subscribe({
      // next runs when the answer arrives. Setting permissions() makes the
      // computed above rebuild itself, and setting loading() to false swaps the
      // skeleton for the real table.
      next: p => { this.permissions.set(p); this.loading.set(false); },
      // The failure path. loading is set to false here too, otherwise a server
      // error would leave the grey bars sweeping for ever and the user would
      // wait in front of a page that will never load.
      // A 403 lands here as well: the route guard can only check what the
      // browser knows, while the server checks MANAGE_ROLES again on the
      // service method, and that server answer is the one that decides.
      error: () => { this.loading.set(false); this.toast.error('Impossible de charger les permissions.'); }
    });
    // No unsubscribe and no takeUntilDestroyed: an HttpClient observable sends
    // one value and then completes on its own, which releases the
    // subscription. A never-ending stream, such as a router event stream, would
    // need to be closed by hand to avoid a memory leak.
  }
}
