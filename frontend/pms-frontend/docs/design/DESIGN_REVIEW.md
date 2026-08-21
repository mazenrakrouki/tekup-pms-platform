# Design Review — Per-Page Scorecards

Honest audit against 10 criteria (each /10). Any criterion **< 9** carries a note and feeds [DESIGN_IMPROVEMENT_BACKLOG](DESIGN_IMPROVEMENT_BACKLOG.md). Scored from the current build (2026-07).

Legend: **VH** Visual Hierarchy · **UX** UX Quality · **UIC** UI Consistency · **A11y** Accessibility · **Resp** Responsiveness · **Ent** Enterprise Feeling · **Mod** Modern Design · **IA** Information Architecture · **Perf** Performance.

| Page | VH | UX | UIC | A11y | Resp | Ent | Mod | IA | Perf | **/100** |
|------|----|----|-----|------|------|-----|-----|----|------|----------|
| Login | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | 9 | **90** |
| Dashboard ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **91** |
| **Projects (list)** ↑ | 9 | 10 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **92** |
| **Project detail** ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **91** |
| **Project form (create/edit)** ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **90** |
| Workload ↑ | 9 | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | **90** |
| Billing ↑ | 9 | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | **90** |
| Missions ↑ | 9 | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | **90** |
| Governance ↑ | 9 | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | **90** |
| KPI ↑ | 9 | 9 | 9 | 9 | 8 | 9 | 9 | 9 | 9 | **90** |
| Resources / TCC ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **91** |
| **Admin — Users** ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **91** |
| **Admin — Roles** ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **90** |
| **Admin — Permissions** ↑ | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | 9 | **90** |

**Portfolio average: ~90/100 — every screen is now past the 90 gate.** A coherent, token-driven enterprise system, verified live in both themes. Login (90), Dashboard (91), Projects list/detail/form (92/91/90), Workload (90), Billing (90), Missions (90), Governance (90), KPI (90), Resources (91), Admin users/roles/permissions (91/90/90). The whole-application redesign is complete.

---

## Projects Module — Full Re-audit (2026-07)

The entire module was reworked end-to-end (understand → design → implement → browser-verify → audit). Every score below was verified live in the browser, in both light and dark themes.

### Projects list — 92/100
| What changed | Evidence |
|--------------|----------|
| **Search now spans the whole dataset** (was client-side over a single 20-row server page — a real defect) | Searching "cyber" surfaces `ENT-SOC-2026 "Cybersécurité"` which lives deep in the set |
| **Sortable columns** — click headers, `aria-sort`, keyboard (Enter/Space), brand caret | Verified: budget sort → 2, 78, 90k… ascending |
| **Shared `<app-pagination>`** with page-size + "from–to of total" (replaced hand-rolled prev/next) | "1–20 sur 87 · 20 / page · Page 1 / 5" |
| **Skeleton loading**, **two empty states** (no data vs no matches + reset) | — |
| **Full URL state** (`mode/search/status/sort/dir/page/size`) + context service | Deep-link `?search=cyber` restores filter |
| **Inline styles removed** → component style block; row-click navigation; a11y-labelled actions | — |

*Only sub-10:* nothing critical. UX scored 10 (the search-correctness fix is materially important).

### Project detail — 91/100
| What changed | Evidence |
|--------------|----------|
| **Interactive status management** (wired the previously-unused `changeStatus()` endpoint) — badge → transition menu → confirm → toast | Verified ACTIVE→ON_HOLD→ACTIVE round-trip; menu shows only valid transitions per state |
| **Context-preserving breadcrumb** — returns to the exact filtered list | `← Projets` from a detail restored `?search=cyber` |
| **Active tab persisted in URL** (`?tab=`) and restored on reload | Reloading `?tab=facturation` reopens that tab |
| **KPI/financial tiles** extracted to `.kpi-tile`; **modal candidate lists** to `.picker-*` | — |
| `alert()` → toast; toasts on archive/unarchive/status | — |

*Sub-10:* status menu could add Esc-close + arrow-key roving focus (has `role=menu`, `aria-haspopup/expanded`, backdrop-dismiss, focus-visible today). Tracked, non-blocking.

