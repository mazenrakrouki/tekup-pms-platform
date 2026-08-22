# Design Improvement Backlog

Tracking of the global UX/UI cleanup, pagination and internationalization pass.
Each entry records the issue, the affected module, the root cause, the correction and the
verification result.

**Last updated:** 2026-08-22

---

## D-01 — Language switcher appears broken in most modules

**Reported:** switching FR↔EN works in Resources and Agile Planning but not in Workload, KPI,
Billing, Governance or My Projects.

**Affected:** Workload, KPI, Billing, Governance, My Projects, Missions, Internal quote, Users,
Roles, Permissions, Project detail, Project form.

**Root cause — the switcher is not broken.** Auditing every component for a Transloco import gave
a clean split:

| Wired to Transloco | Not wired |
|---|---|
| login, change-password, dashboard, sidebar, language-switcher, pagination, resources, agile | permissions, roles, user-list, billing, internal quote, governance, kpi, missions, project-detail, project-form, project-list, workload, confirm-modal, project-picker, toast-container |

The modules reported as "not translating" are exactly the unwired ones. They contain hardcoded
French string literals inside inline component templates, so no language switch can reach them.
This is missing coverage, not a defect in the i18n architecture.

Two aggravating factors found while auditing:

1. `transloco.providers.ts` sets `fallbackLang: 'fr'` with `missingHandler.useFallbackTranslation:
   true`. A missing English key therefore renders French silently, with no error. Switching the
   default language before catalogues are complete would produce a half-translated interface that
   looks like a bug but raises nothing.
2. User-visible strings are not confined to components. `core/models/*.ts` and `core/services/*.ts`
   hold enum-to-label maps — `PROJECT_STATUS_LABELS` and equivalents for statuses, priorities and
   mission component types. These are interface text living in the data layer and must go through
   i18n as well.

**Correction — in progress.** Shared components first, because they appear inside many modules and
one fix propagates: `project-picker` is now fully wired, and project status labels route through
the existing root `status.*` catalogue instead of the hardcoded French map.

**Verification:** FR→EN→FR round trip on the picker, driven in the browser:

| Element | EN | FR | back to EN |
|---|---|---|---|
| Prompt | Select a project to continue | Sélectionnez un projet pour continuer | ✅ |
| Button | Browse projects | Parcourir les projets | ✅ |
| Status badge | Completed | Terminé | ✅ |

No page refresh required; no missing-key warnings in the console.

**Remaining:** 609 lines across 32 files. Tracked as D-09.

---

## D-02 — Helper paragraphs under page titles

**Affected:** KPI, Workload, Agile Planning, Resources/TCC, Users, My Projects.

**Root cause:** a `page-subtitle` paragraph had been added to each page header as a matter of habit,
describing what the page does to a user who is already on it.

**Correction:** every `page-subtitle` removed from the page headers listed above. Where the
information genuinely identifies something — a project code, for instance — it moved to a `title`
tooltip rather than a second visible line.

Removed verbatim:

- KPI — "Marge, coût estimé final (EAC) et rentabilité de votre portefeuille projets."
- Workload — "Planification des ressources (JH) — plan vs. réel, mois par mois."
- Agile — "Plan iterations and track the product backlog"
- My Projects — "{{ headerCount() }}" rendering "20 projets · liste complète"
- Resources/TCC — "26 collaborateurs · tarifs journaliers & coût annuel chargé"
- Users — "{{ n }} utilisateurs · comptes & accès"

**Verification:** `document.querySelectorAll('.page-subtitle')` returns **0** on the audited pages.

---

## D-03 — Duplicate page titles

**Affected:** every module using `app-project-picker` — Workload, Agile, Billing, Missions,
Governance, KPI, Internal quote.

**Root cause:** the shared picker rendered its own `<h1>` from a `featureTitle` input, immediately
below the host page's `<h1>`. Both said the same thing, and each carried its own description:

