# DECISIONS.md — Architecture Decision Records (ADR)

> **Status of this document:** This is the **highest-priority source of truth** for the PMS
> project. When any source conflicts (Excel, BRS, SRS, Use Cases, Project Plan), the resolution
> recorded here wins. Each non-trivial decision is logged as an ADR with context, decision,
> rationale, and consequences.

**Project:** Centralized IT Project Management and Financial Control Platform (PMS)
**Maintained by:** Technical Lead / Solution Architect
**Last updated:** 2026-06-14 (Phase 1 — Business Analysis)

---

## Source-of-Truth Priority (governance)

When sources conflict, resolve in this order (higher wins), document the discrepancy, and record
the resolution as an ADR:

1. Validated decisions in **DECISIONS.md**
2. Latest **user instructions**
3. Business reality extracted from the **Excel files**
4. **Business Rules Specification (BRS)**
5. **Use Case Specification**
6. **Software Requirements Specification (SRS)**
7. **Project Plan** (strategic guidance only — challenge & improve)

---

## ADR-001 — Dynamic RBAC: `Role → Permission → Module`

- **Status:** Accepted
- **Context:** Authorization must survive organizational change. Business documents themselves
  disagree on *who* may do *what* (see ADR-005). Hardcoding `Role → Module` checks (e.g.
  `if (role == PROJECT_MANAGER)`) would force source-code changes every time responsibilities move.
- **Decision:** Authorization is modeled as **Role → Permission → Module**. Roles are bags of
  permissions; all backend guards, all frontend route/menu/visibility logic, and all dynamic menu
  generation key off **permissions**, never off role names.
- **Reason:** Maximum flexibility and maintainability; a responsibility shift becomes a data
  change (grant/revoke a permission), not a code change.
- **Consequences:**
  - Entities: `User`, `Role`, `Permission`, `RolePermission` (many-to-many).
  - Backend secured with permission authorities (e.g. `@PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")`).
  - Frontend menu/guards driven by the permission set returned at login.
  - Forbidden anywhere in the codebase: branching on a role name to gate a feature.

## ADR-002 — Full Excel financial model (not the simplified BRS model)

- **Status:** Accepted (user decision **D1**)
- **Context:** The BRS gives simplified KPI formulas (EAC = Actual + Remaining; Margin = Budget −
  EAC). The real Excel (`DEV_Fiche_revue_projet-Real.xlsx`) and its Glossaire carry a far richer
  model used in production.
- **Decision:** Implement the **full Excel model**: Earned Value % (EV), ETC, EAC, Coût Actuel,
  Coût Prévisionnel, Cumul CA Production, FAE/Stock, RAF, Dérive, and **three** margins
  (vendue / actuelle / EAC), plus billing progress and mission costs.
- **Reason:** Faithfulness to business reality (priority 3) and a stronger graduation project.
  The simplified BRS formulas are a strict subset and remain valid.
- **Consequences:** Richer KPI service + data model; per-resource/per-year TCC; monthly time
  series for plan vs actual; multi-currency (ADR-007). Full formula catalog in
  `docs/BUSINESS_ANALYSIS.md` §8.

## ADR-003 — Actual workload sourced from **manual entry AND KIMAI import**

- **Status:** Accepted (user decision **D2**)
- **Context:** BR-033/FR-032/UC-018 say developers submit actual man-days in the app; the
  Glossaire says actuals are extracted from **KIMAI** (external time-tracking tool).
- **Decision:** Support **both**. Actual-workload records carry a `source` discriminator
  (`MANUAL | KIMAI`). Manual monthly entry by developers is the core path now; a KIMAI import
  pipeline is designed now and implemented as an integration.
- **Reason:** Keeps the Developer role meaningful, removes a hard external dependency for the core
  flow, and honors the real operational source (KIMAI) without redesign.
- **Consequences:** `actual_workload(project, resource, month, man_days, source, accepted, …)`.
  **Reconciliation model (Phase-3 correction):** one row **per source** —
  `UNIQUE(project,resource,month,source)` so MANUAL and KIMAI coexist — plus an `accepted` flag with
  a **partial unique index** guaranteeing exactly one accepted value per cell; the KPI engine reads
  the accepted value. Import idempotency on (project,resource,month,KIMAI); permission `IMPORT_KIMAI`.

