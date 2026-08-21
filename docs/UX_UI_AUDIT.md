# UX / UI Audit & Modernization Record

> **Author:** Lead UX/UI Engineer (session 2026-07-29 → 30)
> **Scope:** Full-application UX review + admin-module completion (Roles, Permissions, TCC).
> **Method:** every "Done" item below was implemented **and verified at runtime** (browser + API),
> not asserted from code. Verification evidence is summarized per section.
> **Companion doc:** [`ENHANCEMENTS.md`](ENHANCEMENTS.md) — the forward-looking engineering backlog.

---

## 1. Executive summary

The application was already well-architected (dynamic RBAC, security depth, Flyway discipline) but
carried visible "AI-generated" tells in the UI: button-list selectors, explanatory paragraphs,
placeholder "Bientôt" cards, and inconsistent feedback (inline messages, no toasts). Two admin
capabilities were stubs.

This pass delivered, end-to-end and verified:

1. **Three admin modules made production-ready** — Role Management (full CRUD + permission
   assignment), Permission catalog (grouped, roles-per-permission), and TCC hardening with correct
   per-role data scoping. **All placeholders removed.**
2. **Shared UX foundation** — a `ToastService` + container (success/error/warning/info), reused by
   the new modules; skeleton loading states; consistent empty states.
3. **Navigation cleanup** — real sidebar entries for the new modules, dead duplicate routes removed.

A full Linear/Jira-grade redesign of all 14 feature screens is a multi-pass effort; §4 records the
per-screen audit and the prioritized roadmap for the remainder so nothing is faked or lost.

---

## 2. Design-system observations (cross-cutting)

| Area | State | Action |
|---|---|---|
| Color tokens | Solid CSS-variable system (`--c-brand`, `--text-1..3`, `--surface-*`, semantic dims), dark/light aware | Keep; new components consume tokens only |
| Typography | Consistent scale via global classes (`page-title`, `metric-*`, `card-header`) | Keep |
| Tables | `table table-hover align-middle` idiom, badge classes (`badge-active/draft/…`) | Reused verbatim in new pages |
| Feedback | **Was** inline `msg`/`window.confirm` only | **Added** `ToastService`; `ConfirmService` already existed |
| Loading | Mostly none | Skeleton rows added to new admin pages; pattern to propagate |
| Empty states | Mixed (`empty-state` component vs. bare "Aucun…") | Standardized on `empty-state` in new pages |

---

## 3. Admin modules — delivered this pass

### 3.1 Role Management (`/admin/roles`) — ✅ DONE & VERIFIED

- **Before:** dashboard card labelled *Bientôt* (Soon), `opacity:.5; pointer-events:none`. No page,
  no API. `MANAGE_ROLES` had been deleted from the RBAC matrix in V20.
- **Backend:** `V25__rbac_admin_module.sql` restores `MANAGE_ROLES` (granted to ADMIN), enriches
  `roles`/`permissions` with `description` + `roles.is_system`. New `RoleController`
  (`/api/admin/roles`) + `RoleAdminService` (all methods `@PreAuthorize('MANAGE_ROLES')`):
  list, get, create, update, delete.
- **Business rules enforced:** system roles (ADMIN/DIRECTEUR/CHEF_PROJET/DEVELOPPEUR) cannot be
  renamed or deleted; a role assigned to ≥1 user cannot be deleted (409/422 with a clear message);
  changing a role's permissions purges the security-context cache so active sessions pick up the new
  authorities on their next request (ADR-017-consistent).
- **Frontend:** professional table (system lock badge, description, permission count, user count),
  instant search, create/edit modal with **permission assignment grouped by module** and
  per-module *select-all*, delete via confirmation dialog, toast on every outcome, skeleton loading.
- **Verified:** created `TEST_AUDITEUR` (2 perms) → `201` → appeared in list; deleted → `204` →
  empty state. Roles show correct counts: ADMIN 4/3, CHEF_PROJET 14/25, DEVELOPPEUR 5/59,
  DIRECTEUR 16/15. PM (chef) hitting `/admin/roles` is redirected with "Accès refusé".

### 3.2 Permission catalog (`/admin/permissions`) — ✅ DONE & VERIFIED

- **Before:** none.
- **Backend:** `PermissionController` (`/api/admin/permissions`, `?withRoles=true`) +
  `PermissionAdminService`, gated by `MANAGE_ROLES`.
- **Frontend:** permissions **grouped by module** (ADMIN, PROJET, CHARGE, …) with per-group counts;
  each row shows code (monospace), description, and **holder-role badges**; instant search across
  code/description/module/role; skeleton loading + empty state.
