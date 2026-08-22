import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectService } from '../../core/services/project.service';
import { TeamService } from '../../core/services/team.service';
import { AuthService } from '../../core/services/auth.service';
import { Project, PROJECT_STATUS_LABELS, ProjectStatus } from '../../core/models/project.model';
import { PaginationComponent } from '../pagination/pagination.component';
import { TranslocoModule } from '@jsverse/transloco';

const FAV_KEY = 'pms.projectPicker.favorites';
const RECENT_KEY = 'pms.projectPicker.recents';
const RECENT_MAX = 6;

/**
 * Project selection as master → detail with progressive disclosure.
 *
 * <p>Default page state is NOT a project browser: it's either an empty prompt (no selection) or a
 * rich <b>Current Project</b> context card (code, status, manager, team, budget, period). The full
 * browser — search, Assignés/Favoris filters, recents, paginated table — lives in a <b>modal</b>
 * opened via "Choisir / Changer de projet", so the host screen's real feature stays primary.
 */
@Component({
  selector: 'app-project-picker',
  standalone: true,
  imports: [CommonModule, FormsModule, PaginationComponent, TranslocoModule],
  template: `
    <!-- ── No selection: on-topic feature landing ── -->
    @if (!selected()) {
      <div class="pp-landing">
        <!-- Pas de titre ici : la page porte déjà le sien. Ce bloc dit quoi faire,
             il ne redit pas où l'on est. -->
        <div class="pp-hero">
          <div class="pp-hero-icon"><i class="bi {{ featureIcon() }}"></i></div>
          <div class="pp-hero-text">
            <p class="pp-hero-lead">{{ 'picker.chooseProject' | transloco }}</p>
          </div>
          <button class="btn btn-primary pp-hero-btn" (click)="openModal()">
            <i class="bi bi-search me-1"></i>{{ 'picker.browseAll' | transloco }}
            @if (projects().length) { <span class="pp-hero-count">{{ projects().length }}</span> }
          </button>
        </div>

        <!-- Les projets récents restent directement cliquables : c'est un raccourci
             réel vers un travail en cours, pas une section décorative. -->
        @if (quickAccess().length) {
          <div class="pp-qa-grid">
            @for (p of quickAccess(); track p.id) {
              <button type="button" class="pp-qa-card" (click)="confirm(p)" [title]="p.code">
                <div class="pp-qa-top">
                  <span class="pp-qa-name">{{ p.name }}</span>
                  <span [class]="badgeClass(p.status)">{{ 'status.' + p.status | transloco }}</span>
                </div>
                <div class="pp-qa-meta">
                  <span class="pp-qa-metaitem"><i class="bi bi-building"></i>{{ p.client || '—' }}</span>
                  <span class="pp-qa-metaitem"><i class="bi bi-person-badge"></i>{{ p.chefProjetName || '—' }}</span>
                </div>
                @if (p.chefProjetId === myId) {
                  <span class="pp-qa-mine"><i class="bi bi-person-check"></i> {{ 'picker.mine' | transloco }}</span>
                } @else if (isFav(p.id)) {
                  <span class="pp-qa-mine" style="color:var(--c-amber)"><i class="bi bi-star-fill"></i> {{ 'picker.favorite' | transloco }}</span>
                }
                <i class="bi bi-arrow-right pp-qa-go"></i>
              </button>
            }
          </div>
        }
      </div>
    }

    <!-- ── Selection: Current Project context card ── -->
    @else {
      <div class="pp-context">
        <div class="pp-ctx-body">
          <div class="pp-ctx-head">
            <i class="bi bi-folder-check pp-ctx-icon"></i>
            <span class="pp-ctx-code">{{ selected()!.code }}</span>
            <span [class]="badgeClass(selected()!.status)">{{ 'status.' + selected()!.status | transloco }}</span>
          </div>
          <div class="pp-ctx-name">{{ selected()!.name }}</div>
          <div class="pp-ctx-stats">
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-person-badge"></i> {{ 'picker.manager' | transloco }}</span>
              <span class="pp-stat-val">{{ selected()!.chefProjetName || '—' }}</span>
            </div>
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-people"></i> {{ 'picker.team' | transloco }}</span>
              <span class="pp-stat-val">{{ teamCount() === null ? '…' : teamCount() }}</span>
            </div>
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-wallet2"></i> {{ 'picker.budget' | transloco }}</span>
              <span class="pp-stat-val">{{ budget() }}</span>
            </div>
            <div class="pp-stat">
              <span class="pp-stat-label"><i class="bi bi-calendar3"></i> {{ 'picker.period' | transloco }}</span>
              <span class="pp-stat-val">{{ period() }}</span>
            </div>
            @if (selected()!.client) {
              <div class="pp-stat">
                <span class="pp-stat-label"><i class="bi bi-building"></i> {{ 'picker.client' | transloco }}</span>
                <span class="pp-stat-val">{{ selected()!.client }}</span>
              </div>
            }
          </div>
        </div>
        <button class="btn btn-outline-secondary pp-ctx-change" (click)="openModal()">
          <i class="bi bi-arrow-left-right me-1"></i>{{ 'picker.change' | transloco }}
        </button>
      </div>
    }

    <!-- ── Modal browser ── -->
    @if (open()) {
      <div class="modal-backdrop fade show"></div>
      <div class="modal d-block" tabindex="-1" (click)="close()" (keydown.escape)="close()">
        <div class="modal-dialog modal-xl modal-dialog-centered" (click)="$event.stopPropagation()">
          <div class="modal-content pp-modal">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-folder2-open me-2"></i>{{ 'picker.modalTitle' | transloco }}</h5>
              <button type="button" class="btn-close" (click)="close()" [attr.aria-label]="'common.close' | transloco"></button>
            </div>

            <div class="pp-search-row">
              <div class="input-wrap pp-search">
                <i class="bi bi-search input-icon"></i>
                <input type="search" class="form-control"
                       [placeholder]="'picker.searchPlaceholder' | transloco"
                       [ngModel]="search()" (ngModelChange)="onSearch($event)">
              </div>
              <div class="pp-filters">
                <button type="button" class="pp-chip" [class.pp-chip-on]="onlyMine()"
                        (click)="onlyMine.set(!onlyMine()); page.set(0)" [title]="'picker.assigned' | transloco">
                  <i class="bi bi-person-check"></i> {{ 'picker.assigned' | transloco }}
                </button>
                <button type="button" class="pp-chip" [class.pp-chip-on]="onlyFav()"
                        (click)="onlyFav.set(!onlyFav()); page.set(0)" [title]="'picker.favorites' | transloco">
                  <i class="bi bi-star-fill"></i> {{ 'picker.favorites' | transloco }}
                </button>
              </div>
            </div>

            @if (recentProjects().length && !search() && !onlyMine() && !onlyFav()) {
              <div class="pp-recents">
                <span class="pp-recents-label"><i class="bi bi-clock-history"></i> {{ 'picker.recents' | transloco }}</span>
                @for (p of recentProjects(); track p.id) {
                  <button type="button" class="pp-recent-chip" (click)="confirm(p)" [title]="p.name">
                    <span class="pp-recent-code">{{ p.code }}</span>
                    <span class="pp-recent-name">{{ p.name }}</span>
                  </button>
                }
              </div>
            }

            <div class="pp-table-wrap">
              <table class="table table-hover align-middle pp-table mb-0">
                <thead>
                  <tr>
                    <th style="width:36px"></th>
                    <th style="width:130px">{{ 'picker.code' | transloco }}</th>
                    <th>{{ 'picker.project' | transloco }}</th>
                    <th class="d-none d-lg-table-cell">{{ 'picker.client' | transloco }}</th>
                    <th style="width:96px">{{ 'common.status' | transloco }}</th>
                    <th class="d-none d-xl-table-cell">{{ 'picker.manager' | transloco }}</th>
                    <th class="d-none d-xl-table-cell" style="width:100px">{{ 'picker.start' | transloco }}</th>
                    <th class="d-none d-xl-table-cell" style="width:100px">{{ 'picker.end' | transloco }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of paged(); track p.id) {
                    <tr class="pp-row" [class.pp-row-active]="pending()?.id === p.id"
                        [class.pp-row-current]="selected()?.id === p.id"
                        (click)="pick(p)" (dblclick)="confirm(p)">
                      <td class="text-center">
                        <button type="button" class="pp-star" [class.pp-star-on]="isFav(p.id)"
                                (click)="toggleFav(p, $event)"
                                [attr.aria-label]="isFav(p.id) ? 'Retirer des favoris' : 'Ajouter aux favoris'">
                          <i class="bi" [class.bi-star-fill]="isFav(p.id)" [class.bi-star]="!isFav(p.id)"></i>
                        </button>
                      </td>
                      <td><span class="pp-code">{{ p.code }}</span></td>
                      <td>
                        <div class="pp-name">{{ p.name }}</div>
                        @if (p.chefProjetId === myId) {
                          <span class="pp-mine"><i class="bi bi-person-check"></i> {{ 'picker.assigned' | transloco }}</span>
                        }
                      </td>
                      <td class="d-none d-lg-table-cell pp-muted">{{ p.client || '—' }}</td>
                      <td><span [class]="badgeClass(p.status)">{{ 'status.' + p.status | transloco }}</span></td>
                      <td class="d-none d-xl-table-cell pp-muted">{{ p.chefProjetName || '—' }}</td>
                      <td class="d-none d-xl-table-cell pp-muted pp-num">{{ p.startDate || '—' }}</td>
                      <td class="d-none d-xl-table-cell pp-muted pp-num">{{ p.endDate || '—' }}</td>
                    </tr>
                  }
                  @empty {
                    <tr><td colspan="8">
                      <div class="empty-state">
                        <div class="es-icon"><i class="bi bi-search"></i></div>
                        <div class="es-title">Aucun projet ne correspond</div>
                      </div>
                    </td></tr>
                  }
                </tbody>
              </table>
            </div>

            <app-pagination
              [page]="page()" [pageSize]="pageSize()" [total]="ordered().length"
              (pageChange)="page.set($event)"
              (pageSizeChange)="pageSize.set($event); page.set(0)" />

            <div class="modal-footer">
              <span class="pp-hint">{{ 'picker.hint' | transloco }}</span>
              <button class="btn btn-secondary" (click)="close()">Annuler</button>
              <button class="btn btn-primary" [disabled]="!pending()" (click)="confirm(pending()!)">
                <i class="bi bi-check-lg me-1"></i>{{ 'picker.select' | transloco }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    /* ── Feature landing (no selection) ── */
    .pp-landing { display: flex; flex-direction: column; gap: 1.25rem; }
    .pp-hero { display: flex; align-items: center; gap: 1.25rem; padding: 1.5rem 1.75rem;
      border: 1px solid var(--border); border-radius: 16px; flex-wrap: wrap;
      background:
        radial-gradient(120% 140% at 0% 0%, var(--c-brand-dim) 0%, transparent 55%),
        var(--surface-1, var(--surface)); }
    .pp-hero-icon { width: 56px; height: 56px; border-radius: 14px; display: grid; place-items: center;
      background: var(--c-brand); color: #fff; font-size: 26px; flex-shrink: 0;
      box-shadow: 0 6px 16px var(--c-brand-dim); }
    .pp-hero-text { flex: 1; min-width: 220px; }
    .pp-hero-title { font-size: 22px; font-weight: 800; color: var(--text-1); margin: 0; }
    .pp-hero-desc { font-size: 14px; color: var(--text-2); margin: .25rem 0 0; max-width: 60ch; }
    .pp-hero-btn { flex-shrink: 0; display: inline-flex; align-items: center; }
    .pp-hero-count { margin-left: .5rem; background: rgba(255,255,255,.22); border-radius: 20px;
      padding: 0 .5rem; font-size: 11px; font-weight: 700; }

    .pp-qa-label { font-size: 12px; font-weight: 700; letter-spacing: .03em; color: var(--text-2);
      display: inline-flex; align-items: center; gap: .4rem; }
    .pp-qa-label > .bi { color: var(--c-amber, #f59e0b); }
    .pp-qa-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 1rem; }
    .pp-qa-card { position: relative; text-align: left; padding: 1.15rem 1.25rem; min-height: 132px;
                 display: flex; flex-direction: column; gap: .55rem; border: 1px solid var(--border);
      border-radius: 14px; background: var(--surface-1, var(--surface)); cursor: pointer;
      transition: transform .12s, border-color .12s, box-shadow .12s; display: flex; flex-direction: column; gap: .4rem; }
    .pp-qa-card:hover { transform: translateY(-2px); border-color: var(--c-brand);
      box-shadow: 0 8px 22px rgba(0,0,0,.10); }
    .pp-qa-top { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
    .pp-qa-code { font-family: var(--font-mono, monospace); font-weight: 800; font-size: 12px; color: var(--c-brand); }
    .pp-qa-name { font-size: 15px; font-weight: 700; color: var(--text-1); line-height: 1.3;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .pp-qa-meta { display: flex; flex-direction: column; gap: .2rem; margin-top: .1rem; }
    .pp-qa-metaitem { font-size: 12px; color: var(--text-2); display: inline-flex; align-items: center; gap: .4rem;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pp-qa-metaitem > .bi { color: var(--text-3); font-size: 12px; flex-shrink: 0; }
    .pp-qa-mine { font-size: 10px; font-weight: 700; color: var(--c-success); display: inline-flex;
      align-items: center; gap: .25rem; }
    .pp-qa-go { position: absolute; right: 1rem; bottom: 1rem; color: var(--text-3); font-size: 14px;
      opacity: 0; transition: opacity .12s; }
    .pp-qa-card:hover .pp-qa-go { opacity: 1; color: var(--c-brand); }
    @media (prefers-reduced-motion: reduce) { .pp-qa-card { transition: none; } .pp-qa-card:hover { transform: none; } }

    /* ── Current Project card ── */
    .pp-context { display: flex; align-items: center; gap: 1.5rem; padding: 1.125rem 1.5rem;
      border: 1px solid var(--border); border-left: 4px solid var(--c-brand); border-radius: 14px;
      background: var(--surface-1, var(--surface)); flex-wrap: wrap; }
    .pp-ctx-body { flex: 1; min-width: 260px; }
    .pp-ctx-head { display: flex; align-items: center; gap: .625rem; }
    .pp-ctx-icon { color: var(--c-success); font-size: 17px; }
    .pp-ctx-code { font-family: var(--font-mono, monospace); font-weight: 800; font-size: 14px; color: var(--c-brand); }
    .pp-ctx-name { font-size: 18px; font-weight: 700; color: var(--text-1); margin: .25rem 0 .75rem; }
    .pp-ctx-stats { display: flex; flex-wrap: wrap; gap: 1.75rem; }
    .pp-stat { display: flex; flex-direction: column; gap: .1rem; }
    .pp-stat-label { font-size: 10px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase;
      color: var(--text-3); display: inline-flex; align-items: center; gap: .3rem; }
    .pp-stat-val { font-size: 14px; font-weight: 600; color: var(--text-1); font-variant-numeric: tabular-nums; }
    .pp-ctx-change { flex-shrink: 0; align-self: flex-start; }

    /* ── Modal shell ── */
    .pp-modal { max-height: calc(100vh - 3.5rem); }
    .pp-search-row { display: flex; align-items: center; gap: .75rem; padding: .875rem 1rem;
      border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .pp-search { flex: 1; min-width: 220px; }
    .pp-filters { display: flex; gap: .5rem; }
    .pp-chip { display: inline-flex; align-items: center; gap: .375rem; padding: .375rem .75rem;
      border: 1px solid var(--border); border-radius: 20px; background: transparent; color: var(--text-2);
      font-size: 12px; font-weight: 600; cursor: pointer; transition: all .15s; }
    .pp-chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
    .pp-chip-on { background: var(--c-brand-dim); border-color: var(--c-brand); color: var(--c-brand); }

    .pp-recents { display: flex; align-items: center; gap: .5rem; flex-wrap: wrap;
      padding: .625rem 1rem; border-bottom: 1px solid var(--border); }
    .pp-recents-label { font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase;
      color: var(--text-3); display: inline-flex; align-items: center; gap: .35rem; }
    .pp-recent-chip { display: inline-flex; align-items: baseline; gap: .4rem; max-width: 260px;
      padding: .3rem .625rem; border: 1px solid var(--border); border-radius: 8px; background: transparent;
      cursor: pointer; transition: all .15s; }
    .pp-recent-chip:hover { border-color: var(--c-brand); background: var(--c-brand-dim); }
    .pp-recent-code { font-family: var(--font-mono, monospace); font-size: 11px; font-weight: 700; color: var(--c-brand); }
    .pp-recent-name { font-size: 12px; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .pp-table-wrap { overflow-y: auto; overflow-x: auto; flex: 1; }
    .pp-table thead th { position: sticky; top: 0; z-index: 1; background: var(--surface-2, var(--surface));
      font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-3); }
    .pp-row { cursor: pointer; }
    .pp-row:hover { background: var(--c-brand-dim); }
    .pp-row-current { background: var(--surface-2, rgba(0,0,0,.03)); }
    .pp-row-active { background: var(--c-brand-dim) !important; box-shadow: inset 3px 0 0 var(--c-brand); }
    .pp-code { font-family: var(--font-mono, monospace); font-weight: 700; font-size: 12px; color: var(--c-brand); }
    .pp-name { font-size: 13px; font-weight: 600; color: var(--text-1); }
    .pp-muted { color: var(--text-2); font-size: 12px; }
    .pp-num { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .pp-mine { font-size: 10px; color: var(--c-success); font-weight: 600; display: inline-flex; align-items: center; gap: .25rem; }
    .pp-star { background: none; border: 0; padding: 0; cursor: pointer; color: var(--text-3); font-size: 14px; line-height: 1; }
    .pp-star:hover { color: var(--c-amber, #f59e0b); }
    .pp-star-on { color: var(--c-amber, #f59e0b); }
    .pp-hint { font-size: 11px; color: var(--text-3); margin-right: auto; }
    @media (prefers-reduced-motion: reduce) { .pp-chip, .pp-recent-chip, .pp-row { transition: none; } }
  `]
})
export class ProjectPickerComponent {
  private readonly projectSvc = inject(ProjectService);
  private readonly teamSvc = inject(TeamService);
  private readonly auth = inject(AuthService);

