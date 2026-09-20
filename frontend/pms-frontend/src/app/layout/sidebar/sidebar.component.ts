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

// Left navigation bar plus the Ctrl+K/Cmd+K quick search ("command palette"). Filters menu
// items by permission for display only — the backend enforces access via @PreAuthorize and
// ProjectScopeInterceptor (ADR-021) regardless of what this menu shows.

// labelKey/titleKey are translation keys, not text, so the template can re-translate on
// language change. permission is optional (dashboard has none); stores a PERMISSION, never a
// role name, since authorization here is dynamic.
interface NavItem    { labelKey: string; icon: string; route: string; permission?: string; }

/** A titled group of menu lines, for example "Finance" holding KPI and Billing. */
interface NavSection { titleKey: string; items: NavItem[]; }

/** One row in the Ctrl+K window; 'kind' distinguishes a menu page from a project result. */
interface CmdResult  { kind: 'page' | 'project'; label: string; sub?: string; icon: string; route: string; }

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
          <div class="brand-name">{{ 'app.name' | transloco }}</div>
          <div class="brand-sub">{{ 'app.tagline' | transloco }}</div>
        </div>
        <!-- Toggles state in LayoutService (not local) so the page content resizes too. -->
        <button class="sb-collapse-btn"
                (click)="layout.toggle()"
                [title]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco"
                [attr.aria-label]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco">
          <i class="bi bi-layout-sidebar-reverse sb-collapse-icon"></i>
        </button>
      </div>

      <!-- Navigation: only the sections the user is allowed to see -->
      <div class="sb-nav">
        <!-- Stable track keys: without them a language/permission refresh would rebuild every
             link and lose the active highlight and keyboard focus. -->
        @for (section of visibleSections(); track section.titleKey) {
          <div class="sb-section">{{ section.titleKey | transloco }}</div>
          @for (item of section.items; track item.route) {
            <!-- [title] only when collapsed: the label text is hidden, so the tooltip is the
                 only way to know what the icon means. -->
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
            <!-- ?. avoids throwing while context is still null right after a page reload. -->
            <div class="sb-user-name">{{ context()?.fullName }}</div>
            <!-- Display label only; no app decision is based on this role name. -->
            <div class="sb-user-role">{{ roleKey() ? (roleKey() | transloco) : '' }}</div>
          </div>
        </div>
        <div class="sb-actions">
          <app-language-switcher class="sb-label"></app-language-switcher>
          <button class="sb-icon-btn"
                  (click)="theme.toggle()"
                  [title]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco"
                  [attr.aria-label]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco">
            <!-- Icon always shows the mode the click will switch TO. -->
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

    <!-- Quick search window (Ctrl/Cmd + K); @if keeps it out of the DOM while closed. -->
    @if (paletteOpen()) {
      <div class="modal-backdrop fade show" style="z-index:1090"></div>
      <!-- Click outside the box closes the window. -->
      <div (click)="closePalette()"
           style="position:fixed;inset:0;z-index:1091;display:flex;align-items:flex-start;justify-content:center;padding-top:12vh">
        <!-- stopPropagation: a click inside must not bubble up and close the window. -->
        <div (click)="$event.stopPropagation()"
             style="width:min(560px,92vw);background:var(--surface);border:1px solid var(--border);border-radius:var(--r-xl);box-shadow:var(--sh-xl);overflow:hidden">

          <div style="display:flex;align-items:center;gap:.5rem;padding:.75rem .875rem;border-bottom:1px solid var(--border)">
            <i class="bi bi-search" style="color:var(--text-3)"></i>
            <!-- id used by openPalette() to focus the input on open. Split ngModel/change
                 (not [(ngModel)]) because onQuery() also resets activeIndex on each keystroke. -->
            <input id="cmdk-input" type="text" autocomplete="off"
                   [placeholder]="'palette.placeholder' | transloco"
                   [ngModel]="query()" (ngModelChange)="onQuery($event)"
                   (keydown)="onKey($event)"
                   style="flex:1;border:none;background:transparent;outline:none;color:var(--text-1);font-size:14px">
            <span style="font-size:11px;color:var(--text-3);background:var(--border);padding:1px 6px;border-radius:4px">Esc</span>
          </div>

          <div style="max-height:52vh;overflow-y:auto;padding:.375rem">
            <!-- $index compared to activeIndex() so mouse and keyboard share one selection. -->
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
              <div style="padding:1.25rem;text-align:center;color:var(--text-3);font-size:13px">{{ 'common.noResults' | transloco }}</div>
            }
          </div>
        </div>
      </div>
    }
  `
})
// Holds no business data: reads AuthService and only decides what to display. Menu is one
// array (NAV) filtered by a computed signal, so the permission check can't be forgotten.
export class SidebarComponent {
  // Public: the template calls these directly (layout.toggle(), theme.current()).
  readonly auth    = inject(AuthService);
  readonly theme   = inject(ThemeService);
  readonly layout  = inject(LayoutService);
  private readonly router     = inject(Router);
  private readonly projectSvc = inject(ProjectService);
  // Needed because search results are built in TypeScript, where the template pipe can't run.
  private readonly transloco  = inject(TranslocoService);
  readonly lang    = inject(LanguageService);
  // Reads the shared signal (not a copy) so the header updates after login/profile changes.
  readonly context = this.auth.context;

  // Quick search window state.
  readonly paletteOpen = signal(false);
  readonly query       = signal('');
  readonly activeIndex = signal(0);
  private readonly projects = signal<Project[]>([]);
  // Plain boolean, not a signal: only guards against a duplicate backend call.
  private projectsLoaded = false;

  // One array so the permission filter and the Ctrl+K search both walk the same data.
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
    // Roles and Permissions share MANAGE_ROLES: two views of the same job.
    {
      titleKey: 'nav.section.administration',
      items: [
        { labelKey: 'nav.users',       icon: 'bi-person-lines-fill', route: '/admin/users',       permission: 'MANAGE_USERS' },
        { labelKey: 'nav.roles',       icon: 'bi-shield-lock',       route: '/admin/roles',       permission: 'MANAGE_ROLES' },
        { labelKey: 'nav.permissions', icon: 'bi-key',               route: '/admin/permissions', permission: 'MANAGE_ROLES' },
      ]
    },
  ];

/** Translation key of the user's first role (e.g. "roles.ADMIN"); display label only. */
  readonly roleKey = computed(() => {
    const role = this.auth.context()?.roles?.[0] ?? '';
    return role ? `roles.${role}` : '';
  });

  /** First letters of the user's name for the avatar circle; falls back to "??" while loading. */
  readonly initials = computed(() => {
    const name = this.auth.context()?.fullName ?? '';
    return name.split(' ').slice(0, 2).map(n => n[0] ?? '').join('').toUpperCase() || '??';
  });

  // Spreads into a NEW section object rather than mutating NAV, so NAV stays complete for the
  // next user in the same tab. Comfort only — the backend still enforces access on its own.
  readonly visibleSections = computed(() => {
    const perms = this.auth.permissions();
    return this.NAV
      .map(s => ({ ...s, items: s.items.filter(i => !i.permission || perms.includes(i.permission)) }))
      .filter(s => s.items.length > 0);
  });

  /** Ends the session; AuthService clears the tokens and sends the user to the login page. */
  logout(): void { this.auth.logout(); }

  // ---- Quick search window ------------------------------------------------

  // Pages first (even with empty query), projects only once something is typed (else the
  // full project list buries the pages).
  readonly results = computed<CmdResult[]>(() => {
    const q = this.query().trim().toLowerCase();
    // Reads the language signal (unused) so this computed reacts to language changes, since
    // transloco.translate() below is a plain call signals can't otherwise see.
    this.lang.current();

    const pages: CmdResult[] = this.visibleSections().flatMap(s =>
      s.items.map(i => ({
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
        // Matches on both code and name; people remember a project either way.
        .filter(p => p.code.toLowerCase().includes(q) || p.name.toLowerCase().includes(q))
        // Hard cap at 6: a short query like "a" would otherwise match hundreds of projects.
        .slice(0, 6)
        .map(p => ({ kind: 'project' as const, label: p.name, sub: p.code, icon: 'bi-folder2-open', route: `/projects/${p.id}` }));
    }

    return [...pageMatches, ...projMatches];
  });

  /** Opens the search window, clears the previous search, and focuses the input. */
  openPalette(): void {
    this.paletteOpen.set(true);
    this.query.set('');
    this.activeIndex.set(0);
    this.ensureProjectsLoaded();
    // setTimeout(0): waits for the @if block to actually render the input before focusing it.
    setTimeout(() => document.getElementById('cmdk-input')?.focus(), 0);
  }

  /** Closes the search window. The typed text is cleared on the next opening. */
  closePalette(): void { this.paletteOpen.set(false); }

  // Resets activeIndex so it can't point past the end of a changed results list.
  onQuery(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

  /** Arrow keys move the selection, Enter opens it, Escape closes the window. */
  onKey(e: KeyboardEvent): void {
    const n = this.results().length;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.activeIndex.set(n ? (this.activeIndex() + 1) % n : 0);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.activeIndex.set(n ? (this.activeIndex() - 1 + n) % n : 0);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const r = this.results()[this.activeIndex()];
      if (r) this.go(r);
    } else if (e.key === 'Escape') {
      this.closePalette();
    }
  }

  // Closes before navigating so the window doesn't stay on top of the page that's opening.
  go(r: CmdResult): void {
    this.closePalette();
    this.router.navigateByUrl(r.route);
  }

  // Loads once, gated on VIEW_PROJECT (else every opening would draw a 403). Flag set before
  // subscribing so two fast openings can't both fire a request.
  private ensureProjectsLoaded(): void {
    if (this.projectsLoaded || !this.auth.hasPermission('VIEW_PROJECT')) return;
    this.projectsLoaded = true;
    this.projectSvc.listAll().subscribe({
      next: list => this.projects.set(list),
      error: () => { this.projectsLoaded = false; }
    });
  }

  // Document-wide so the shortcut works from any page, not just when the sidebar has focus.
  @HostListener('document:keydown', ['$event'])
  onGlobalKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
      // Prevents the browser's own Ctrl+K (jumps to the address bar in several browsers).
      e.preventDefault();
      this.paletteOpen() ? this.closePalette() : this.openPalette();
    } else if (e.key === 'Escape' && this.paletteOpen()) {
      this.closePalette();
    }
  }
}
