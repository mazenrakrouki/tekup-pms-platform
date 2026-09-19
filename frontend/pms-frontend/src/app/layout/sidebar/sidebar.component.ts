import { Component, computed, inject, signal, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive, Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { LayoutService } from '../../core/services/layout.service';
import { ProjectService } from '../../core/services/project.service';
import { Project } from '../../core/models/project.model';
import { LanguageService } from '../../core/i18n/language.service';
import { LanguageSwitcherComponent } from '../language-switcher/language-switcher.component';

/*
 * FILE: sidebar.component.ts
 *
 * WHAT THIS FILE IS
 * The left navigation bar of the application, plus the quick search window
 * ("command palette") that opens with Ctrl+K (Cmd+K on a Mac).
 *
 * WHERE IT SITS IN THE FLOW
 * The main layout shell places <app-sidebar> on every page once the user is
 * logged in. This component asks four core services for what it needs:
 *  - AuthService     -> who the user is and which permissions he has
 *  - LayoutService   -> is the sidebar collapsed (narrow) or expanded
 *  - ThemeService    -> light or dark theme
 *  - LanguageService -> the current language (used to recompute translated text)
 * It also calls ProjectService.listAll() to feed the search window with
 * projects. Clicking a link hands over to the Angular Router, which loads the
 * matching feature page.
 *
 * WHY IT EXISTS
 * Without it there is no menu: the user could only move between pages by
 * typing URLs by hand. It is also the place where the menu is filtered by
 * permission, so a user never sees a link to a page he is not allowed to open.
 *
 * SECURITY NOTE (important for the defence)
 * Hiding a link here is ONLY a comfort feature for the user. It is NOT the
 * security. The real check is dynamic and permission-based on the backend:
 * @PreAuthorize("hasAuthority('X')") on the service methods, plus the
 * ProjectScopeInterceptor (ADR-021) which checks BOTH the permission AND the
 * project scope for URLs like /api/projects/{id}/**. If someone types the URL
 * of a hidden page by hand, the backend still refuses the data.
 */

/**
 * One clickable line in the menu.
 * labelKey / titleKey hold translation KEYS, not finished text, because the
 * user can switch language at any moment; the template translates them.
 * 'permission' is optional: an item without it (the dashboard) is shown to
 * everyone who is logged in.
 * Note the code stores a PERMISSION name (VIEW_PROJECT), never a role name.
 * Why: authorization in this project is dynamic; an admin can move a
 * permission from one role to another without any code change.
 */
interface NavItem    { labelKey: string; icon: string; route: string; permission?: string; }

/** A titled group of menu lines, for example "Finance" holding KPI and Billing. */
interface NavSection { titleKey: string; items: NavItem[]; }

/**
 * One row shown in the Ctrl+K search window.
 * 'kind' says where the row comes from (a menu page or a project) so the two
 * sources can be mixed in a single list while staying readable.
 */
interface CmdResult  { kind: 'page' | 'project'; label: string; sub?: string; icon: string; route: string; }

// @Component turns this class into a reusable Angular tag, here <app-sidebar>.
// standalone: true means the component declares its own dependencies in
// `imports` and needs no NgModule. Why: this project uses standalone
// components everywhere; without it, Angular would refuse to compile the
// component because it belongs to no module.
// `imports` must list every directive used in the template below. Example of
// what breaks without it: removing RouterLink makes [routerLink] a plain
// unknown attribute, so every menu entry stops navigating.
@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, RouterLinkActive, TranslocoModule, LanguageSwitcherComponent],
  template: `
    <nav class="sidebar">

      <!-- Header: logo of the company, product name, and the collapse button -->
      <div class="sb-header">
        <img src="https://lh3.googleusercontent.com/aida-public/AB6AXuCw_yrHN-OmVPPni9xX2nNod_E6MTlwO1lflvFjx-0UznQ8bwUkA4RwBfzUcTVnbQ-5HH23gMON8novBTrdrNinlybsrRx6sUzhLrjTuN8UrDZFRnTOuB-TI3jfnxp7WcHqMwIRDv21_t0z5w__oYTKLe6WGemmNGbYMy7G0tBNC-hc4vnfeq_EM_3VW4O3rdQ8IxGtfB9nsEsgFShhYidPbQ52BLx6wfZLiluM4NwiUdsguWnzG3v7f8gq4nQOMZeqk2w1Rnazt3k"
             alt="ST2I"
             class="sb-brand-icon">
        <div class="sb-brand-text">
          <!-- The 'transloco' pipe swaps a key such as app.name for the text of -->
          <!-- the active language. Without it the page would literally print -->
          <!-- "app.name" and nothing would follow a language change. -->
          <div class="brand-name">{{ 'app.name' | transloco }}</div>
          <div class="brand-sub">{{ 'app.tagline' | transloco }}</div>
        </div>
        <!-- layout.toggle() flips the collapsed state kept in LayoutService, not -->
        <!-- in this component. Why: the page content next to the sidebar must -->
        <!-- resize at the same time, so the state has to be shared. -->
        <!-- [attr.aria-label] gives screen readers a name for a button that -->
        <!-- shows only an icon; without it the button is read as "button". -->
        <button class="sb-collapse-btn"
                (click)="layout.toggle()"
                [title]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco"
                [attr.aria-label]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco">
          <i class="bi bi-layout-sidebar-reverse sb-collapse-icon"></i>
        </button>
      </div>

      <!-- Navigation: only the sections the user is allowed to see -->
      <div class="sb-nav">
        <!-- track tells Angular how to recognise a row it has already drawn. -->
        <!-- Without a stable track key, a language change or a permission -->
        <!-- refresh would destroy and rebuild every link, losing the active -->
        <!-- highlight and the keyboard focus. -->
        @for (section of visibleSections(); track section.titleKey) {
          <div class="sb-section">{{ section.titleKey | transloco }}</div>
          @for (item of section.items; track item.route) {
            <!-- routerLinkActive adds the "active" CSS class when the current -->
            <!-- URL matches this link, so the user sees where he is. -->
            <!-- [title] is filled only when the sidebar is collapsed: in that -->
            <!-- narrow mode the text is hidden and the tooltip is the only way -->
            <!-- to know what the icon means. -->
            <a class="nav-link"
               [routerLink]="item.route"
               routerLinkActive="active"
               [attr.data-label]="item.labelKey | transloco"
               [title]="layout.sidebarCollapsed() ? (item.labelKey | transloco) : ''">
              <i class="bi {{ item.icon }}"></i>
              <span class="sb-label">{{ item.labelKey | transloco }}</span>
            </a>
          }
        }
      </div>

      <!-- Footer: who is logged in, language, theme, and logout -->
      <div class="sb-footer">
        <div class="sb-user">
          <div class="sb-avatar">{{ initials() }}</div>
          <div class="sb-user-info">
            <!-- ?. (safe navigation) prints nothing while the user context is -->
            <!-- still null, for example during the first moments after a page -->
            <!-- reload. Without it Angular would throw on "fullName of null". -->
            <div class="sb-user-name">{{ context()?.fullName }}</div>
            <!-- The role is only a LABEL shown to the user. No decision in the -->
            <!-- application is taken from this name; decisions use permissions. -->
            <div class="sb-user-role">{{ roleKey() ? (roleKey() | transloco) : '' }}</div>
          </div>
        </div>
        <div class="sb-actions">
          <app-language-switcher class="sb-label"></app-language-switcher>
          <button class="sb-icon-btn"
                  (click)="theme.toggle()"
                  [title]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco"
                  [attr.aria-label]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco">
            <!-- [class.x] adds one CSS class only when the condition is true, so -->
            <!-- the icon shows a sun in dark mode and a moon in light mode: the -->
            <!-- button always displays the mode the click will switch TO. -->
            <i class="bi"
               [class.bi-sun]="theme.current() === 'dark'"
               [class.bi-moon]="theme.current() === 'light'"></i>
          </button>
          <button class="sb-icon-btn" (click)="logout()" [title]="'common.logout' | transloco" [attr.aria-label]="'common.logout' | transloco">
            <i class="bi bi-box-arrow-right"></i>
          </button>
        </div>
      </div>

    </nav>

    <!-- Quick search window (Ctrl/Cmd + K). -->
    <!-- @if keeps it completely out of the page while it is closed, so its -->
    <!-- input field cannot steal the keyboard focus from the real page. -->
    @if (paletteOpen()) {
      <!-- Grey layer that dims the page behind. z-index 1090 puts it above the -->
      <!-- normal content but below the window itself (1091). -->
      <div class="modal-backdrop fade show" style="z-index:1090"></div>
      <!-- A click anywhere outside the white box closes the window. -->
      <div (click)="closePalette()"
           style="position:fixed;inset:0;z-index:1091;display:flex;align-items:flex-start;justify-content:center;padding-top:12vh">
        <!-- stopPropagation() keeps a click INSIDE the box from bubbling up to -->
        <!-- the parent above; without it, selecting text in the input would -->
        <!-- immediately close the window. -->
        <div (click)="$event.stopPropagation()"
             style="width:min(560px,92vw);background:var(--surface);border:1px solid var(--border);border-radius:var(--r-xl);box-shadow:var(--sh-xl);overflow:hidden">

          <div style="display:flex;align-items:center;gap:.5rem;padding:.75rem .875rem;border-bottom:1px solid var(--border)">
            <i class="bi bi-search" style="color:var(--text-3)"></i>
            <!-- The id "cmdk-input" is used by openPalette() to put the cursor -->
            <!-- here as soon as the window opens, so the user can type at once. -->
            <!-- [ngModel] with (ngModelChange) is one-way binding plus an -->
            <!-- explicit handler, not [(ngModel)]. Why: onQuery() must also -->
            <!-- reset the highlighted row to the first one on every keystroke. -->
            <input id="cmdk-input" type="text" autocomplete="off"
                   [placeholder]="'palette.placeholder' | transloco"
                   [ngModel]="query()" (ngModelChange)="onQuery($event)"
                   (keydown)="onKey($event)"
                   style="flex:1;border:none;background:transparent;outline:none;color:var(--text-1);font-size:14px">
            <span style="font-size:11px;color:var(--text-3);background:var(--border);padding:1px 6px;border-radius:4px">Esc</span>
          </div>

          <div style="max-height:52vh;overflow-y:auto;padding:.375rem">
            <!-- $index gives the position of the row; it is compared with -->
            <!-- activeIndex() to paint the row currently selected by the -->
            <!-- arrow keys, so mouse and keyboard show the same selection. -->
            @for (r of results(); track r.route; let i = $index) {
              <button type="button"
                      (click)="go(r)"
                      (mouseenter)="activeIndex.set(i)"
                      [style.background]="i === activeIndex() ? 'var(--c-brand-dim)' : 'transparent'"
                      style="display:flex;align-items:center;gap:.625rem;width:100%;border:none;border-radius:var(--r-sm);padding:.5rem .625rem;text-align:left;cursor:pointer;color:var(--text-1)">
                <i class="bi {{ r.icon }}" style="color:var(--text-3);font-size:14px;width:16px;flex-shrink:0"></i>
                <span style="flex:1;font-size:13px">{{ r.label }}</span>
                <span style="font-size:11px;color:var(--text-3)">{{ r.sub }}</span>
              </button>
            } @empty {
              <!-- @empty runs when the list has zero rows. Without it the user -->
              <!-- would face an empty box and not know if the search failed. -->
              <div style="padding:1.25rem;text-align:center;color:var(--text-3);font-size:13px">{{ 'common.noResults' | transloco }}</div>
            }
          </div>
        </div>
      </div>
    }
  `
})
/**
 * The sidebar component.
 * It holds no business data of its own: it reads the user context and the
 * permissions from AuthService and only decides what to DISPLAY.
 * Why written this way: keeping the menu definition in one array (NAV) and
 * filtering it with a computed signal means adding a page later is a one-line
 * change, and the permission filter can never be forgotten for that new page.
 */
