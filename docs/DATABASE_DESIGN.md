# DATABASE DESIGN — PMS

**Project:** Centralized IT Project Management and Financial Control Platform (PMS)
**Phase:** 3 — Database Design · **Status:** For validation · **Date:** 2026-06-14 · **DDL:** [docs/schema.sql](schema.sql)
**Author role:** Database Architect / Technical Lead
**DBMS:** PostgreSQL 17 · **Migrations:** Flyway, schema authoritative (ADR-019)
**Inputs:** [BUSINESS_ANALYSIS.md](BUSINESS_ANALYSIS.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [DECISIONS.md](../DECISIONS.md)

> **Scope.** This phase turns the domain into a concrete relational model: conventions, ERD, entity
> catalog, constraints, the month-based time-series & `KpiSnapshot` model, currency/parameters/audit,
> and seed data. The runnable **DDL** lands in `docs/schema.sql` (next step) and becomes the first
> Flyway migration in **Phase 5**. No application code yet.

---

## 1. Conventions

| Aspect | Convention |
|--------|-----------|
| Tables | `snake_case`, **plural** (`users`, `billing_milestones`) |
| Columns | `snake_case`; PK = `id` (`bigint generated always as identity`) |
| Foreign keys | `<entity>_id` (e.g. `project_id`), `ON DELETE RESTRICT` by default |
| Booleans | `boolean` (e.g. `active`, `validated`, `first_login`) |
| Timestamps | `timestamptz` (`created_at`, `modified_at`, `deleted_at`) |
| Dates / months | `date`; **month buckets stored as the first day of the month** (`2025-03-01`) |
| Money | `numeric(18,3)` **+** a `*_currency` `char(3)` FK → `currencies` (never a bare float) |
| Percentages | `numeric(6,4)` as a ratio (0.16 = 16 %) |
| Enums | `varchar` + `CHECK` constraint (portable with Flyway/JPA; values in UPPER_SNAKE) |
| Audit (ADR-009) | every business table carries `created_by, created_at, modified_by, modified_at` |
| Soft delete (ADR-009) | `active boolean not null default true`, `deleted_at timestamptz null`; queries filter `active = true` |
| Optimistic locking | each entity maps a JPA `@Version` column `version bigint not null default 0` (added per-entity in the Phase-5 migration) to prevent lost updates |

**Base columns** (logical mixin applied to business entities):
`created_by bigint, created_at timestamptz, modified_by bigint, modified_at timestamptz,
active boolean, deleted_at timestamptz`.

---

## 2. Logical model (ERD) — relationship map

```
                 role_permissions (M:N)
   roles ─────<──────────────────>───── permissions
     │1
     │N
   users ──1───────────────────────────── resources (0..1 user per resource)
     │1 (director)                              │1
     │N                                         │N
   projects ──1──< project_manager_assignments >──1── users        tcc (per resource, per year)
     │1                                                              
     ├──1──< team_assignments >──N── resources  (+ staffing %)
     ├──1──< workload_plan >──N── resources     (by month, planned JH)
     ├──1──< actual_workload >──N── resources   (by month, real JH, source)
     ├──1──< billing_milestones >──1──< payments
     ├──1──< missions >──1── resources
     ├──1──< risks >──N── risk_severity_levels / risk_probability_levels
     ├──1──< deliverables
     ├──1──< change_requests
     ├──1──< actions
     ├──1──< stakeholders
     ├──1──< avenants
     └──1──< kpi_snapshots                      (by month + a "current" rollup)

 currencies ──1──< exchange_rates >──1── currencies      parameters (key → typed value)
```

Cardinality summary: a **role** has many **users** (one role per user); **roles ⇄ permissions** is
many-to-many; a **project** has one **director** (user) and, over time, project-manager assignments
(exactly **one active** at a time) and many team/workload/billing/mission/governance children; a
**resource** has one **TCC per year** and may link to a login **user**.

---

## 3. Entity catalog

### 3.1 Security / RBAC
| Table | Purpose | Key columns | Constraints & relationships |
|-------|---------|-------------|------------------------------|
| `users` | Login accounts | `id, first_name, last_name, email, password_hash, role_id, active, first_login, token_version` + audit | `email` UNIQUE; `role_id` → roles; `token_version` for immediate revocation (ADR-017); `first_login` default true |
| `roles` | RBAC roles | `id, name, description` + audit | `name` UNIQUE (ADMINISTRATOR, DIRECTOR, PROJECT_MANAGER, DEVELOPER, …) |
| `permissions` | Business capabilities | `id, code, module, description` | `code` UNIQUE (e.g. `ASSIGN_DEVELOPER`); grouped by `module` |
| `role_permissions` | Role ⇄ Permission | `role_id, permission_id` | PK(`role_id, permission_id`); both FK; the heart of dynamic RBAC |

### 3.2 Reference / configuration
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `currencies` | Currency catalog | `code (char3), name, symbol` | PK `code` (FCFA→`XAF`, TND, EUR) |
| `exchange_rates` | Dated FX rates | `id, from_currency, to_currency, rate, rate_date` | UNIQUE(`from_currency,to_currency,rate_date`); FKs → currencies |
| `parameters` | Configurable constants | `id, param_key, param_value, value_type, description` | `param_key` UNIQUE (overhead, working-days, hours/day, PPR %, billing cap, reporting currency) |
| `risk_probability_levels` | Risk probability scale | `id, label, value, display_client` | from the Excel *Paramètres* (0 / 0.2 / … / 1) |
| `risk_severity_levels` | Risk severity scale | `id, label, value, max_percent, max_tnd` | Mineur…Critique |

### 3.3 Resources & cost rates
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `resources` | People who consume effort | `id, full_name, profile, user_id, active` + audit | `user_id` → users (**nullable**, UNIQUE) — Resource-only people (external consultant/subcontractor) have no user; see **ADR-022** (User vs Resource); profile = BA/Dev/Chef de mission… |
| `tcc` | Charged daily cost basis | `id, resource_id, year, annual_charged_cost, currency, validated` + audit | UNIQUE(`resource_id, year`); validated → immutable (BR-025). Daily TCC derived at runtime from `parameters` |

### 3.4 Projects & organization
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `projects` | Project master | `id, code, name, description, client, contract_id, funder, business_model, engagement_type, budget_amount, budget_currency, start_date, end_date, status, director_id` + audit | `code` UNIQUE; `budget_amount > 0` (BR-015); `start_date < end_date` (BR-016); `status` CHECK ∈ {DRAFT,ACTIVE,ON_HOLD,COMPLETED,CANCELLED}; `engagement_type` ∈ {FP,TM}; `director_id` → users |
| `project_manager_assignments` | PM history | `id, project_id, manager_id, assigned_by, assigned_at, active` | FK project/users; **partial UNIQUE(`project_id`) WHERE active** → one active PM (BR-010/012) |
| `team_assignments` | Developers on a project | `id, project_id, resource_id, staffing_percent, staffing_start, staffing_end, assigned_by, assigned_at, active, removed_at` | FK project/resources; partial UNIQUE(`project_id,resource_id`) WHERE active; staffing period captured; removal keeps history (BR-020) |
| `stakeholders` | Parties prenantes | `id, project_id, category, name, requirements, impact` + audit | `category` ∈ {CLIENT,PARTNER,INTERNAL,…} |

### 3.5 Workload (month-based time series) — see §4
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `workload_plan` | Planned JH per month | `id, project_id, resource_id, month, planned_md` + audit | UNIQUE(`project_id,resource_id,month`); `planned_md >= 0` (BR-028) |
| `actual_workload` | Real JH per month (dual-source) | `id, project_id, resource_id, month, man_days, source, accepted, validated, submitted_by, imported_at` + audit | **UNIQUE(`project_id,resource_id,month,source`)** so MANUAL **and** KIMAI coexist (ADR-003/D2); **partial UNIQUE(`project,resource,month`) WHERE `accepted`** → one reconciled value feeds KPI; `man_days >= 0`; history preserved (BR-037) |

### 3.6 Financial / billing
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `billing_milestones` | Échéances de facturation | `id, project_id, num, description, percent, amount, currency, planned_date, initial_date, actual_date, status, invoice_number, invoice_date` + audit | `amount > 0` (BR-041); `status` ∈ {PENDING,INVOICED,PAID}; `invoice_number/date` keep Excel invoice identity; Σ`percent` ≤ 100 enforced in service (BR-042) |
| `payments` | Règlements | `id, milestone_id, amount, currency, paid_date, comment` + audit | FK → billing_milestones |
| `avenants` | Contract amendments | `id, project_id, avenant_date, jh, guarantee_jh, amount, currency, amount_tnd, ppr_tnd, ppp_tnd, costs_tnd, net_margin_tnd, net_margin_percent` + audit | FK → projects; impacts budget & billing totals |

### 3.7 Missions
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `missions` | Déplacements | `… perdiem_daily, perdiem_currency, fx_rate, ticket_amount, stamp_amount, transport_amount, cost_currency, total_cost` + audit | `start_date < end_date` (BR-046); **`cost_currency`** = currency of ticket/stamp/transport & of `total_cost`; `total_cost` (generated) `= nights·perdiem_daily·fx_rate + ticket + stamp + transport`, perdiem converted into `cost_currency` |

### 3.8 Governance
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `risks` | Registre des risques | `id, project_id, code, label, impact_description, risk_type, severity_id, probability_id, impact_amount_tnd, weighted_impact, treatment, measure, owner, status` + audit | FK severity/probability; `weighted_impact = probability.value · impact_amount`; `treatment` ∈ {REDUCTION,ACCEPTANCE,AVOIDANCE,TRANSFER} |
| `deliverables` | Livrables | `id, project_id, phase, num, designation, planned_date, delivered, delivery_date` + audit | Delivery % = delivered / planned (per project) |
| `change_requests` | Registre des changements | `id, project_id, description, requester, impact, validated, responsible, due_date, done, done_date, effective, closure_date` + audit | FK → projects |
| `actions` | Liste des actions | `id, project_id, code, statement, stakeholder, origin, action_plan, owner, planned_date, progress_percent, real_date, efficacy, comment` + audit | FK → projects |

### 3.9 KPI — see §4
| Table | Purpose | Key columns | Constraints |
|-------|---------|-------------|-------------|
| `kpi_snapshots` | Computed KPI per project & month | `… currency, …, margin_eac, effective_budget, budget_remaining, …` (full set: EV%, ETC, EAC, labor/other/total cost, forecast, CA Production, FAE, RAF, Dérive, 3 margins, billing %, Delivery %) | UNIQUE(`project_id, snapshot_month`); **`currency`** column (reporting = TND); **`effective_budget`** = initial budget + validated avenants; `budget_remaining = effective_budget − EAC` |

---

## 4. Month-based time-series & KpiSnapshot model (ADR-002 / ADR-015)

The Excel is fundamentally a **resource × month** grid. We model that **normalized**, one row per
`(project, resource, month)`, rather than 25 month columns:

- **`workload_plan`** and **`actual_workload`** each store `month` (first-of-month `date`) + a JH
  value → trivial to aggregate, pivot, and extend beyond 25 months.
- **`kpi_snapshots`** stores one row per `(project, snapshot_month)` plus an `is_current` rollup.
  This gives **KPI history** for evolution charts (the Excel "Evolution KPIs") and fast dashboard
  reads (ADR-015). The snapshot separates **`actual_labor_cost`** (Σ JH×TCC) from
  **`actual_other_cost`** (missions/frais) and exposes **`actual_total_cost`** — honoring the
  Phase-2 correction that missions are **not** folded into labor cost.
- **Dual-source actuals (ADR-003/D2):** `actual_workload` keeps one row **per source** (`MANUAL`,
  `KIMAI`) for a cell, plus an `accepted` flag; a partial unique index guarantees exactly **one
  accepted** value per `(project, resource, month)` — that reconciled value is what the KPI engine
  reads. This lets manual entry and KIMAI import coexist and be reconciled instead of colliding.
- Recompute is async, after-commit, **serialized per project** (ARCHITECTURE §7.1).

---

## 5. Multi-currency, parameters, audit

- **Currency (ADR-007):** every monetary column pairs `amount` with a `*_currency` FK; conversions
  use dated `exchange_rates`; reporting currency (TND) is a parameter. **Rate-date rule:** each
  amount is converted using the rate effective at its **business date** — billing uses the
  milestone/invoice date, monthly costs use the month, missions use the mission date. `kpi_snapshots`
  carries an explicit **`currency`** column and stores all amounts in the reporting currency resolved
  at computation time.
- **Effective budget:** `projects.budget_amount` is the *initial* budget; the **effective budget =
  initial + validated `avenants`** is computed into `kpi_snapshots.effective_budget`, and
  `budget_remaining = effective_budget − EAC` (matches the Excel "Budget total, avenants inclus").
- **Mission cost currency:** `missions.cost_currency` defines the currency of ticket/stamp/transport
  and of `total_cost`; the perdiem (in `perdiem_currency`) is fx-converted into `cost_currency`
  before summing — no silent mixing of currencies.
- **Parameters (ADR-008):** `parameters` + the two risk-scale tables hold every tunable constant —
  nothing hardcoded.
- **Audit & soft delete (ADR-009):** base columns on every business table; `created/modified_by`
  reference `users`; logical deletion preserves history (BR-020/037/061).

---

## 6. Reference / seed data (ships as a Flyway seed migration)

- **Roles:** ADMINISTRATOR, DIRECTOR, PROJECT_MANAGER, DEVELOPER.
- **Permissions:** the full capability catalog (Business Analysis §4.1).
- **role_permissions:** the default matrix (Business Analysis §5) — incl. `ASSIGN_DEVELOPER` for
  **Director and PM** (D4).
- **Currencies:** XAF (FCFA), TND, EUR; a few exchange rates.
- **parameters:** overhead 0.70, working-days 22 (2024)/20 (2025), hours/day 8, PPR 0.05, billing
  cap 1.00, reporting currency TND.
- **risk scales:** probability 0/0.2/0.4/0.6/0.8/1; severity Mineur…Critique with max %/TND.
- A bootstrap **Administrator** user (temp password, `first_login = true`).

---

## 7. Integrity & indexes (highlights)
- Unique business keys: `users.email`, `projects.code`, `permissions.code`, `roles.name`,
  `tcc(resource_id,year)`, `(project,resource,month)` on both workload tables.
- Partial unique indexes for "one active": active PM per project, active team assignment.
- FK indexes on all `*_id` columns used in joins (project_id, resource_id, role_id…).
- **Composite `(project_id, month)` indexes** on `workload_plan` and `actual_workload` for fast
  monthly rollups.
- CHECK constraints for enums, `amount > 0`, `start < end`, `percent` range.

---

## 8. Migration strategy (ADR-019)
Flyway, **SQL-first**, schema authoritative; JPA `ddl-auto=validate`. Phase-3 DDL lives in
`docs/schema.sql`; in Phase 5 it becomes `V1__core_schema.sql` (+ `V2__seed.sql`) under
`backend/src/main/resources/db/migration/`.

---

## 9. Traceability (entity ↔ business ↔ Excel)
| Entity group | Business module (BA) | Excel sheet(s) |
|---|---|---|
| users/roles/permissions/role_permissions | Auth & RBAC | — (platform) |
| resources, tcc | TCC Management | TCC |
| projects, project_manager_assignments | Project Management | Fiche identification, S. Contractuelle |
| team_assignments, stakeholders | Team / Stakeholders | Parties Prenantes |
| workload_plan | Workload Planning | Coût Prévisionnel |
| actual_workload | Actual Workload | Coût réel Actualisé |
| billing_milestones, payments | Billing | Avancement_Facturation, Recap Facturation |
| missions | Mission Management | Missions |
| avenants | Avenants | Avenants |
| risks (+ scales) | Risk Register | Base des Risques, Paramètres |
| deliverables | Deliverables | Livrables projet |
| change_requests, actions | Change / Actions | Registre Changement, Liste des actions |
| kpi_snapshots | KPI & Reporting | Statistiques, Dashboard, Glossaire |
| currencies, exchange_rates, parameters | Cross-cutting | Paramètres |

---

## 10. Conclusion
This model normalizes the 21-sheet workbook into ~26 relational tables with dynamic RBAC at the
core, a month-based time series for plan/actual, a history-bearing `KpiSnapshot`, first-class
multi-currency and parameters, and full audit/soft-delete. The runnable PostgreSQL 17 DDL is in
[docs/schema.sql](schema.sql) (≈26 tables + constraints + indexes + seed). Report **Chapter 4**
mirrors this design; then the Phase 3 validation gate.

*End of Database Design (Phase 3). Awaiting validation before Phase 5 — implementation.*
