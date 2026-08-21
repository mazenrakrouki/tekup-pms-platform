# Spacing & Radius

An **8px rhythm** (with 4px half-steps) governs all padding, gaps, and section spacing. Values are expressed in `rem` (root 16px) so `.5rem = 8px`, `.75rem = 12px`, `1rem = 16px`, `1.5rem = 24px`.

---

## 1. Spacing ladder

| Step | rem | px | Typical use |
|------|-----|----|-------------|
| 2xs | `.25rem` | 4 | Icon–text gap, badge dot gap, tight control gaps |
| xs | `.375rem` | 6 | Button internal gap, nav margin |
| sm | `.5rem` | 8 | Default gap between controls, avatar gap |
| md | `.75rem` | 12 | Toolbar gap, card-header inline |
| base | `1rem` | 16 | Table cell horizontal, card gutter |
| lg | `1.125rem` | 18 | Metric-card / card-header padding |
| xl | `1.5rem` | 24 | `.page-body` padding, section spacing |
| 2xl | `2.5rem` | 40 | Empty-state vertical padding |

Grid gaps use Bootstrap `g-3` (16px) / `g-4` (24px) for metric and card rows — stay on these.

---

## 2. Real component padding (reference)

| Component | Padding |
|-----------|---------|
| `.page-body` | `1.5rem` (mobile `1rem`) |
| `.card-header` | `.875rem 1.125rem` |
| `.metric-card` | `1.125rem` |
| `.table` cell | `.65rem 1rem` (head `.55rem 1rem`) |
| `.btn` sm | `.3rem .625rem` |
| `.form-control` | `.45rem .75rem` (sm `.3rem .625rem`) |
| `.modal-header` | `1rem 1.25rem` |
| `.topbar` | `0 1.25rem` |
| `.nav-link` | `.38rem .75rem` |

Section rhythm inside a page: `page-header` has `margin-bottom:1.5rem`; stacked cards/rows use `mb-4` (24px). Keep vertical tiers consistent — 16 / 24 by hierarchy, not arbitrary.

---

## 3. Radius scale

| Token | Value | Use |
|-------|-------|-----|
| `--r-xs` | 3px | Role badges, tiny chips, sidebar tooltip |
| `--r-sm` | 5px | Badges, tab items, icon buttons, skeleton |
| `--r` | 7px | **Default** — buttons, inputs, nav items, search |
| `--r-md` | 9px | (reserved) |
| `--r-lg` | 12px | Cards, metric cards, empty-state icon |
| `--r-xl` | 16px | Modals |
| `--r-full` | 9999px | Avatars, progress tracks, pills |

Radius grows with surface size: controls `7px`, cards `12px`, modals `16px`. Never mix an arbitrary radius; pick the token nearest the element's scale.

---

## 4. Elevation (paired with spacing, not decorative)

| Token | Use |
|-------|-----|
| `--sh-xs` | Resting cards & metric tiles |
| `--sh-sm` | Card/tile hover, active tab |
| `--sh-md` | Popover, sidebar tooltip |
| `--sh-lg` | Dropdown/menu |
| `--sh-xl` | Modal, mobile sidebar drawer |

Shadows deepen in dark mode (defined under `[data-theme="dark"]`). One elevation step per component — a resting card is `xs`, its hover is `sm`. Do not hand-roll shadow values.