export class SidebarComponent {
  // inject() is the function form of dependency injection. It is used instead
  // of a constructor because these fields are initialised where they are
  // declared, which keeps the class shorter and works in field initialisers.
  // `readonly auth/theme/layout/lang` are public on purpose: the template
  // above calls them directly (layout.toggle(), theme.current()).
  readonly auth    = inject(AuthService);
  readonly theme   = inject(ThemeService);
  readonly layout  = inject(LayoutService);
  private readonly router     = inject(Router);
  private readonly projectSvc = inject(ProjectService);
  // TranslocoService is the code-side twin of the `transloco` pipe: it is
  // needed because the search results are built in TypeScript, where a
  // template pipe cannot be used.
  private readonly transloco  = inject(TranslocoService);
  readonly lang    = inject(LanguageService);
  // The logged-in user (name, roles, ...) as a signal owned by AuthService.
  // Reading the shared signal, instead of copying the value, means the header
  // updates by itself after a login or a profile change.
  readonly context = this.auth.context;

  // Quick search window state.
  // A signal is a value that remembers who reads it: when it changes, only the
  // parts of the template that use it are redrawn. Example of the gain: typing
  // in the search box repaints the result list, not the whole menu.
  readonly paletteOpen = signal(false);
  readonly query       = signal('');
  // Index of the row highlighted by the arrow keys.
  readonly activeIndex = signal(0);
  // Projects cached for the search. Private: nothing outside may write it.
  private readonly projects = signal<Project[]>([]);
  // Plain boolean, not a signal, on purpose: it is only a guard against
  // calling the backend twice and no part of the screen depends on it.
  private projectsLoaded = false;

