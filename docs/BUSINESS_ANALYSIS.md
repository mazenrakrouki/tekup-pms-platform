# BUSINESS ANALYSIS — PMS

**Project:** Centralized IT Project Management and Financial Control Platform (PMS)
**Phase:** 1 — Business Analysis · **Status:** For final approval · **Date:** 2026-06-14
**Author role:** Business Analyst / Technical Lead
**Authoritative inputs (read-only):** `Providers/` — Cahier des Charges, BRS (65 rules), SRS
(FR/NFR), Use Case Specification (UC-001…020), Project Plan, and the production Excel
`DEV_Fiche_revue_projet-Real.xlsx` (**21 sheets, 2 088 formulas — primary business source**).
**Governance:** Conflicts resolved by the Source-of-Truth Priority in [DECISIONS.md](../DECISIONS.md).

> ### Phase 1 scope boundary (strictly business)
> This document is **business-only**. It defines *what* the system must do and *why* — actors,
> capabilities, workflows, business rules, the financial/KPI model, and a full analysis of the
> existing Excel workbook. It deliberately contains **no** technical design. The following are
> **deferred** and will be produced in later phases:
> - **System/API architecture, security enforcement mechanics, dynamic-menu implementation →
>   Phase 2 (Architecture).**
> - **Database/ERD, tables, schema, data types → Phase 3 (Database Design).**
> - **UML (class/sequence/activity) → Phase 4.**
> - **Frontend structure/components/routing → Phase 8 (Frontend).**
>
> Where this document names a permission or a "business object", it is describing a **business
> responsibility or concept**, not a technical artifact.

---

## 1. Business context & problem

The company manages IT projects with **dispersed Excel files**. The provided
`DEV_Fiche_revue_projet-Real.xlsx` is the real artifact: a **21-sheet** project-review workbook
containing **2 088 formulas** per project. This causes the pains stated in the Cahier des Charges:
no centralization; calculation errors and broken references (the file contains 169 `#REF!`/`#VALUE!`
cells); poor visibility on real cost vs budget; difficult budget follow-up; no consolidated
indicators; weak traceability of decisions.

**Objective:** a centralized web platform that becomes the **single source of truth** for project
governance — operational *and* financial — replacing the Excel process while preserving and
automating its real business logic (TCC, plan de charge, EAC, margins, billing milestones,
missions, risks, deliverables).

**Business value:** scattered spreadsheets become automated, reliable KPIs (EAC, margin, billing
progress) that let Project Managers steer profitability and Directors govern a portfolio in real
time.

---

## 2. Scope

### In scope (business capabilities)
Authentication · User Management · Role & Permission Management (dynamic authorization) · Project
Management · Team Management · TCC Management · Workload Planning · Actual Workload (manual +
KIMAI) · Billing · Mission Management · KPIs · Reporting — **plus** Avenants, Risk Register,
Deliverables, Stakeholders, Change Register (full Excel scope, ADR-004).

### Out of scope
- Replacing KIMAI (we integrate with it, ADR-003).
- Full accounting/ERP/HR systems — only future integration points (BR-065).
- Payroll computation (the TCC salarial input is provided/managed by the Administrator).

### Assumptions
- One company (single tenant), multiple projects, a shared resource pool.
- Reporting currency = TND; contracts in FCFA, missions in EUR (multi-currency is a business reality).
- A "resource" who logs effort is usually a Developer user, but TCC/workload also cover
  non-developer profiles present in the workbook (BA, Chef de mission).

---

## 3. Actors

| Actor | Mission | Can (summary) | Cannot (boundary) |
|------|---------|----------------|-------------------|
| **Administrator** | Technical & functional platform admin | Manage users, roles, permissions, **TCC**, parameters | Run projects; assign developers; manage workload/billing/missions; see project financials |
| **Director** | Strategic governance | Create/edit projects, set budget, change status, **assign PM**, **assign developers (D4)**, view all projects/teams, all financials & executive KPIs, all reports | Daily operations are delegated (not forbidden) |
| **Project Manager** | Primary operational user | Build teams, plan workload, billing milestones, missions, risks/deliverables/stakeholders/changes/avenants, validate actuals, view financials/KPIs of **managed** projects | Manage users/roles/TCC/parameters; act on non-managed projects |
| **Developer** | Effort declaration | View own assignments & missions, **submit actual workload**, view own history | Any financial data (budget/margin/EAC/billing) (BR-050) |
| **System / KIMAI** (secondary) | Automation & integration | Compute KPIs; import actual workload from KIMAI | — |

