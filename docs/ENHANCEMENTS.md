# Engineering Improvement Backlog

> **Owner:** Chief Software Architect (appointed 2026-07-05)
> **Nature:** Living document — the current engineering vision of the project. Updated whenever a
> weakness is discovered or an improvement lands.
> **Relationship to other documents:** [`ENHANCEMENTS_AND_CORRECTIONS.md`](ENHANCEMENTS_AND_CORRECTIONS.md)
> is the *frozen* independent review + verification record of 2026-07-04/05 (score 72 → 88/100).
> This document is the *forward-looking* roadmap. Items inherited from that record are referenced,
> not duplicated.
> **Method:** every claim below was verified against the code on 2026-07-05 (file-level evidence),
> not copied from previous documents.

---

## Executive Review

### Overall opinion

This is a **strong PFE that is better engineered than it is presented**. The backend would pass
review in a professional services company: the security chain, the data-layer discipline, and the
fidelity to the client's real financial method (F-AFF-13) are above what juries usually see. The
weaknesses are concentrated in three places: **documentation drift** (UML describes a system that
was never built), **frontend engineering depth** (zero tests, two oversized components), and the
**production story** (no deployment documentation, no API documentation page).

None of the weaknesses require redesign. They require finishing discipline. I found **no
architectural decision I would reverse** — I found several I would have made myself, and a
documentation layer that lags the implementation by roughly three weeks.

### Current strengths (verified, keep)

1. **Modular monolith** (10 feature packages: `auth, user, project, team, workload, billing, kpi,
   mission, governance, shared`). Exactly right for this scale. Microservices here would be
   résumé-driven overengineering; a layered ball of mud would be under-engineering. This is the
   defensible middle.
2. **Security architecture genuinely above PFE standard:** single-use refresh-token rotation in an
   HttpOnly `SameSite=Strict` cookie with replay detection; token-version revocation wired to
   *every* invalidation path (role change, deactivation, deletion, logout, password change, DI
   permission grant); login rate-limiting with bcrypt timing equalization; server-side first-login
   enforcement via filter; security response headers. Any one of these is a differentiator; the
   project has all of them.
3. **Dynamic RBAC that proved itself.** Role→permission lives in data; the V12 authorization audit
   fixed a real permission leak *without touching code*, and V20/V23 evolved the matrix the same
   way. This is the strongest single argument in the defense: the design absorbed change exactly
   as intended.
4. **Data-layer discipline:** Flyway-only DDL (23 migrations), soft delete with partial unique
   indexes (`WHERE deleted = FALSE`), JPA auditing on all 19 tables, CHECK constraints, per-year
   rate history. `ddl-auto=validate` means the schema cannot drift silently.
5. **Domain fidelity:** the KPI engine implements the company's official glossary (EV, Delivery %,
   dérive JH vs *sold* workload, CA production, FAE, marge actuelle vs vendue) with the correct
   subtleties — TCC of the imputation's year, DI-computed baseline margin with fiche-identification
   fallback, amounts derived at read time and never stored twice.
6. **The DI decision** (structure as empty template, values never seeded, `MANAGE_DI` gate,
   documented in BUSINESS_ANALYSIS §16) is a mature product/security trade-off worth a slide on
   its own.
7. **REST API design is idiomatic:** resource nouns, `PATCH` for state transitions
   (`/archive`, `/assign-chef`, `/status`), correct status semantics (401/403/404/409/422/429),
   stable `Page` contract, 13-handler `GlobalExceptionHandler`.
8. **Backend test culture:** 87 tests across 11 suites, including *service-level* tests of the
   business rules that define the product (BR-033 both paths, jalon rules, avenant accumulation,
   scope service, DI computation).

### Current weaknesses (owned below)

1. UML diagrams contradict the implementation (fictional entities, a fictional async KPI pipeline,
   two missing domains). **Report-blocking.**
2. Frontend has **zero** `.spec.ts` against a backend with 87 tests. The asymmetry will be noticed.
3. `project-detail.component.ts` (972 lines, 8 tabs) and `governance.component.ts` (664 lines)
   are monolith components with inline templates.