  // The whole menu, written once as data instead of as repeated HTML.
  // Why: the permission filter and the Ctrl+K search both walk this same
  // array, so a page can never appear in one place and be missing in the other.
  // The dashboard entry carries no `permission`: every logged-in user has it.
  private readonly NAV: NavSection[] = [
    {
      titleKey: 'nav.section.overview',
      items: [{ labelKey: 'nav.dashboard', icon: 'bi-house', route: '/dashboard' }]
    },
    {
      titleKey: 'nav.section.projects',
      items: [
        { labelKey: 'nav.projects',   icon: 'bi-folder2-open', route: '/projects',     permission: 'VIEW_PROJECT'   },
      ]
    },
    {
      titleKey: 'nav.section.teamWorkload',
      items: [
        { labelKey: 'nav.resources',       icon: 'bi-people',        route: '/resources',        permission: 'VIEW_RESOURCES' },
        { labelKey: 'nav.workload',         icon: 'bi-calendar3',     route: '/workload',         permission: 'VIEW_WORKLOAD'  },
        { labelKey: 'nav.agile',            icon: 'bi-kanban',        route: '/agile',            permission: 'VIEW_AGILE'     },
      ]
    },
    {
      titleKey: 'nav.section.finance',
      items: [
        { labelKey: 'nav.kpi',      icon: 'bi-graph-up-arrow', route: '/kpi',     permission: 'VIEW_KPI'     },
        { labelKey: 'nav.billing',  icon: 'bi-receipt-cutoff', route: '/billing', permission: 'VIEW_BILLING' },
        { labelKey: 'nav.missions', icon: 'bi-airplane',       route: '/missions',permission: 'VIEW_MISSION' },
      ]
    },
    {
      titleKey: 'nav.section.governance',
      items: [
        { labelKey: 'nav.governance', icon: 'bi-shield-check', route: '/governance', permission: 'VIEW_GOVERNANCE' },
      ]
    },
    // Administration pages. Roles and Permissions share one permission
    // (MANAGE_ROLES) because they are two views of the same job: deciding who
    // is allowed to do what.
    {
      titleKey: 'nav.section.administration',
      items: [
        { labelKey: 'nav.users',       icon: 'bi-person-lines-fill', route: '/admin/users',       permission: 'MANAGE_USERS' },
        { labelKey: 'nav.roles',       icon: 'bi-shield-lock',       route: '/admin/roles',       permission: 'MANAGE_ROLES' },
        { labelKey: 'nav.permissions', icon: 'bi-key',               route: '/admin/permissions', permission: 'MANAGE_ROLES' },
      ]
    },
  ];

