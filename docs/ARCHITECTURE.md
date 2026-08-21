# ARCHITECTURE — PMS

**Project:** Centralized IT Project Management and Financial Control Platform (PMS)
**Phase:** 2 — Architecture Design · **Status:** For validation · **Date:** 2026-06-14
**Author role:** Solution Architect / Technical Lead
**Inputs:** [BUSINESS_ANALYSIS.md](BUSINESS_ANALYSIS.md) (Phase 1) · [DECISIONS.md](../DECISIONS.md)
(ADR-001…015) · BRS / SRS / Use Cases.

> **Scope of this document (the "how").** Phase 2 designs the technical architecture only:
> global structure, security, dynamic RBAC enforcement, backend, frontend, KPI engine,
> multi-currency, configurable parameters, and deployment. **No application code** is written until
> this phase is approved (gate). Database schema/DDL is **Phase 3**; UML is **Phase 4**.

---

## 1. Architectural drivers & constraints

| Driver | Source | Architectural implication |
|--------|--------|---------------------------|
| Dynamic authorization `Role → Permission → Module` | ADR-001 (core) | Permission-based security everywhere; no role-name branching |
| Full Excel financial/KPI model | ADR-002 / D1 | Dedicated KPI engine over a month-based time series |
| Actual workload manual **+** KIMAI | ADR-003 / D2 | Workload records carry a `source`; an import boundary |
| Full scope (+5 governance modules) | ADR-004 / D3 | Feature-based modular structure that scales to ~18 modules |
| Multi-currency (FCFA/TND/EUR) | ADR-007 | Centralized, dated currency conversion |
| Configurable business constants | ADR-008 | Runtime parameter store; nothing hardcoded |
| Soft delete + audit | ADR-009 | Cross-cutting auditing + logical deletion |
| JWT access + refresh, first-login | ADR-010 | Stateless security; forced password change gate |
| Tech stack | ADR-006 | Java 21 / Spring Boot 3.3 / PostgreSQL 17 / Angular 21 / Node 22 / Maven 3.9.16 |
| Deployment | ADR-026 | Containerized (Docker Compose: db + backend + nginx); native run kept as the dev loop (ADR-014, superseded) |
| Hybrid KPI snapshots | ADR-015 | Recompute-on-change + per-project/per-month snapshot store |
| NFRs | SRS | ≤2 s responses, clean architecture, auditability, async logging |

---

## 2. Architecture style (ADR-016)

**Modular monolith, layered.** A single Spring Boot deployable, internally partitioned into
**feature modules**; each module is layered (Controller → Service → Repository → Domain). Chosen over
microservices for **simplicity first** (project principle): one team, one database, strong
consistency for financial data, far simpler to build, test, and defend in a PFE — while feature
boundaries keep it maintainable and leave a clean path to extract services later if ever needed.

**Module boundaries** are kept by package discipline (a feature exposes a service API; its
repositories and entities are package-private to that feature). Cross-feature calls go through
service interfaces, not repositories — so the monolith stays *modular*, not a big ball of mud. See
**ADR-016** for the full boundary rules (no cross-module repository access, communicate via services,
explicit domain boundaries, prefer events over tight coupling).

---

## 3. High-level / global architecture

Three tiers, native runtime:

```
┌─────────────────────────────────────────────────────────────────────┐
│  CLIENT — Angular 21 SPA (Bootstrap 5, Chart.js, French)              │
│  • Login / first-login change-password                               │
│  • Dynamic menu built from the user's permissions                    │
│  • Route guards + permission directive (UI hidden if not permitted)  │
└───────────────▲───────────────────────────────────────┬─────────────┘
                │  HTTPS / REST + JWT (Authorization: Bearer)          │
┌───────────────┴───────────────────────────────────────▼─────────────┐
│  SERVER — Spring Boot 3.3 (Java 21) — Modular Monolith               │
│                                                                      │
│  Web layer:   REST controllers · JWT filter · @PreAuthorize guards   │
│  Service:     business logic · KPI engine · currency · parameters    │
│  Repository:  Spring Data JPA                                        │
│  Cross-cutting: auditing · soft-delete · validation · exceptions ·   │
│                 async structured logging · KIMAI import boundary     │
└───────────────────────────────────────────────────────┬─────────────┘
                                                         │ JDBC
┌────────────────────────────────────────────────────────▼────────────┐
│  DATA — PostgreSQL 17 (installed Windows service postgresql-x64-17)   │
└──────────────────────────────────────────────────────────────────────┘
                ▲ (future) import
        ┌───────┴────────┐
        │  KIMAI (ext.)  │  actual workload import (ADR-003)
        └────────────────┘
```