## ADR-004 — Full Excel scope (16 phases **plus** 5 extra modules)

- **Status:** Accepted (user decision **D3**)
- **Context:** The Excel covers more than the 16 defined phases: **Risk Register, Deliverables,
  Stakeholders, Change Register, Avenants** (with a full risk scoring config in the `Paramètres`
  sheet).
- **Decision:** These five modules are **in scope** for the build, in addition to the 16 phases.
- **Reason:** Completeness against the real business artifact; Avenants in particular change budget
  and billing totals and cannot be ignored financially.
- **Consequences:** Extra entities and permissions (`MANAGE_RISKS/DELIVERABLES/STAKEHOLDERS/
  CHANGES/AVENANTS` + `VIEW_*`); risk probability/severity/treatment scales are configurable
  parameters (ADR-008); these modules are sequenced after the core phases in `PROJECT_TODO.md`.

## ADR-005 — Default role–permission mapping: `ASSIGN_DEVELOPER` → `DIRECTOR` + `PROJECT_MANAGER`

- **Status:** Accepted (user decision **D4**) — sets a **default mapping**; **overrides** the
  SRS/Use Cases/Project Plan on this point
- **Context — documented discrepancy:**
  - **Project Plan, SRS, Use Case Spec:** Director **cannot** assign developers; only the Project
    Manager assigns developers (FR-019, UC-010, Plan §3.2 "Restrictions").
  - **Cahier des Charges:** Director "choisit les développeurs / construit l'équipe" yet also lists
    "ne peut pas affecter les développeurs" — internally inconsistent.
  - **User instruction (D4):** Both **Director and Project Manager** must be able to assign
    developers, with the Director able to take it over "tomorrow" by configuration.
- **Decision:** In the **default** role–permission seed, the `ASSIGN_DEVELOPER` (and
  `REMOVE_DEVELOPER`) permission is mapped to **both** `DIRECTOR` and `PROJECT_MANAGER`:
  `ASSIGN_DEVELOPER → DIRECTOR, PROJECT_MANAGER`. This is a **default configuration, not a fixed
  rule** — per ADR-001 it can be re-mapped at runtime via the database (granted to, or revoked from,
  any role) with **zero** backend/frontend change.
- **Reason:** Latest user instruction + DECISIONS.md outrank the specification documents
  (priorities 1–2 over 5–7). More importantly, this is the canonical proof of ADR-001: a real
  disagreement about authority is resolved by a **permission grant (one DB row)**, with **zero**
  backend/frontend change.
- **Consequences:** The default Role→Permission seed grants `ASSIGN_DEVELOPER`/`REMOVE_DEVELOPER`
  to Director and PM. Revoking it from the Director later is a configuration action. The
  specification documents are superseded on this point; the discrepancy is preserved here for
  traceability and will be narrated in the report as the motivating example for dynamic RBAC.

## ADR-006 — Technology stack (aligned to versions installed on the build machine)

- **Status:** Accepted (updated 2026-06-14 after toolchain detection)
- **Context:** Cahier des Charges and SRS fix the stack; the project is a PFE expected to use
  current, mainstream enterprise technologies. The user requested aligning documented versions to
  what is actually installed on the build machine so the project builds with the local toolchain.
- **Detected on this machine (evidence):**

  | Tool | Documented | **Installed** | Outcome |
  |------|-----------|---------------|---------|
  | Java (JDK) | 21 | **21.0.9 LTS** | keep |
  | Angular CLI | 18 | **21.2.8** | → **21** |
  | Node.js | (unset) | **22.20.0** | pin **22** |
  | npm | (unset) | **10.9.3** | pin |
  | PostgreSQL | 16 | **17** (service `postgresql-x64-17`, running) | → **17** |
  | Maven | (implied) | **3.9.16 installed** (`C:\Program Files\Apache\Maven\apache-maven-3.9.16`, not on PATH; IntelliJ also bundles 3.9.11) | use it + ship `mvnw` |
  | JDK home | — | `C:\Program Files\Java\jdk-21` | — |
  | Git | — | 2.51.0 | — |
  | Docker | — | 28.4.0 | available for DB/container |