  /**
   * Translation key of the user's first role, for example "roles.ADMIN";
   * empty string when the user has no role.
   * It is a key and not finished text so the label follows the language.
   * computed() recalculates only when auth.context() changes, so the value is
   * not rebuilt on every redraw of the screen.
   * This is a display label only: no permission decision is taken from it.
   */
  readonly roleKey = computed(() => {
    // ?. and ?? guard the case where the context, the roles list, or the first
    // role is missing (user not loaded yet, or a user with no role assigned).
    const role = this.auth.context()?.roles?.[0] ?? '';
    return role ? `roles.${role}` : '';
  });

  /**
   * The two first letters shown in the round avatar, e.g. "Mazen Rakrouki" -> "MR".
   * Why initials rather than a photo: the application stores no user picture.
   * Falls back to "??" so the circle is never empty while the user loads.
   */
  readonly initials = computed(() => {
    const name = this.auth.context()?.fullName ?? '';
    // slice(0, 2) keeps at most the first two words, so a long name such as
    // "Jean Pierre De La Tour" gives "JP" and not five letters.
    // n[0] ?? '' protects against an empty word coming from a double space.
    return name.split(' ').slice(0, 2).map(n => n[0] ?? '').join('').toUpperCase() || '??';
  });

  /**
   * The menu the current user is allowed to see.
   * It keeps only the items whose permission the user holds, then drops any
   * section left with zero items, so no empty title is displayed.
   * The spread { ...s, items: ... } builds a NEW section object instead of
   * editing the one in NAV. Why: NAV must stay complete; if it were modified
   * in place, the removed items would be lost for the next user in the same
   * browser tab (for example after a logout followed by another login).
   * Reminder: this is comfort, not security. The backend still refuses the
   * data for a page reached by typing its URL.
   */
  readonly visibleSections = computed(() => {
    const perms = this.auth.permissions();
    return this.NAV
      .map(s => ({ ...s, items: s.items.filter(i => !i.permission || perms.includes(i.permission)) }))
      .filter(s => s.items.length > 0);
  });