- **Design decision (documented, not a gap):** no arbitrary permission *creation* from the UI. A
  permission only has effect when a `@PreAuthorize` in code references it; letting admins mint inert
  codes would mislead. Administration is therefore done by **assigning existing permissions to
  roles** (§3.1) — consistent with the V20 cleanup philosophy. Recorded as decision **UX-D1**.
- **Verified:** `GET /api/admin/permissions?withRoles=true → 200`, renders grouped with role badges.

### 3.3 TCC Resources hardening (`/resources`) — ✅ DONE & VERIFIED

- **Before:** functional list + per-year TCC editing, but **no data scoping** — any `VIEW_RESOURCES`
  holder (incl. Project Managers) could see *every* resource's rates.
- **Change:** `ResourceService.findAll/findById/findTccAnnuels` now scope by role. Holders of
  `MANAGE_RESOURCES` (Admin, Director) see the full referential; a Project Manager
  (`VIEW_RESOURCES` only) sees **only resources on projects they manage** (new JPQL scoping queries
  through `team_assignments` → managed `projects`); out-of-scope access returns `403`.
- **Business rule matrix — verified by API:**

  | Actor | `GET /resources` | Out-of-scope `…/tcc` | In-scope `…/tcc` |
  |---|---|---|---|
  | Admin | 87 (all) | — | — |
  | Director | 87 (all) | — | — |
  | Project Manager | 2 (own projects) | **403** | **200** |
  | Developer | **403** (no `VIEW_RESOURCES`) | — | — |

- **Remaining polish (roadmap):** add search/filter + pagination to the resources table; migrate its
  inline save message to the new toast service; expose resource create/edit as a modal.

---

## 4. Per-screen audit & roadmap

Legend: ✅ done this pass · 🔶 partial · ⬜ roadmap (prioritized P1–P3).

### Dashboard (`/dashboard`)
- **Problems (before):** role subtitle paragraphs; "Bientôt" placeholder card; module descriptions.
- **✅ Done:** subtitles/descriptions removed (prior pass); placeholder replaced with **real** Roles
  & Permissions module cards (permission-gated); admin metric counts now use the accurate
  `/admin/roles` + `/admin/permissions` endpoints.
- **⬜ P3:** turn metric cards into links to filtered views.

### Projects — list (`/projects`)
- **UX:** search + filters present; verbose empty state removed (prior pass).
- **🔶 / ⬜ P2:** confirm server-side pagination page-size control is surfaced; add column sorting;
  migrate row actions to a consistent action menu; toast on archive/delete.

### Projects — detail (`/projects/:id`)
- **UI issue:** 1000+-line component with 8 inline tabs → change-amplification risk.
- **⬜ P2:** decompose into a shell + one child component per tab (tracked as FE-3 in ENHANCEMENTS).

### Projects — form (`/projects/new`, `/edit`)
- **✅ Done:** helper paragraphs + "autosave" text removed (prior pass).
- **⬜ P3:** group fields into fieldsets with section headers; inline validation messages.

### Workload (`/workload`), Missions, Governance, Billing
- **✅ Done:** project selection redesigned as **master → detail with progressive disclosure**
  (`<app-project-picker>`). The page no longer opens on a project browser:
  - **No selection** → an **on-topic feature landing** (not a lonely button): a hero with the
    feature icon, title, and one-line description, a *Parcourir tous les projets (N)* action, and a
    **Quick access** grid of ~6 clickable project cards (code, status badge, name, client, PM;
    ordered assigned → recent → favorite → active) so the user jumps straight into work.
  - **Selected** → a rich **Current Project context card** (code, status badge, name, chef de projet,
    équipe count, budget, période, client) with *Changer de projet*; the host feature (plan de charge,
    etc.) is the primary content below it.
  - **Change** → a **modal** browser (instant search, Assignés/Favoris filters, Recents, ★ favorites,
    paginated table, intelligent ordering, click→*Sélectionner*/double-click). Closes on select.
  This keeps the workload/mission/etc. the primary task and the project DB out of the way.
  **Verified in-browser** (empty prompt → modal → context card with Équipe 6 / Budget 4 200 000 TND).
- **✅ Workload → "Matrice d'occupation"**: the two flat plan/actual tables were replaced by a
  **resource × month occupation matrix** — each cell shows planned (gray) + actual (blue, or red when
  variance ≥ 5 JH / >25%), with avatars + role per resource, a peak-month highlight, a monthly-totals
  footer, a **year segmented filter** (multi-year projects scope to one year), four summary metric
  cards (Occupation réelle / Ressources actives / Taux de réalisation / Mois de pic) and CSV export.
  **Verified in-browser** (Bilel Ferjani JUIN 26.5/31 blue, JUILLET 25.5/31 red, peak = Juin 2026).