- **Decision:** **Backend** — Java 21 (21.0.9 LTS), Spring Boot 3.3.x (resolved via Maven; Java-21
  compatible), Spring Security + JWT, Spring Data JPA, **PostgreSQL 17**. Build with **Maven 3.9.16**
  (installed locally) and also ship the **Maven Wrapper** (`mvnw`) for portability. **Frontend** —
  **Angular 21**, TypeScript (bundled with Angular), Bootstrap 5, Chart.js, ng-bootstrap, PrimeNG,
  on **Node 22**; **French** UI.
- **Reason:** The platform must build on the available local toolchain. Java 21 already matches;
  Angular and PostgreSQL are bumped to the installed majors (21, 17); Maven 3.9.16 is present (just
  not on PATH — add it or invoke by full path). Spring Boot stays a managed dependency (not a
  machine tool).
- **Consequences:** `frontend/package.json` will pin Angular 21 / Node 22; `backend/pom.xml` will
  target Java 21 + PostgreSQL 17 driver and ship `mvnw`. The Cahier des Charges and Project Plan
  were updated (Angular 18→21, PostgreSQL 16→17) in **both** `docs/` working copies **and** the
  `Providers/` originals, at the user's request.
- **Setup note:** Maven is not on PATH and `M2_HOME`/`MAVEN_HOME` are unset. To use `mvn` directly,
  add `C:\Program Files\Apache\Maven\apache-maven-3.9.16\bin` to PATH (the shipped `mvnw` avoids
  needing this).

## ADR-007 — Multi-currency with stored exchange rates

- **Status:** Accepted
- **Context:** The Excel mixes currencies: contract in **FCFA** (e.g. 248 992 444 FCFA →
  1 269 861 TND), reporting in **TND**, missions priced in **EUR** with a `Taux de change`.
- **Decision:** First-class multi-currency. Monetary amounts store value + currency; conversions
  use **stored** `ExchangeRate` rows (per currency pair, dated). Reporting currency = TND
  (configurable parameter).
- **Reason:** Business reality (priority 3); rates must be auditable and never hardcoded.
- **Consequences:** `Currency`, `ExchangeRate` entities; KPI/billing/mission services are
  currency-aware; conversion is centralized.

## ADR-008 — Business parameters are configurable data, never hardcoded constants

- **Status:** Accepted
- **Context:** The Excel encodes tunable constants: TCC overhead **×1.7** (~70% indirect), working
  days/year (**22** in 2024, **20** in 2025), **PPR = 5%**, milestone total **≤ 100%**, hours→days
  divisor (**/8**), and the risk **probability/severity/treatment** scales in `Paramètres`.
- **Decision:** All such constants live in a configurable `Parameter` store (and dedicated config
  tables for scales), editable by the Administrator via `MANAGE_PARAMETERS` — never compiled in.
- **Reason:** "Business rules may evolve without code change" is a core project requirement.
- **Consequences:** `Parameter` entity + typed accessors; TCC computation, KPI formulas, and risk
  scoring read parameters at runtime.

## ADR-009 — Soft delete + audit fields on major entities

- **Status:** Accepted (BR-020, BR-037, BR-060, BR-061; NFR-008)
- **Decision:** Major business entities use **logical deletion** (`active`/`deletedAt`) and carry
  audit fields `createdBy`, `createdAt`, `modifiedBy`, `modifiedAt`. Historical workload,
  assignments, TCC, and billing records are **never** physically deleted.
- **Reason:** Auditability and preservation of history are explicit business rules.
- **Consequences:** JPA auditing (`@CreatedBy`/`@CreatedDate`/…); queries filter on `active`;
  validated historical TCC is immutable (BR-025).

## ADR-010 — Authentication: JWT access + refresh token, forced first-login password change

- **Status:** Accepted (FR-001..006, UC-001/002, BR-005/006)
- **Decision:** Stateless auth with a short-lived **JWT access token** and a longer-lived
  **refresh token**. Admin-created accounts get a generated temporary password and
  `firstLogin = true`; the user is forced to change it before any other access. Passwords stored
  hashed (BCrypt). The JWT/permission set drives dynamic menu + guards (ADR-001).
- **Reason:** Matches requirements and standard enterprise practice; supports an SPA + REST API.
- **Consequences:** `RefreshToken` handling; `firstLogin` gate; permissions delivered to the frontend
  at login via `/api/me/context`. See **ADR-017** for the authoritative token/revocation model
  (token-version + cache-backed security context).

