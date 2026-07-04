# PROJECT_MAP.md — PMS Living Project Map

> Single-glance picture of the platform. Updated at the end of every phase. Authoritative
> decisions live in [DECISIONS.md](DECISIONS.md); the task roadmap lives in
> [PROJECT_TODO.md](PROJECT_TODO.md); the business analysis lives in
> [docs/BUSINESS_ANALYSIS.md](docs/BUSINESS_ANALYSIS.md).

---

## [PROJECT_STATUS]
- **Project:** Centralized IT Project Management & Financial Control Platform (PMS)
- **Type:** Enterprise platform + TEK-UP engineering graduation project (PFE), 70–100 page report
- **Overall progress:** **~80%** — Backend phases 5–15 ✅ — Frontend 60% 🟡 — See [PROJECT_STATUS.md](PROJECT_STATUS.md)
- **Backend:** 10/10 modules implémentés (Auth, Users, Projects, Teams, Workload, KPI, Billing, Missions, Governance, Shared)
- **Frontend:** Angular 21 — auth/guards/menu dynamique/dashboard/formulaire Projet ✅ ; modules métier restants
- **Tests:** 51/51 tests d'intégration ✅ (Auth, Project+RBAC, Governance, Billing, Team, Workload, KPI)
- **Rapport:** 8/10 chapitres rédigés (ch.1–8, miroirs Markdown dans docs/report/)

## [CURRENT_PHASE]
- **Phases 1 / 2 / 3 :** APPROVED ✅ (Analyse métier, Architecture, BD).
- **Phase 4 — Conception UML :** DONE (gate en attente — ADR-023/024, 11 diagrammes Niveau 1).
- **Phase 5 — Auth & RBAC :** DONE ✅ (JWT, tokenVersion, cache, 4 rôles, 18 permissions).
- **Phase 6 — Users & Resources :** DONE ✅ (CRUD, tarif JH, TCC rate, MapStruct).
- **Phase 7 — Projects :** DONE ✅ (cycle de vie PROSPECT→TERMINE, budget effectif).
- **Phase 8 — Teams :** DONE ✅ (affectation, partial unique index, re-affectation OK).
- **Phase 9 — Workload :** DONE ✅ (plan_charges + charges_reelles, 9 endpoints).
- **Phase 10 — KPI Engine :** DONE ✅ (budgetPlanifie, budgetConsome, EAC, marge, snapshots).
- **Phase 11 — Facturation :** DONE ✅ (jalons Σ%≤100, paiements auto-PAYE, avenants → revisedBudget).
- **Phase 12 — Missions :** DONE ✅ (missions + composantes, multi-devises, 8 endpoints).
- **Phase 13 — Gouvernance :** DONE ✅ (Risk, Livrable machine à états, PartiePrenante, DemandeChangement).
- **Phase 14 — Frontend Angular :** 60% 🟡 (auth, guards, menu dynamique, dashboard, formulaire Projet).
- **Phase 15 — Tests d'intégration :** 95% 🟢 (51/51 ✅ — Auth, Project+RBAC, Governance, Billing, Team, Workload, KPI).
- **Suivant :** Tests Team/Workload/KPI + composants frontend métier.

## [TECH_STACK]  (ADR-006 — aligned to installed versions)
- **Backend:** Java 21 (21.0.9 LTS, JDK `C:\Program Files\Java\jdk-21`) · Spring Boot 3.3.x · Spring Security + JWT · Spring Data JPA · **PostgreSQL 17** · **Maven 3.9.16** (installed, not on PATH) + `mvnw` wrapper
- **Frontend:** **Angular 21** (CLI 21.2.8) · **Node 22.20.0** · TypeScript · Bootstrap 5 · Chart.js · ng-bootstrap · PrimeNG · French UI
- **Also installed:** Git 2.51.0 · Docker 28.4.0 · Python 3.8.10 · MiKTeX (pdflatex)
- **Auth:** JWT access + refresh token; forced first-login password change (ADR-010)
- **Authorization:** Dynamic RBAC, permission-based (ADR-001)

## [SYSTEM_FLOW]
```
Administrator → creates Users (Director, PM, Developer) + manages TCC + parameters
Director      → creates Project, sets budget, assigns Project Manager (+ may assign developers, D4)
Project Mgr   → builds team, plans workload (JH/month), billing milestones, missions, risks…
Developer     → submits actual workload (manual) ── KIMAI import also feeds actuals
System        → computes KPIs (EV, ETC, EAC, margins, CA Production, FAE, billing %) → dashboards
```
Authorization on every action = permission check (never role check).

## [BUSINESS_RULES]
- Source: **65 BRS rules (BR-001…BR-065)** reconciled with Excel reality + FR/UC. Full catalog in
  `docs/BUSINESS_ANALYSIS.md` §7–§8.
- Key configurable constants (ADR-008): TCC overhead ×1.7 (~70%), working days/year 22 (2024)/20
  (2025), PPR = 5%, milestone Σ% ≤ 100%, hours→days ÷8.
- Financial walls: Developers have **no** access to budget/margin/EAC/billing (BR-050).