```
Sprints & Backlog                                 ← page header
Plan iterations and track the product backlog     ← page subtitle
Sprints & Backlog                                 ← picker hero title
Select a project to plan its sprints.             ← picker hero description
```

**Correction:** the picker no longer renders a title. The page owns the title; the picker states the
action — "Select a project to continue". One heading, one instruction.

**Verification:** `document.querySelectorAll('.page-title')` returns exactly **1** element, and
`.pp-hero-title` no longer exists in the DOM.

---

## D-04 — "Accès rapide — reprenez là où vous en étiez"

**Affected:** every module using the project picker.

**Root cause:** a marketing-style section label above the shortcut cards.

**Correction:** the label is gone. The shortcut cards remain, because they are a real shortcut into
work in progress rather than decoration, but they now stand without narration.

**Verification:** `.pp-qa-label` no longer present in the DOM.

---

## D-05 — Project code repeated under every project name

**Affected:** project picker cards.

**Root cause:** each card printed the code as its first line, above the project name, making the
code more prominent than the thing a person actually scans for.

**Correction:** the card leads with the project name. The code moved to the card's `title`
attribute, and remains a first-class column in the browser table and in the selected-project
context card, where it identifies the record.

**Verification:** `.pp-qa-code` count is **0**; the first card's `title` attribute reads
`S2I-2022-001`.

---

## D-06 — Project lists showed 6 items

**Affected:** every module using the project picker.

**Root cause:** two separate caps — `pageSize = signal(10)` for the browser table and
`slice(0, 6)` for the shortcut grid.

**Correction:** both raised to 12.

**Verification:** the browser table renders **12 rows**, with pagination reporting
`1–12 of 20 · Page 1 of 2`.

---

## D-07 — Status labels hardcoded in the data layer

**Affected:** anywhere a project status is displayed.

**Root cause:** `PROJECT_STATUS_LABELS` in `core/models/project.model.ts` is a French `Record`
consumed by each component's `statusLabel()` helper. Interface text living in a model file cannot
react to a language change.

**Correction:** the picker now renders `'status.' + code | transloco`, using the `status.*`
catalogue that already existed in the root translation files, complete in both languages. The old
map is left in place for now because other components still call it; they will be converted with
their own module.

**Verification:** the status badge changes from "Completed" to "Terminé" and back on switching
language, without a refresh.

---

## D-08 — Unused inputs left on the picker

`featureTitle` and `featureDescription` are still declared and still passed by host components, but
nothing renders them since D-03. Harmless, and removing them means touching every caller, so it is
deferred rather than done half-way.

---

## D-09 — Remaining i18n migration (open)

**609 lines across 32 files.** Ordered by size, which is also roughly the order of effort:

| File | Lines |
|---|---|
| `features/projects/project-detail/project-detail.component.ts` | 93 |
| `features/governance/governance.component.ts` | 85 |
| `features/billing/billing.component.ts` | 81 |
| `features/admin/user-list/user-list.component.ts` | 46 |
| `features/workload/workload.component.ts` | 38 |
| `features/projects/project-form/project-form.component.ts` | 33 |
| `features/missions/missions.component.ts` | 32 |
| `features/kpi/kpi.component.ts` | 26 |
| `features/admin/roles/role-list.component.ts` | 25 |
| `features/di/devis-interne.component.ts` | 23 |
| `features/projects/project-list/project-list.component.ts` | 23 |
| `core/services/*.ts` (billing 15, governance 11, di 9, workload 7) | 42 |
| `core/models/*.ts` (kpi 8, governance 7, billing 5, …) | 22 |
| `app.routes.ts` (route titles) | 10 |
| remaining files | 1–7 each |

**Method for each module:** create a Transloco scope, add `en.json` and `fr.json` under
`public/i18n/<scope>/`, replace literals with `| transloco`, register the scope with
`provideTranslocoScope`, and verify key parity programmatically rather than by eye.

**Only when every module is converted:** flip `defaultLang` to `'en'` in `transloco.providers.ts`.
Doing it earlier hides gaps behind the French fallback described in D-01.