4. No generated API documentation (springdoc absent) and no `DEPLOYMENT.md`; JWT secret falls back
   to a dev default in every profile.
5. `AUTHORIZATION_MATRIX.md` is stale (predates `MANAGE_DI` and the V20 descope).
6. Assorted debt: dead `Parameter` entity, deprecated Sass `@import`, `PageImpl` warning suppressed
   rather than fixed, KPI computed live on every GET with no memoization.

### Assessments

| Dimension | Grade | One-line justification |
|---|---|---|
| Architecture | **A−** | Right-sized monolith, real security depth; loses points only for doc drift |
| Software engineering | **B+** | Backend A, frontend B− (no tests, 2 monolith components) |
| Database | **A** | Flyway discipline, soft-delete indexes, auditing, per-year history |
| Documentation | **B−** | Corpus is unusually complete but UML/matrix lag the code |
| PFE readiness | **B+ → A achievable** | The gap to A is presentation work (Phases 1 & 4 below), not engineering |

---

## Architecture Improvements

### A-1 — Realign UML with the implemented system *(Priority: P0 — report-blocking)*
- **Current design:** 11 report diagrams (Phase 4, 2026-06-25) + engineering annex.
- **Problem (audited 2026-07-05):**
  - Class *Projet & Équipe* shows `ProjectManagerAssignment` — **does not exist** (code:
    `Project.chefProjet` direct association). `TeamAssignment` links **User**, not Resource, and
    carries `roleInTeam`/`startDate`/`endDate`, not `staffingPercent`.
  - Class *Charges & KPI* uses invented names (`WorkloadPlan`, `ActualWorkload`, `Tcc`) instead of
    the real `PlanCharge`, `ChargeReelle`, `TccAnnuel`.
  - Sequence 4.3 and Activity 5.2 describe an **asynchronous event-driven KPI recompute that was
    never built** (see Decision Log D-3).
  - The **governance domain** (Risk, Livrable, DemandeChangement, PartiePrenante) and the
    **Devis Interne** are absent everywhere.
  - Package diagram lists modules (`TCC`, `PLAN`, `ACTUAL`) that don't match the real packages.
  - No state diagram (yet two real state machines exist: ProjectStatus, JalonStatut) and no
    deployment diagram.
- **Proposed solution:** redesign Level-1 set → use case (updated actors/UCs incl. gouvernance,
  revue mensuelle, DI), package diagram matching the real 10 packages, **5 domain class diagrams**
  (Security, Projet & Équipe, Financier incl. DI, Charges & KPI, Gouvernance — each one page, real
  names, key attributes only), 3 sequences (login, scope-checked assignment, workload → hybrid KPI
  as implemented), 1 activity (lifecycle), **1 state diagram** (ProjectStatus + archivage),
  **1 deployment diagram** (SPA / API / PostgreSQL). Engineering annex kept as archived reference.
- **Expected benefit:** documentation/implementation consistency — the jury's first cross-check.
- **Status: ✅ COMPLETED 2026-07-05.** 13 diagrams redesigned/rendered (`docs/uml/`, UML_DESIGN.md
  v2.0 with per-diagram rationale). Side effect: the audit exposed that `changeStatus` enforced
  **no transitions** — the state machine is now guarded (`ProjectStatus.canTransitionTo`, illegal
  transition → 422, 2 new tests, suite 89/89). The state diagram documents real behavior.

### A-2 — API documentation endpoint *(P0, ~30 min)*
- **Problem:** 75+ endpoints, no generated docs; jury cannot explore the API.
- **Solution:** `springdoc-openapi-starter-webmvc-ui`, permit `/v3/api-docs/**` + `/swagger-ui/**`
  (dev profile; behind auth or disabled in prod), `@Tag` per controller.
- **Benefit:** one URL demonstrates the entire API surface during the defense.

### A-3 — Production configuration hardening *(P1)*
- **Problem:** `application.yml` ships a base64 dev JWT secret as fallback in *all* profiles;
  no `application-prod.yml`; no health endpoint for the deployment story.
