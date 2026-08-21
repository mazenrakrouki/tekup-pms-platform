# AUTHORIZATION MATRIX — PMS (Audit & Correction)

**Phase:** C — RBAC authorization audit · **Date:** 2026-06-27
**Last updated:** 2026-07-07 — V20 descopes (MANAGE_ROLES/VIEW_AUDIT_LOG) + V23 MANAGE_DI (Directeur) + VIEW_ALL_PROJECTS (V13)
**Author role:** Solution Architect / Business Analyst / Security Engineer
**Governance:** Source-of-Truth Priority ([DECISIONS.md](../DECISIONS.md)) →
ADR/DECISIONS > user instructions > Excel > BRS > Use Cases > SRS > Plan.
**Mandate:** Rebuild the authorization matrix **from the business rules**, never from the
existing code. Detect every inconsistency. Respect **ADR-001** (dynamic RBAC) and **ADR-005**
(default mapping `ASSIGN_DEVELOPER → DIRECTOR + PM`).

> **Method note.** The original Use Case Specification (UC-001…020) is an external Provider input
> and is not in the repository. The use cases below are **reconstructed** from the authoritative
> [BUSINESS_ANALYSIS.md](BUSINESS_ANALYSIS.md) (§3 actors, §4 capabilities, §5 workflows, §6 business
> rules) and the BRS rule references it cites. Where a UC maps to a business action, the **business
> rule** — not the controller — determines the required permission.

---

## 1. Actor responsibilities (authoritative — BA §3 + mandate)

| Actor | IS responsible for | Is explicitly NOT allowed to |
|-------|--------------------|------------------------------|
| **Administrator** | Platform admin: users, roles, permissions, **TCC/resources**, parameters, audit | Run projects, assign developers, manage workload/billing/missions/governance, **see project financials/KPI** |
| **Director** | Portfolio governance: create/edit projects, budget, status, **assign PM**, **assign developers (ADR-005)**, view all projects/teams, **all financials & executive KPIs**, view (not manage) operational modules | Daily operational management (delegated to PM); platform admin (users/roles/TCC) |
| **Project Manager** | Operational execution of **managed** projects: build team, plan & validate workload, billing milestones, missions, governance (risks/deliverables/stakeholders/changes), monitor KPI/financials | Act on non-managed projects; platform admin |
| **Developer** | Own work only: view assigned projects & own missions, **submit actual workload**, view own history | **Any financial/KPI data (BR-050)**; governance; team/billing/mission management |

