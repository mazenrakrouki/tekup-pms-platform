# Design Improvement Backlog

Living, prioritized list of design/UX debt. Complements the engineering `ENHANCEMENTS.md`; this one is design-scoped. Priority: **P1** (visible/systemic) · **P2** (consistency/polish) · **P3** (nice-to-have). Status: `todo` / `in-progress` / `done`.

---

## P1 — Systemic / user-visible

### D-1 · Projects list: adopt the shared pagination + sorting — `done` (2026-07)
- **Problem:** hand-rolled prev/next only; no page-size selector, no "from–to of total", no sortable columns. Diverges from every other list and from the platform reuse rule. **Also:** search/status filtered only the current 20-row page (a real defect at 87 projects).
- **Delivered:** load-once + client-side search/sort/filter/paginate; `<app-pagination>` with page-size; sortable headers (`aria-sort`, keyboard, caret) on all columns; skeleton + dual empty states; full URL state. Fixed the shared pagination `<select>` so it reflects the bound page-size (benefits every list). Verified in browser, both themes.

### D-2 · Table sorting across the app — `in-progress`
- **Problem:** Resources/TCC, Billing jalons, Governance tables have no sort. Enterprise-table mandate expects sortable, filterable, paginated lists.
- **Delivered:** Resources/TCC already had sortable columns from its own redesign pass. **Billing** — Jalons (Libellé/%/Montant/Date prévue) and Avenants (N°/Montant/Date) now sortable, same `th.th-sort`/`aria-sort`/keyboard/caret recipe as Resources, client-side `computed()` signals. Verified: clean build, `billing-component` chunk compiles.
- **Remaining:** Governance sub-tables deliberately left unsorted (datasets ≈7–13 rows, sort adds cost without payoff — documented in DESIGN_REVIEW). **Complexity:** M. **Status:** `in-progress`.