### 3.1 Feature modules (backend & frontend mirror these)
`auth` · `user` · `role` · `permission` · `project` · `team` · `tcc` · `workload-plan` ·
`actual-workload` · `billing` · `mission` · `kpi` · `reporting` · `risk` · `deliverable` ·
`stakeholder` · `change` · `avenant` — plus shared/cross-cutting: `security`, `currency`,
`parameter`, `audit`, `common`.

---

## 4. Backend architecture

### 4.1 Layering (per feature)
```
controller/   REST endpoints, request/response DTOs, validation, no business logic
service/      business logic, transactions (@Transactional), orchestration
repository/   Spring Data JPA interfaces
domain/       entities + enums + domain rules
dto/ + mapper/  DTOs and entity↔DTO mapping (MapStruct — MANDATORY, ADR-018)
```
Rule: controllers never touch repositories directly; entities never leave the service layer
(DTOs cross the boundary). **All entity↔DTO conversion uses MapStruct** (compile-time generated,
type-safe mappers); hand-written mapping is **not allowed** (ADR-018). Each feature owns a
`@Mapper(componentModel = "spring")` interface.

### 4.2 Package structure (feature-based)
```
com.pms
 ├─ security/        JWT, filters, permission evaluation, current-user
 ├─ common/          base entity (audit + soft-delete), error model, pagination, utils
 ├─ currency/        Currency, ExchangeRate, ConversionService
 ├─ parameter/       Parameter store + typed accessors
 ├─ user/  role/  permission/        dynamic RBAC
 ├─ project/ team/ tcc/
 ├─ workloadplan/ actualworkload/
 ├─ billing/ mission/
 ├─ kpi/             KPI engine + snapshots
 ├─ risk/ deliverable/ stakeholder/ change/ avenant/
 └─ reporting/
```

### 4.3 Cross-cutting concerns
- **Auditing (ADR-009):** a `BaseEntity` carries `createdBy/At`, `modifiedBy/At` via JPA auditing;
  `active`/`deletedAt` for **soft delete**. Repositories filter on `active`.
- **Validation:** Bean Validation (`@NotNull`, `@Positive`, `@Email`…) on DTOs; business invariants
  (e.g. `Σ milestone % ≤ 100`, `start < end`) in services.
- **Exception handling:** a single `@RestControllerAdvice` maps exceptions to a consistent error
  body `{ timestamp, status, error, code, message, path }`.
- **Logging (NFR):** **asynchronous, structured** (JSON) via SLF4J/Logback async appender; levels
  ERROR/WARN/INFO/DEBUG; no sensitive data; correlation id per request. Non-blocking.

---

## 5. Security architecture (ADR-010, ADR-017)

### 5.1 Authentication flow (JWT access + refresh)
```
1. POST /api/auth/login {email, password}
2. Server verifies BCrypt hash; if user.active=false → 403
3. Issues:  access token  (short TTL ~15 min, carries userId + role + token_version)
            refresh token (longer TTL, rotated, HttpOnly cookie)
4. If user.firstLogin = true → response flags MUST_CHANGE_PASSWORD; only
   /api/auth/change-password is allowed until firstLogin=false
5. Client calls APIs with  Authorization: Bearer <access>
6. On 401/expiry → POST /api/auth/refresh → new access token (permissions reloaded fresh)
7. POST /api/auth/logout → refresh token invalidated
```

### 5.2 Token model decision (ADR-017)
The access token carries **identity only** (`userId`, `role`, `token_version`) — **not** the
permission set. On each request the server validates signature/expiry, then loads the user's
**security context** from a small in-memory cache (`active`, `token_version`, `permissions`): it
rejects the token if the user is inactive or the version is stale, and authorizes against the cached
permissions. The cache is invalidated on security events, so **permission changes and revocations
take effect immediately** — no "wait one TTL". Authentication remains stateless; only this single,
cheap, cache-backed authorization lookup is stateful — by design (so the system is *not* fully
stateless, and that is intentional).

### 5.3 Token storage (frontend)
Access token kept **in memory** (Angular service); refresh token in an **HttpOnly, Secure cookie**
to reduce XSS exposure. Passwords are **never** stored or logged in clear; **BCrypt** at rest.