---

## 4. Business capabilities & authorization policy

The platform expresses *who may do what* as **business capabilities (permissions)**, assigned to
roles. This keeps responsibility assignment a **business/configuration decision**, never a
hard rule. (The *technical enforcement* of these permissions — backend guards, route protection,
dynamic menus — is **deferred to Phase 2 / Architecture**.)

### 4.1 Capability catalog (business actions)
| Area | Capabilities |
|------|-------------|
| Users / Auth | MANAGE_USERS, CREATE_USER, EDIT_USER, DEACTIVATE_USER, VIEW_USERS, RESET_USER_PASSWORD |
| Roles / Perms | MANAGE_ROLES, MANAGE_PERMISSIONS, ASSIGN_ROLE |
| Projects | CREATE_PROJECT, EDIT_PROJECT, DELETE_PROJECT, VIEW_PROJECT, VIEW_ALL_PROJECTS, CHANGE_PROJECT_STATUS, ASSIGN_PROJECT_MANAGER, MANAGE_PROJECT_BUDGET |
| Team | ASSIGN_DEVELOPER, REMOVE_DEVELOPER, VIEW_TEAM, VIEW_RESOURCE_AVAILABILITY |
| TCC | MANAGE_TCC, VIEW_TCC |
| Workload plan | MANAGE_WORKLOAD_PLAN, VIEW_WORKLOAD_PLAN |
| Actual workload | SUBMIT_ACTUAL_WORKLOAD, VALIDATE_ACTUAL_WORKLOAD, VIEW_ACTUAL_WORKLOAD, IMPORT_KIMAI |
| Billing | MANAGE_BILLING, VIEW_BILLING |
| Missions | MANAGE_MISSIONS, VIEW_MISSIONS |
| Financials / KPI | VIEW_FINANCIALS, VIEW_KPI, VIEW_EXECUTIVE_DASHBOARD |
| Risk / Deliverables | MANAGE_RISKS, VIEW_RISKS, MANAGE_DELIVERABLES, VIEW_DELIVERABLES |
| Stakeholders / Change / Avenants | MANAGE_STAKEHOLDERS, VIEW_STAKEHOLDERS, MANAGE_CHANGES, VIEW_CHANGES, MANAGE_AVENANTS, VIEW_AVENANTS |
| Reporting / Config | VIEW_REPORTS, EXPORT_REPORTS, MANAGE_PARAMETERS |

### 4.2 Default authorization policy (Role → Capability)
| Capability group | ADMIN | DIRECTOR | PM | DEVELOPER |
|---|:--:|:--:|:--:|:--:|
| User / Role / Permission management | ✔ | | | |
| MANAGE_TCC, MANAGE_PARAMETERS | ✔ | | | |
| Project create/edit/status/budget, assign PM | | ✔ | | |
| VIEW_ALL_PROJECTS | | ✔ | | |
| **ASSIGN_DEVELOPER / REMOVE_DEVELOPER** | | **✔ (D4)** | **✔** | |
| Workload plan / billing / missions / governance modules | | (view) | ✔ | |
| SUBMIT_ACTUAL_WORKLOAD | | | | ✔ |
| VALIDATE / VIEW actual workload | | ✔ | ✔ | (own) |
| VIEW_FINANCIALS / VIEW_KPI | | ✔ (all) | ✔ (managed) | ✖ (BR-050) |
| VIEW_EXECUTIVE_DASHBOARD | | ✔ | | |
| Reports | | ✔ (all) | ✔ (managed) | (own) |