### D-3 · Kill inline one-off styles — `in-progress`
- **Problem:** Projects list, KPI, Project detail carry dozens of inline `style="font-size/color/…"` literals — the single biggest "AI-generated" tell and a token-consistency leak.
- **Delivered (Projects module):** list rewritten with a component style block (no inline literals); detail KPI tiles → `.kpi-tile`, modal candidate lists → `.picker-*`, status control → `.status-*`; form readonly fields → `.field-ro`.
- **Delivered (2026-08, KPI + Project detail):** new global utilities in `styles.scss` (`.text-caption`, `.text-micro`, `.fs-9`…`.fs-13` font-size-only, `.metric-icon--brand/success/teal/warning`) replace repeated static `font-size/color` and icon-background literals on KPI; reused KPI's own `.kpi-bar--profit-high/break-even/loss-high` classes to remove hardcoded hex from the portfolio-health bar, legend dots/swatches, and both chart-row bar segments (was duplicated ~16x); Project detail's 8 static `.kpi-tile` backgrounds → `.kpi-tile--brand/warning/teal/success/neutral` modifiers (the 2 genuinely data-driven tiles keep `[style.background]`); delete-member button → `.act-danger` (matches Billing/Missions) + added a missing `aria-label`.
- **Delivered (2026-08, KPI pass 2 — full sweep):** deeper cleanup of the two chart-row layouts (`.chart-row-grid` shared class replacing a duplicated `display:grid` block) and the project-card KPI grid (`.kpi-mini-label`/`.kpi-mini-unit`/`.kpi-stat-box`/`.kpi-stat-label` — each was a 4-8x-repeated literal). Reused Bootstrap `.text-truncate`, `.flex-shrink-0`, `.fw-semibold` where they matched exactly instead of inventing new classes. KPI's inline-`style` count dropped from ~90 to 39 — remaining ones are genuine per-instance layout (`position:absolute`/computed widths for the bullet-chart bars, one-off `max-width`/`margin-left`) that must stay inline since they're runtime-computed, not repeated tokens.
- **Remaining (KPI):** none flagged as systemic; the file is at its practical floor for static-literal extraction.
- **Delivered (2026-08, Dashboard):** extended `.metric-icon--*` with `--purple`/`--amber` variants (Dashboard uses 6 icon colors vs KPI's 4); added local `.dash-module-title`/`.dash-module-sub`/`.dash-module-cta` (each repeated 4x across the admin module cards) and `.dash-link` (repeated 3x on "Voir tous les projets" links); reused `.text-caption` for 5 repeated code/label captions. Inline-`style` count on Dashboard dropped from 69 to 29 — remainder is skeleton-loading widths, one-off layout, and per-row computed values.
- **Delivered (2026-08, Workload + Login):** Workload — `.wl-metric-card`/`.wl-value-lg`/`.wl-value-md`/`.wl-metric-sub` local classes (each repeated 3-4x across the 4 metric cards) + `.metric-icon--*` reuse; 20 → 3 inline styles (remainder: two single-property legend swatches). Login — found and fixed a real token-consistency bug: the brand color was hardcoded as `#2563EB` in two places instead of `var(--c-brand)` (same hex, just not using the token) — now uses the existing global `.text-brand` utility; extracted `.login-hero-dim` for the repeated `rgba(255,255,255,.35)` hero caption color; `fs-12`/`fs-13` on alerts and icons; 14 → 9 (remainder: one-off hero image sizing and the custom theme-toggle button, genuinely single-use layout). Verified live: mobile-logo icon computed color confirmed `rgb(37, 99, 235)` = `--c-brand` exactly.
- **Status:** `done` for KPI, Projects, Project-detail, Dashboard, Workload, Login — every screen with a double-digit inline-style count has been swept. Remaining single-digit counts (Resources, user-list, role-list, Governance, Missions, Billing, DI) are diminishing returns. **Complexity:** M.

### D-4 · Font loading & Sass modernization (perf) — `done` (2026-08)
- **Problem:** `@import url(Google Fonts)` is render-blocking; Sass `@import` is deprecated.
- **Delivered:** moved Inter loading to `<link rel="preconnect">` (googleapis + gstatic) + `<link rel="stylesheet">` in `index.html` (already carries `display=swap`); migrated `styles.scss` `@import 'bootstrap/scss/bootstrap'` → `@use 'bootstrap/scss/bootstrap' as *;`. Kept bootstrap-icons as a plain CSS `@import` (not a Sass module, no deprecation). Verified live: `styles.css` 200, Bootstrap-derived styles intact (border-radius, flex), Inter resolved, zero console errors, Sass deprecation warning gone from a fresh build.

---

## P2 — Consistency & polish

### D-5 · One switch language — `in-progress`
- **Problem:** `.pms-tabs` + `.seg` (kpi/workload) + `.nav-pills` (governance) coexist.
- **Delivered:** Governance sub-tabs migrated from Bootstrap `.nav-pills` → design-system `.pms-tabs` (verified: 4 `.tab-item`, 0 `.nav-pills`, deep-linked tab restored). Governance was the sole `nav-pills` offender.
- **Remaining:** the `.seg` controls in KPI/Workload are a deliberate local variant (year/view toggles) — converge to `.pms-tabs` if a fully single control is wanted. **Complexity:** S. **Status:** `in-progress`.

### D-6 · Accessibility pass on icon-only controls — `done` (2026-08)
- **Problem:** some row-action / topbar icon buttons lack `aria-label`.
- **Delivered:** audited every icon-only `<button>`/`<a>` app-wide (Explore agent sweep). Fixed the ones with **no** `title` fallback at all: DI (Devis Interne) save/cancel/edit/delete row actions (6 buttons) and the Dashboard portfolio row-link. Added `aria-label` alongside existing `title` on the sidebar's 3 highest-traffic controls (collapse, theme toggle, logout) via `[attr.aria-label]` bound to the same transloco key as their `title`, so both stay in sync across FR/EN.
- **Remaining:** a handful of `title`-only buttons in Missions/Roles/Governance/Project-detail (already screen-reader-adjacent via `title`, lower priority — `title` isn't reliably announced by all SR/AT but these are secondary actions). **Complexity:** S. **Status:** `done` (highest-priority items); minor remainder tracked informally.

### D-7 · Empty-state consistency — `done` (2026-08)
- **Problem:** some tables use ad-hoc `<td class="text-center text-muted">Aucune…` instead of the `.empty-state` component.
- **Delivered:** converted every ad-hoc table empty-row found in the sweep to `.empty-state`/`.es-icon`/`.es-title`: Project detail (6 tables — équipe, charges planifiées, charges réelles, jalons, avenants, missions), DI empty section rows, Resources' TCC-modal empty row. Each got a fitting Bootstrap icon per data type. Left the KPI "Calcul en cours…" spinner alone — it's a loading state, not an empty state, correctly a different pattern.
- **Benefit:** coherent zero states + a11y icon labels app-wide. **Complexity:** S. **Status:** `done`.

### D-8 · Template lint cleanup — `in-progress`
- **Problem:** `NG8102` redundant-`??` warnings (KPI, Project detail) + `bootstrap @import` deprecation warning at build.
- **Delivered:** the Sass `@import` deprecation warning is gone (fixed alongside D-4, `@use` migration).
- **Remaining:** `NG8102` on `project-detail.component.ts:433` (`m.roleInTeam ?? '—'`) and similarly in KPI — remove redundant `?? ` on non-nullable expressions.
- **Benefit:** clean build signal. **Complexity:** S. **Status:** `in-progress`.

---

## P3 — Enhancements

### D-9 · Column visibility & bulk actions on primary tables
- **Problem:** no column chooser or multi-select/bulk actions on large tables.
- **Solution:** optional column-visibility popover + row checkboxes → bulk action bar on Projects/Resources.
- **Benefit:** power-user density control. **Complexity:** M–L. **Status:** `todo`.

### D-10 · Scroll-restoration on back
- **Problem:** URL restores filters/selection but not scroll position.
- **Solution:** lightweight `ScrollRestorationService` keyed by route+query, saved on leave / restored on enter for list pages.
- **Benefit:** "never left the page" feel. **Complexity:** M. **Status:** `todo`.

### D-11 · Responsive-table strategy
- **Problem:** mobile column-priority is ad-hoc (`d-none d-*-table-cell` per page).
- **Solution:** shared directive declaring column priority + optional card fallback below a breakpoint.
- **Benefit:** consistent mobile tables. **Complexity:** M. **Status:** `todo`.

### D-12 · Command palette (⌘K)
- **Problem:** sidebar shows a search affordance (`.sb-search` + `⌘K` hint) — verify it opens a real palette (jump to project/route/action).
- **Solution:** global command palette over routes + projects + primary actions; deep-links via existing query-param nav.
- **Benefit:** Linear-grade navigation speed. **Complexity:** L. **Status:** `todo` (verify current state first).

---

## Recently completed

### Dashboard / KPI / Workload — the final three — `done` (2026-08)
**Whole application now past the ≥90 gate.** Dashboard (91): loading skeleton + keyboard-accessible portfolio rows. KPI (90): dark-mode bar-track fix (`#e5e7eb`→`--surface-3`, zero-line markers tokenized), portfolio-health chart `aria-label`, NG8102 warnings cleared. Workload (90): submit toasts + label consistency. Verified live.

### Billing / Missions / Resources to enterprise quality — `done` (2026-08)
All three reached **90–91**. Billing + Missions: toasts + error handling on every CRUD handler (were silent), `.empty-state` with actions, `.act-danger`/`aria-label`, `.form-label`. **Missions dark-mode bug fixed** (`.bg-light`/`.bg-white` expanded row → token `.sub-row`/`.sub-table`, verified `--surface-2` in dark). Resources: added client-side **search + sortable columns** (were absent), skeleton, dual empty states, TCC-modal a11y + save toast. Verified live.

### Governance module to enterprise quality — `done` (2026-08)
Reached **90/100**. `.nav-pills` → `.pms-tabs` (unified switch language, D-5); toasts + error handling on all 14 CRUD/workflow handlers (were silent, deletes/workflow had no error path); 4 ad-hoc empty rows → `.empty-state` with actions; `aria-label` on icon delete actions; inline styles removed. Verified live (renders, deep-linked tabs).

### Admin module full redesign — `done` (2026-08)
Users / Roles / Permissions to enterprise quality (avg **~90/100**). **User-list** rebuilt: server-side sortable headers + `<app-pagination>` (replaced hand-rolled prev/next) + debounced server search + role/status filters + reset; toasts on all mutations; **fixed an error shown via a confirm dialog → now a toast**; two empty states, skeleton, aria-labels, `.form-label`. Roles + Permissions polished (design-system labels, inline styles → classes, `.role-badge-light`). Verified live (200 responses, sort/search/pagination requests, DOM).

### Projects module full redesign — `done` (2026-07)
Entire module brought to enterprise quality (avg **91/100**, see [DESIGN_REVIEW](DESIGN_REVIEW.md)): list (D-1), detail, and form. New capabilities: **interactive status management** (wired the unused `changeStatus()` endpoint — badge → transition menu → confirm → toast), **context-preserving breadcrumbs** (`ProjectsListStateService` returns to the exact filtered list), **tab-in-URL** on detail, **dark-mode readonly-field fix** on the form, sticky form action bar. Fixed the shared `<app-pagination>` page-size display. Verified live in both themes.

### D-0 · Navigation context preservation — `done` (2026-07)
URL-encoded filters/pagination/selection/tab across Projects, Workload, Billing, Missions, Governance; in-app back buttons; browser-back restores state. See [`UX_NAVIGATION_IMPROVEMENTS.md`](../UX_NAVIGATION_IMPROVEMENTS.md).

---

## Suggested sequence
`D-4` (quick perf win) → `D-1` (highest-visibility page) → `D-3` on that page in the same pass → `D-2`/`D-5` (systemic consistency) → `D-6`/`D-7`/`D-8` (polish) → P3 as capacity allows.