**Detection:** `frontend/pms-frontend/tools/find-french.py` reports the current count. It matches
accented characters *and* a list of unambiguous French words, because a large share of the interface
text carries no accent at all — `Supprimer`, `Annuler`, `Ajouter`, `Enregistrer`, `Statut`.

---

## D-10 — Marketing tagline on the login page

**Affected:** login screen (`auth` scope).

**Root cause:** the hero carried generated advertising copy rather than product identity:

> Précision et Contrôle pour vos Projets d'Entreprise
> Gérez les charges, la facturation et les missions complexes avec une architecture robuste conçue
> pour les Directeurs et Administrateurs.

Two problems beyond the tone. It claims an audience ("conçue pour les Directeurs et
Administrateurs") that contradicts the product — every role signs in here, including developers.
And it duplicated in both catalogues text that no one reads twice.

**Correction:** the hero now renders `app.name` and `app.tagline`, the same keys the sidebar already
used. The `auth.hero` block was deleted from both catalogues since nothing referenced it any more.

**Verification:** login hero reads **PMS / ST2I Enterprise**, unchanged across FR↔EN — a product
name does not translate. `auth` scope at 23 keys with EN/FR parity, no dangling `auth.hero`
reference in the source.

---

## D-11 — Company logo loaded from an external Google URL (open)

Found while doing D-10, not reported.

Both `login.component.ts` and `sidebar.component.ts` load the ST2I logo from a hardcoded
`https://lh3.googleusercontent.com/aida-public/…` address. There is no local logo asset in the
project.

Why it matters:

- The URL is a generated, non-permanent one. When it expires the logo disappears from the sign-in
  screen and the sidebar, in both the running app and any screenshot taken afterwards.
- The application contacts Google on every page load, purely to fetch its own branding.
- The containerized deployment is otherwise self-contained; this is the single outbound dependency
  in the interface.
- A demonstration on a machine without internet access shows no logo at all — worth knowing before
  a defence.

**Fix:** drop the real logo into `public/` and reference it locally. Left open because the actual
image file is not in the repository; `report/.../img/logoSociete.png` is a placeholder awaiting the
company's own asset.

---

## D-12 — Charges tab rendered every row at once

**Affected:** project detail, "Charges" tab (`/projects/{id}?tab=charges`).

**Root cause:** both tables were loaded with a page size of 1000 and rendered whole:

```ts
this.workloadSvc.listPlanCharges(this.projectId, 0, 1000).subscribe(d => this.planCharges.set(d.content));
this.workloadSvc.listChargesReelles(this.projectId, 0, 1000).subscribe(d => this.chargesReelles.set(d.content));
```

There was no pagination on either. The workload model is one row per resource, per month, per
year, so the count grows with team size multiplied by project duration. On project 80 that is
**240 rows in each table — 480 rows on a single tab**, all in the DOM.

**Correction:** client-side pagination at **10 rows per page** on each table, reusing the existing
`app-pagination` component rather than introducing another control. The two tables page
independently, since they answer different questions. The pagination bar only appears when a table
actually exceeds one page.

The data was already fetched in one call, so paging is a pure slice — no additional server round
trip. A `slicePage` helper clamps the page index with `Math.min`, so a shrinking list cannot leave
the view on an empty page.

**Verification:** on `/projects/80?tab=charges`, each table renders **10 rows** and reports
`1–10 of 240 · Page 1 of 24`. Clicking next on the first table moves it to page 2 and changes its
first row, while the second table stays where it was.

---

## D-13 — Login page did not say what PMS stands for

**Affected:** login screen.

**Correction:** the hero subtitle now renders `app.fullName`, added to the root catalogue in both
languages — "Project Management System" and "Système de gestion de projets". The acronym is
English, but the interface is bilingual, so the expansion is translated rather than frozen.

**Verification:** login reads **PMS / Project Management System** in English and
**PMS / Système de gestion de projets** in French. Root catalogue at 141 keys, EN/FR parity.