## ADR-011 — Source-of-Truth Priority (governance, formalized)

- **Status:** Accepted (user instruction)
- **Decision:** Adopt the priority order listed at the top of this file. The Project Plan is
  strategic guidance to be challenged, not a fixed spec; Excel business reality overrides it on
  rules, calculations, workflows, and relationships.
- **Reason:** Prevents replicating outdated/simplified document statements; keeps the build aligned
  with real business needs.
- **Consequences:** Every conflict is resolved by this order and recorded as an ADR (ADR-005 is the
  first applied instance).

## ADR-012 — Report build toolchain: Overleaf is the canonical target; `tpl/` kept pristine

- **Status:** Accepted
- **Context:** The TEK-UP/ISI LaTeX template's own `README` states it *"works perfectly online
  (Overleaf)"* and only needs tweaks for **local** builds. On the local MiKTeX 2025 toolchain, the
  template's `tpl/` files show pre-existing incompatibilities **unrelated to project content**:
  cover-page `@`-catcode handling, `résumé` `tcolorbox`, and a babel-french ⇆ `enumitem` list
  conflict affecting the template's own `acronyms` environment and all lists. A 27-page PDF still
  builds and the project chapters render correctly; the errors originate in `tpl/`, not in
  `chap_01`/`chap_02`/`acronymes`.
- **Decision:** **Overleaf** is the canonical build target for the PFE report. The university
  template under `tpl/` is kept **pristine** (per instruction — do not modify/replace the template).
  Local MiKTeX builds are best-effort. A placeholder `img/logoSociete.png` (copy of `tekup.png`)
  is provided so the cover compiles; the student replaces it with the real company logo.
- **Reason:** Honors the "do not modify `tpl/`" instruction, matches the template's documented
  supported environment, and keeps report content portable.
- **Consequences:** All report content is authored to be Overleaf-compatible. If local building
  becomes a requirement, a separate, user-approved task will modernize `tpl/` for current TeX
  distributions (tracked in `PROJECT_TODO.md`).

## ADR-013 — Phase 1 is strictly business; technical design deferred

- **Status:** Accepted (user instruction, Phase 1 review)
- **Context:** Phase 1 (Business Analysis) had begun to include some technical framing (enforcement
  mechanics, entity-schema notation). The user requires Phase 1 to remain strictly business.
- **Decision:** `docs/BUSINESS_ANALYSIS.md` and report chapters 1–2 contain **business content
  only** (actors, capabilities, workflows, business rules, financial/KPI model, full Excel workbook
  analysis). All technical design is **deferred**: system/API architecture + security enforcement +
  dynamic-menu mechanics → **Phase 2**; database/ERD/schema → **Phase 3**; UML → **Phase 4**;
  frontend structure → **Phase 8**.
- **Reason:** Clean separation of *what/why* (business) from *how* (technical); keeps each phase
  reviewable on its own terms.
- **Consequences:** Capabilities/permissions are described as business responsibilities, not
  technical artifacts; "business objects" are a domain glossary, not a data model. Added to the
  Business Analysis: a **complete 21-sheet workbook analysis** and a **formula→calculation coverage
  map** proving all 2 088 Excel formulas are accounted for.

## ADR-014 — Native local deployment (no Docker)

- **Status:** Accepted (user decision, Phase 2)
- **Context:** The build machine already runs PostgreSQL 17 (service `postgresql-x64-17`), Node 22,
  Maven 3.9.16, and Docker 28.4.0.
- **Decision:** Run and deploy **natively**: backend via `./mvnw spring-boot:run` (Java 21), frontend
  via `ng serve` (Angular 21), database on the installed **PostgreSQL 17** service. **Docker is not
  used.**
- **Reason:** Fastest iteration on the available toolchain; no container overhead; uses the running
  Postgres service directly.
- **Consequences:** Spring profiles `dev`/`prod`; externalized config; DB credentials via env vars.
  Docker can be added later without architectural change.

## ADR-015 — Hybrid KPI architecture (recompute-on-change + snapshots)

- **Status:** Accepted (user decision, Phase 2)
- **Context:** The Excel does heavy month-by-month KPI math and tracks "Evolution KPIs" over time;
  dashboards must be fast (NFR-001 ≤2 s).