### 5.4 Immediate revocation & first-login enforcement *(correction)*
A stateless JWT cannot be withdrawn before it expires — so the access token alone cannot enforce a
**deactivation** (it would stay valid until expiry, violating **BR-007**). To close this:
- Each user has a **`token_version`** (security stamp), carried in the access token. The per-request
  filter (§5.2) rejects any token whose version is stale **or** whose user is `active = false`, via
  the in-memory security-context cache (not a full DB query per request).
- `token_version` is **incremented on**: user deactivation, forced logout, password change, role
  change, and permission-set change → those events take effect **immediately**.
- Because permissions are served from the cache-backed security context (§5.2), **all** permission
  changes — grants *and* revocations — are immediate on cache invalidation; there is no "stale up to
  one TTL" window.

**First-login enforcement** is a real filter, not just a flag: while the authenticated user's
`firstLogin = true`, the security filter **blocks every endpoint except** `change-password` and
`logout` (HTTP 403 with a `MUST_CHANGE_PASSWORD` code), regardless of what the client requests.

**Login hardening *(correction)*:** the login endpoint is protected against brute force —
rate-limiting and a temporary account lockout after repeated failed attempts — to satisfy the
"unauthorized access attempts must be blocked" rule (BR-058).

---

## 6. Dynamic RBAC enforcement architecture ⭐ (ADR-001 realization)

This is the heart of the platform. Authorization keys off **permissions**, never role names, at
three layers.

### 6.1 Model recap
`User —< has one >— Role —< RolePermission >— Permission`. A user's effective authorities =
the permissions of its role.

### 6.2 Backend enforcement — method security
Every protected operation declares the **permission** it needs:
```java
@PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")
public TeamAssignment assignDeveloper(...) { ... }
```
Global method security is enabled; the JWT filter populates the `Authentication` with the permission
authorities from the user's **security context** (§5.2), not from the token itself. **No code ever
branches on a role name.**

### 6.3 Dynamic menu + UI capability endpoint
```
GET /api/me/context  →  { user, role, permissions[], menu[] }
```
The server returns the permitted navigation (menu items each gated by a permission). The frontend
**builds the menu from this response** — it is never hardcoded. The same permission list drives the
permission directive (§9.4).

### 6.4 Data-scope filtering (on top of permissions) — ADR-021
Some rules are *scoped*: "PM sees financials only for **managed** projects" (BR-063), "Developer sees
only **own** workload" (BR-064). Permission = *may do the action*; scope = *on which data*.

