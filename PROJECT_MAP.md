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
- **Phase F — DevOps :** DONE ✅ (conteneurisation 3 services, CI/CD GitHub Actions, `DEPLOYMENT.md`, ADR-026) — **pile vérifiée en exécution le 2026-08-20** : 3 conteneurs sains, 26 migrations, login réel via nginx, RBAC conforme (PROJECT_TODO F.10).
- **Suivant :** débloquer Docker (F.10), pousser la ligne de base (F.11), captures d'écran du rapport.

## [TECH_STACK]  (ADR-006 — aligned to installed versions)
- **Backend:** Java 21 (21.0.9 LTS, JDK `C:\Program Files\Java\jdk-21`) · Spring Boot 3.3.x · Spring Security + JWT · Spring Data JPA · **PostgreSQL 17** · **Maven 3.9.16** (installed, not on PATH) + `mvnw` wrapper
- **Frontend:** **Angular 21** (CLI 21.2.8) · **Node 22.20.0** · TypeScript · Bootstrap 5 · Chart.js · ng-bootstrap · PrimeNG · **Bilingual UI (FR default / EN)** via **Transloco** i18n (ADR-025)
- **Also installed:** Git 2.51.0 · Docker 28.4.0 · Python 3.8.10 · MiKTeX (pdflatex)
- **DevOps (Phase F):** **Docker Compose** 3 services (`db` postgres:17-alpine · `backend` temurin-21-jre non-root · `frontend` nginx:1.27-alpine) · **GitHub Actions** (`.github/workflows/ci.yml`) · Spring Boot **Actuator** (`/actuator/health` en `permitAll`) — voir [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) et ADR-026
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
- **Internationalization (ADR-025):** Transloco runtime i18n — FR (default) / EN, **instant switch, no
  reload**; catalogs in `public/i18n/` (root shared + lazy per-module scopes); backend stays
  language-independent (returns codes `ACTIVE`/`CHEF_PROJET`, Angular maps `status.*`/`roles.*`);
  adding a language = drop a JSON file, zero code change. Guide: `docs/FRONTEND_I18N.md`.
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
- **Devis Interne (F-AFF-13 §3) :** GET/POST/PUT/DELETE /api/projects/{id}/devis-interne[/lignes] — structure vide, capacité MANAGE_DI (Directeur), montants/marges calculés à la lecture (jamais stockés), lignes taxes en % du total vendu
- **TCC par année (F-AFF-13 §6.3-4) :** GET/PUT /api/resources/{id}/tcc — tarif de l'année d'imputation, fallback tarif de base
- **KPI EVM (F-AFF-13 §5) :** EV %, Delivery %, consommé/RAF/dérive JH, CA production, FAE, marge actuelle vs vendue ; snapshot = revue mensuelle (EV saisi + faits marquants + date fin estimée)
- **23 migrations Flyway** V1–V23 (schema + seed + V20 descope RBAC + V21 tcc_annuels + V22 EVM + V23 lignes_di)
- **87 tests** (11 suites : contrôleurs + services ChargeReelle/Jalon/Avenant/Scope/DevisInterne) — H2, profile=test — BUILD SUCCESS ✅
**Frontend Angular 21 (signals, standalone)** — login/guards/menu dynamique, dashboards, projets (fiche identification + détail à onglets), équipe, charges (pagination), KPI + bannière warnings, facturation, missions, gouvernance, ressources (tarifs par année), admin utilisateurs, **écran Devis Interne** `/projects/:id/devis-interne` (gated MANAGE_DI)

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
- ⚠️ **Stale — do not trust.** `docs/report/*.md` mirrors the **former** 10-chapter French report.
  The report has since been restructured to **7 chapters in English** (Context/Methodology,
  Requirements, Architecture & Design, then Releases 1–4 covering Sprints 1–9); the old chapters
  live in `report/Rapport PFE TEKUP LATEX/_archive_old_chapters/`.
- The `.tex` sources under `report/Rapport PFE TEKUP LATEX/` are the **only** current version.
  Resynchronising the mirrors is tracked as PROJECT_TODO **F.12** (P2).

## [NEXT_ACTIONS]
2. **Pousser la ligne de base (F.11, P0)** : 2 commits pour 277 fichiers non commités ; à faire **avant** d'activer les checks obligatoires sur `develop`/`main`.
3. **Captures d'écran du rapport** : 8 captures Excel anonymisées + 5 captures UI — blocs `% TODO:` déjà en place dans `chap_01.tex`.
4. **Backlog qualité restant** (voir `docs/ENHANCEMENTS.md`) : ADRs (C-1 writer unique, H-4 recalcul jalons), seed tests frontend (H-7), cible `test` Angular absente (FE-1/T-3).
5. **Phase 15 (restant)** : review OWASP, performance NFR-001.
