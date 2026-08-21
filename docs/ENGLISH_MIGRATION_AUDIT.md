# English Migration Audit

**Date:** 2026-08-20
**Scope:** whole PMS platform — backend, database, API, frontend, i18n, UML, documentation
**Outcome:** identifier migration **declined**; UI language migration **approved**

---

## 1. Purpose

A product-wide English standardization was proposed: rename Java classes, DTOs, services,
repositories, enums, database tables and API endpoints from French to English, and make the
interface English while keeping French available.

This document records what the codebase actually contains, what each part of that migration would
cost, and which parts were carried out.

## 2. Method

Every figure below was measured against the working tree, not estimated:

- Java inventory from `find` over `backend/src/main/java`, filtered for French stems and manually
  checked for false positives (`Permission*` and `Mission*` match a naive "mission" pattern but are
  correct English).
- Enum values extracted from each `public enum` body rather than from file names.
- Table names extracted from `CREATE TABLE` statements across the 26 Flyway migrations.
- API paths extracted from `@RequestMapping` and `@*Mapping` annotations.
- Frontend strings detected by `frontend/pms-frontend/tools/find-french.py`, which combines two
  signals: accented characters **and** a list of unambiguous French words.

  **Method correction.** The first pass counted accented characters only and reported 37 files /
  613 lines. That undercounts, because a large share of French interface text carries no accent at
  all — `Supprimer` (40 occurrences), `Livrable` (26), `Annuler` (22), `Ajouter` (22),
  `Enregistrer` (19), `Statut` (16). Re-running with both signals, and excluding comments and
  already-extracted translation keys, gives the corrected figure used throughout this document:
  **32 files / 626 lines**. Per-module counts changed materially — `billing` from 42 to 81,
  `governance` from 65 to 85 — so the corrected numbers are what the migration is planned against.

## 3. Summary

| Layer | English | French | Decision |
|---|---|---|---|
| Report UML (`docs/uml/`, 15 files) | all | 0 | already complete |
| Engineering UML (`docs/uml/engineering/`, 13 files) | — | 43 occurrences | **migrate labels** |
| Java sources (173 files) | 107 | **66** | **keep** |
| Enums (11) | 1 | **10** | **keep** |
| Database tables (22) | 10 | **12** | **keep** |
| API paths | partial | **15+** | **keep** |
| Frontend sources (63 files) | 31 | **32 files / 626 lines** | **migrate to i18n** |
| i18n keys | 203 | 203 | **extend** |

## 4. Backend — French identifiers

66 of 173 Java files carry French names.

| Package | Files | Principal types |
|---|---|---|
| `governance` | 26 | `Livrable`, `PartiePrenante`, `DemandeChangement`, `NiveauRisque`, `StatutRisque`, `StatutLivrable`, `StatutChangement`, `PrioriteChangement` |
| `billing` | 14 | `JalonFacturation`, `Avenant`, `JalonStatut`, `FacturerRequest` |
| `workload` | 12 | `PlanCharge`, `ChargeReelle` |
| `mission` | 7 | `ComposanteMission`, `TypeComposante` |
| `project` | 4 | `DevisInterneController/Response/Service`, `SectionDi` |
| `user` | 3 | `TccAnnuel`, `TccAnnuelDto`, `TccAnnuelRepository` |

Note that `Risk`, `Project`, `ProjectStatus`, `User`, `Resource`, `Role`, `Permission`,
`TeamAssignment` and the entire `auth` and `kpi` packages are already English. The codebase is
mixed, not French.

## 5. Enums — the decisive finding

Ten of the eleven enums hold French values, and those values are **persisted as strings** in the
database.

| Enum | Values |
|---|---|
| `JalonStatut` | `PREVU`, `FACTURE`, `PAYE` |
| `NiveauRisque` | `FAIBLE`, `MOYEN`, `ELEVE` |
| `PrioriteChangement` | `FAIBLE`, `NORMALE`, `ELEVEE`, `CRITIQUE` |
| `StatutChangement` | `EN_ATTENTE`, `APPROUVE`, `REJETE` |
| `StatutLivrable` | `EN_ATTENTE`, `EN_COURS`, `LIVRE`, `VALIDE` |
| `StatutRisque` | `OUVERT`, `MITIGE`, `FERME` |
| `TypeComposante` | `PERDIEM`, `BILLET`, `TIMBRE`, `TRANSPORT`, `SEJOUR` |
| `BusinessModel` | `SEUL`, `GROUPEMENT` |
| `EngagementType` | `FORFAIT`, `REGIE` |
| `SectionDi` | `HONORAIRES`, `FRAIS`, `AUTRES_FRAIS` |
| `ProjectStatus` | `DRAFT`, `ACTIVE`, `ON_HOLD`, `COMPLETED`, `CANCELLED` — already English |

Renaming these is not a source-code change. It requires a Flyway migration issuing `UPDATE`
statements against every affected row, plus rewriting the matching literals inside
`V26__seed_demo_data.sql` (1098 lines). A partial failure leaves rows holding values the Java enum
can no longer parse, and the application fails at read time rather than at startup.

## 6. Database — French table names

12 of 22 tables:

`avenants`, `charges_reelles`, `composantes_mission`, `demandes_changement`, `jalons_facturation`,
`lignes_di`, `livrables`, `paiements`, `parties_prenantes`, `plan_charges`, `snapshot_kpis`,
`tcc_annuels`

Already English: `users`, `roles`, `permissions`, `role_permissions`, `projects`, `resources`,
`risks`, `missions`, `team_assignments`, `parameters`.

The schema runs under `ddl-auto=validate`, so any table rename must be matched exactly by a Flyway
migration or the application refuses to start.

## 7. API — French paths