  selected = input<Project | null>(null);
  emptyHint = input<string>('Choisissez un projet pour continuer.');
  featureTitle = input<string>('');
  featureIcon = input<string>('bi-folder2-open');
  featureDescription = input<string>('');
  projectSelected = output<Project>();

  readonly myId = this.auth.currentUserId;

  projects = signal<Project[]>([]);
  open = signal(false);
  search = signal('');
  onlyMine = signal(false);
  onlyFav = signal(false);
  page = signal(0);
  pageSize = signal(12);
  pending = signal<Project | null>(null);
  teamCount = signal<number | null>(null);
  favorites = signal<Set<number>>(this.loadFavorites());
  recents = signal<number[]>(this.loadRecents());

  constructor() {
    this.projectSvc.listAll().subscribe(list => this.projects.set(list));
    // Fetch the team size for the current-project context card whenever the selection changes.
    effect(() => {
      const p = this.selected();
      this.teamCount.set(null);
      if (p) {
        this.teamSvc.list(p.id).subscribe({
          next: (m: unknown[]) => { if (this.selected()?.id === p.id) this.teamCount.set(m.length); },
          error: () => {}
        });
      }
    });
  }

  // ── Context card presentation ──────────────────────────────────
  readonly budget = computed(() => {
    const p = this.selected();
    const v = p?.effectiveBudget ?? p?.budgetTnd;
    return v != null ? v.toLocaleString('fr-FR', { maximumFractionDigits: 0 }) + ' TND' : '—';
  });
  readonly period = computed(() => {
    const p = this.selected();
    if (!p) return '—';
    return `${p.startDate ?? '—'} → ${p.endDate ?? '…'}`;
  });

