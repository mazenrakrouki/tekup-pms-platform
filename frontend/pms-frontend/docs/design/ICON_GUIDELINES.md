# Icon Guidelines

Icon set: **Bootstrap Icons** (`bi bi-*`), loaded globally. One family, one visual language across the entire app. No second icon library, no emoji as UI.

---

## 1. Sizing

Icons inherit `font-size`; set it to match the text or control they sit in. Standard sizes in use:

| Context | Size |
|---------|------|
| Sidebar nav icon | 15px (fixed 18px box, centered) |
| Metric-card icon | 16px (in 36px `--r` tile) |
| Topbar / table-row action | 14–15px |
| Input affordance (`.input-icon`) | 14px |
| Breadcrumb / meta | 13px |
| Empty-state glyph | 22px (in 48px `--r-lg` tile) |
| Badge status dot | 5px (drawn, not an icon) |

Keep sizes on this ladder — don't scatter 20/24/28px arbitrarily.

---

## 2. Icon tiles

Colored icon backgrounds (metric cards, empty states) use a **dim token fill + solid token foreground**:

```html
<div class="metric-icon" style="background:var(--c-brand-dim);color:var(--c-brand)">
  <i class="bi bi-briefcase-fill"></i>
</div>
```

Pair each accent consistently: brand→brand-dim, purple→purple-dim, teal→teal-dim, amber→amber-dim. (This is the one sanctioned inline-style pattern, because the color is semantic to that specific tile.)

---

## 3. Accessibility

- **Icon-only buttons must carry a label.** Provide `aria-label` (or `title` where a tooltip is also wanted). Pagination and topbar controls already do this — match that.
- Decorative icons beside a text label need no label (the text names the action).
- Never convey status by icon color alone; pair with a word or the status badge.
- Touch target ≥ the control box (icon buttons are 32×32).

---

## 4. Rules

| Do | Don't |
|----|-------|
| Use `bi bi-*` from Bootstrap Icons | Introduce Lucide/Phosphor/Heroicons alongside it |
| Use SVG-backed font glyphs (scales, themes) | Use emoji (🚀 ⚙️ ✅) as structural icons |
| Keep one weight/style per hierarchy level | Mix filled + outline at the same level randomly |
| Match icon color to its text via tokens | Hardcode icon hex values |

> The `@phosphor-icons`/Heroicons guidance in the ui-ux-pro-max skill is React-oriented. **This is an Angular + Bootstrap Icons codebase** — stay on `bi bi-*` for consistency. Documented as a deliberate deviation in [DESIGN_DECISIONS](DESIGN_DECISIONS.md).
