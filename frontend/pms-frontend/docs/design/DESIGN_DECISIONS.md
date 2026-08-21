# Design Decisions (ADR-style)

Why the system is the way it is. Each entry: decision · rationale · trade-off.

---

### DD-1 · Dark sidebar in both light and dark themes
The navigation rail stays dark (`--s-bg`) even in light mode. **Why:** anchors the eye, separates "where I navigate" from "where I work," and matches Linear/Jira/Vercel. **Trade-off:** two contrast systems to maintain (dark rail tokens `--s-*` are theme-independent).

### DD-2 · Bootstrap as reset, tokens as skin
We keep Bootstrap 5.3 for grid/utilities/reset but override its CSS variables to point at our tokens (`--bs-*` bridge). **Why:** grid + utility velocity without Bootstrap's default look; components stay token-driven and re-theme instantly. **Trade-off:** must bridge each new Bootstrap surface variable; a few components still lean on Bootstrap defaults (e.g. `nav-pills` in governance — flagged to migrate).

### DD-3 · Inter as the single typeface
One family, weights 300–700. **Why:** neutral, dense, excellent at small UI sizes; the enterprise-SaaS default for a reason. **Trade-off:** currently `@import`ed from Google Fonts (render-blocking) — migration to `<link preconnect>` is queued.

### DD-4 · Bootstrap Icons, not Phosphor/Heroicons
The ui-ux-pro-max skill recommends Phosphor/Heroicons, but those are React packages. **Why:** this is Angular; `bi bi-*` is already global, consistent, and framework-agnostic. **Trade-off:** smaller glyph set than Phosphor; acceptable for an internal tool. Deliberate deviation from the skill default.

### DD-5 · 7px base radius, scaling with surface
Controls `7px` → cards `12px` → modals `16px`. **Why:** a soft, modern-but-serious feel; radius proportional to element size reads as intentional. **Trade-off:** more radius tokens to remember (mitigated by "pick nearest scale" rule).

### DD-6 · Navigation state in the URL, not a service
Filters/pagination/selection/tab are encoded as query params. **Why:** browser back/forward, refresh, deep-link, and share all "just work"; no bespoke state store to keep in sync; survives component recreation. **Trade-off:** scroll position isn't URL-friendly (deferred to an optional scroll-restoration service). See [UX_PRINCIPLES §4](UX_PRINCIPLES.md).

### DD-7 · `<app-project-picker>` over a project `<select>`
Per-project modules select via a searchable, paginated picker with recents. **Why:** the portfolio has 50+ projects; a native dropdown is unusable at that scale and hides recents. **Trade-off:** heavier component than a `<select>`; justified by scale and reused everywhere.

### DD-8 · Signals + standalone components
Angular standalone components with `signal()`/`computed()`/`inject()`. **Why:** less boilerplate, fine-grained reactivity, no NgModules. **Trade-off:** requires Angular 17+ (already the baseline).

### DD-9 · `.pms-tabs` as the one switch language
Segmented in-page switches use `.pms-tabs`. **Why:** a single, recognizable control for "change the view of this page." **Trade-off:** existing `.seg` (kpi/workload) and `nav-pills` (governance) predate the rule and are queued to converge.

### DD-10 · TND, French-first
Currency is TND, UI copy is French (`Charges`, `Facturation`, `Gouvernance`). **Why:** the deploying organization's locale. **Trade-off:** i18n not abstracted yet; acceptable for current scope.