## [ARCHITECTURE]  (designed in Phase 2 — see `docs/ARCHITECTURE.md`)
- **Style:** modular monolith, layered (Controller→Service→Repository→Domain), feature-based (ADR-016).
- **Dynamic RBAC (ADR-001):** permission authorities + `@PreAuthorize('hasAuthority(...)')` +
  `GET /api/me/context` dynamic menu. No role-name branching.
- **Authorization scope (ADR-021):** permission ∧ data scope, enforced systematically at the
  repository boundary (Director=all, PM=managed, Developer=assigned, Admin=admin-only).
- **User vs Resource (ADR-022):** auth identity vs workload/cost person; `resources.user_id` nullable
  (external consultants/subcontractors = Resource only).
- **Security (ADR-010/017):** JWT access (short TTL, identity + token_version) + refresh + cache-backed security context (immediate revocation); first-login
  password change; BCrypt; access in memory / refresh in HttpOnly cookie.
- **KPI engine (ADR-015):** hybrid — per-project/per-month `KpiSnapshot`, recompute-on-change, history.
- **Cross-cutting:** multi-currency conversion (ADR-007), parameter store (ADR-008), soft delete +
  audit (ADR-009), async structured logging.
- Feature modules: auth, user, role, permission, project, team, tcc, workload-plan, actual-workload,
  billing, mission, kpi, reporting + risk, deliverable, stakeholder, change, avenant.

## [DATABASE]
- Conceptual entities identified (schema is Phase 3): User, Role, Permission, RolePermission;
  Project, ProjectManagerAssignment; Resource, TeamAssignment; TCC(resource, year);
  WorkloadPlan, ActualWorkload(source=MANUAL|KIMAI); BillingMilestone, Invoice, Payment;
  Mission, MissionCost; Risk, Deliverable, Stakeholder, ChangeRequest, Avenant;
  Currency, ExchangeRate; Parameter, AuditLog; KpiSnapshot.
- DBMS: PostgreSQL 16. No DDL yet.

## [COMPLETED_FEATURES]
**Backend REST API (75+ endpoints) — Spring Boot 3.3.6 / Java 21 / PostgreSQL 17**
- **Auth :** POST /login /refresh /logout /change-password — GET /me/context (menu dynamique)
- **Users :** CRUD /api/users — PUT activate/deactivate
- **Resources :** CRUD /api/resources (tarif JH + TCC rate)
- **Projects :** CRUD /api/projects — PUT assignChefProjet — GET byStatus
- **Teams :** CRUD /api/projects/{id}/team (affectation, historique)
- **Workload :** 9 endpoints plan-charges + charges-reelles (submit/validate/delete)
- **KPI :** GET live / GET snapshots / POST snapshot — /api/projects/{id}/kpi
- **Billing :** jalons + paiements + avenants — /api/projects/{id}/{jalons,avenants} (11 endpoints)
- **Missions :** missions + composantes — /api/projects/{id}/missions (8 endpoints)
- **Governance :** risks, livrables, parties-prenantes, demandes-changement (19 endpoints)
- **11 migrations Flyway** V1–V11 (schema + seed)
- **32 tests d'intégration** (Auth, Project+RBAC 403, Governance, Billing) — H2, profile=test — BUILD SUCCESS ✅

## [ORPHANS_AND_PENDING]
- Cahier des Charges copied into `docs/` ✓ (housekeeping done).
- KIMAI import contract (fields, frequency, auth) not yet specified — to define in Phase 13.

## [TECHNICAL_DEBT]
- **Report local build (MiKTeX 2025):** the TEK-UP template `tpl/` has pre-existing
  incompatibilities (cover-page `@`-catcode, `résumé` tcolorbox, babel-french ⇆ enumitem lists)
  affecting *local* builds only. Canonical target is **Overleaf** (template README); `tpl/` kept
  pristine (ADR-012). A 27-page PDF still builds locally; project chapters render correctly.
  `img/logoSociete.png` is a placeholder (student supplies the company logo).
- Excel source contains `#REF!`/`#VALUE!` artifacts — the platform recomputes cleanly from
  normalized data, never replicating broken cell references.

## [REPORT_READABLE_MIRRORS]
- Every LaTeX report chapter is mirrored as readable Markdown in `docs/report/` (so the report can
  be read without LaTeX): `introduction.md`, `chap_01.md`, `chap_02.md`, `chap_03.md`, `chap_04.md`,
  `chap_05.md`, `chap_06.md`, `chap_07.md`, `chap_08.md`. Kept in sync with the `.tex` sources each phase.

## [NEXT_ACTIONS]
1. **Phase 11 — Facturation** : `V9__schema_billing.sql`, entités `JalonFacturation` / `Paiement` / `Avenant`, règle Σ%≤100, Service + Controller.
2. **Phase 12 — Missions** : `V10__schema_missions.sql`, composantes coût, multi-devises (ADR-007).
3. **Phase 13 — Gouvernance** : Risk, Deliverable, Stakeholder, ChangeRequest.
4. **Phase 14 — Frontend Angular** : skeleton → login → guards → menu → dashboards.
5. **Rapport ch.6** : rédiger chapitre implémentation (Auth + Backend core).