**Why this matters (the D4 example):** the specification documents disagree on whether the Director
may assign developers (SRS/UC/Plan say PM-only). Resolving this is a **business policy choice** — we
grant `ASSIGN_DEVELOPER` to *both* Director and PM by default, and it can be changed later by
re-assigning the capability. No rule is hard-wired to a role. *(How this is enforced technically is
Phase 2.)*

---

## 5. Business workflows

### 5.1 End-to-end lifecycle
```
1. Admin creates users (Director, PM, Developer); temporary password; first login forces change
2. Director creates a Project (code, client, budget, dates, currency)
3. Director assigns a Project Manager (exactly one active PM)
4. PM (or Director, D4) builds the team (active developers only)
5. Admin maintains TCC per resource & per year
6. PM builds the Workload Plan: JH per resource per month → totals, ETC
7. PM defines Billing milestones (% of contract, amounts, dates); Σ% ≤ 100%
8. PM records Missions (perdiem, billet, séjour, transport) → automatic total cost
9. Developers submit actual JH per month (assigned projects only) and/or KIMAI import
10. PM validates actuals; system computes KPIs (EV, ETC, EAC, margins, CA Production, FAE, billing %)
11. Director monitors the portfolio via the Executive Dashboard; reports exported
```

### 5.2 Module sub-workflows (business level)
- **Auth:** login → first-login password change → access.
- **Team:** view available developers → assign (records *assigned by* + date) → remove keeps history.
- **TCC:** select resource + year → enter charged cost → daily TCC derived; validated history immutable.
- **Plan vs actual:** plan grid (resource×month JH) → forecast cost + ETC; actual grid → real cost.
- **Billing:** milestone create/update; status PENDING → INVOICED → PAID; cumulative % automatic.
- **Mission:** create → cost components → automatic total (multi-currency).
- **Governance:** risks, deliverables, stakeholders, changes, avenants tracked per project.
- **KPI/Reporting:** recompute on data change; PM dashboard (managed), Director dashboard
  (portfolio), Developer dashboard (own, no financials).

---

## 6. Business rules (reconciled: BRS + Excel + FR/UC)

All 65 BRS rules (BR-001…BR-065) are adopted and enriched with rules extracted from the Excel.

- **Users (BR-001…007):** admin-only creation; no self-registration; one role per user; unique
  email; temp password; forced first-login change; inactive users blocked.
- **Projects (BR-008…016):** Director creates; one Director + one **active** PM; PM may manage many
  projects; status ∈ {DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED}; unique project code; budget>0;
  start<end.
- **Teams (BR-017…022):** assign/remove developers; a developer may be on many projects; removal
  keeps history; record *assigned by* + date; no inactive developers. Authority = `ASSIGN_DEVELOPER`
  (Director **and** PM, D4).
- **TCC (BR-023…025) + Excel:** admin-only; per-resource per-year; validated history immutable.
  `TCC_jour = (coût_annuel_chargé × (1+overhead)) / jours_ouvrés_an`, overhead ≈ 70 % (×1.7),
  jours_ouvrés = 22 (2024) / 20 (2025) — **configurable**.
- **Workload planning (BR-026…032):** PM-only; effort in Man-Days (JH); no negatives; compute
  Planned/Actual/Remaining; capacity warning when assigned > available.
- **Actual workload (BR-033…037) + Excel:** developers submit; assigned projects only; linked to
  project+month+developer; history preserved. Effort may be entered in **hours → JH = hours / 8**
  (configurable); **dual source** MANUAL/KIMAI.
- **Billing (BR-038…043) + Excel:** PM-only; milestone ∈ one project; status ∈ {PENDING, INVOICED,
  PAID}; amount>0; **Σ % ≤ 100 %**; progress automatic; milestones carry %, montant, devise, dates
  (initiale/prévisionnelle/réelle), *réglé O/N*, montant payé; **multi-currency** (FCFA → TND).