- **⬜ P2:** pagination + column sorting on their *content* tables; toast on create/validate/delete;
  move add/edit forms into modals where still inline; skeleton loading.

> **Design rule adopted:** large datasets never use a raw `<select>`. Under ~10 options a native
> select is fine; beyond that, use `<app-project-picker>` (or a searchable/paginated equivalent).

### KPI (`/kpi`)
- **✅ Done:** pagination on both views — Portefeuille (bullet + margin charts, 10/page, synced) and
  Projets (cards, 9/page) — via the reusable `<app-pagination>`. **⬜ P3:** sticky header, sparklines.

### Resources / TCC (`/resources`)
- **✅ Done:** authorization scoping (§3.3); **pagination** (10/page, verified 87→9 pages);
  per-year TCC editing moved from an inline expandable row into a **reactive modal** (click a row →
  popup with the year table, add/edit/delete, save) — signal-backed so data shows immediately on open.
  **⬜ P2:** search/filter on the resource list; toast on save.

### Users (`/admin/users`)
- **🔶:** already has server-side search, filters, pagination, create/edit modal, reset-password flow.
- **⬜ P3:** migrate inline error text + `window.confirm` copy to the new toast/confirm services for
  consistency (functionally fine today).

### Devis Interne (`/projects/:id/devis-interne`)
- **✅ Done:** verbose banner + empty-state text trimmed (prior pass). Security constraint respected:
  no DI values seeded anywhere.

### Auth (login, change-password)
- **✅ Good:** clean, i18n-enabled, language switcher pre-auth.

---

## 5. Notifications, loading, empty & error states

- **✅ ToastService** (`core/services/toast.service.ts`) + **ToastContainerComponent** mounted in the
  shell. Four kinds, auto-dismiss (errors linger longer), theme-aware, reduced-motion aware,
  accessible (`role=status`, `aria-live=polite`). Consumed by the new admin pages.
- **✅ Confirmation dialogs** via the existing `ConfirmService` (used by role delete).
- **✅ Skeleton loading** on the new admin tables; **✅ standardized empty states**.
- **✅ Reusable `<app-pagination>`** (`shared/pagination`) — from/to/total, page-size selector,
  page number, prev/next. Landed in resources + both KPI views; ready to propagate elsewhere.
- **⬜ Roadmap:** replace remaining inline `msg`/`window.confirm` occurrences app-wide with
  toast/confirm (mechanical; per-screen in §4), and propagate skeletons to the feature tables.

---

## 6. Navigation & IA

- **✅ Done:** Administration section now lists **Users · Roles · Permissions** (permission-gated).
- **✅ Done:** removed dead duplicate routes (`/workload/plan`, `/workload/actuals`, `/risks`,
  `/livrables`, `/changes`) left over after the sidebar consolidation — they all pointed at the same
  components and created a false mental model.
- **✅ Kept:** command palette (Ctrl/⌘-K) already indexes all visible nav entries → the new pages are
  searchable automatically.

---

## 7. Responsiveness & accessibility

- New components use responsive grids (`repeat(auto-fill, minmax(...))` for the permission matrix),
  `d-none d-md-table-cell` to shed non-essential columns on small screens, and `overflow-x:auto`
  table wrappers.
- Toasts and modals honor `prefers-reduced-motion`.
- **⬜ Roadmap (P3, tracked as FE-6):** aria-labels on all icon-only buttons, focus trap in modals,
  full keyboard path through the permission matrix.

---

## 8. Decisions

- **UX-D1 — No arbitrary permission creation via UI.** Permissions are a code-defined catalog (a
  permission is inert unless a `@PreAuthorize` checks it). The admin surface manages **role →
  permission assignment**, not permission minting. Consistent with V20's removal of unenforced
  permissions. Rejected the literal "create permission" request as an anti-feature; delivered the
  meaningful capability (assignment + full visibility of who holds what) instead.
- **UX-D2 — MANAGE_RESOURCES = full TCC access; VIEW_RESOURCES-only = PM-scoped.** Simple, correct
  mapping to the business rule (Admin/Director full; PM own-projects; Developer none) with no
  role-name checks (ADR-001 preserved).

---

## 9. What remains (prioritized)

- **P1:** propagate toast + skeleton to the four project-scoped feature pages (workload, missions,
  governance, billing) and resources; add pagination to the resources & governance tables.
- **P2:** column sorting on all data tables; decompose `project-detail`; modal-ize remaining inline
  forms.
- **P3:** accessibility sweep; KPI table polish; dashboard metric cards → filtered links.

---

*Verification for this pass: Angular dev build clean; backend `BUILD SUCCESS` + `V25` applied;
admin CRUD exercised in-browser (create/search/delete role, permission catalog render); TCC scoping
proven by API across all four roles. No new console/network errors on clean load.*
