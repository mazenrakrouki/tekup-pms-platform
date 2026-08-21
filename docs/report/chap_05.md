# Chapter 5 — UML Design

> **Markdown mirror** of `report/Rapport PFE TEKUP LATEX/chap_05.tex` (official source = `.tex`).
> Full PlantUML sources and Mermaid mirrors: [docs/UML_DESIGN.md](../UML_DESIGN.md).
> Two-level UML policy: ADR-024 — Level 1 (report, simple) vs. Level 2 (engineering,
> detailed, under `docs/uml/engineering/`, **not included in the report**).

## Introduction
**11 Level 1 diagrams**: 1 use case · 1 package diagram (10 modules) · 4 domain class diagrams
(Security · Project & Team · Financial · Workload & KPI) · 3 sequences (login, assignment,
workload → KPI) · 2 activities (lifecycle, KPI recompute). Each diagram fits on one page and
is understood in under 30 seconds.

## 1. Use case
4 actors, 15 business use cases. Two abstract actors (*Platform User*, *Portfolio User*) factor
out what's shared by generalization — every role authenticates, and every role above Developer
views KPIs — instead of repeating the same association on each concrete actor.
`ASSIGN_DEVELOPER` is granted by default to both the Director and the Project Manager (ADR-005).

![Use case diagram](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/use_case.png)

## 2. Packages — 10 functional modules

![Package diagram](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/package_diagram.png)

## 3. Classes by domain

### 3.1 Security
Dynamic RBAC: User–Role–Permission (a configurable mapping stored in the database, ADR-001).

![Security classes](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_security.png)

### 3.2 Project & Team
A project has a director + project managers (succession) + a team (resources). User and
Resource are kept separate (ADR-022).

![Project & Team classes](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_project_team.png)

### 3.3 Financial
Billing milestones + payments; missions; amendments — all attached to the project.

![Financial classes](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_financial.png)

### 3.4 Workload & KPI
Monthly time series (planned vs. actual); yearly TCC rate; historized KPI snapshots.

![KPI classes](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_kpi.png)

## 4. Sequences

### 4.1 Login
Password check (BCrypt), JWT token issuance, redirect on first login.

![Login sequence](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_login.png)

### 4.2 Assign a developer (permission AND scope)
Checks the `ASSIGN_DEVELOPER` permission, then the caller's project scope.

![Assignment sequence](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_assign_developer.png)

### 4.3 Submit workload → recompute KPIs
Synchronous entry; KPI recomputation is **asynchronous**, triggered after commit.

![Workload/KPI sequence](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_submit_workload.png)

## 5. Activities

### 5.1 Project lifecycle

![Lifecycle](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/act_project_lifecycle.png)

### 5.2 Hybrid KPI recomputation

![KPI recompute](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/act_kpi_recompute.png)

## Conclusion
11 simple diagrams cover actors, modules, domain, key flows, and lifecycle. The detailed
engineering diagrams remain in `docs/uml/engineering/` (not part of the report). Next:
implementation, starting with authentication and dynamic RBAC.
