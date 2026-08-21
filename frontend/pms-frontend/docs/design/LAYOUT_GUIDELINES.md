# Layout Guidelines

Shell = fixed dark **sidebar** + scrollable **main content** with a sticky **topbar**. Defined in `styles.scss` and assembled in `ShellComponent`.

```
┌──────────┬─────────────────────────────────────┐
│ sidebar  │ topbar (sticky, 56px)               │
│ (fixed,  ├─────────────────────────────────────┤
│  240px,  │ page-body (scrolls)                 │
│  dark)   │   page-header                       │
│          │   cards / tables / …                │
└──────────┴─────────────────────────────────────┘
```

---

## 1. Shell

`ShellComponent` renders `.app-layout` (flex, full height) containing `<app-sidebar>` + `.main-content` (router-outlet), plus global `<app-confirm-modal>` and `<app-toast-container>`. Collapse state is driven by `LayoutService.sidebarCollapsed()` toggling `.sidebar-collapsed` on `.app-layout`.

---

## 2. Sidebar

| Property | Value |
|----------|-------|
| Width | `--s-width` 240px (expanded) · `--s-width-c` 56px (collapsed) |
| Position | `fixed`, full height, `z-index:200` |
| Background | `--s-bg` (dark in both themes) |
| Regions | `.sb-header` (brand) → `.sb-search` → `.sb-nav` (sections + items) → `.sb-footer` (user + actions) |

- Nav items: `.nav-link` with `.active` (brand fill + 3px left bar + `--s-active-text`).
- Sections: `.sb-section` uppercase 10px labels group items (Vue générale / Projets / Équipe & charges / Finance / Gouvernance).
- **Collapsed:** labels/section/brand-text hide; nav icons center; hovering a `.nav-link[data-label]` shows a fixed tooltip. Icon-only nav must keep its label available (tooltip + `aria-label`).

---

## 3. Topbar

Sticky (`top:0`, `z-index:100`), 56px, `--tb-bg` with bottom border. Left = `.tb-breadcrumb` (context). Right = `.tb-right` (page-scoped actions: `.tb-btn` icon buttons, `.tb-user`). The topbar states *where you are* and *what you can do here* — nothing global-scoped belongs on the right except the user menu.

---

## 4. Page scaffold

`.page-body` (24px padding) → `.page-header` (title + subtitle, `margin-bottom:24px`) → content. One `.page-title` per route. See [COMPONENT_LIBRARY §B](COMPONENT_LIBRARY.md).

---

## 5. Z-index scale

| Layer | z | Element |
|-------|---|---------|
| Base | 0 | Content |
| Topbar | 100 | Sticky header |
| Sidebar | 200 | Fixed rail |
| Sidebar tooltip | 400 | Collapsed hover label |
| Modal/backdrop | Bootstrap (1050+) | Dialogs |
| Sticky table head/foot | 1–3 (local) | Within `.occ` matrix |

Keep new overlays on this scale; don't invent competing z-values.

---

## 6. Scroll model

`html,body { overflow:hidden }`. Only `.main-content` (and `.sb-nav`) scroll. This keeps the sidebar and topbar fixed while content moves. Wide content (tables, the workload matrix) gets its own `overflow-x:auto` container so the page body never scrolls horizontally. Sticky table headers/footers use local sticky positioning within that container.