- **Solution:** `prod` profile that **fails fast** if `JWT_SECRET`/`DB_*` env vars are absent
  (no silent fallback), plus Spring Actuator `health`/`info` only. Document in `DEPLOYMENT.md`
  (docker-compose: postgres + api + nginx serving the Angular build).
- **Benefit:** closes the last security finding; gives the report a credible ops chapter.

### A-4 — KPI read-path memoization *(P3 — conscious debt, not urgent)*
- **Problem:** `GET /kpi` recomputes from raw rows every call; the portfolio screen fires one call
  per project.
- **Solution when needed:** short-TTL Caffeine cache keyed `(projectId, lastMutationVersion)` or a
  batch endpoint. **Not now** — volumes are tiny; measure first.
- Kept here so the "known limitations" slide is honest.

### Explicit non-goals (rejected as overengineering for this project)
API versioning prefix, microservices/modulith split, event sourcing, CQRS, Kubernetes, WebSockets
for dashboards, multi-tenancy. Each would add surface without adding defense value.

---

### A-5 — Project status transitions unguarded *(found during A-1; fixed same day)*
- **Problem:** `ProjectService.changeStatus` accepted **any** transition (COMPLETED → DRAFT worked).
- **Solution shipped:** transitions owned by the enum (`ProjectStatus.canTransitionTo`,
  state pattern); illegal transition → `BusinessRuleException` (422). Tests: legal + illegal paths.
- **Status: ✅ COMPLETED 2026-07-05** (89/89 tests).

## Business Improvements

- **B-1 (P2) — Revue mensuelle completeness:** snapshot now captures EV %, faits marquants, date
  fin estimée. The Excel review also tracks *décisions* per review. Add a free-text `decisions`
  field to the snapshot → one column, closes the loop with F-AFF-13.
- **B-2 (P2) — Dérive sign convention:** the spec formula (`vendu − consommé − RAF`) yields
  negative on overrun; the Excel narrates positive "dérive de 494 JH". UI should display
  `|dérive|` with an explicit "dépassement/reste" label to avoid jury confusion. Backend keeps the
  spec formula.
- **B-3 (P3) — APO module & Liste des actions:** consciously descoped (user decision 2026-07-05).
  Documented as perspectives in the report, not as gaps.
- **B-4 (done, keep enforcing) — permission-gated UI:** every write button hidden without the
  permission (C.15 audit). Any new screen must follow the same `canX()` pattern.

## Database Improvements

- **D-1 (P2) — FK index audit:** PostgreSQL does not index FKs automatically. Verify covering
  indexes on hot FKs (`charges_reelles(project_id, user_id)`, `plan_charges(...)`,
  `lignes_di(project_id)` ✅ exists, `snapshot_kpis(project_id)`). One migration `V24` if gaps found.
- **D-2 (P3) — Drop dead `parameters` table** together with the entity (see T-1) after confirming
  nothing reads it. Data-destructive → verify + backup first.
- **D-3 — Explicitly NOT normalizing further:** monetary amounts stay `NUMERIC(15,2)`, rates
  `NUMERIC(7,4)`; enums as VARCHAR + CHECK is deliberate (readable dumps, Flyway-friendly). No change.

## Backend Improvements

- **BE-1 (P2) — Decompose nothing, extract little:** package layout is right. Only extraction
  worth doing: `SecurityUtils` (current-user/authority helpers duplicated in ~4 services — the
  duplication that produced C-2 once). One small class, mechanical refactor, tests unchanged.
- **BE-2 (P2) — `PageResponse<T>` DTO (proper N-4 fix):** replace raw `Page<T>` returns with a
  thin record `{content, page, size, totalElements, totalPages}` via one mapper. Removes the
  suppressed `PageImpl` warning *and* freezes the wire contract we already promised the frontend.
  Coordinated change: 5 controllers + frontend `PagedResponse` (field names identical → low risk).
- **BE-3 (P3) — Repository naming:** `findActiveByEmailWithRole`-style names are verbose but
  self-documenting; keep, but align the two outliers found during the next touch of those files.
