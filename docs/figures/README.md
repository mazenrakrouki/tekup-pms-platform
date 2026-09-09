# Explanatory figures

Hand-authored SVG sources for the illustrations that explain a *concept* in the report, as
opposed to the UML diagrams (whose PlantUML sources live in `docs/uml/`) and the application
screenshots.

| Source | Rendered to | Used in |
|---|---|---|
| `st2i_organigramme.svg` | `img/st2i_organigramme.png` | 1.1.4 Organizational structure |
| `scrum_cycle.svg` | `img/scrum_cycle.png` | 1.6.1 The Scrum framework |
| `scrum_roles.svg` | `img/scrum_roles.png` | 1.6.2 Scrum roles |
| `rbac_chain.svg` | `img/rbac_chain.png` | 2.5 The Dynamic Authorization Model |
| `three_tier.svg` | `img/three_tier.png` | 3.1.1 A Three-Tier Modular Monolith |
| `layered_architecture.svg` | `img/layered_architecture.png` | 3.1.2 Layered Organisation |
| `indicator_engine.svg` | `img/indicator_engine.png` | 3.2.3 A Hybrid Indicator Engine |

`img/` above is `report/Rapport PFE TEKUP LATEX/img/`.

## Rendering

```bash
node render.js                      # all of them
node render.js three_tier scrum_cycle   # or a subset, by basename
```

`render.js` drives the installed Chrome through puppeteer-core and writes PNGs at a device scale
factor of 3, which is roughly 550 dpi at the width these figures are included in the report. It
needs `puppeteer-core` on the module path.

## House style

The figures share one palette and one type stack so that they read as a set:

- `#1F4E79` primary (the cover rule colour, close to the ST2i blue `#034D89`)
- `#34719F` secondary, `#DCE7F1` / `#E8F0F7` / `#F4F8FB` tints
- `#C4212A` used **once per figure**, on the one thing the figure exists to say

The org chart is the one exception to the single-accent rule, and deliberately: its five activity
lines each carry their own hue (`#1F4E79` blue, `#C4212A` red, `#1E7A6F` teal, `#B0761B` ochre,
`#5B4A9E` violet), because the colour is what lets the reader follow a line down into the
transversal band of technical teams. The red is still doing the accent's job — it is the line whose
economics the platform steers.
- `#16212B` text, `#5C6B7A` secondary text, `#AEBCC9` card borders
- Segoe UI / Helvetica Neue / Arial, with Consolas for identifiers taken from the code

Font sizes are chosen for the width each figure is included at: roughly 21 units for a title and
16–17 for a label, on a canvas about 1150 units wide included at `\columnwidth`, which lands near
8 pt and 6.5 pt on the page. Making a canvas narrower without scaling its type down is what makes
a figure's text too large next to the body text.