- **Missions (BR-044…048) + Excel:** PM-only; mission ∈ project + employee; start<end; cost
  automatic. `Perdiem_DT = Séjour × Perdiem(devise) × Taux_change`; `Coût = Perdiem_DT + Billet +
  Timbre (+ Transport + Séjour)`.
- **KPIs (BR-049…054) + Excel:** financial KPIs visible to Director & PM only; developers walled
  off; all KPIs automatic (see §7).
- **Security/Audit/Reporting (BR-055…064):** authorization enforced by capability; passwords hashed;
  created/modified by/at tracked; **soft delete**; Director sees all reports, PM managed, Developer
  own only.
- **Future evolution (BR-065):** must allow ERP/HR/accounting integration without core redesign.

---

## 7. Financial & KPI business model (full Excel model — ADR-002 / D1)

All amounts are currency-aware; TCC inputs and constants are configurable business parameters.

| Indicator | Business definition / formula | Excel origin |
|----------|-------------------------------|--------------|
| **TCC (daily)** | `(coût annuel chargé × (1+overhead)) / jours ouvrés/an` (overhead≈70 %; 22/2024, 20/2025) | TCC `=(B6*1.7)/22` |
| **Coût Prévisionnel** | `Σ (JH planifié × TCC)` per month | Coût Prévisionnel |
| **ETC** | `Σ (JH restants × TCC)` | Coût Prévisionnel / Glossaire |
| **Coût Actuel** | `Σ (JH consommés × TCC)` (JH = heures/8) | Coût réel Actualisé |
| **EAC** | `Coût Actuel + ETC` | Glossaire |
| **Earned Value (EV %)** | real project progress (tasks or PM estimate) | Glossaire / Situation actuelle |
| **Cumul CA Production** | `Total contrat × EV%` | Glossaire / Statistiques |
| **FAE / Stock** | `Cumul CA Production − Total facturé` | Glossaire |
| **RAF (JH)** | `charge planifiée restante − charge consommée` | Glossaire |
| **Dérive (JH)** | `charges vendues − charges consommées − RAF` | Glossaire |
| **Marge nette vendue %** | `(Prix de vente − Coût total) / Prix de vente` | Fiche identification |
| **Marge nette actuelle** | `Cumul CA Production − Coût Actuel` | Glossaire |
| **Marge nette EAC** | `Budget − (Coût Actuel + Coût Prévisionnel) = Budget − EAC` | Glossaire |
| **Budget restant** | `Budget − EAC` | BR-053 |
| **PPR** | `Budget × 5 %` (configurable) | Fiche identification `=B26*5%` |
| **Avancement facturation %** | `Cumul facturé / Total contrat` | Avancement_Facturation |
| **Coût mission** | `Perdiem_DT + Billet + Timbre (+Transport+Séjour)` | Missions `=N3+J3+I3` |
| **Delivery %** | `livrables réalisés / livrables planifiés` | Livrables projet |
| **Impact risque pondéré** | `Probabilité × Impact` | Base des Risques |

Worked reference (real project A24001 – PFS_AIE): contract 248 992 444 FCFA ≈ 1 269 861 TND;
workload sold 1 717 JH; PPR ≈ 63 493 TND; milestones 10 % + 16 %×4 + 5 %×2. These seed demo/test data.

---

## 8. KPI per role (answers user questions)

| Role | KPI access | What they DO with KPIs |
|------|-----------|------------------------|
| **Developer** | None financial; only own planned vs submitted JH | Self-tracking of effort (BR-050) |
| **Project Manager** | Full financial KPIs for **managed** projects | Steer profitability: detect overrun (EAC>Budget, Dérive>0), adjust plan, trigger billing |
| **Director** | Portfolio-wide KPIs + Executive Dashboard | Govern: compare projects, protect budgets/margins |
| **Administrator** | None project-financial | Maintains TCC & parameters KPIs depend on |

**Why a Developer declares JH:** `actual JH → Coût Actuel = Σ(JH×TCC) → EAC = Coût Actuel + ETC →
Marge = Budget − EAC` and `Dérive`. The declaration is the raw measurement of real consumption —
essential to producing financials, which is exactly why developers provide the input but are walled
off from the output. **How the Admin manages TCC:** per resource and per year, enters the charged
cost; daily TCC is derived from configurable `overhead` and `working-days/year`; validated history
is immutable.