  // ── Modal derived lists ────────────────────────────────────────
  readonly filtered = computed(() => {
    const s = this.search().toLowerCase().trim();
    const fav = this.favorites();
    return this.projects().filter(p => {
      if (this.onlyMine() && p.chefProjetId !== this.myId) return false;
      if (this.onlyFav() && !fav.has(p.id)) return false;
      if (!s) return true;
      return p.code.toLowerCase().includes(s)
          || p.name.toLowerCase().includes(s)
          || (p.client ?? '').toLowerCase().includes(s)
          || (p.chefProjetName ?? '').toLowerCase().includes(s);
    });
  });

  readonly ordered = computed(() => {
    const fav = this.favorites();
    const recents = this.recents();
    const recentRank = (id: number) => { const i = recents.indexOf(id); return i === -1 ? Infinity : i; };
    return [...this.filtered()].sort((a, b) => {
      const fa = fav.has(a.id) ? 0 : 1, fb = fav.has(b.id) ? 0 : 1;
      if (fa !== fb) return fa - fb;
      const ma = a.chefProjetId === this.myId ? 0 : 1, mb = b.chefProjetId === this.myId ? 0 : 1;
      if (ma !== mb) return ma - mb;
      const ra = recentRank(a.id), rb = recentRank(b.id);
      if (ra !== rb) return ra - rb;
      return a.code.localeCompare(b.code);
    });
  });

