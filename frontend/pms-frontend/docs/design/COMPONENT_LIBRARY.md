# Component Library

Catalog of the shipped, reusable building blocks. **Prefer these over new markup.** Two layers: CSS component classes (global, in `styles.scss`) and Angular shared components (`src/app/shared/*`).

---

## A. Angular shared components

| Component | Selector | Purpose | Inputs / Outputs |
|-----------|----------|---------|------------------|
| Pagination | `<app-pagination>` | Standard list footer: "from–to of total", page-size selector, prev/next | in: `page`, `pageSize`, `total`, `pageSizeOptions` · out: `pageChange`, `pageSizeChange` |
| Project picker | `<app-project-picker>` | Canonical project selector for feature modules — searchable, paginated, quick-access recents. **Replaces giant dropdowns.** | in: `selected`, `featureTitle`, `featureIcon`, `featureDescription` · out: `projectSelected` |
| Confirm modal | `<app-confirm-modal>` | App-wide destructive-action confirmation, driven by `ConfirmService` | via service |
| Toast container | `<app-toast-container>` | Non-blocking feedback, driven by `ToastService` | via service |

> **Mandatory reuse:** any list over ~10 rows uses `<app-pagination>`; any per-project feature uses `<app-project-picker>`. Hand-rolled prev/next or `<select>` of all projects is a defect (see backlog). Destructive actions go through `ConfirmService`, not `window.confirm`.

---

## B. Page scaffold

```html
<div class="topbar">
  <div class="tb-breadcrumb"> … </div>       <!-- left: context path -->
  <div class="tb-right"> … </div>            <!-- right: page actions -->
</div>
<div class="page-body">
  <div class="page-header">
    <h1 class="page-title">…</h1>
    <p class="page-subtitle">…</p>
  </div>
  …cards / tables…
</div>
```

Breadcrumb supports a back affordance: `.bc-back-btn` (returns to a parent state), `.bc-sep` (`›`), `.bc-curr` (current, `--text-1`).

---

## C. Cards

| Class | Use |
|-------|-----|
| `.card` | Container: `--surface`, `--border`, `--r-lg`, `--sh-xs` |
| `.card-header` | 13px/600 header row, flex |
| `.module-card` | Hoverable navigation card (elevates on hover) |
| `.metric-card` | KPI tile — see below |

**Metric tile anatomy:** `.metric-card` → `.metric-icon` (colored tile) + `.metric-label` (uppercase 11px) + `.metric-value` (28px/700 tabular) + optional `.metric-sub`. Use a 4-up row (`col-6 col-xl-3`) for a metric band.

Use cards to **group**, not to fragment. A list of records is a table, not N cards.

---

## D. Buttons

| Class | Role |
|-------|------|
| `.btn-primary` | The one primary action per screen (brand fill) |
| `.btn-outline-primary` | Secondary emphasis, brand outline |
| `.btn-outline-secondary` / `.btn-secondary` | Neutral secondary |
| `.btn-ghost` | Tertiary / toolbar, no border |
| Modifiers | `.btn-sm`, `.btn-icon` (32×32 square) |

One `.btn-primary` per view. Everything else is subordinate. Disabled = `opacity:.55`. Async actions show a spinner and disable while pending.

---

## E. Data tables

Base `.table` (13px, uppercase `thead`, `--surface-2` header, row hover). Wrap in `.table-responsive` (or an `overflow-x:auto` container) so the page body never scrolls sideways.

**Enterprise table target** (partially implemented — see backlog): search, sort (`aria-sort`), pagination via `<app-pagination>`, row actions, column visibility, bulk actions, keyboard nav. Prefer a table over card grids for record lists.

---

## F. Status & identity

| Class | Use |
|-------|-----|
| `.badge-active/-draft/-on-hold/-completed/-cancelled` | Project status (dot + tint) |
| `.badge-ouvert/-mitige/-ferme/-prevu` | Governance/billing status |
| `.role-badge` (dark) · `.role-badge-light` | RBAC role chips |

All status badges share the `status-badge` mixin: dim background + solid text + a 5px status dot. Add new statuses to the mixin, not as ad-hoc spans.

---

## G. Navigation & switches

| Pattern | Use | Canonical? |
|---------|-----|-----------|
| `.pms-tabs` + `.tab-item` | Segmented in-page view switch (Actifs/Archivés, detail tabs) | **Yes — standard** |
| `.seg` / `.seg-btn` (kpi, workload) | Local segmented control | Tolerated; converge on `.pms-tabs` |
| Bootstrap `.nav-pills` (governance) | Sub-tabs | **No — migrate to `.pms-tabs`** |

One switch language. Convergence tracked in the backlog.

---

## H. Overlays & feedback

| Class | Use |
|-------|-----|
| `.modal-content` / `-header` / `-footer` | Dialog (`--r-xl`, `--sh-xl`); backdrop `rgba(0,0,0,.45→.65)` |
| `.alert-info/-success/-warning/-danger` | Inline, token-tinted messages |
| Toast (via `ToastService`) | Transient success/error, non-blocking, `aria-live` |
| `.empty-state` | `.es-icon` + `.es-title` + `.es-desc` + one primary action |
| `.skeleton` | Loading placeholder (`sk-pulse`, respects reduced-motion) |
| `.pms-progress` + `.pms-progress-bar` | Determinate progress |

---

## I. Forms

`.form-label` (12/600) → `.form-control`/`.form-select` (13px, brand focus ring `0 0 0 3px rgba(37,99,235,.12)`). Search fields use `.input-wrap` + `.input-icon` for the leading glyph. Errors: `.alert-danger` near the field; required marked with a danger asterisk. See [UX_PRINCIPLES](UX_PRINCIPLES.md) for form layout (grouping, sticky actions, progressive disclosure).
