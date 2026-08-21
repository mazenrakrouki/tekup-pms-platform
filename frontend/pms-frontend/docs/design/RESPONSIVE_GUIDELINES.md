# Responsive Guidelines

Desktop-first product (an enterprise PMS is used primarily on laptops/desktops) that **adapts** — not merely shrinks — down to tablet and mobile.

---

## 1. Breakpoints

| Range | Behavior |
|-------|----------|
| ≥ 1025px | Full layout: 240px sidebar + content |
| 769–1024px (tablet) | Sidebar narrows to 220px (`--s-width:220px`); content unchanged |
| ≤ 768px (mobile) | Sidebar becomes an **off-canvas drawer** (`translateX(-100%)`, slides in with `--sh-xl`); `.main-content` goes full-width; `.page-body` padding drops to 16px |

Bootstrap grid tiers (`col-6 col-xl-3`, `col-12`, `d-none d-md-table-cell`, `d-none d-lg-table-cell`, `d-none d-xl-table-cell`) handle intra-page reflow.

---

## 2. Adaptive patterns (not just scaling)

| Element | Desktop | Mobile |
|---------|---------|--------|
| Sidebar | Persistent rail | Drawer over content |
| Metric band | 4-up (`col-xl-3`) | 2-up (`col-6`) |
| Table columns | All visible | Non-essential columns hidden (`d-none d-md/lg/xl-table-cell`); keep identity + status + primary metric |
| Wide table / matrix | Fits | `overflow-x:auto`, horizontal scroll within its container only |
| Toolbar | Inline row | `flex-wrap` to multiple rows |

Content priority on small screens: identity → status → primary metric first; secondary columns fold.

---

## 3. Rules

- **Never** disable zoom; keep the viewport meta at `width=device-width, initial-scale=1`.
- **No** horizontal page scroll on mobile — only designated `overflow-x:auto` regions scroll sideways.
- Body text stays ≥ 13px (inputs effectively 16px-equivalent to avoid iOS zoom on focus where relevant).
- Touch targets ≥ 44×44 on mobile; icon buttons already meet this (32px visual + hit area).
- Test at 375px (small phone), 768px (drawer threshold), 1024px (narrow rail), and 1440px.

---

## 4. Known gaps

Mobile column-priority and toolbar wrapping are handled ad-hoc per page today. A shared responsive-table directive (declarative column priority + optional card fallback under a threshold) is proposed in [DESIGN_IMPROVEMENT_BACKLOG](DESIGN_IMPROVEMENT_BACKLOG.md).