---

## 9. Complete Excel workbook analysis (21 sheets)

The workbook `DEV_Fiche_revue_projet-Real.xlsx` is the company's "Fiche de revue projet"
(ref. F-AFF-13, v03, Nov 2024). Each sheet is analyzed below and mapped to a PMS module.

| # | Sheet | Purpose | Business owner | Key business rules | Calculations (formulas) | PMS module |
|---|-------|---------|----------------|--------------------|--------------------------|------------|
| 1 | **Fiche Doc** | Document control of the review sheet (Rédigé/Vérifié/Approuvé, version) | Quality / Direction (Admin) | Versioned, approved document | none (0) | Project Management (project metadata) |
| 2 | **Fiche identification** | Project identity: client, contract, budget, currency, duration, sold workload | Director | Budget>0; one contract; FCFA→TND; PPR=5% | budget conversions, PPR, margin (4) | Project Management |
| 3 | **TCC** | Daily charged cost per resource per year | Administrator | One value/resource/year; overhead ×1.7; jours 22/20; validated→immutable | `(base×1.7)/jours`, SUM (10) | TCC Management |
| 4 | **Missions** | Business-trip costs (perdiem, billet, séjour) | Project Manager | Mission∈project+employee; auto cost | `Séjour×Perdiem×FX`, sum (21) | Mission Management |
| 5 | **Avancement_Facturation** | Billing milestones in % of contract, monthly cumul | Project Manager | Σ%≤100; status; multi-currency | `contrat×%`, cumul, reste (43) | Billing |
| 6 | **Recap Facturation (2)** | Billing recap variant (per-invoice, RAC) | Project Manager | amount>0; cumul payé | invoice math, cumul (49) | Billing |
| 7 | **Coût Prévisionnel** | Plan de charge (resource×month JH) + forecast cost | Project Manager | JH≥0; ETC=remaining; cost=JH×TCC | SUM, ETC running sum, `JH×TCC`, cumul (419) | Workload Planning + KPI |
| 8 | **Coût réel Actualisé** | Actual imputations (resource×month JH) + actual cost | Developer (input) / PM / System | JH≥0; hours/8; history preserved; cost=JH×TCC | SUM, cumul, `JH×TCC` (520) | Actual Workload + KPI |
| 9 | **Recap Facturation** | Billing recap + total frais consumed vs mission budget | Project Manager | cumul facturé; reste à consommer | billing+mission recap (57) | Billing |
| 10 | **S. Contractuelle** | Contractual situation: dev period, guarantee period, dates | Director / PM | dev vs garantie periods; currency | DATE/EDATE period math (60) | Project Management + Billing |
| 11 | **Situation actuelle** | Sprint-level progress and Earned Value | Project Manager | EV% per sprint; current month | DATE, HLOOKUP, SUM, IF (199) | KPI/Reporting + Deliverables |
| 12 | **Parties Prenantes** | Staffing/team + stakeholders (client, partners) with requirements/impact | Project Manager | Staffing %, start/end; stakeholder register | none (data entry) (0) | Stakeholders + Team |
| 13 | **Livrables projet** | Deliverables per sprint, delivered? Delivery % | Project Manager | Delivery%=delivered/planned | `COUNTA`, ratios (11) | Deliverables |
| 14 | **Base des Risques** | Risk register: severity, probability, weighted impact, treatment | Project Manager | Impact pondéré=Prob×Impact; scales from Paramètres | `Prob×Impact`, lookups (5) | Risk Register |
| 15 | **Liste des actions** | Action list: action, owner, %, dates, efficacy | Project Manager | % réalisation; planned vs real date | % progress, dates (3) | Risk/Actions |
| 16 | **Registre Changement** | Change requests: requester, impact, validated?, dates | Project Manager | change validated? responsible; closure | none (data entry) (0) | Change Register |
| 17 | **Avenants** | Contract amendments: JH, montant, PPR, margin impact | Director / PM | amendment changes budget & billing totals | margin/PPR per avenant (1) | Avenants |
| 18 | **Statistiques** | KPI computation engine over months (budget, EAC, margins, CA, FAE) | System (computed) / Director+PM (view) | full KPI model; month selection | DATE/HLOOKUP/SUM/IF (623) | KPI engine |
| 19 | **Dashboard** | KPI board for a project (margins, JH, dates, evolution) | System / Director+PM | reads computed KPIs | HLOOKUP/refs, margins (57) | KPI dashboards / Reporting |
| 20 | **Glossaire** | Definitions of all management indicators | Reference (System/all) | authoritative KPI definitions | none (0) | KPI (definitions) |
| 21 | **Paramètres** | Config: risk probability/severity/treatment scales, classes | Administrator | scale values; max %/TND per severity | scale refs (6) | Configuration / Risk scales |