- **BE-4 (P3) — Request validation sweep:** `@Valid` coverage is good on write DTOs; add
  `@PositiveOrZero` on the DI numeric fields and workload day fields where missing.

## Frontend Improvements

- **FE-1 (P1) — Test seed (the biggest credibility gap):** `AuthService` (login/refresh/logout,
  corrupt-storage recovery), `permissionGuard`, `DevisInterneComponent` compute rendering, and one
  form (project-form dirty-guard). ~12–15 specs. Target: `ng test` green in CI-able state.
- **FE-2 (P1) — Toast service:** replace scattered inline `snapshotMsg`-style signals with one
  `ToastService` + container in `ShellComponent` (same pattern as `ConfirmService` — proven here).
- **FE-3 (P2) — Decompose the two monoliths only:** `project-detail` (972 lines → shell + one
  child component per tab) and `governance` (664 → 3 registers). *No* global atomic-design
  refactor — the other components are healthy sizes.
- **FE-4 (P2) — Responsive sidebar + loading states:** collapsible sidebar under 992 px (pure CSS
  + one signal), skeleton rows on the 4 paginated tables.
- **FE-5 (P2) — `UserService`:** `user-list` still calls `HttpClient` inline; extract to a service
  for symmetry with every other feature.
- **FE-6 (P3) — Accessibility pass:** aria-labels on icon buttons, focus trap in the confirm
  modal, `prefers-reduced-motion`. Cheap, jury-visible.
- **FE-7 (P3) — Sass `@use` migration** (deprecation warning in every build log).

## Security Improvements

- ~~**S-1 (P1):** prod fail-fast secrets (see A-3).~~ ✅ **DONE (ADR-026)** — `DB_PASSWORD` and
  `JWT_SECRET` no longer have fallback values in `application.yml`; a misconfigured start fails
  immediately instead of running with a known secret. The `dev` profile carries its own explicit
  (dev-only) secret. Verified: 89/89 backend tests still pass.
