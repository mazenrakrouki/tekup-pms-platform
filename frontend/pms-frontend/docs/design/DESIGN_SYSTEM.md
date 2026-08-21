# PMS Design System

> Enterprise SaaS design system for the PMS platform.
> Inspired by the interaction and information-density decisions of **Linear, GitHub, Vercel, and Jira Cloud** — not their branding.
> Single source of truth: [`src/styles.scss`](../../src/styles.scss). This folder documents it.

---

## 1. Philosophy

| Principle | What it means here |
|-----------|--------------------|
| **Dark rail, light canvas** | The sidebar is dark in *both* themes (Linear/Jira). The workspace is light by default, fully dark under `[data-theme="dark"]`. This anchors navigation and lets content breathe. |
| **Tokens, never literals** | Components consume CSS custom properties (`var(--…)`). A component must not hardcode a hex value. One token change re-themes the whole app. |
| **Density with air** | Enterprise users scan, they don't read. Type is 13–14px, rows are compact, but an 8px rhythm keeps it from feeling cramped. |
| **One question per page** | Each route answers a single question. Secondary data is progressively disclosed (tabs, drawers, modals), never dumped. |
| **Color = meaning** | Color communicates status (success/warning/danger/info), never decorates. Neutrals carry the layout. |
| **Context is sacred** | Navigating into a detail and back restores filters, pagination, search, sort, and selection (URL-encoded). See [UX_PRINCIPLES](UX_PRINCIPLES.md). |

---

## 2. Token architecture

Tokens live on `:root` (light) and are overridden under `[data-theme="dark"]`. Bootstrap 5.3 variables are *bridged* to our tokens so third-party classes (`text-muted`, `nav-pills`, `input-group-text`) adapt automatically.

```
:root                    → light-mode token values + Bootstrap bridge
[data-theme="dark"]      → dark-mode overrides (surfaces, text, shadows, sidebar)
```

| Group | Prefix | Doc |
|-------|--------|-----|
| Brand & semantic color | `--c-*` | [COLOR_SYSTEM](COLOR_SYSTEM.md) |
| Surfaces & borders | `--bg --surface* --border*` | [COLOR_SYSTEM](COLOR_SYSTEM.md) |
| Text | `--text-1/2/3 --text-ph` | [COLOR_SYSTEM](COLOR_SYSTEM.md) · [TYPOGRAPHY](TYPOGRAPHY.md) |
| Sidebar | `--s-*` | [LAYOUT_GUIDELINES](LAYOUT_GUIDELINES.md) |
| Topbar | `--tb-*` | [LAYOUT_GUIDELINES](LAYOUT_GUIDELINES.md) |
| Elevation | `--sh-xs … --sh-xl` | [COMPONENT_LIBRARY](COMPONENT_LIBRARY.md) |
| Radius | `--r-xs … --r-full` | [SPACING_SYSTEM](SPACING_SYSTEM.md) |
| Motion | `--t --t-slow` | this doc §4 |

---

## 3. Document map

| Document | Covers |
|----------|--------|
| [COLOR_SYSTEM.md](COLOR_SYSTEM.md) | Every color token, dim variants, dark-mode pairs, WCAG contrast, usage rules |
| [TYPOGRAPHY.md](TYPOGRAPHY.md) | Inter, type scale, weights, semantic text roles, tabular figures |
| [SPACING_SYSTEM.md](SPACING_SYSTEM.md) | 4/8px rhythm, radius scale, real padding values |
| [ICON_GUIDELINES.md](ICON_GUIDELINES.md) | Bootstrap Icons, sizing tokens, a11y labels, no-emoji rule |
| [COMPONENT_LIBRARY.md](COMPONENT_LIBRARY.md) | Catalog of every class + Angular shared component, anatomy, when-to-use |
| [LAYOUT_GUIDELINES.md](LAYOUT_GUIDELINES.md) | Shell, sidebar, topbar, page scaffold, z-index scale |
| [RESPONSIVE_GUIDELINES.md](RESPONSIVE_GUIDELINES.md) | Breakpoints, sidebar collapse/drawer behavior, adaptive rules |
| [UX_PRINCIPLES.md](UX_PRINCIPLES.md) | Workflow, navigation-state preservation, IA, hierarchy |
| [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) | ADR-style log of *why* each major choice was made |
| [DESIGN_REVIEW.md](DESIGN_REVIEW.md) | Per-page scorecards (10 criteria) |
| [DESIGN_IMPROVEMENT_BACKLOG.md](DESIGN_IMPROVEMENT_BACKLOG.md) | Prioritized, actionable fixes |

---

## 4. Motion

Two tokens only. Consistency beats variety.

| Token | Value | Use |
|-------|-------|-----|
| `--t` | `140ms ease` | Micro-interactions: hover, color, background, small state changes |
| `--t-slow` | `220ms ease` | Structural: sidebar collapse, margin shift, drawer |

Rules: animate `transform`/`opacity`/`color`/`background` only — never `width`/`height`/`top`/`left` on interactive paths. Respect `prefers-reduced-motion` for any keyframe animation (skeleton, progress). Exit ≤ enter.

---

## 5. The one rule that keeps this coherent

> **Before adding any new CSS, check whether a token or component class already exists.**
> If it does, use it. If a new pattern is genuinely needed, add it to `styles.scss` *and* document it here — never inline a one-off style on a single page.

Inline `style="…"` for anything beyond a dynamic value (a computed width %, a data-driven color) is technical debt. It fragments the system and is the #1 driver of the "AI-generated" look this platform must avoid.