```
/api/projects/{projectId}/plan-charges
/api/projects/{projectId}/charges-reelles
/api/projects/{projectId}/charges-reelles/{id}/validate
/api/projects/{projectId}/demandes-changement
/api/projects/{projectId}/livrables
/api/projects/{projectId}/parties-prenantes
/api/projects/{projectId}/devis-interne
/api/projects/{projectId}/missions/{missionId}/composantes
.../jalons, .../jalons/{id}/facturer, .../jalons/{jalonId}/paiements
.../avenants, .../{id}/tcc
```

Endpoint renames break backend and frontend at the same instant; both sides must ship together.

## 8. Frontend — French user interface

32 of 63 TypeScript files contain French interface text, totalling 626 lines (comments and
already-extracted keys excluded). Components use inline templates, so the strings live in `.ts`
files rather than `.html` — the project contains only 2 HTML files.

| File | French lines |
|---|---|
| `features/projects/project-detail/project-detail.component.ts` | 93 |
| `features/governance/governance.component.ts` | 85 |
| `features/billing/billing.component.ts` | 81 |
| `features/admin/user-list/user-list.component.ts` | 47 |
| `features/workload/workload.component.ts` | 39 |
| `features/projects/project-form/project-form.component.ts` | 33 |
| `features/missions/missions.component.ts` | 32 |
| `features/kpi/kpi.component.ts` | 27 |
| `features/admin/roles/role-list.component.ts` | 25 |
| `shared/project-picker/project-picker.component.ts` | 24 |
| `features/di/devis-interne.component.ts` | 23 |
| `features/projects/project-list/project-list.component.ts` | 23 |
| `core/services/*.ts` (billing 15, governance 11, di 9, workload 7) | 42 |
| `core/models/*.ts` (kpi 8, governance 7, billing 5, …) | 22 |
| `app.routes.ts` | 10 |
| remaining files | 1–5 each |

**Finding not visible in the first pass:** French UI text is not confined to components. Several
`core/services/*.ts` and `core/models/*.ts` files hold enum-to-label maps — the human-readable
names for statuses, priorities and component types. These are user-visible strings living in the
data layer, and they must be translated through the same i18n mechanism as the templates rather
than being left behind.

## 9. Translation coverage gap

The i18n layer exists and is healthy, but covers little of the application.

| Scope | Keys (EN) | Keys (FR) |
|---|---|---|
| root (`i18n/en.json`) | 114 | 114 |
| `auth` | 25 | 25 |
| `dashboard` | 64 | 64 |
| **total** | **203** | **203** |

English and French are perfectly synchronized — there is no missing-translation debt. The problem
is reach: only 5 real components use Transloco (`login`, `change-password`, `dashboard`,
`sidebar`, `language-switcher`). Eight feature modules have no scope at all.

### Two configuration traps

`core/i18n/transloco.providers.ts` sets `defaultLang: 'fr'`, `fallbackLang: 'fr'` and
`missingHandler.useFallbackTranslation: true`.

1. Switching the default to English **before** the English catalogues are complete makes every
   missing key fall back to French silently. The interface would look half-translated with no error
   raised. The default must therefore be flipped last.
2. `logMissingKey: true` logs to the browser console, which is a more reliable completeness check
   than reading screens.

## 10. UML

`docs/uml/` (15 report-level diagrams) contains **zero** French class names; that migration was
completed in earlier work.

`docs/uml/engineering/` (13 diagrams) contains 43 French occurrences: `ChargeReelle` (10),
`PlanCharge` (9), `JalonFacturation` (9), `Avenant` (5), `Paiement` (4), `Livrable` (4),
`PartiePrenante` (2).

## 11. Risk assessment and decision

| Change | Risk | Reversible | Decision |
|---|---|---|---|
| Java identifier renames | medium — mechanical, compiler-checked | yes | **declined** |
| API path renames | high — breaks both tiers simultaneously | yes, with effort | **declined** |
| Table renames | high — `ddl-auto=validate` fails startup on mismatch | migration required | **declined** |
| **Enum value renames** | **highest — rewrites live rows; failure surfaces at read time** | data migration required | **declined** |
| DI, TCC, FORFAIT/REGIE | these are the company's own business vocabulary | — | **declined, explicitly** |
| Frontend strings → i18n | low — templates only, compiler and build catch errors | yes | **approved** |
| Engineering UML labels | none — text files, nothing reads them at runtime | yes | **approved** |

The identifier migration was declined. It carries real risk to a working system and delivers no
functional benefit; the visible outcome the request was aiming at — an application that reads as an
English product — is achieved entirely through the i18n layer, which cannot affect business logic.

DI (`Devis Interne`), TCC (`Taux de Coût Chargé`) and the contract types `FORFAIT`/`REGIE` are
excluded on separate grounds: they are the host company's own terminology, they appear in the
report and in the source Excel workbook, and translating them would misrepresent the business
domain rather than clarify it.

## 12. Work carried out

1. This audit.
2. Extraction of the ~550 hardcoded French interface strings into Transloco scopes, with English
   and French catalogues, and English set as the default language once coverage is complete.
3. English labels across the 13 engineering UML diagrams, with a note in `docs/UML_DESIGN.md`
   recording that the diagrams use English labels while the implementation retains its original
   French identifiers.
4. A separate UX audit (`docs/UX_AUDIT.md`) covering interface quality: oversized selectors,
   navigation state, dark mode, responsive behaviour and residual placeholder text.

## 13. Consequence to keep in mind

The platform is deliberately left bilingual at two different layers: the **interface** speaks
English (and French on demand), while the **code and schema** keep their original French domain
vocabulary. Anyone reading `ChargeReelle` in the source and `Actual workload` on screen is looking
at the same concept. The engineering UML note in `docs/UML_DESIGN.md` exists so that this mapping
is documented rather than discovered.