This is enforced **systematically**, not ad-hoc per query (a single forgotten predicate would leak
another PM's financials). Scope is applied as **reusable query specifications** at the repository
boundary: read queries for scoped aggregates (project, workload, billing, mission, KPI) go through a
`ScopeSpecification` that injects the ownership predicate (`project.manager = :me`,
`assignment.developer = :me`) derived from the current user — unless the user holds an
"all-projects" capability (e.g. the Director's `VIEW_ALL_PROJECTS`). A direct unscoped find of a
scoped aggregate is treated as a bug.

### 6.5 The D4 example, end-to-end
`ASSIGN_DEVELOPER` is seeded to **DIRECTOR and PROJECT_MANAGER**. To give it to another role
tomorrow, an Administrator adds one `role_permission` row — the `@PreAuthorize` guard, the menu
endpoint, and the frontend directive all honor it immediately (within token TTL). **Zero code
change.** This is the architectural payoff of ADR-001.

---

## 7. KPI engine architecture ⭐ (ADR-002 + ADR-015 hybrid)

### 7.1 Strategy: recompute-on-change + snapshots
- **`KpiSnapshot`** is stored **per project and per month** (and a project-level "current" rollup),
  holding the full indicator set: EV %, ETC, EAC, Coût Actuel, Coût Prévisionnel, Cumul CA
  Production, FAE, RAF, Dérive, the 3 margins, Budget restant, Avancement facturation %, Delivery %.
- **Recompute triggers:** whenever **workload (plan/actual), billing, mission costs, TCC rates, or
  budget/avenants** change, the affected project's KPIs are recomputed (domain events →
  `KpiRecalculationService`).
- **Timing & concurrency *(correction)*:** recomputation runs **asynchronously, after the triggering
  transaction commits** (so writes stay fast and KPIs never read stale uncommitted data), and is
  **serialized/debounced per project** (a single in-flight recompute per project; rapid successive
  changes coalesce into one run) to avoid races and redundant work. Dashboards are therefore
  *eventually consistent* — typically within a second or two of a change.
- **Read path:** dashboards and reports **read snapshots** — never recompute on the request path →
  fast, ≤2 s (NFR-001).
- **History:** snapshots are retained per month, enabling **KPI evolution charts** (mirroring the
  Excel "Evolution KPIs"/Dashboard sheets).

### 7.2 Computation pipeline (business logic from §7–§8 of the Business Analysis)
> **Correction — labor vs. other costs.** In the Excel, `Coût Actuel` (labor) `= Σ(JH × TCC)` is
> **only** the workforce cost; **missions and other frais are a separate cost stream**. The *total*
> cost and the *margins* aggregate **both**. They must not be folded into the labor `Coût Actuel`.
> So: `Coût total réel = Coût Actuel (labor) + Autres coûts (missions, frais)`, and
> `EAC = Coût total réel + ETC` (ETC may likewise carry forecast other-costs).

```
TCC(resource, year)  ─┐
WorkloadPlan (JH/mo) ─┼─► Coût Prévisionnel (labor) ─┐
ActualWorkload (JH/mo)┼─► Coût Actuel (labor)        ├─► Coût total réel ─┐
Missions / autres ────┴─► Autres coûts ──────────────┘                    ├─► EAC = Coût total + ETC
Budget / Avenants ──────► Budget restant, Marge nette EAC  ◄──────────────┘
EV %  ──────────────────► Cumul CA Production ──► FAE, Marge actuelle ( = CA Prod − Coût total réel)
Billing milestones ─────► Avancement facturation %
            ▼
        KpiSnapshot(project, month)  ──► dashboards / reporting (read-only)
```
All monetary inputs pass through the **currency conversion service** (§8) before aggregation;
all constants (overhead, working-days, PPR, hours/day) come from the **parameter store** (§8.2).

---

## 8. Multi-currency & configurable parameters

### 8.1 Currency (ADR-007)
`Currency` + dated `ExchangeRate(from, to, rate, date)`. A `ConversionService.convert(amount, from,
to, asOf)` centralizes all conversion; reporting currency = **TND** (a parameter). Monetary values
store amount + currency. No conversion factor is hardcoded.

### 8.2 Parameters (ADR-008)
A `Parameter` store (key → typed value) read at runtime by the TCC, KPI, billing, and risk services:
`tcc.overhead` (0.70), `tcc.workingDaysPerYear[year]` (22/20), `workload.hoursPerDay` (8),
`finance.pprPercent` (0.05), `billing.maxTotalPercent` (1.00), `reporting.currency` (TND), and the
risk probability/severity/treatment scales. Administered via `MANAGE_PARAMETERS`.

---

## 9. Frontend architecture (Angular 21)

### 9.1 Structure (feature-based, standalone components)
```
src/app
 ├─ core/        auth service, http interceptor, guards, current-user, menu service
 ├─ shared/      UI components, permission directive, pipes, French i18n, Bootstrap theme
 ├─ layout/      shell, dynamic sidebar/menu, header
 └─ features/    auth, users, roles, projects, teams, tcc, workload, actuals,
                 billing, missions, kpi-dashboards, risks, deliverables, …
```

### 9.2 Auth interceptor & refresh
An HTTP interceptor attaches `Authorization: Bearer <access>`; on 401 it calls `/auth/refresh` once
and retries, else routes to login.

### 9.3 Route guards
- `authGuard` — must be authenticated; redirects to login.
- `firstLoginGuard` — if `firstLogin`, force the change-password route.
- `permissionGuard(data.permission)` — route allowed only if the user holds the permission.

### 9.4 Permission directive (UI visibility)
```html
<button *hasPermission="'ASSIGN_DEVELOPER'">Affecter un développeur</button>
```
Elements render only if the current user holds the permission — the UI mirror of the backend guard.

### 9.5 Dynamic menu & dashboards
The sidebar is built from `GET /api/me/context.menu`. KPI dashboards use **Chart.js** (project view
for PM, executive/portfolio view for Director, personal view for Developer — no financials).
UI is **French**, responsive (Bootstrap 5).

---

## 10. API design conventions
- REST under `/api`; resource-oriented; standard verbs; pagination `?page&size`; sorting `?sort`.
- DTOs in/out (entities never serialized directly).
- Consistent error body (§4.3); validation errors return field-level details.
- Auth endpoints under `/api/auth`; current-user context under `/api/me`.

---

## 11. Deployment & runtime architecture (ADR-026 — containerized; ADR-014 superseded)

Two modes coexist. **Native** remains the primary *development* loop; **containers** are the
deployment strategy. Host ports are offset so both can run simultaneously.

**A. Native development loop**
```
PostgreSQL 17   → existing Windows service `postgresql-x64-17` (DB: pms_dev)   :5432
Backend         → ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev        :8090
Frontend        → ng serve                                                      :4200
```

**B. Containerized stack (ADR-026)**
```
browser :8081 ──► frontend  nginx 1.27-alpine        (serves the Angular build,
                     │                                reverse-proxies /api)
                     ▼
        :8091 ──► backend   Spring Boot, profile prod                    :8080
                     ▼
        :5433 ──► db        postgres:17-alpine  ── volume pms-db-data    :5432
```
- **Single origin.** The application and `/api` are served from the same origin, so **no CORS is
  involved** and the refresh cookie (`SameSite=Strict`) works naturally — unlike the dev loop,
  which is cross-origin by construction.
- **Spring profiles:** `dev` (local Postgres, verbose logging, CORS for :4200), `prod`
  (externalized config, tightened CORS/security, Flyway `validate-on-migrate: true`).
- **Config:** externalized; `DB_PASSWORD` and `JWT_SECRET` have **no fallback value** — a
  misconfigured start fails fast rather than running with a known secret.
- **Schema:** owned by **Flyway** (ADR-019), applied automatically at container startup against a
  pristine volume.
- **Transport:** HTTP on localhost; **HTTPS** is terminated by an upstream reverse proxy in a real
  deployment, at which point `COOKIE_SECURE=true`.
- Operating procedures, environment variables and backup/restore: **[`DEPLOYMENT.md`](DEPLOYMENT.md)**.

---

## 12. Logging & observability (NFR)
Asynchronous, non-blocking, **structured (JSON)** logging; per-request correlation id; levels
ERROR/WARN/INFO/DEBUG; security and audit events logged (without secrets).

**Health probe (ADR-026).** `spring-boot-starter-actuator` exposes `/actuator/health` and
`/actuator/info` only. `/actuator/health` is reachable **unauthenticated** — required by the
container health check — with `show-details: never`, so it returns liveness without disclosing any
infrastructure detail (database state, disk space). All other actuator endpoints stay closed.

---

## 13. New architecture decisions (recorded in DECISIONS.md)
- **ADR-014** — ~~Native local deployment (no Docker)~~ — **superseded by ADR-026**; native
  execution remains the primary development loop.
- **ADR-015** — Hybrid KPI architecture (recompute-on-change + per-project/per-month snapshots + history).
- **ADR-016** — Modular monolith, layered, feature-based packaging **+ module boundary rules** (no
  cross-module repository access; communicate via services; explicit domain boundaries).
- **ADR-017** — JWT authentication with **token-version revocation**: identity-only access token +
  cache-backed security context → permission changes & deactivations effective **immediately** (the
  system is intentionally not fully stateless).
- **ADR-018** — MapStruct is the mandatory entity↔DTO mapping strategy (no hand-mapping).
- **ADR-021** — Authorization **scope** enforcement (permission ∧ data scope; systematic at the
  repository boundary).
- **ADR-022** — Separation of **User** (auth identity) and **Resource** (workload/cost).

---

## 14. Traceability
| Requirement / Phase-1 item | Architecture response |
|---|---|
| ADR-001 dynamic RBAC | §6 (authorities + @PreAuthorize + dynamic menu + scope) |
| Full KPI model (§7–§8 BA) | §7 KPI engine + snapshots |
| Multi-currency | §8.1 conversion service |
| Configurable constants | §8.2 parameter store |
| JWT + first-login (ADR-010) | §5 security flow |
| Soft delete + audit | §4.3 cross-cutting |
| Manual + KIMAI workload | §3 import boundary; `actual-workload` module |
| ≤2 s reads (NFR-001) | §7.1 snapshots read path |

---

## 15. Conclusion — what Phase 2 unlocks
This architecture realizes the dynamic `Role → Permission → Module` mandate, the full Excel KPI model
via a performant snapshot engine, multi-currency and configurable parameters, and a clean
feature-based modular monolith runnable natively on the local toolchain. On approval, **Phase 3
(Database Design)** turns the domain into a concrete PostgreSQL schema, after which implementation
begins with **Phase 5 (Authentication & Dynamic RBAC)**.

*End of Architecture (Phase 2). Awaiting validation before Phase 3 — Database Design.*
