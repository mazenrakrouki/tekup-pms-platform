# UX Principles

How we decide *what* to build before *how* to build it.

---

## 1. The workflow (every feature, every page)

1. **Understand** — What one question does this page answer? What's the primary action? What's secondary? What can be deferred (tab/drawer/modal)?
2. **Design** — Fewest clicks, least cognitive load. Sketch the flow before markup.
3. **Review** — Would this screen look at home in Linear / Jira / GitHub / Azure DevOps? If not, rework it.
4. **Implement** — Only against the design system tokens/components.
5. **Audit** — Score it (see [DESIGN_REVIEW](DESIGN_REVIEW.md)); anything < 9/10 gets reworked.

---

## 2. One question per page

Each route has a single job. Supporting detail is progressively disclosed, never dumped:

| Route | The one question |
|-------|------------------|
| Projects | "Which project do I open?" |
| Project detail | "What's the state of *this* project?" (tabs: Infos/KPI, Équipe, Charges, Facturation, Missions, Gouvernance) |
| Workload | "Is this project's resourcing plan vs. actual healthy?" |
| KPI | "Which projects are profitable / at risk?" |
| Billing | "What's billed, pending, and paid on this project?" |

If a page grows a second unrelated question, split it.

---

## 3. Visual hierarchy

Every screen ranks: **Primary action → Secondary actions → Information → Navigation → Status.** Nothing competes equally. One `.btn-primary`. The eye should land on the primary action within a second.

---

## 4. Navigation state is sacred (implemented)

Opening a detail and returning must restore the exact working context — filters, search, pagination, sort, selected project, active tab, and (where feasible) scroll. **Mechanism: URL query params**, so browser back/forward, refresh, deep-link, and share all work with no extra state service.

| Page | Encoded state |
|------|--------------|
| Projects list | `?page&search&status&mode` |
| Workload / Billing / Missions | `?p={projectId}` |
| Governance | `?p={projectId}&tab={sub-tab}` |

`replaceUrl:true` for filter tweaks (no history spam); a new history entry for project selection (so *back* un-selects). Every per-project page shows an in-app back affordance (`.bc-back-btn`) that returns to the picker. Full write-up: [`UX_NAVIGATION_IMPROVEMENTS.md`](../UX_NAVIGATION_IMPROVEMENTS.md).

---

## 5. Selection at scale — no giant dropdowns

A `<select>` listing all projects is banned. Per-project features use `<app-project-picker>` (searchable, paginated, with quick-access recents). Large record lists use tables + `<app-pagination>`. This is a firm platform rule.

---

## 6. Forms

- Group related fields; label sections. Two columns where it aids scanning (dates, amounts), single column for a linear flow.
- Visible labels (12/600), not placeholder-only. Required = danger asterisk.
- Validate on blur; error in `.alert-danger` near the field; focus the first invalid field on submit.
- Long/creation forms: sticky action bar, confirm-on-dismiss with unsaved changes.
- Destructive actions: `ConfirmService` dialog, danger color, visually separated from primary.

---

## 7. Empty, loading, error

- **Empty:** `.empty-state` — one line + one primary action. No paragraphs, no illustrations-for-illustration's-sake, never "Coming soon."
- **Loading > 300ms:** `.skeleton` matching the final layout (KPI already does this), not a bare spinner.
- **Error:** state the cause and the recovery path (retry/edit), never a dead end or a blank chart.

---

## 8. Anti-patterns (reject on sight)

Giant dropdowns · card grids replacing tables · placeholder/tutorial copy · "under development" · lorem ipsum · helper paragraphs restating the obvious · inline one-off styles · a new color/tab/spacing invented for a single page · icon-only controls with no label · color as the only status signal.