  /** Ends the session; AuthService clears the tokens and sends the user to the login page. */
  logout(): void { this.auth.logout(); }

  // ---- Quick search window ------------------------------------------------

  /**
   * The rows shown in the search window: navigation pages first, then matching
   * projects.
   * With an empty search box it lists every page the user may open, so the
   * window is useful even before typing anything.
   * Projects are only added once something is typed, because the full project
   * list would bury the pages under dozens of rows.
   */
  readonly results = computed<CmdResult[]>(() => {
    const q = this.query().trim().toLowerCase();
    // Reading the language signal on purpose, without using the value: it
    // registers this computed as a reader of the language. Why: the labels
    // below come from transloco.translate(), which is a plain function call
    // that signals cannot see. Without this line, switching from French to
    // English would leave the search window showing the old language.
    this.lang.current(); // makes the computation react to a language change

    // flatMap flattens the sections into a single list of rows, keeping the
    // section title as the small grey text on the right of each row.
    const pages: CmdResult[] = this.visibleSections().flatMap(s =>
      s.items.map(i => ({
        // `as const` pins the type to the literal 'page' instead of the wider
        // `string`, so the object still matches the CmdResult union type.
        kind: 'page' as const,
        label: this.transloco.translate(i.labelKey),
        sub: this.transloco.translate(s.titleKey),
        icon: i.icon,
        route: i.route,
      }))
    );
    // Both sides are lower-cased before comparing, so typing "kpi" also finds
    // "KPI". The search also looks at the section title, so typing "finance"
    // brings up every page of that section.
    const pageMatches = q
      ? pages.filter(p => p.label.toLowerCase().includes(q) || (p.sub ?? '').toLowerCase().includes(q))
      : pages;

    let projMatches: CmdResult[] = [];
    if (q) {
      projMatches = this.projects()
        // Search on the code and on the name, because people remember a
        // project either way.
        .filter(p => p.code.toLowerCase().includes(q) || p.name.toLowerCase().includes(q))
        // Hard cap at 6 rows: a short word like "a" would otherwise match
        // hundreds of projects and make the window unusable.
        .slice(0, 6)
        .map(p => ({ kind: 'project' as const, label: p.name, sub: p.code, icon: 'bi-folder2-open', route: `/projects/${p.id}` }));
    }

    return [...pageMatches, ...projMatches];
  });

