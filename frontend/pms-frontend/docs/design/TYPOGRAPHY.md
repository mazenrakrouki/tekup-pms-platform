# Typography

Typeface: **Inter** (300/400/500/600/700), with `system-ui` fallback. Base is 14px / line-height 1.5. Monospace (`JetBrains Mono`, `Fira Code`) is reserved for project codes and identifiers via `.monospace`.

> Loading note: Inter is currently pulled via `@import url(fonts.googleapis…)` in `styles.scss`, which is render-blocking. Migrating to a `<link rel="preconnect">` + `<link>` in `index.html` is tracked in [DESIGN_IMPROVEMENT_BACKLOG](DESIGN_IMPROVEMENT_BACKLOG.md).

---

## 1. Type scale

The scale is deliberately small — 3 display sizes, a 14px base, and a 10–13px UI band. Do not introduce new sizes.

| Role | Size | Weight | Token/class | Notes |
|------|------|--------|-------------|-------|
| Page title | `1.375rem` (22px) | 700 | `.page-title` | `letter-spacing:-.025em`, one per page |
| Metric value | `1.75rem` (28px) | 700 | `.metric-value` | `letter-spacing:-.03em`, tabular |
| Modal title | `15px` | 600 | `.modal-title` | |
| Body / table / input | `13–14px` | 400 | base | 13px in dense tables, 14px body |
| Card header | `13px` | 600 | `.card-header` | |
| Page subtitle | `13px` | 400 | `.page-subtitle` | `--text-2` |
| Nav item | `13px` | 450 → 500 active | `.nav-link` | |
| Button | `13px` (sm 12px) | 500 | `.btn` | |
| Label (form) | `12px` | 600 | `.form-label` | `--text-1` |
| Metric label / table head | `11px` | 600 | `.metric-label`, `thead th` | UPPERCASE, `letter-spacing:.05–.06em` |
| Badge | `11px` | 600 | `.badge` | |
| Role badge / section | `10px` | 600 | `.role-badge`, `.sb-section` | UPPERCASE, `letter-spacing:.06–.08em` |

---

## 2. Weight ladder

| Weight | Use |
|--------|-----|
| 700 | Page titles, metric values only |
| 600 | Card headers, labels, badges, table heads, emphasis |
| 500 | Buttons, active nav, tab items, table primary cells |
| 450 | Resting nav items (a hair lighter than 500) |
| 400 | Body, subtitles, secondary meta |

Hierarchy comes from **weight + size + color**, not from color alone. A secondary line is `13px/400/--text-2`, not brand-colored.

---

## 3. Uppercase micro-labels

Small uppercase labels (`10–11px`, `600`, `letter-spacing .05–.08em`) are the system's signature for *category headers*: sidebar sections, metric labels, table column heads. They are **labels, not content** — never uppercase a sentence or a data value.

---

## 4. Numerals

Use `font-variant-numeric: tabular-nums` wherever digits align in a column or update in place: metric values, table numeric columns, pagination counters, the workload matrix, EVM/KPI figures. This prevents width jitter. Currency is TND, formatted via Angular `number:'1.0-0'`.

---

## 5. Truncation

Prefer wrapping. When space forces truncation (sidebar user name, long client names), use `text-overflow: ellipsis` on a `min-width:0` flex child and expose the full value via `title`. Never truncate a value the user must act on without a tooltip.
