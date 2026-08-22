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

interface NavItem    { labelKey: string; icon: string; route: string; permission?: string; }
interface NavSection { titleKey: string; items: NavItem[]; }
interface CmdResult  { kind: 'page' | 'project'; label: string; sub?: string; icon: string; route: string; }

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, RouterLinkActive, TranslocoModule, LanguageSwitcherComponent],
  template: `
    <nav class="sidebar">

      <!-- ── Header: brand + collapse ── -->
      <div class="sb-header">
        <img src="https://lh3.googleusercontent.com/aida-public/AB6AXuCw_yrHN-OmVPPni9xX2nNod_E6MTlwO1lflvFjx-0UznQ8bwUkA4RwBfzUcTVnbQ-5HH23gMON8novBTrdrNinlybsrRx6sUzhLrjTuN8UrDZFRnTOuB-TI3jfnxp7WcHqMwIRDv21_t0z5w__oYTKLe6WGemmNGbYMy7G0tBNC-hc4vnfeq_EM_3VW4O3rdQ8IxGtfB9nsEsgFShhYidPbQ52BLx6wfZLiluM4NwiUdsguWnzG3v7f8gq4nQOMZeqk2w1Rnazt3k"
             alt="ST2I"
             class="sb-brand-icon">
        <div class="sb-brand-text">
          <div class="brand-name">{{ 'app.name' | transloco }}</div>
          <div class="brand-sub">{{ 'app.tagline' | transloco }}</div>
        </div>
        <button class="sb-collapse-btn"
                (click)="layout.toggle()"
                [title]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco"
                [attr.aria-label]="(layout.sidebarCollapsed() ? 'common.next' : 'common.previous') | transloco">
          <i class="bi bi-layout-sidebar-reverse sb-collapse-icon"></i>
        </button>
      </div>

      <!-- ── Navigation ── -->
      <div class="sb-nav">
        @for (section of visibleSections(); track section.titleKey) {
          <div class="sb-section">{{ section.titleKey | transloco }}</div>
          @for (item of section.items; track item.route) {
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

      <!-- ── Footer: user + language + theme + logout ── -->
      <div class="sb-footer">
        <div class="sb-user">
          <div class="sb-avatar">{{ initials() }}</div>
          <div class="sb-user-info">
            <div class="sb-user-name">{{ context()?.fullName }}</div>
            <div class="sb-user-role">{{ roleKey() ? (roleKey() | transloco) : '' }}</div>
          </div>
        </div>
        <div class="sb-actions">
          <app-language-switcher class="sb-label"></app-language-switcher>
          <button class="sb-icon-btn"
                  (click)="theme.toggle()"
                  [title]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco"
                  [attr.aria-label]="(theme.current() === 'dark' ? 'theme.light' : 'theme.dark') | transloco">
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

    <!-- ── Palette de recherche rapide (Ctrl/⌘ + K) ── -->
    @if (paletteOpen()) {
      <div class="modal-backdrop fade show" style="z-index:1090"></div>
      <div (click)="closePalette()"
           style="position:fixed;inset:0;z-index:1091;display:flex;align-items:flex-start;justify-content:center;padding-top:12vh">
        <div (click)="$event.stopPropagation()"
             style="width:min(560px,92vw);background:var(--surface);border:1px solid var(--border);border-radius:var(--r-xl);box-shadow:var(--sh-xl);overflow:hidden">

          <div style="display:flex;align-items:center;gap:.5rem;padding:.75rem .875rem;border-bottom:1px solid var(--border)">
            <i class="bi bi-search" style="color:var(--text-3)"></i>
            <input id="cmdk-input" type="text" autocomplete="off"
                   [placeholder]="'palette.placeholder' | transloco"
                   [ngModel]="query()" (ngModelChange)="onQuery($event)"
                   (keydown)="onKey($event)"
                   style="flex:1;border:none;background:transparent;outline:none;color:var(--text-1);font-size:14px">
            <span style="font-size:11px;color:var(--text-3);background:var(--border);padding:1px 6px;border-radius:4px">Esc</span>
          </div>

          <div style="max-height:52vh;overflow-y:auto;padding:.375rem">
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
export class SidebarComponent {
  readonly auth    = inject(AuthService);
  readonly theme   = inject(ThemeService);
  readonly layout  = inject(LayoutService);
  private readonly router     = inject(Router);
  private readonly projectSvc = inject(ProjectService);
  private readonly transloco  = inject(TranslocoService);
  readonly lang    = inject(LanguageService);
  readonly context = this.auth.context;

  // ── Palette de recherche rapide ──────────────────────────────────
  readonly paletteOpen = signal(false);
  readonly query       = signal('');
  readonly activeIndex = signal(0);
  private readonly projects = signal<Project[]>([]);
  private projectsLoaded = false;

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
    {
      titleKey: 'nav.section.administration',
      items: [
        { labelKey: 'nav.users',       icon: 'bi-person-lines-fill', route: '/admin/users',       permission: 'MANAGE_USERS' },
        { labelKey: 'nav.roles',       icon: 'bi-shield-lock',       route: '/admin/roles',       permission: 'MANAGE_ROLES' },
        { labelKey: 'nav.permissions', icon: 'bi-key',               route: '/admin/permissions', permission: 'MANAGE_ROLES' },
      ]
    },
  ];

  /** Clé i18n du rôle courant (traduite dans le template) ; vide si aucun rôle. */
  readonly roleKey = computed(() => {
    const role = this.auth.context()?.roles?.[0] ?? '';
    return role ? `roles.${role}` : '';
  });

  readonly initials = computed(() => {
    const name = this.auth.context()?.fullName ?? '';
    return name.split(' ').slice(0, 2).map(n => n[0] ?? '').join('').toUpperCase() || '??';
  });

  readonly visibleSections = computed(() => {
    const perms = this.auth.permissions();
    return this.NAV
      .map(s => ({ ...s, items: s.items.filter(i => !i.permission || perms.includes(i.permission)) }))
      .filter(s => s.items.length > 0);
  });

  logout(): void { this.auth.logout(); }

  // ── Palette de recherche rapide ──────────────────────────────────
  /** Résultats combinés : pages de navigation (toujours) + projets (si recherche). */
  readonly results = computed<CmdResult[]>(() => {
    const q = this.query().trim().toLowerCase();
    this.lang.current(); // rend le calcul réactif au changement de langue

    const pages: CmdResult[] = this.visibleSections().flatMap(s =>
      s.items.map(i => ({
        kind: 'page' as const,
        label: this.transloco.translate(i.labelKey),
        sub: this.transloco.translate(s.titleKey),
        icon: i.icon,
        route: i.route,
      }))
    );
    const pageMatches = q
      ? pages.filter(p => p.label.toLowerCase().includes(q) || (p.sub ?? '').toLowerCase().includes(q))
      : pages;

    let projMatches: CmdResult[] = [];
    if (q) {
      projMatches = this.projects()
        .filter(p => p.code.toLowerCase().includes(q) || p.name.toLowerCase().includes(q))
        .slice(0, 6)
        .map(p => ({ kind: 'project' as const, label: p.name, sub: p.code, icon: 'bi-folder2-open', route: `/projects/${p.id}` }));
    }

    return [...pageMatches, ...projMatches];
  });

  openPalette(): void {
    this.paletteOpen.set(true);
    this.query.set('');
    this.activeIndex.set(0);
    this.ensureProjectsLoaded();
    setTimeout(() => document.getElementById('cmdk-input')?.focus(), 0);
  }

  closePalette(): void { this.paletteOpen.set(false); }

  onQuery(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

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

  go(r: CmdResult): void {
    this.closePalette();
    this.router.navigateByUrl(r.route);
  }

  /** Charge la liste des projets une seule fois, uniquement si l'utilisateur peut les voir. */
  private ensureProjectsLoaded(): void {
    if (this.projectsLoaded || !this.auth.hasPermission('VIEW_PROJECT')) return;
    this.projectsLoaded = true;
    this.projectSvc.listAll().subscribe({
      next: list => this.projects.set(list),
      error: () => { this.projectsLoaded = false; }
    });
  }

  /** Raccourci global Ctrl/⌘ + K pour ouvrir/fermer la palette. */
  @HostListener('document:keydown', ['$event'])
  onGlobalKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
      e.preventDefault();
      this.paletteOpen() ? this.closePalette() : this.openPalette();
    } else if (e.key === 'Escape' && this.paletteOpen()) {
      this.closePalette();
    }
  }
}