  /** Opens the search window, clears the previous search, and focuses the input. */
  openPalette(): void {
    this.paletteOpen.set(true);
    // Start from a clean box; leaving the old text would show stale results.
    this.query.set('');
    this.activeIndex.set(0);
    this.ensureProjectsLoaded();
    // setTimeout(..., 0) waits for Angular to actually put the input in the
    // page. The @if block above only creates it after this change is applied,
    // so calling focus() immediately would find no element and do nothing.
    setTimeout(() => document.getElementById('cmdk-input')?.focus(), 0);
  }

  /** Closes the search window. The typed text is cleared on the next opening. */
  closePalette(): void { this.paletteOpen.set(false); }

  /**
   * Called on every keystroke in the search box.
   * Resetting activeIndex is the important part: after the list changes, the
   * old position could point past the end, and pressing Enter would then
   * navigate nowhere or to the wrong row.
   */
  onQuery(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

  /**
   * Keyboard handling inside the search box: move with the arrows, open with
   * Enter, close with Escape. This lets the user reach any page without
   * touching the mouse.
   */
  onKey(e: KeyboardEvent): void {
    const n = this.results().length;
    if (e.key === 'ArrowDown') {
      // preventDefault stops the browser default for this key; without it the
      // arrow would also move the text cursor inside the input.
      e.preventDefault();
      // The modulo (% n) wraps around: past the last row we come back to the
      // first. The `n ?` guard avoids a division by zero when the list is
      // empty, which would produce NaN and break the highlight.
      this.activeIndex.set(n ? (this.activeIndex() + 1) % n : 0);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      // "+ n" before the modulo keeps the number positive: without it, going
      // up from row 0 would give -1, which matches no row.
      this.activeIndex.set(n ? (this.activeIndex() - 1 + n) % n : 0);
    } else if (e.key === 'Enter') {
      // Without preventDefault, Enter could submit a surrounding form and
      // reload the page.
      e.preventDefault();
      const r = this.results()[this.activeIndex()];
      // The check protects the case of an empty list, where the lookup gives
      // undefined and go() would crash on r.route.
      if (r) this.go(r);
    } else if (e.key === 'Escape') {
      this.closePalette();
    }
  }

  /**
   * Goes to the page or project chosen in the search window.
   * The window is closed BEFORE navigating, so it does not stay on top of the
   * page that is opening.
   */
  go(r: CmdResult): void {
    this.closePalette();
    this.router.navigateByUrl(r.route);
  }

  /**
   * Loads the project list once, and only if the user may see projects.
   * Why the permission test here: without it, a user without VIEW_PROJECT
   * would trigger a call the backend answers with 403 every time he opens the
   * search window, filling the console with errors for nothing.
   * Why the flag: it keeps one single HTTP call for the whole session instead
   * of one per opening.
   */
  private ensureProjectsLoaded(): void {
    if (this.projectsLoaded || !this.auth.hasPermission('VIEW_PROJECT')) return;
    // The flag is set BEFORE subscribing, so two fast openings in a row cannot
    // both start a request.
    this.projectsLoaded = true;
    this.projectSvc.listAll().subscribe({
      next: list => this.projects.set(list),
      // On failure (network down, session expired) the flag goes back to false
      // so the next opening of the window tries again.
      error: () => { this.projectsLoaded = false; }
    });
  }

  /**
   * Global shortcut Ctrl+K (Cmd+K on a Mac) to open or close the search window,
   * plus Escape to close it.
   * @HostListener registers the handler on the whole document, so the shortcut
   * works from any page and even when the focus is not on the sidebar. It is
   * safe to put it here because the sidebar exists on every logged-in screen.
   */
  @HostListener('document:keydown', ['$event'])
  onGlobalKey(e: KeyboardEvent): void {
    // metaKey covers the Command key on macOS, ctrlKey covers Windows and Linux.
    // Both 'k' and 'K' are tested because the browser reports the capital
    // letter when Shift or Caps Lock is on.
    if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
      // preventDefault stops the browser's own Ctrl+K, which in several
      // browsers jumps to the address bar and starts a web search.
      e.preventDefault();
      this.paletteOpen() ? this.closePalette() : this.openPalette();
    } else if (e.key === 'Escape' && this.paletteOpen()) {
      // The second condition means Escape is only swallowed while the window
      // is open, leaving Escape free for dialogs on the page underneath.
      this.closePalette();
    }
  }
}