- **Decision:** Maintain a **`KpiSnapshot` per project and per month** (plus a current rollup).
  Recompute KPIs whenever **workload (plan/actual), billing, mission costs, TCC rates, or
  budget/avenants** change. **Store KPI history** for trend analysis. **Dashboards read snapshots**,
  not live recomputation. Support KPI evolution charts like the Excel "Evolution KPIs".
- **Reason:** Fast dashboards, preserved KPI history, executive-reporting friendly, faithful to the
  Excel business model.
- **Consequences:** Domain events trigger a `KpiRecalculationService`; read path is snapshot-only;
  the month-based time-series model is designed in Phase 3.

## ADR-016 — Modular monolith, layered, feature-based

- **Status:** Accepted
- **Context:** ~18 feature modules, one team, strong consistency needed for financial data; project
  principle = simplicity first.
- **Decision:** A single Spring Boot deployable, partitioned into **feature modules**, each layered
  **Controller → Service → Repository → Domain**. Frontend mirrors the same feature structure.
- **Reason:** Simplest architecture that meets the needs; easy to build/test/defend; clean path to
  extract services later if ever required. Microservices rejected as over-engineering.
- **Consequences:** Feature-based packaging (`com.pms.<feature>`); controllers never touch
  repositories; entities never cross the API boundary (DTOs do).
- **Module boundary rules (enhancement):**
  1. **No cross-module repository access** — a module never injects or queries another module's
     repository; a feature's repositories/entities are package-private to that feature.
  2. **Communicate through application services** — cross-module interaction goes through the other
     module's published **service interface** (returning DTOs), never its internal classes.
  3. **Explicit domain boundaries** — each module owns its aggregates; shared concepts live in
     `common`/`currency`/`parameter`; no entity is co-owned by two modules.
  4. **Avoid tight coupling** — prefer events for fan-out side-effects (e.g. KPI recompute on
     workload/billing changes) over direct synchronous chains; no circular module dependencies.
  5. Boundaries are verifiable (package-private discipline; optionally Spring Modulith in tests).

## ADR-017 — JWT Authentication with Token-Version Revocation