**Coverage:** 21/21 sheets analyzed; every sheet is owned by a PMS role and mapped to a PMS module.
Sheets 12 and 16 are pure data-entry (0 formulas); all computed sheets are covered in §10.

---

## 10. Excel formula → future PMS calculation mapping (coverage verification)

The workbook contains **2 088 formulas**. They use a **finite vocabulary**, so each is provably
mapped to a future PMS calculation. The categories below are **exhaustive** (their counts sum to the
whole population, allowing for formulas that combine categories).

| # | Formula category (Excel) | Count | Future PMS calculation | Owning module |
|---|--------------------------|------:|------------------------|---------------|
| F1 | Date functions `DATE/YEAR/MONTH/DAY/EDATE/TODAY` | 460 | Project/sprint/milestone schedule computation; "current month" period; durations, elapsed/remaining | Project Mgmt + KPI period engine |
| F2 | `SUM` aggregation | 92 | Monthly totals & cumulative cost/billing/workload | Workload, Cost, Billing, KPI services |
| F3 | `HLOOKUP` (month selection) | 31 | "Selected/current month" KPI snapshot (parameterized query on month) | KPI/Reporting |
| F4 | `IF / ISBLANK / IFERROR` | 36 | Conditional status, empty-safe & error-safe values | Service-level conditionals/validation |
| F5 | `AVERAGE / MAX / COUNTA` | 7 | Averages, max, deliverable counts (Delivery %) | KPI / Deliverables |
| F6 | Arithmetic cost math (`JH × TCC`, sums, diffs) | ~1 421 | Coût Prévisionnel, Coût Actuel, ETC, EAC, mission cost | KPI engine, Missions |
| F7 | Percentage math (`×%`, `÷`) | 13 | Margins, billing %, PPR, Delivery %, weighted risk | KPI, Billing, Risk |
| F8 | Cross-sheet references (`TCC!`, `Coût…!`, `Fiche…!`) | 1 426 | Relational joins between entities (workload ↔ TCC ↔ project ↔ contract) | All financial services (replaced by DB queries — Phase 3) |
| F9 | Broken `#REF!` / `#VALUE!` | 169 | **Not replicated.** Recomputed cleanly from normalized data | Data-integrity guarantee |

**Cross-sheet reference targets** (top): `TCC` (771), `Coût réel Actualisé` (151),
`Fiche identification` (92), `Coût Prévisionnel` (89), `S. Contractuelle` (54), `Statistiques` (27),
`Avancement_Facturation` (25), `Livrables projet` (25), `Recap Facturation` (25),
`Situation actuelle` (21), `Dashboard` (5), `Missions` (5).

**Verification result:** every one of the 2 088 formulas belongs to categories **F1–F8** (live
business logic, each mapped above) or **F9** (broken artifacts that the platform replaces with clean
recomputation). **No formula is left unmapped.** The concrete KPI formulas (EAC, ETC, margins, CA
Production, FAE, Dérive, RAF, billing %, mission cost, Delivery %, weighted risk) are itemized in §7.

---

## 11. Business objects (domain glossary)