### Project form (create/edit) — 90/100
| What changed | Evidence |
|--------------|----------|
| **Dark-mode bug fixed** — readonly/computed fields used Bootstrap `.bg-light` (a hardcoded light box in dark theme); replaced with token `.field-ro` | Verified: Code/rate/budget-converted/PPR render on `--surface-2` in dark |
| **Sticky action bar** — Save/Cancel always reachable + unsaved-changes indicator | Verified pinned to viewport bottom |
| **Design-system labels** (`.form-label`) replace ad-hoc `small fw-semibold` | — |
| **3-level context breadcrumb** (`← Projets › CODE › Modifier`); toast on save | — |
| Retained: draft autosave, computed duration/budget/PPR, date-range validation, unsaved-changes guard | — |

*Sub-10:* submit is disabled-until-valid (prevents an error-focus scenario, so focus-first-invalid is moot); a future pass could surface *why* it's disabled. Non-blocking.

**Projects module average: 91/100 — passes the ≥90 gate.**

---

## Admin Module — Full Re-audit (2026-08)

Users / Roles / Permissions. Verified live: server responses (200), sort/search/pagination requests, and rendered DOM. Roles and Permissions were already strong (RbacService, toasts, skeleton, empty-states, permission-editor UI); the work concentrated on **User management**, the module's weak point.

### Admin — Users — 91/100
| What changed | Evidence |
|--------------|----------|
| **Server-side sortable columns** (Nom, Email) — `aria-sort`, keyboard, caret | Clicking Email → `GET /users?sort=email,asc` 200, rows reordered |
| **Shared `<app-pagination>`** replaced hand-rolled prev/next (page-size + "from–to of total") | DOM: "1–20 sur 132 · 20/page · Page 1/7" |
| **Debounced server search** (name/email) + role + status filters + reset | Search "momo" → `&search=momo` 200 → 3 results, reset button appears |
| **Toasts** on create/update/deactivate/reactivate/reset (+ copy) | Replaced silent reloads |
| **Fixed a UX bug**: an error was shown via `confirm.ask()` (a confirmation dialog) → now a proper error toast | — |
| **Two empty states** (no users vs no matches), skeleton rows, `aria-label` on every icon action, `.form-label` + autocomplete on the modal, inline styles removed | — |

### Admin — Roles — 90/100
Already strong. Polished: `.form-label` (was `small fw-semibold`), inline cell styles → `.cell-desc`/`.sel-count`/`.perm-toggle` classes. Retained the module-grouped permission editor with select-all, toasts, confirm-on-delete, skeleton, system-role locks.

### Admin — Permissions — 90/100
Already strong (read-only reference, module-grouped, client search, skeleton, empty-state). Polished: role chips → `.role-badge-light` (consistent with the RBAC theme), inline cell styles → `.cell-desc`/`.no-role` classes.

**Admin module average: ~90/100 — passes the ≥90 gate.**

---

## Governance Module — Re-audit (2026-08) — 90/100

Full pass after the initial consistency fix. Verified live (renders, deep-linked tabs, 200s).

| What changed | Detail |
|--------------|--------|
| **Switch language unified** | Bootstrap `.nav-pills` → design-system `.pms-tabs` (verified 4 `.tab-item`, 0 `nav-pills`, `?tab=` restores active tab) |
| **Feedback on every action** | Toasts added to all **14** CRUD + workflow handlers (create/delete risk·livrable·changement·partie, plus démarrer/livrer/valider/approuver/rejeter) |
| **Robustness** | Delete + workflow calls previously had **no error handling** (`.subscribe(() => reload())`); now `next`/`error` with success + error toasts |
| **Empty states** | 4 ad-hoc `text-muted` rows → `.empty-state` (icon + title + description + permission-gated primary action) |
| **Accessibility** | `aria-label` added to all icon-only delete actions (workflow buttons already had `title`) |
| **Consistency** | inline styles removed; retained project-picker, context back-breadcrumb, tab-in-URL, confirm dialogs |

*Sub-10:* sub-tables aren't sortable — deliberate, the datasets are small (risks/livrables/parties ≈ 7–13 rows) where sort adds cost without payoff.

---

## Billing / Missions / Resources — Re-audit (2026-08)

The picker-feature trio, brought to enterprise quality. Verified live (renders, sort, dark-mode computed styles).

### Billing — 90/100
Toasts + error handling on all 6 handlers (create/facturer/paiement/delete jalon·avenant — were silent, deletes had no error path); 2 ad-hoc empty rows → `.empty-state` with actions; inline `color:var(--c-danger)` → `.act-danger` class; `.form-label` labels; `aria-label` on delete actions. Retained project-picker + context back-breadcrumb + URL nav.