- **Status:** Accepted (revised — **supersedes** the earlier "fully stateless / token-embedded
  permissions / changes propagate within one TTL" wording)
- **Context:** Dynamic RBAC must be performant **and** let an Administrator's changes take effect
  **immediately**: user deactivation (BR-007), permission/role revocation, forced logout. A purely
  stateless JWT cannot be withdrawn before expiry. The system is therefore **stateless for
  authentication, with a deliberate, lightweight stateful check for authorization** — it is *not*
  fully stateless, and that is intentional.
- **Decision:**
  - **Access token** — short-lived (~15 min) signed JWT carrying `userId`, `role`, and
    `token_version`. It proves *identity*; it is **not** the authority source for permissions.
  - **Per-request check** — verify signature + expiry, then load the user's **security context** from
    a small in-memory cache (`active`, `token_version`, `permissions[]`). **Reject** if
    `active = false` or the token's `token_version` ≠ the current value; otherwise authorize the
    operation against the context's permissions.
  - **Refresh token** — longer-lived, **rotated**, stored in an **HttpOnly, Secure cookie**; issues
    new access tokens and reloads identity/version.
  - **Token version** — integer on the user; **bumped** on deactivation, forced logout, password
    change, role change, and permission-set change → all outstanding access tokens are invalidated at
    once and the cached context is refreshed.
  - **User deactivation (BR-007)** — `active = false` + version bump → the next request from any
    outstanding token is rejected **immediately**.
  - **Permission revocation** — changing `role_permissions` invalidates the affected users' cached
    context (and bumps their version) → effective **immediately**, not "within a TTL".
  - **First-login flow** — while `first_login = true`, the security filter permits only
    `change-password` and `logout` (`403 MUST_CHANGE_PASSWORD` otherwise); after the change,
    `first_login = false` and the version is bumped.
- **Reason:** Achieves *immediate* dynamic authorization (the project's core mandate) and BR-007
  compliance, while keeping the request path fast (in-memory cache, not a DB hit per request).
  Folding permissions into the same per-request security context we already need for revocation is
  simpler than running two mechanisms (token-embedded permissions **plus** a separate revocation
  check) and removes the "stale up to one TTL" caveat.
- **Consequences:** a small per-user security-context cache invalidated on security events; BCrypt
  password hashing; tokens never carry secrets. Authentication scales statelessly; authorization
  carries one cheap, cache-backed lookup. The client keeps the access token in memory and the refresh
  token in an HttpOnly cookie.

## ADR-018 — MapStruct is the mandatory entity↔DTO mapping strategy

- **Status:** Accepted (user instruction, Phase 2)
- **Context:** Every feature converts between JPA entities and DTOs at the service/web boundary.
  Mapping can be hand-written, done with reflection-based libraries, or generated at compile time.
- **Decision:** **MapStruct is mandatory** for all entity↔DTO mapping. Hand-written mappers and
  reflection-based mappers (e.g. ModelMapper) are **not allowed**. Each feature defines a
  `@Mapper(componentModel = "spring")` interface; mappings are generated at compile time and
  injected as Spring beans.
- **Reason:** Compile-time, type-safe, fast (no reflection), explicit and reviewable; mapping
  mismatches fail the build rather than at runtime; keeps controllers/services free of boilerplate.
- **Consequences:** `backend/pom.xml` adds the MapStruct dependency **and** its annotation processor
  to the compiler plugin (alongside Lombok's processor if Lombok is used, ordering matters). A
  consistent mapper package per feature (`<feature>/mapper`). Unmapped target fields must be
  explicit (`@Mapping(ignore = true)`), enforced via `unmappedTargetPolicy = ERROR`.

## ADR-019 — Flyway for database migrations; schema is the source of truth

- **Status:** Accepted (Phase 3; default chosen — switchable on request)
- **Context:** The schema must be versioned, reproducible on the installed PostgreSQL 17, and
  reviewable. Options: Flyway (SQL-first, versioned), Liquibase (changelog/XML-YAML), or JPA
  `ddl-auto` (entity-driven).
- **Decision:** Use **Flyway** with **plain SQL** migrations (`V1__*.sql`, `V2__*.sql`, …). The
  **database schema (DDL) is authoritative**; JPA runs with `hibernate.ddl-auto=validate` (entities
  must match the schema, never generate it).
- **Reason:** SQL-first gives explicit, readable, jury-friendly control over constraints, indexes and
  the financial data model; integrates natively with Spring Boot; simpler than Liquibase for this
  project. `validate` prevents entity/schema drift.
- **Consequences:** In Phase 3 the DDL lives in `docs/schema.sql` (design). In Phase 5 it becomes the
  first Flyway migration under `backend/src/main/resources/db/migration/`. Seed/reference data
  (roles, permissions, default matrix, currencies, parameters) ships as a versioned migration too.

## ADR-020 — Financial precision rules (currency, effective budget, mission cost)

- **Status:** Accepted (Phase-3 self-review correction)
- **Context:** The first Database-Design draft was vague on *which exchange rate* applies, on the
  currency of stored KPIs, on whether "budget" includes avenants, and on mixed-currency mission
  costs — any of which silently distorts margins.
- **Decision:**
  1. **Rate-date rule:** every conversion uses the `exchange_rate` effective at the amount's
     **business date** (billing → milestone/invoice date; monthly cost → that month; mission → mission
     date). `kpi_snapshots` carries an explicit `currency` and stores all amounts in the reporting
     currency resolved at computation time.
  2. **Effective budget:** `effective_budget = initial budget + validated avenants`, computed into
     `kpi_snapshots.effective_budget`; `budget_remaining = effective_budget − EAC` (Excel "budget
     total, avenants inclus").
  3. **Mission cost currency:** `missions.cost_currency` is the currency of ticket/stamp/transport and
     of `total_cost`; perdiem is fx-converted into it before summing — no silent currency mixing.
- **Reason:** Deterministic, auditable financials; removes ambiguity that would have surfaced as
  wrong margins in Phase 16.
- **Consequences:** schema additions (`kpi_snapshots.currency`, `effective_budget`;
  `missions.cost_currency`); the KPI engine applies the rate-date rule; also added composite
  `(project_id, month)` indexes and a JPA `@Version` optimistic-lock convention.

## ADR-021 — Authorization scope enforcement (permission ∧ data scope)

- **Status:** Accepted
- **Context:** Permissions answer *may the user perform this action?* but not *on which data?*. Several
  rules are data-scoped: developers have **no** financial data (BR-050); Directors see all projects,
  PMs only their projects, developers only their own workload (BR-062/063/064). Without a systematic
  mechanism, a single forgotten predicate leaks another project's financials.
- **Decision:** Effective authorization = **Permission ∧ Scope**.
  - **Permission** (ADR-001) — the capability check, e.g. `hasAuthority('VIEW_FINANCIALS')`.
  - **Scope** — a data-access predicate applied **systematically** at the repository boundary via
    reusable `ScopeSpecification`s, derived from **capabilities + relationships** (not hardcoded role
    names, staying true to ADR-001):
    - **All-access capability** (`VIEW_ALL_PROJECTS`) → no scope filter (portfolio-wide).
    - **Active-PM relationship** → `project.activePM = currentUser`.
    - **Assignment relationship** → active `team_assignment.resource = currentUser`.
    - No applicable capability/relationship → **deny**.
  - **Default role outcomes** (from the default matrix): **DIRECTOR** = all projects (holds
    `VIEW_ALL_PROJECTS`); **PROJECT_MANAGER** = only projects where they are the active PM;
    **DEVELOPER** = only assigned projects, non-financial only; **ADMINISTRATOR** = administrative
    scope only, **no** project financial/operational data unless a capability is explicitly granted.
- **Permission vs Scope:** permission is global ("can view financials at all"); scope restricts the
  *rows* ("for these projects"). A PM holds `VIEW_FINANCIALS` (permission) but only for managed
  projects (scope).
- **Leakage prevention:** scope is enforced at the **data layer** (not ad-hoc per controller); the
  all-access bypass requires an explicit capability; **unscoped reads of scoped aggregates are
  forbidden** (code-review failure); every client-supplied id is re-validated against scope (no IDOR);
  isolation is asserted by tests.
- **Reason:** Enforces BR-050/062/063/064 systematically while staying permission-driven and dynamic.
- **Consequences:** a scope-resolver / `ScopeSpecification` component; scoped aggregates (project,
  workload, billing, mission, KPI, governance) are reachable only through scoped queries.

## ADR-022 — Separation of User and Resource

- **Status:** Accepted (formalizes the existing schema split: `resources.user_id` nullable)
- **Context:** Two notions are easily conflated: the **identity that logs in** and the **person whose
  effort and cost are tracked**. The Excel TCC/workload lists include people who are not necessarily
  platform users.
- **Decision:** Two linked entities:
  - **User** — authentication & authorization identity (email, password, role, permissions); exists
    to **log in and be authorized**.
  - **Resource** — an operational person who **consumes workload (JH) and carries a cost (TCC)**;
    exists to be **planned, assigned, and costed**. `resources.user_id` is a **nullable** link.
  - A person may be **User only**, **Resource only**, or **both**.
- **Examples:**

  | Person | User | Resource | Why |
  |--------|:----:|:--------:|-----|
  | Internal developer | ✔ | ✔ | logs in *and* logs effort/cost |
  | Director / PM (if they bill time) | ✔ | ✔ | logs in; may also consume workload |
  | Administrator | ✔ | ✖ | logs in; consumes no project workload |
  | External consultant | ✖ | ✔ | planned/costed but has no account |
  | Future subcontractor | ✖ | ✔ | costed via TCC; no login |

- **Why separated:** (1) not everyone who incurs cost logs in (externals, subcontractors); (2) not
  every login is a billable resource (pure Administrator); (3) decouples security identity from
  financial/operational identity, so auth changes don't disturb cost history and vice-versa;
  (4) supports onboarding external people into planning/costing without creating accounts.
- **Consequences:** `tcc`, `workload_plan`, `actual_workload`, `team_assignments`, `missions`
  reference `resource_id`; `submitted_by`/`created_by`/`director_id`/`manager_id` reference `user_id`;
  a developer's submission resolves their `resource` via `resources.user_id`. A Resource can later be
  promoted to a User (link set) without data migration.

## ADR-023 — UML tooling: PlantUML as source of truth, Mermaid as readable mirror

- **Status:** Accepted (user decision, Phase 4)
- **Context:** UML diagrams must be version-controlled, diff-able, traceable to the prior phases, and
  renderable into the LaTeX report — while staying easy to preview.
- **Decision:** Authoritative UML lives as **PlantUML** `.puml` files under `docs/uml/`, rendered to
  **PNG/SVG** for the LaTeX report. Each diagram is mirrored as **Mermaid** inside the `.md` docs for
  instant preview (VS Code/GitHub). **PlantUML is the source of truth; Mermaid is a convenience mirror
  kept in sync.**
- **Reason:** text-based and version-controllable (engineering standard); Mermaid gives zero-setup
  preview without a plugin.
- **Consequences:** `docs/uml/` holds the `.puml` sources; rendered images go under the report
  `img/`; diagrams trace to Business Analysis (actors/use cases), Architecture (components/flows) and
  Database Design (class ↔ schema). Rendering uses `plantuml.jar` (+ Graphviz for class/use-case) or
  the PlantUML server.

## ADR-024 — Two-level UML: report-level (Level 1) vs engineering-level (Level 2)

- **Status:** Accepted (user directive — *PFE-first principle: readability over completeness*)
- **Context:** The first UML pass produced engineering-grade diagrams that were technically correct
  but too dense for a PFE report and an oral defense — too many classes, too many attributes, too
  many controllers/repositories/mappers, too many sequence interactions.
- **Decision:** Maintain **two levels** of UML, both authored in PlantUML:
  - **Level 1 — Report UML** in `docs/uml/`: simple, business-oriented, pedagogical. **Only Level 1
    appears in the PFE report.** Constraints: every diagram fits one A4 page; a jury member must
    understand it in under 30 seconds; show only domain entities (no Controller/Service/Repository/
    DTO/Mapper/Config/Filter/Event/Util/Validator/Exception); show only major attributes and major
    relationships; class diagrams are **split by domain** (Security · Project & Team · Financial ·
    Charges & KPI); use cases ≤ 12–15; sequences ≤ ~10 interactions.
  - **Level 2 — Engineering UML** in `docs/uml/engineering/`: the full detailed model (single big
    class diagram with audit fields, full sequence diagrams with all participants and alt branches).
    Reference only — **not** in the report.
- **Rule:** when readability and completeness conflict in a report diagram, **readability wins**.
- **Consequences:** report diagrams are simple and immediately readable; engineering detail is
  preserved for maintenance; both levels stay in sync because each Level-1 file is the simplified
  view of its Level-2 counterpart (same names, same structure, less detail).

---

## Decision index

| ADR | Title | Driver |
|----|-------|--------|
| 001 | Dynamic RBAC (Role→Permission→Module) | Core requirement |
| 002 | Full Excel financial model | D1 |
| 003 | Actual workload: manual + KIMAI | D2 |
| 004 | Full scope (+5 extra modules) | D3 |
| 005 | Default mapping: ASSIGN_DEVELOPER → DIRECTOR + PM | D4 (overrides SRS/UC/Plan) |
| 006 | Tech stack | Constraints |
| 007 | Multi-currency + stored rates | Excel reality |
| 008 | Configurable parameters | Evolution requirement |
| 009 | Soft delete + audit | BR/NFR |
| 010 | JWT + refresh + first-login | FR/UC/BR |
| 011 | Source-of-truth priority | Governance |
| 012 | Report toolchain: Overleaf target, tpl/ pristine | Build/template |
| 013 | Phase 1 strictly business; technical deferred | Scope/process |
| 014 | Native local deployment (no Docker) | Deployment |
| 015 | Hybrid KPI architecture (snapshots + recompute) | KPI engine |
| 016 | Modular monolith, layered, feature-based | Architecture style |
| 017 | JWT authentication with token-version revocation | Security |
| 018 | MapStruct mandatory for entity↔DTO mapping | Backend/mapping |
| 019 | Flyway SQL migrations; schema is source of truth | Database |
| 020 | Financial precision: rate-date, effective budget, mission currency | Database/finance |
| 021 | Authorization scope enforcement (permission ∧ scope) | Security |
| 022 | Separation of User and Resource | Domain model |
| 023 | UML tooling: PlantUML source of truth + Mermaid mirrors | Process/UML |
| 024 | Two-level UML: Level 1 (report, simple) vs Level 2 (engineering) | Process/UML |