*Business meaning only — the data model (entities, keys, types, relationships) is **Phase 3**.*

User · Role · Capability(Permission) · Project · Project Manager assignment · Resource · Team
assignment · TCC (per resource, per year) · Workload plan (planned JH) · Actual workload (real JH,
source = manual/KIMAI) · Billing milestone · Invoice · Payment · Mission (+ cost components) · Risk ·
Deliverable · Stakeholder · Change request · Avenant · Currency & exchange rate · Business parameter ·
Audit trail · KPI snapshot.

---

## 12. Business parameters to keep configurable (business requirement)

*The requirement is business; the storage mechanism is Phase 2/3.*

| Parameter | Default | Used by |
|----------|---------|---------|
| TCC overhead | 0.70 (×1.7) | TCC daily cost |
| Working days / year | 22 (2024), 20 (2025) | TCC daily cost |
| Hours per day | 8 | hours→JH conversion |
| PPR percent | 5 % | provision for risks |
| Billing max total % | 100 % | milestone validation |
| Reporting currency | TND | conversions |
| Risk probability scale | 0 / 0.2 / 0.4 / 0.6 / 0.8 / 1 | risk scoring |
| Risk severity scale | Mineur…Critique + Max %/TND | risk scoring |
| Risk treatment | Réduction/Acceptation/Évitement/Transfert | risk module |

---

## 13. Ambiguities & resolutions

| # | Ambiguity | Resolution | Ref |
|---|-----------|------------|-----|
| A1 | Simplified BRS KPIs vs rich Excel model | Full Excel model | ADR-002 / D1 |
| A2 | Actual workload: app vs KIMAI | Both (source discriminator) | ADR-003 / D2 |
| A3 | Extra Excel modules in scope? | Yes, full scope | ADR-004 / D3 |
| A4 | Director assign developers? (Plan/SRS/UC say no) | Yes — Director **and** PM | ADR-005 / D4 |
| A5 | Currencies (FCFA/TND/EUR) | Multi-currency (business reality) | ADR-007 |
| A6 | Hardcoded constants (1.7, 22/20, 5 %) | Configurable business parameters | ADR-008 |
| A7 | Effort in hours vs days | Store JH; configurable hours/day | §6 |

---

## 14. Risks & recommendations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Excel has 169 `#REF!`/`#VALUE!` cells | Mis-modeling if copied literally | Recompute from normalized data (F9) |
| Full financial model complexity | Scope/effort | Sequence KPI engine after plan/actual/billing/mission exist; validate vs the real sheet |
| Multi-currency correctness | Wrong margins | Centralize conversion with dated rates; test KPI math |
| KIMAI integration unknowns | Import bugs | Manual entry is the core path; import idempotent + reconciled |
| Spec contradictions (D4) | Confusion | All authority via capabilities; discrepancy logged in DECISIONS.md |
| History immutability (TCC/workload) | Audit failures | Soft delete + validated-period locks |

**Recommendations:** keep every business constant configurable; use project A24001 as the KPI
validation oracle; design the capability/permission model first so every module plugs into it;
generate the report chapter at the end of each phase.

---

## 15. Appendix — direct answers to the three user questions

1. **Why & how does a Developer declare JH?** Each month, per assigned project, the developer records
   the real man-days consumed (manual entry or KIMAI import). This feeds `Coût Actuel → EAC →
   Marge/Dérive`. Developers provide the input but never see the financial output (BR-050). §8.
2. **What are KPIs, and KPI-per-role?** KPIs are the automated indicators of §7. Developers: none
   financial; PM: full set on managed projects; Director: portfolio + executive dashboard; Admin:
   none, but owns TCC/parameters. §8.
3. **How does the Admin manage TCC?** Per resource and year, enters the charged annual cost; daily
   TCC is derived via configurable overhead and working-days/year; history is preserved and becomes
   immutable once used in a validated period. §6, §8.

---

*End of Business Analysis (Phase 1, business-only). Technical design begins in Phase 2 (Architecture)
after approval.*