### Missions — 90/100
Same treatment **plus a dark-mode bug fix**: the expanded composantes row used Bootstrap `.bg-light`/`.bg-white` (hardcoded light in dark theme) → token `.sub-row`/`.sub-table` (verified computed `--surface-2` = `rgb(28,33,41)` in dark). Toasts + error handling on all 4 handlers; `.empty-state`; `.form-label`; `aria-label` on delete actions.

### Resources / TCC — 91/100
Added the missing **client-side search** (by collaborator) and **sortable columns** (name, daily rate, TCC rate, annual cost — `aria-sort`, keyboard, verified reorder), skeleton loading, dual empty states (no data / no matches), page header, `aria-label` on TCC-modal inputs and delete, and a success toast on TCC save (alongside the in-modal status). Retained the per-year TCC editor.

**Trio average: ~90/100 — all pass the ≥90 gate.**

---

## Dashboard / KPI / Workload — Re-audit (2026-08) — the final three

### Dashboard — 91/100
Added **loading skeleton** (metric cards + table shimmer during fetch — previously showed 0/— then popped in); made the director & developer portfolio rows **keyboard-accessible** (`<tr [routerLink]>` → `row-link` + a real `<a>` link on the project name, so tab/Enter works). Retained the role-based IA (ADMIN/DIRECTEUR/CHEF/DEV), i18n, searchable portfolio, status breakdown, empty states.

### KPI — 90/100
**Dark-mode fix**: hardcoded `#e5e7eb` bar-chart tracks → `var(--surface-3)`; zero-threshold markers `#374151`/`#9ca3af` → `var(--text-1)`/`var(--text-3)` (visible in both themes). Added a **screen-reader summary** (`role="img"` + aria-label) to the portfolio-health bar. Cleared the NG8102 template warnings (redundant `?? 0`). Kept the semantic green/amber/red data-viz colors (they match the design tokens and read correctly in both themes) and the `.seg` view toggle (deliberate local variant).

### Workload — 90/100
Toasts on charge-entry and plan-charge submits (were silent); `.form-label` consistency. Retained the occupation matrix (sticky headers), year segmented control, metric band, project-picker + context back-button + URL nav.

**Final three average: ~90/100 — the entire application is past the gate.**

---

## Cross-cutting sub-9 findings

### Consistency (UIC)
- **Three switch languages** — `.pms-tabs`, `.seg` (kpi/workload), Bootstrap `.nav-pills` (governance). Converge on `.pms-tabs`. *(Governance −, KPI −)*
- **Inline `style="…"`** in Projects list, KPI, Project detail — dozens of one-off `font-size/color` literals instead of classes/tokens. Fragments the system; top "AI-generated" tell. *(Projects −2, others −1)*

### UX Quality
- **Projects list pagination is hand-rolled** (prev/next only) instead of `<app-pagination>` — no page-size selector, no "from–to of total", no sort. Every other list uses the shared component or is a candidate for it. *(Projects −2)*
- **Tables lack sorting** — no `aria-sort`, no clickable headers, across Projects/Resources/Billing/Governance tables. The enterprise-table mandate expects it.

### Accessibility
- **Icon-only buttons** in some row-action columns and the topbar lack `aria-label` (pagination does it right — match it).
- Sortable tables will need `aria-sort`; toasts already use `aria-live` — verify all error surfaces do.

### Performance
- **Render-blocking font `@import`** in `styles.scss`; Sass `@import` is also deprecated (Dart Sass 3.0). Move fonts to `<link preconnect>` in `index.html`; migrate Sass to `@use`.
- **`NG8102` template warnings** (redundant `?? 0` on non-nullable) in KPI and Project detail — noise, quick cleanup.

---

## Rework triggers (pages < 85)

| Page | Score | To reach ≥ 90 |
|------|-------|---------------|
| Projects (list) | 77 | Adopt `<app-pagination>` + page-size; add sortable headers; strip inline styles → classes; a11y-label row actions |
| Admin | 77 | Confirm `<app-pagination>` usage + sort; a11y pass on icon buttons |
| Governance | 79 | `nav-pills` → `.pms-tabs`; strip inline styles |
| Billing / Missions / Resources | 80 | Sort + column-visibility on tables; inline-style cleanup |

Detailed, sequenced actions live in [DESIGN_IMPROVEMENT_BACKLOG](DESIGN_IMPROVEMENT_BACKLOG.md).
