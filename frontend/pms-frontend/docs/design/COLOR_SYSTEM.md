# Color System

Every value below is a live token in [`src/styles.scss`](../../src/styles.scss). Components reference tokens, never raw hex.

---

## 1. Brand & semantic

| Token | Light | Meaning | Paired "dim" (12–18% tint) |
|-------|-------|---------|----------------------------|
| `--c-brand` | `#2563EB` | Primary action, active nav, links | `--c-brand-dim` `.10` · `--c-brand-dim2` `.18` |
| `--c-brand-hover` | `#1D4ED8` | Primary hover/press | — |
| `--c-success` | `#16A34A` | Completed, valid, profitable | `--c-success-dim` `.12` |
| `--c-warning` | `#D97706` | On-hold, at-risk, break-even | `--c-warning-dim` `.12` |
| `--c-danger` | `#DC2626` | Cancelled, error, loss, destructive | `--c-danger-dim` `.12` |

### Accent (charts, metric-icon backgrounds — categorical, not status)

| Token | Hex | Dim |
|-------|-----|-----|
| `--c-purple` | `#7C3AED` | `--c-purple-dim` `.12` |
| `--c-teal` | `#0D9488` | `--c-teal-dim` `.12` |
| `--c-amber` | `#F59E0B` | `--c-amber-dim` `.12` |

**Rule** — `success/warning/danger` are reserved for *status*. Purple/teal/amber are *categorical* accents (a metric tile, a chart series). Never use a status color decoratively, and never use an accent to imply "good/bad."

---

## 2. Surfaces & borders

| Token | Light | Dark | Role |
|-------|-------|------|------|
| `--bg` | `#F6F8FA` | `#0D1117` | App canvas (scroll area) |
| `--surface` | `#FFFFFF` | `#161B22` | Cards, tables, modals, inputs |
| `--surface-2` | `#F8FAFC` | `#1C2129` | Table headers, row hover, subtle fills |
| `--surface-3` | `#F1F5F9` | `#21262D` | Track backgrounds, tab bar, ghost hover |
| `--border` | `#E2E8F0` | `#30363D` | Default 1px separators |
| `--border-2` | `#CBD5E1` | `#3D444D` | Emphasis border, hover border |

Elevation layers upward: `--bg` → `--surface` → (`--surface-2` header) → shadow. Do not stack more than one surface step per component.

---

## 3. Text

| Token | Light | Dark | Role | Min contrast |
|-------|-------|------|------|--------------|
| `--text-1` | `#0F172A` | `#E6EDF3` | Primary: titles, values, table cells | AAA on surface |
| `--text-2` | `#64748B` | `#8B949E` | Secondary: labels, subtitles, meta | AA (4.5:1) |
| `--text-3` | `#94A3B8` | `#6E7681` | Tertiary: hints, separators, disabled | ≥3:1 (large/decorative only) |
| `--text-ph` | `#94A3B8` | `#6E7681` | Input placeholder | — |

**Rule** — never put `--text-3` on `--surface-2`/`--surface-3` for body-sized text; it fails AA. Use `--text-2` for anything a user must read.

---

## 4. Sidebar (dark in both themes)

| Token | Value | Role |
|-------|-------|------|
| `--s-bg` | `#111827` (light) / `#0D1117` (dark) | Rail background |
| `--s-text` | `#9CA3AF` / `#8B949E` | Nav item resting |
| `--s-text-hover` | `#F3F4F6` / `#C9D1D9` | Nav item hover |
| `--s-active-bg` | `rgba(37,99,235,.14)` | Active item fill |
| `--s-active-text` | `#93C5FD` | Active item text |
| `--s-section` | `rgba(255,255,255,.26)` | Section labels (uppercase) |

Active item also gets a 3px brand bar on its left edge (`.nav-link.active::before`).

---

## 5. Dark-mode contract

Dark mode is **not** an inversion. It re-tones surfaces (GitHub Dimmed family: `#0D1117`→`#161B22`→`#21262D`) and *deepens* shadows (`rgba(0,0,0,.20→.60)`). Semantic hues stay the same — their `-dim` tints already read on dark surfaces. Always verify contrast independently; never assume a light pairing survives.

The viewer's theme toggle stamps `data-theme` on `<html>`; some components additionally use `:host-context([data-theme="dark"])` for local overrides (see KPI badges).

---

## 6. Status-color map (canonical)

| Domain state | Class | Color |
|--------------|-------|-------|
| Project ACTIVE | `.badge-active` | success |
| Project DRAFT / PREVU | `.badge-draft` / `.badge-prevu` | neutral (`surface-3` + `text-2`) |
| Project ON_HOLD | `.badge-on-hold` | warning |
| Project COMPLETED | `.badge-completed` | brand |
| Project CANCELLED | `.badge-cancelled` | danger |
| Risk OUVERT | `.badge-ouvert` | danger |
| Risk MITIGE | `.badge-mitige` | warning |
| Risk FERME | `.badge-ferme` | success |

Reuse these classes verbatim — do not re-map a status to a new color on a single page.