  readonly paged = computed(() => {
    const size = this.pageSize();
    const pages = Math.max(1, Math.ceil(this.ordered().length / size));
    const page = Math.min(this.page(), pages - 1);
    return this.ordered().slice(page * size, page * size + size);
  });

  readonly recentProjects = computed(() => {
    const byId = new Map(this.projects().map(p => [p.id, p]));
    return this.recents().map(id => byId.get(id)).filter((p): p is Project => !!p).slice(0, RECENT_MAX);
  });

  /** Landing shortcut cards: assigned → recent → favorites → active → any, deduped, max 12. */
  readonly quickAccess = computed(() => {
    const all = this.projects();
    if (!all.length) return [];
    const byId = new Map(all.map(p => [p.id, p]));
    const fav = this.favorites();
    const seen = new Set<number>();
    const out: Project[] = [];
    const add = (arr: (Project | undefined)[]) => {
      for (const p of arr) {
        if (p && !seen.has(p.id)) { seen.add(p.id); out.push(p); if (out.length >= 12) return; }
      }
    };
    add(all.filter(p => p.chefProjetId === this.myId));
    add(this.recents().map(id => byId.get(id)));
    add(all.filter(p => fav.has(p.id)));
    add(all.filter(p => p.status === 'ACTIVE'));
    add(all);
    return out.slice(0, 12);
  });