**Administrator is NOT a business actor** (mandate rule #6). It administers the *platform*, not the
*projects*.

---

## 2. Implemented permission model (coarse, module-level)

The build uses a **coarser** permission set (22 permissions) than the BA capability catalog (~50).
This is an accepted *simplicity-first* coarsening: fine-grained capabilities are folded into
module-level permissions (e.g. `CREATE_USER/EDIT_USER/DEACTIVATE_USER → MANAGE_USERS`;
`MANAGE_RISKS/DELIVERABLES/STAKEHOLDERS/CHANGES → MANAGE_GOVERNANCE`;
`MANAGE_TCC → MANAGE_RESOURCES`; `REMOVE_DEVELOPER → ASSIGN_DEVELOPER`). The coarsening is legitimate
**provided the role→permission assignment respects the business boundaries** of §1 — which is exactly
where the defects are.

| Module | Permissions | Status |
|--------|-------------|--------|
| ADMIN | MANAGE_USERS | live |
| ~~ADMIN~~ | ~~MANAGE_ROLES, VIEW_AUDIT_LOG~~ | **descoped V20** — features not built for this release |
| RESSOURCE (TCC) | MANAGE_RESOURCES, VIEW_RESOURCES | live |
| PROJET | VIEW_PROJECT, **VIEW_ALL_PROJECTS** (scope global, ADR-021), CREATE_PROJECT, EDIT_PROJECT, DELETE_PROJECT, ASSIGN_CHEF_PROJET | live |
| PROJET (DI) | **MANAGE_DI** — consultation + édition du Devis Interne (**Directeur only**, données sensibles) | live V23 |
| EQUIPE | ASSIGN_DEVELOPER, VIEW_TEAM | live |
| CHARGE | SUBMIT_WORKLOAD, VALIDATE_WORKLOAD, VIEW_WORKLOAD | live |
| FACTURATION | MANAGE_BILLING, VIEW_BILLING | live |
| MISSION | MANAGE_MISSION, VIEW_MISSION | live |
| GOVERNANCE | MANAGE_GOVERNANCE, VIEW_GOVERNANCE | live |
| KPI | VIEW_KPI | live |

> **V20 descope rationale:** `MANAGE_ROLES` and `VIEW_AUDIT_LOG` were inserted in V2 but no
> `@PreAuthorize` check ever guarded them (no role-permission-admin UI, no audit-log viewer feature).
> V20 deletes the rows from `permissions` and `role_permissions` to keep the matrix clean.
> These are *formally descoped*, not security-relevant omissions — the features don't exist.

> **Known coarsening note:** workload **planning** and **validation** are both gated by
> `VALIDATE_WORKLOAD` (`PlanChargeService` uses it for create/update/delete). This keeps planning
> PM-only (BR-026) but conflates two business capabilities; a dedicated `MANAGE_WORKLOAD_PLAN` is a
> future refinement, not a correctness defect.

---

## 3. Use Case → Permission → Module → Default Roles → Justification

| UC | Business action | Permission | Module | Default roles | Justification (rule) |
|----|-----------------|-----------|--------|---------------|----------------------|
| UC-001 | Authenticate (login) | — (authenticated) | Auth | all | BR-005/006, ADR-010 |
| UC-002 | First-login password change | — (authenticated) | Auth | all | BR-006, ADR-010/017 |
| UC-003 | Manage users (create/edit/deactivate) | MANAGE_USERS | ADMIN | **ADMIN** | BR-001…007: admin-only |
| UC-004 | Manage roles & permissions | ~~MANAGE_ROLES~~ *(descoped V20)* | ADMIN | — | Feature not built; descoped |
| UC-005 | Manage TCC / resources (cost) | MANAGE_RESOURCES | RESSOURCE | **ADMIN** | BR-023…025: TCC admin-only |
| UC-006 | View resource availability/cost | VIEW_RESOURCES | RESSOURCE | ADMIN, DIRECTOR, PM | Planning/costing visibility |
| UC-007 | Create project | CREATE_PROJECT | PROJET | **DIRECTOR** | BR-008: Director creates |
| UC-008 | Edit project / budget / status | EDIT_PROJECT | PROJET | DIRECTOR, PM(managed) | BR-008…016; PM runs managed |
| UC-009 | Delete (soft) project | DELETE_PROJECT | PROJET | **DIRECTOR** | Portfolio owner decision |
| UC-010 | Assign / replace Project Manager | ASSIGN_CHEF_PROJET | PROJET | **DIRECTOR** | BR-009: one active PM |
| UC-011 | View project(s) | VIEW_PROJECT | PROJET | DIRECTOR(all), PM(managed), DEV(assigned) | BR-062/063/064 (scope) |
| UC-012 | Assign / remove developer | ASSIGN_DEVELOPER | EQUIPE | **DIRECTOR + PM** | **ADR-005 / D4** |
| UC-013 | View team | VIEW_TEAM | EQUIPE | DIRECTOR, PM, DEV(own) | BR-017…022 |
| UC-014 | Plan workload (plan de charge) | VALIDATE_WORKLOAD* | CHARGE | PM | BR-026: PM-only planning |
| UC-015 | Submit actual workload | SUBMIT_WORKLOAD | CHARGE | **DEVELOPER** | BR-033…037: developer submits |
| UC-016 | Validate actual workload | VALIDATE_WORKLOAD | CHARGE | PM | BR-035; PM validates |
| UC-017 | View workload | VIEW_WORKLOAD | CHARGE | DIRECTOR, PM, DEV(own) | BR-062…064 (scope) |
| UC-018 | Manage billing milestones / payments | MANAGE_BILLING | FACTURATION | **PM** | BR-038…043: **PM-only** |
| UC-019 | View billing | VIEW_BILLING | FACTURATION | DIRECTOR, PM | Financial visibility |
| UC-020 | Manage missions (+ components) | MANAGE_MISSION | MISSION | **PM** | BR-044…048: PM-only |
| UC-21 | View missions | VIEW_MISSION | MISSION | DIRECTOR, PM, DEV(own) | Developer sees own missions |
| UC-22 | Manage governance (risk/deliv/stake/change) | MANAGE_GOVERNANCE | GOVERNANCE | **PM** | ADR-004; PM owns governance |
| UC-23 | View governance | VIEW_GOVERNANCE | GOVERNANCE | DIRECTOR, PM | Portfolio + operational view |
| UC-24 | View KPI / financials | VIEW_KPI | KPI | DIRECTOR(all), PM(managed) | **BR-049…054, BR-050: NOT developer** |
| UC-25 | Consult & edit Devis Interne | **MANAGE_DI** | PROJET (DI) | **DIRECTOR** | BUSINESS_ANALYSIS §16 — données sensibles, Directeur uniquement |

\* Planning is gated by `VALIDATE_WORKLOAD` due to the §2 coarsening note.

---

## 4. Live Role → Permission matrix (as of V23)

> Applied migrations: V12 (RBAC fix) · V13 (VIEW_ALL_PROJECTS + scope) · V20 (descope MANAGE_ROLES/VIEW_AUDIT_LOG) · V23 (MANAGE_DI)

| Permission | Module | ADMIN | DIRECTEUR | CHEF_PROJET | DEVELOPPEUR |
|-----------|--------|:-----:|:---------:|:-----------:|:-----------:|
| MANAGE_USERS | ADMIN | ✔ | | | |
| ~~MANAGE_ROLES~~ | ~~ADMIN~~ | ~~✔~~ | | | | *(descoped V20)* |
| ~~VIEW_AUDIT_LOG~~ | ~~ADMIN~~ | ~~✔~~ | | | | *(descoped V20)* |
| MANAGE_RESOURCES | RESSOURCE | ✔ | | | |
| VIEW_RESOURCES | RESSOURCE | ✔ | ✔ | ✔ | |
| VIEW_PROJECT | PROJET | | ✔ | ✔ | ✔ |
| VIEW_ALL_PROJECTS | PROJET | | ✔ | | | *(scope lift, ADR-021, added V13)* |
| CREATE_PROJECT | PROJET | | ✔ | | |
| EDIT_PROJECT | PROJET | | ✔ | ✔ | |
| DELETE_PROJECT | PROJET | | ✔ | | |
| ASSIGN_CHEF_PROJET | PROJET | | ✔ | | |
| **MANAGE_DI** | **PROJET** | | **✔** | | | *(added V23 — données sensibles DI)* |
| ASSIGN_DEVELOPER | EQUIPE | | ✔ | ✔ | |
| VIEW_TEAM | EQUIPE | | ✔ | ✔ | ✔ |
| SUBMIT_WORKLOAD | CHARGE | | | | ✔ |
| VALIDATE_WORKLOAD | CHARGE | | | ✔ | |
| VIEW_WORKLOAD | CHARGE | | ✔ | ✔ | ✔ |
| MANAGE_BILLING | FACTURATION | | | ✔ | |
| VIEW_BILLING | FACTURATION | | ✔ | ✔ | |
| MANAGE_MISSION | MISSION | | | ✔ | |
| VIEW_MISSION | MISSION | | ✔ | ✔ | ✔ |
| MANAGE_GOVERNANCE | GOVERNANCE | | | ✔ | |
| VIEW_GOVERNANCE | GOVERNANCE | | ✔ | ✔ | |
| VIEW_KPI | KPI | | ✔ | ✔ | |
| **Live total** | | **2** | **15** | **14** | **5** |

> Admin count drops from 5→2 after V20 removes MANAGE_ROLES and VIEW_AUDIT_LOG.
> Director count goes 13→15 after V13 (+VIEW_ALL_PROJECTS) and V23 (+MANAGE_DI).

---

## 5. Inconsistencies detected (CURRENT vs TARGET)

### 5.1 ADMIN — **17 illegitimate permissions** (CRITICAL)
Root cause: `V2__seed_rbac.sql` line 42-43 `ADMIN : tout` (`SELECT * FROM permissions`), then
V9/V10/V11 added billing/mission/governance to admin.

| Permission held by ADMIN | Verdict | Rule violated |
|--------------------------|---------|---------------|
| VIEW/CREATE/EDIT/DELETE_PROJECT, ASSIGN_CHEF_PROJET | ❌ remove | BA §3: admin cannot run projects |
| ASSIGN_DEVELOPER, VIEW_TEAM | ❌ remove | BA §3: admin cannot build teams |
| SUBMIT/VALIDATE/VIEW_WORKLOAD | ❌ remove | BA §3: admin cannot manage workload |
| MANAGE_BILLING, VIEW_BILLING | ❌ remove | BR-038: PM-only; admin no financials |
| MANAGE_MISSION, VIEW_MISSION | ❌ remove | BR-044: PM-only |
| MANAGE_GOVERNANCE, VIEW_GOVERNANCE | ❌ remove | ADR-004: PM owns governance |
| VIEW_KPI | ❌ remove | BR-050: admin sees no project financials |
| MANAGE_USERS, MANAGE_ROLES, VIEW_AUDIT_LOG, MANAGE_RESOURCES, VIEW_RESOURCES | ✔ keep | Platform admin + TCC (BA §3) |

### 5.2 DIRECTEUR — wrong composition (HIGH)
| Issue | Verdict | Rule |
|-------|---------|------|
| Has **MANAGE_BILLING** | ❌ remove (keep VIEW_BILLING) | BR-038: billing is **PM-only**; Director views only |
| Has **MANAGE_RESOURCES** | ❌ remove (keep VIEW_RESOURCES) | BR-023: TCC is **admin-only** |
| **Missing ASSIGN_DEVELOPER** | ✅ add | **ADR-005 / D4** mandates Director + PM |

### 5.3 DEVELOPPEUR — financial leak (CRITICAL)
| Issue | Verdict | Rule |
|-------|---------|------|
| Has **VIEW_KPI** | ❌ remove | **BR-050**: developers walled off from all financial/KPI data |
| Has **VIEW_GOVERNANCE** | ❌ remove | BA §3: developer scope = own work only, not governance |

#### 5.3.1 Payload-level financial leak — ✅ **RESOLVED (audit 2026-07-16)**
Removing `VIEW_KPI` (V12) closed the **endpoint** surface (`/kpi`, `/jalons`, `/risks`… → 403), but
**not the payload**: `ProjectResponse` carries `initialBudget`, `revisedBudget`, `effectiveBudget`,
`budgetTnd`, `pprTnd`, `margeNetteVendue`, `penaltyProvision`, `licenseSubcontractBudget`, and
`GET /api/projects/{id}` is gated on `VIEW_PROJECT` only. A developer assigned to a project therefore
received the full financials — a direct **BR-050** violation (verified live: budget 650 000, PPR
32 500, marge 0,36 returned to `dev@pms.local`).

**Fix (capability-based, ADR-001 — never role-based):**
- `ProjectResponse.withoutFinancials()` — redacted copy; workload in JH (`soldWorkloadDays`,
  `warrantyWorkloadDays`) is **not** financial and stays visible.
- `ProjectService.toResponse()` applies it on every read/write path when the caller lacks `VIEW_KPI`.
- Frontend defence-in-depth: budget rows (project detail), Budget column (project list + developer
  dashboard) gated on `VIEW_KPI`; the *Infos & KPI* tab reads *Infos* without the capability.

**Validated:** DEVELOPPEUR → 0 financial fields (detail + list); DIRECTEUR/CHEF_PROJET → unchanged.

#### 5.3.2 `DEV(own)` scope not enforced on reads — ✅ **RESOLVED (audit 2026-07-16)**
UC-017 / UC-21 specify **DEV(own)** — a developer views *their own* workload and missions only
(BA §3 "own work only"; BR-062…064). `ProjectScopeService` filters **which projects** are reachable,
but **inside** a reachable project the read paths were permission-only. Verified live: `dev@pms.local`,
assigned to project 35, saw **60 workload entries from 5 colleagues** and **3 missions belonging to
other people** — none of them his.

Note the asymmetry that hid this: `ChargeReelleService.assertOwnership()` already enforced own-only on
**writes** (BR-033) since day one; the **reads** never had the equivalent filter.

**Fix (capability-based, ADR-001 — no migration, no new permission):**
| Read path | Broad view granted by | Otherwise |
|-----------|----------------------|-----------|
| `ChargeReelleService.findByProject` | `VALIDATE_WORKLOAD` (PM validates) ∨ `VIEW_ALL_PROJECTS` (Director) | own rows only |
| `PlanChargeService.findByProject` | idem | own rows only |
| `MissionService.findByProject` | `MANAGE_MISSION` (PM) ∨ `VIEW_ALL_PROJECTS` (Director) | own rows only |

Filtering is done **in the query** (`findActiveByProjectIdAndUserId[Paged]`), not in memory, so
pagination totals stay correct.

> **Why not `VALIDATE_WORKLOAD` alone?** The Director holds `VIEW_WORKLOAD` **without**
> `VALIDATE_WORKLOAD` (§4) — gating on it alone would have wrongly restricted the Director to their
> own rows. `VIEW_ALL_PROJECTS` is the Director's existing scope-lift capability (ADR-021).

**Validated:** DEV submits own workload → 201, then sees **1 row (his own) out of 61**; submitting for
another user → 403 (BR-033). CHEF_PROJET → 61 rows / 6 people. DIRECTEUR → unchanged. Out-of-scope
project → 403.

### 5.4 CHEF_PROJET — **correct** ✅
All 14 permissions match the business boundary (operational execution of managed projects). No change.

### 5.5 Cross-cutting — data scope (ADR-021) — ✅ **RESOLVED (V13 + ProjectScopeService)**
Previously all `@PreAuthorize` checks were **permission-only**, so a PM saw *all* projects
(BR-062/063 violation). **Now enforced** as *Permission ∧ Scope*:
- New capability **`VIEW_ALL_PROJECTS`** (→ DIRECTEUR) lifts the scope filter (portfolio access).
- `ProjectScopeService` derives the scope from **relationships** (chef de projet ∪ active team
  assignment) — never from a role name (ADR-001).
- `ProjectScopeInterceptor` enforces it **systematically** on every `/api/projects/{id}/**` route;
  `ProjectService.findAll/findById` filter the list & assert detail access.
- **Validated:** Director sees all; PM/DEV see only managed/assigned; out-of-scope access → **403**
  (list-hidden + direct + child resources). 13/13 scope probes PASS.

---

## 6. Correction strategy (ADR-001 in action)

The fix is **data-only** — a single Flyway migration that corrects `role_permissions`. **Zero backend
and zero frontend code changes**, which is the canonical proof of dynamic RBAC (ADR-001):

- **V12__fix_rbac_matrix.sql** — for each role, delete the wrong grants and insert the missing ones to
  match §4. Idempotent (`DELETE … WHERE …` + `INSERT … ON CONFLICT DO NOTHING`).

**Consequence on demo data:** the Phase-A seed created projects/teams/billing/missions **as admin**,
which will no longer be permitted after V12. Existing rows remain; the demo seed must be re-run using
the **correct actors** — Director creates projects & assigns PM/developers; PM manages
team/workload/billing/missions/governance — which also makes the Phase-C role test meaningful.

---

*End of authorization audit. Apply V12, then re-seed with correct actors, then execute the per-role
test plan (Phase C).*