- **S-2 (P2):** logout without a valid access token should still clear the refresh cookie
  (currently requires auth → a user with an expired access token can't revoke server-side).
- **S-3 (P2):** update `AUTHORIZATION_MATRIX.md` — add `MANAGE_DI` (Directeur), record the V20
  descope of `MANAGE_ROLES`/`VIEW_AUDIT_LOG` as *formally descoped*, re-verify the matrix against
  `V12+V13+V20+V23`.
- **S-4 (P3):** CSP header (currently only frame/content-type/referrer). Angular dev-mode inline
  styles complicate strict CSP; do it with the prod build only.
- **Assessment:** no critical findings. The chain is coherent; the remaining items are hardening.

## Documentation Improvements

- **DOC-1 (P0):** UML redesign (A-1) + rewrite `UML_DESIGN.md` with per-diagram design rationale.
- ~~**DOC-2 (P1):** `DEPLOYMENT.md` (prerequisites, env vars, docker-compose, first-run, backup).~~
  ✅ **DONE (ADR-026)** — [`docs/DEPLOYMENT.md`](DEPLOYMENT.md): two run modes, prerequisites and
  port check, env-var table, first run, demo-account warning, operations, backup/restore,
  troubleshooting, CI table, explicit non-goals.
- **DOC-3 (P1):** refresh `AUTHORIZATION_MATRIX.md` (S-3).
- **DOC-4 (P2):** two missing ADRs — *ADR-027 single-writer budget* (C-1: only `AvenantService`
  mutates the revised budget) and *ADR-028 hybrid KPI engine* (live compute + frozen monthly
  snapshot; rejection of the async pipeline — see D-3).
  > **Renumbered.** These were reserved as 025/026, but ADR-025 is already *Internationalization*
  > and ADR-026 is now *Containerized deployment + CI/CD*. Next free number is **027**.
- **DOC-5 (P2):** report ch. 9 (frontend, with DI screen captures), ch. 10 (87 tests, security,
  deployment), conclusion + "known limitations" (honest: frontend tests seeded late, mobile
  partial, role-admin descoped).
- **DOC-6 (P3):** `ARCHITECTURE.md` §naming — document the deliberate FR domain vocabulary
  (`JalonFacturation`, `ChargeReelle`…) as ubiquitous language; stop treating it as inconsistency.
- **DOC-7 (P1) — `DATABASE_DESIGN.md` / `schema.sql` drift** *(found by the 2026-07-05 acceptance
  re-review)*: both described the *planned* Phase-3 schema, including tables never built
  (`project_manager_assignments`, `currencies`, `exchange_rates`, `actions`, `risk_*_levels`) and
  none of V21–V23. **Partially fixed same day:** §2 ERD rewritten as-built (22 real tables, real
  names, real links) with a prominent as-built banner; `schema.sql` demoted to historical snapshot
  (header pointing to Flyway as the sole source of truth). **Remaining:** rewrite the §3+ entity
  catalog tables against the real columns — do together with the report's database chapter pass.

## UML Improvements

Covered by **A-1** (the full audit findings and target set). Summary of principles applied:
*one domain per diagram, one page per diagram, real class names only, key attributes only, no
technical classes (Controller/Service/Repository/DTO/Mapper), enums only where they carry business
meaning (statuses), every diagram cross-checked against an entity file before rendering.*

## Technical Debt

| # | Item | Why it is debt | Resolution |
|---|---|---|---|
| T-1 | `Parameter` entity referenced by nothing but itself | Dead code invites "what is this?" in review | Delete entity (+ table via D-2 after verification) |
| T-2 | `PageImpl` warning suppressed via log level | Symptom hidden, contract still unpinned | BE-2 `PageResponse<T>` |
| T-3 | Zero frontend specs | Untested UI logic (auth flow regressed once already) | FE-1 |
| T-4 | 972-line component | Change amplification, merge pain | FE-3 |
| T-5 | Sass `@import` deprecation | Breaks on Dart Sass 3 | FE-7 |
| T-6 | Dev JWT secret fallback in all profiles | Secret could reach prod | S-1/A-3 |
| T-7 | KPI recompute per GET | Latent N×M on portfolio views | A-4 (measure first) |
| T-8 | Report LaTeX template local-build issues | Pre-existing (ADR-012); Overleaf is canonical | Accept; document |

## Improvement Roadmap

- **Phase 1 — Report-critical consistency (now):**
  A-1 UML redesign + render + `UML_DESIGN.md` rewrite → DOC-3/S-3 matrix refresh → A-2 springdoc.
- **Phase 2 — Engineering credibility (next):**
  FE-1 test seed → FE-2 toasts → FE-3 decomposition → FE-5 → BE-1 `SecurityUtils`.
- **Phase 3 — Production story:**
  A-3/S-1 prod profile + DOC-2 DEPLOYMENT.md → BE-2 PageResponse → S-2 logout edge → FE-4.
- **Phase 4 — Defense polish:**
  DOC-4 ADRs → DOC-5 report chapters + captures → FE-6/FE-7 → T-1/D-2 dead-code removal →
  B-1/B-2 small business closures.

Ordering rationale: everything the jury *reads* first (Phase 1) → everything the jury *checks*
(Phase 2) → everything the jury *asks about* (Phase 3) → polish (Phase 4). Risk is front-loaded
low: Phase 1 touches no runtime code.

## Decision Log

- **D-1 — Keep the modular monolith.** *Before:* same. *Considered:* Spring Modulith, split
  services. *Rejected because:* no independent scaling need, one team, one DB; boundaries are
  already enforced by package structure + tests. *Trade-off:* single deployable. *Impact:* none.
- **D-2 — Keep French domain vocabulary in code.** *Before:* treated as a naming inconsistency to
  fix. *Rejected the "fix":* the client's method (F-AFF-13) is French; `JalonFacturation` and
  `ChargeReelle` are its ubiquitous language, and renaming to English would *create* the
  translation gap DDD warns about. *Instead:* document the convention (DOC-6). *Trade-off:* mixed
  FR/EN identifiers. *Impact:* zero code churn.
- **D-3 — Reject the async event-driven KPI pipeline (docs described it; code never built it).**
  *Before:* UML sequence/activity showed post-commit events, async recompute, dashboard
  notification. *Why rejected:* an event bus for a single-process app computing sums over a few
  hundred rows is accidental complexity; failure modes (lost events, stale snapshots) exceed the
  benefit. *Instead:* the implemented hybrid — compute on read + explicitly frozen monthly snapshot
  (matches the business's actual monthly review ritual). *Trade-off:* recompute cost per read
  (T-7). *Impact:* UML redesigned to match (A-1); ADR-026 records it (DOC-4).
- **D-4 — DI = structure without data** (2026-07-05, with owner). Recorded in
  BUSINESS_ANALYSIS §16; `MANAGE_DI` Directeur-only; computed amounts never stored. *Trade-off:*
  demo shows an empty DI unless fake demo lines are entered live — rehearse accordingly.
- **D-5 — Formal descope of `MANAGE_ROLES`/`VIEW_AUDIT_LOG`** (V20) instead of building a
  role-admin UI. An honest matrix beats a rushed admin screen. *Trade-off:* "dynamic RBAC" is
  administered by migration — which V12 proved is a *feature* for auditability.
- **D-6 — `PageResponse` DTO over `VIA_DTO` mode.** *Before:* `@EnableSpringDataWebSupport(VIA_DTO)`
  attempt broke the wire contract (nested `page.*`) and a test. *Why:* our own record keeps the
  flat contract the frontend already types, with zero magic. *Trade-off:* one mapper to maintain.

---

## Landed — Internationalization (i18n) architecture (2026-07-28)

- **What:** First-class bilingual UI (FR default / EN), instant runtime switch with **no page reload**,
  built on **Transloco** (`@jsverse/transloco`). Recorded as **ADR-025**; full guide in
  [`FRONTEND_I18N.md`](FRONTEND_I18N.md).
- **Why it matters (addresses the "frontend engineering depth" weakness):** i18n is designed as an
  architectural concern — root shared catalog + lazy per-module scopes under `public/i18n/`, a reusable
  `<app-language-switcher>`, `localStorage` + browser-language persistence, and an `APP_INITIALIZER`
  that fixes the language before first render. Adding a language = drop a JSON file, **zero component
  changes**. The backend stays language-independent (returns codes; Angular maps `status.*`/`roles.*`).
- **Live & verified (FR↔EN):** i18n infrastructure, root tokens (`common`/`nav`/`roles`/`status`/
  `validation`), global chrome (sidebar nav + command palette + footer), and the `auth` scope
  (login + change-password, switcher usable pre-authentication).
- **Incidental fix:** the duplicated hardcoded `ROLE_LABELS` maps (with the `DEVELOPER` vs
  `DEVELOPPEUR` typo) are replaced by `roles.<code>` keys.
- **Remaining (mechanical, tracked in `FRONTEND_I18N.md` §8):** the feature pages (dashboard,
  project-list/detail/form, user-list, billing, workload, missions, kpi, resources, governance, di)
  apply the identical documented pattern (§7). *Guardrail:* any new user-facing literal is a
  review-blocking defect; a CI grep for literals in `template:` blocks is a recommended follow-up.

---

## Landed — UX/UI Enterprise Overhaul (2026-07-29)

### UX-1 — Replace button-list project selectors with searchable dropdowns

- **Problem:** Workload, Missions, Governance, and Billing pages rendered every project as a horizontal row of clickable buttons. With 10+ projects, the page filled with a wall of buttons — unusable for enterprise scale, immediately recognizable as AI-generated.
- **Why:** Button lists don't scale; they waste space, lack search/filter capability, and look unprofessional in a code review or PFE defense.
- **Solution:** Replaced all 4 instances with a native `<select>` dropdown showing `code — name`, bound via `[ngModel]` with a `selectById()` bridge method. Max-width 360px, consistent placement across all pages.
- **Benefit:** Compact, scalable, professional project selection. O(1) visual footprint regardless of project count.

### UX-2 — Remove AI-generated helper text and verbose empty states

- **Problem:** Multiple pages contained instructional paragraphs, tips, and verbose empty-state descriptions that scream "AI-generated": "Sélectionnez un projet pour afficher ses charges", "Les montants TND et marges sont dérivés…", "Calculé automatiquement (bornes incluses)", "Budget initial × taux de change", "Sauvegarde automatique activée", etc.
- **Why:** Enterprise applications don't explain themselves in paragraph form. Experienced engineers recognize these patterns instantly as AI output. Every extra line of explanatory text reduces credibility.
- **Solution:** Stripped all helper text from: workload (empty state), missions (empty state), governance (empty state), billing (empty state), devis-interne (info banner + verbose empty state → "Aucune ligne"), resources (verbose header → "Tarifs journaliers", verbose TCC empty state), project-form (3 helper texts + autosave indicator), project-list (verbose empty state).
- **Benefit:** Clean, professional UI that trusts the user to understand the application. Information density increases; visual noise decreases.

### UX-3 — Reduce dashboard visual noise

- **Problem:** Dashboard displayed `<p class="page-subtitle">` paragraphs for each role and `<p>` description blocks for each administration module card. These subtitles and descriptions added no actionable information.
- **Why:** Dashboard should be a launch pad — scan and click, not read paragraphs. Module cards with descriptions like "Gérer les utilisateurs du système" state the obvious.
- **Solution:** Removed all 4 role subtitle paragraphs and all 3 module card descriptions. Simplified i18n titles: "Gestion Utilisateurs" → "Utilisateurs", "Administration du Système" → "Administration". Both FR and EN translation files updated.
- **Benefit:** Compact dashboard that respects user attention. Module cards are self-explanatory from their titles and icons.

### UX-4 — Consolidate redundant sidebar navigation

- **Problem:** Governance had 3 sidebar links (Risks, Deliverables, Changes) all routing to the same `GovernanceComponent`. Workload had 2 links (Plan, Actuals) routing to the same `WorkloadComponent`. Multiple links to the same page confuse users and waste sidebar space.
- **Why:** Redundant nav entries create the illusion of separate pages that don't exist. The component already has internal tabs/sections.
- **Solution:** Consolidated to 1 link each: "Gouvernance" (permission: `VIEW_GOVERNANCE`) and "Charges" (permission: `VIEW_WORKLOAD`). Added corresponding i18n keys in both FR and EN root translation files.
- **Benefit:** Cleaner navigation, fewer clicks, accurate mental model of the application structure.

### UX-5 — Director TCC access (RBAC-based)

- **Problem:** Director role lacked `MANAGE_RESOURCES` permission, preventing view and modification of TCC rates. The permission model required Director to have full TCC access, PM read-only, Developer no access.
- **Why:** The business requirement (F-AFF-13) specifies Director as the authority over resource rates. Without `MANAGE_RESOURCES`, the Director couldn't manage the rate table — a gap in the RBAC matrix.
- **Solution:** Flyway migration `V24__director_tcc_access.sql` — `INSERT INTO role_permissions` granting `MANAGE_RESOURCES` to `DIRECTEUR` role, using `ON CONFLICT DO NOTHING` for idempotency. Follows the established pattern from V12.
- **Benefit:** RBAC matrix now correctly reflects business authority. No hardcoded role checks — purely permission-based via `@PreAuthorize("hasAuthority('MANAGE_RESOURCES')")`.

---

## Landed — Admin RBAC modules + UX foundation (2026-07-30)

Full detail in [`UX_UI_AUDIT.md`](UX_UI_AUDIT.md). Summary:

- **Role Management (`/admin/roles`)** — full CRUD + permission assignment (grouped by module),
  system-role & in-use guards, cache-eviction on permission change. Restored `MANAGE_ROLES`
  (deleted in V20) via `V25__rbac_admin_module.sql`; new `RoleController`/`RoleAdminService`.
  Removes the dashboard "Bientôt" placeholder. **Verified end-to-end in-browser.**
- **Permission catalog (`/admin/permissions`)** — grouped by module, roles-per-permission, search.
  Decision **UX-D1**: no arbitrary permission minting (inert without a code check); administration is
  role↔permission assignment.
- **TCC hardening** — `ResourceService` now scopes by role: `MANAGE_RESOURCES` → full;
  `VIEW_RESOURCES`-only (PM) → own-project resources; Developer → 403. **Verified by API across all
  four roles** (Admin 87 / Director 87 / PM 2 / Dev 403; out-of-scope TCC → 403).
- **UX foundation** — `ToastService` + container (FE-2, partially landed), skeleton loading &
  standardized empty states on new pages, sidebar Admin entries, dead duplicate routes removed.
- **Schema** — `roles.description`, `roles.is_system`, `permissions.description` (+ backfill).

Remaining UX work is tracked per-screen in [`UX_UI_AUDIT.md`](UX_UI_AUDIT.md) §4/§9.

---

## Landed — « Se souvenir de moi » rendu fonctionnel (2026-09-18)

- **Problem:** the checkbox on the login page was purely decorative. It carried no
  `[(ngModel)]` binding, `rememberMe` appeared nowhere else in the frontend, and the word
  did not exist anywhere in the backend. Every session lasted the short duration no matter
  what the user ticked.
- **Solution:** `rememberMe` added to `LoginRequest` (with a two-arg convenience constructor so
  existing call sites keep compiling), carried as a **claim inside the refresh token**, and used
  by `JwtService.refreshMaxAge()`. New `TokenBundle` record carries the cookie lifetime alongside
  the token so the JWT `exp` and the cookie `Max-Age` cannot drift apart.
- **Why the claim matters:** refresh rotation mints a brand-new token on every call. Had the
  session length not been carried by the token itself, the user would have silently dropped to
  the short duration on their first refresh and been logged out days early, with nothing in the
  UI to explain it.
- **Also note:** the authoritative `Set-Cookie` header is written by hand in `AuthController`;
  it overrides the Servlet `Cookie` API, so setting `cookie.setMaxAge()` alone would have left
  the feature inert.
- **Verified:** 4 new tests in `RememberMeTest` + live HTTP against a running backend —
  `Max-Age=86400` unticked, `Max-Age=2592000` ticked, preserved across two consecutive rotations.

---

## Fixed — permission-less role locked users out (2026-09-18)

- **Problem:** `UserRepository.findActiveByEmailWithRole` fetched `r.permissions` with an **inner**
  join. A role holding zero permissions is a perfectly legitimate state — a role just created from
  the Roles page, or one whose permission matrix was emptied — but it produced no rows at all, so
  the account was invisible to the login query and was rejected with *« Identifiants incorrects »*.
  The message accuses the password while the password is correct; `RoleRequest.permissionIds` has no
  `@NotEmpty`, so an administrator can reach this state from the UI.
- **Solution:** `LEFT JOIN FETCH r.permissions`, with a comment on the query explaining why the join
  type is load-bearing. Regression test `login_roleWithoutPermissions_stillAuthenticates`.
- **Found by:** the remember-me tests above, whose fixture role happened to have no permissions.

---

## Open — malformed request bodies return 500 instead of 400

- **Problem:** `HttpMessageNotReadableException` has no handler, so an unparseable JSON body
  returns **500**. Reproduced on `POST /api/auth/login` with an empty body, with truncated JSON,
  and with a type mismatch such as `{"rememberMe":"yes"}`. Pre-existing and global — not specific
  to auth. (A body that *parses* but fails bean validation correctly returns 400.)
- **Why it matters:** a client-side mistake is reported as a server fault, which misleads callers
  and pollutes error monitoring with false server errors.
- **Suggested fix:** one `@ExceptionHandler(HttpMessageNotReadableException.class)` in the global
  handler returning a 400 problem detail, without echoing the parser message back to the client.

---

*Last review pass: 2026-07-30 — admin RBAC modules + UX foundation (backend + frontend, verified).
Next scheduled pass: UX_UI_AUDIT §9 P1 items (toast/skeleton propagation, table pagination).*