  // ── Actions ────────────────────────────────────────────────────
  openModal(): void {
    this.open.set(true);
    this.search.set('');
    this.onlyMine.set(false);
    this.onlyFav.set(false);
    this.page.set(0);
    this.pending.set(this.selected());
    setTimeout(() => document.querySelector<HTMLInputElement>('.pp-search input')?.focus(), 0);
  }

  close(): void { this.open.set(false); }
  onSearch(v: string): void { this.search.set(v); this.page.set(0); }
  pick(p: Project): void { this.pending.set(p); }

  confirm(p: Project): void {
    this.addRecent(p.id);
    this.open.set(false);
    this.projectSelected.emit(p);
  }

  // ── Favorites & recents (localStorage) ─────────────────────────
  isFav(id: number): boolean { return this.favorites().has(id); }

  toggleFav(p: Project, e: Event): void {
    e.stopPropagation();
    this.favorites.update(s => {
      const n = new Set(s);
      n.has(p.id) ? n.delete(p.id) : n.add(p.id);
      this.persist(FAV_KEY, [...n]);
      return n;
    });
  }

  private addRecent(id: number): void {
    this.recents.update(list => {
      const next = [id, ...list.filter(x => x !== id)].slice(0, 20);
      this.persist(RECENT_KEY, next);
      return next;
    });
  }

  private loadFavorites(): Set<number> {
    try { return new Set(JSON.parse(localStorage.getItem(FAV_KEY) ?? '[]')); } catch { return new Set(); }
  }
  private loadRecents(): number[] {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]'); } catch { return []; }
  }
  private persist(key: string, value: unknown): void {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* ignore quota errors */ }
  }

  // ── Presentation ───────────────────────────────────────────────
  statusLabel(s: string): string {
    return PROJECT_STATUS_LABELS[s as ProjectStatus] ?? s;
  }
  badgeClass(s: string): string {
    const m: Record<string, string> = {
      ACTIVE: 'badge-active', COMPLETED: 'badge-completed',
      DRAFT: 'badge-draft', ON_HOLD: 'badge-on-hold', CANCELLED: 'badge-cancelled'
    };
    return m[s] ?? 'badge-draft';
  }
}
