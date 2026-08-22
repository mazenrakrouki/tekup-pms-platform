# SUPERPLAN — PMS final year project report

Living plan for bringing the PFE report to the standard expected by the academic supervisor.
Updated as each chapter is completed.

**Target structure:** `report/examples-reports/RapportPFE3.pdf` — the reference the supervisor
designated. Structure is followed, content is not copied.

**Host organization:** ST2i, consulting firm of the STUDI group.
**Assigned subject:** ST2i PFE Book 2025–2026, subject 6, C.DEV track.
**Duration:** 5 January – 30 August 2026, 34 weeks.

---

## Ground rules

These hold for every chapter and are not renegotiated per section.

1. **Every claim traces to evidence** — the ST2i material, the PFE Book subject, the F-AFF-13
   workbook, or the code. Anything that cannot be traced is cut, not softened.
2. **No invented process history.** Describing the Scrum framework, its roles and its ceremonies is
   textbook material and legitimate. Claiming that a specific stand-up, review or retrospective took
   place, or presenting velocity or burndown figures, is not. The author worked alone.
3. **ST2i is named; the client is not.** Reference reports name their host companies. The workbook's
   client name, contract number, funder, staff names and salary-derived rates stay anonymised.
4. **DI values are never printed.** Structure may be described; figures may not.
5. **English throughout.** Company vocabulary keeps its French form with an English gloss on first
   use: TCC, Devis Interne, FORFAIT, REGIE.
6. **UML follows real notation** — multiplicities on every association, composition and aggregation
   used correctly, `<<include>>` / `<<extend>>` used properly. A class diagram is not an ERD.

---

## Chapter structure (target)

| Ch | Title | Status |
|---|---|---|
| — | General introduction | written, revisit at the end |
| 1 | Project Framework | **done** |
| 2 | Analysis and Specification of Requirements | pending |
| 3 | System Architecture and Design | pending |
| 4 | Authentication and Access Control (R1) | **done** — pattern reference |
| 5 | Project and Operational Management (R2) | **done** — 3 sprints, incl. new Sprint 5 Agile |
| 6 | Financial Management and Governance (R3) | **done** — 3 sprints |
| 7 | Consolidation, Delivery and Deployment (R4) | **done** — 2 sprints + final state |
| — | General conclusion | written, revisit at the end |

### The sprint pattern (established in chapter 4)

Every release chapter opens with its backlog, then each sprint has exactly three subsections:

```
X.1     Release N backlog
X.Y     Sprint N — <name>
X.Y.1     Sprint N use case diagram
X.Y.2     Sprint N analysis and design      sequence diagrams, design decisions
X.Y.3     Sprint N realization              implementation, interface captures
```

No sprint objective, functional scope, features list, testing section, outcome or transition.
Chapter titles name the functional theme, not the release number.

---

## Prerequisite — agile planning module

The assigned subject requires *"la planification agile (sprints, backlog)"*. A search of every
entity under `backend/src/main/java/com/pms` confirmed PMS implements no such feature. Rather than
explain the gap away, the module is being built.

- **Backend:** new `agile` package mirroring `governance` in shape. `Sprint` and `BacklogItem`
  entities extending `BaseEntity`. Migration **V27**. Permissions `MANAGE_AGILE` / `VIEW_AGILE`
  seeded and enforced through `@PreAuthorize` — never by role name.
- **Endpoints:** `/api/projects/{projectId}/sprints`, `/api/projects/{projectId}/backlog`.
- **Frontend:** `features/agile`, reusing `app-pagination` and the project-picker pattern.
- **Constraint:** workload, billing, KPI and DI must be untouched.
- **Report consequence:** becomes a sprint in Release 2, where operational steering lives.

---

## Chapter 1 — Project Framework

```
Introduction
1.1 Host organization
    1.1.1 Presentation of ST2i
    1.1.2 Areas of expertise
    1.1.3 Services offered
    1.1.4 Organizational structure        [figure]
1.2 Project framework
    1.2.1 Context and assigned subject
    1.2.2 Project objectives
1.3 Study of the existing system
    1.3.1 The current process
    1.3.2 Analysis of the reference workbook    [keep — 8 sheets already written]
    1.3.3 Existing market solutions
    1.3.4 Comparative synthesis                 [table]
    1.3.5 Critique of the existing system
1.4 Problem statement
1.5 Proposed solution
1.6 Project management methodology
    1.6.1 The Scrum framework                   [figure]
    1.6.2 Scrum roles
    1.6.3 Scrum ceremonies
    1.6.4 Adaptation to this project
Conclusion
```

**Why this order.** All four reference reports open on the host organization and close on
methodology. Moetez and Mariem place the study of the existing before the problem statement, which
reads better: the problem is a conclusion drawn from the study, not an assertion made before it.

**1.3.3–1.3.4, the market study.** Two of the four references include one. Four tools are compared
— MS Project, Jira, Odoo Project, monday.com — against criteria taken from the workbook rather than
invented: loaded cost rate per resource **and per year**, revenue accrued but not invoiced, billing
milestones as a percentage of contract capped at 100%, earned-value indicators, multi-currency with
a dated rate, configurable authorization. The conclusion is that these tools plan work but do not
model a consulting firm's cost and revenue structure, which is why the workbook survived alongside
them.

**Moved out.** *Organisation into Releases and Sprints* leaves chapter 1; it duplicates the release
planning in chapter 2, which is where the references keep it.

**Figures:** `st2i_logo.png`, `st2i_organigramme.png`, `scrum_cycle.png`, plus the eight anonymised
Excel captures whose slots already exist.

---

## Chapters 2 and 3 — outline

**Chapter 2** keeps the actors, functional and non-functional requirements, global use case diagram,
product backlog and release planning. Adds the Scrum team composition and a development environment
section listing tools and frameworks — all four references carry one, and the current chapter has
none.

**Chapter 3** is largely sound. Needs the global class diagram promoted to chapter level and a
deployment diagram added, now that the containerized topology is real and verified.

---

## Chapters 5 to 7 — mechanical application

Apply the chapter-4 pattern to Sprints 3 through 9: release backlog at chapter level, three
subsections per sprint, sequence diagrams in analysis and design, interface captures in realization.

---

## Open items

| Item | Owner | Note |
|---|---|---|
| Screenshots — 8 Excel, ~15 interface | user | `% TODO` slots already in place; app runs at `localhost:8081` |
| `dedicaces.tex`, `remerciement.tex` | user | empty; they name real people |
| babel main language still French | decision | "Figure" and "Table" generate in French inside an English report |
| Consolidated testing section | decision | per-sprint testing removed as instructed; 89 backend tests currently appear nowhere |
| ST2i logo and organigramme | user | needed for 1.1 |

---

## Verification per chapter

1. `pdflatex` twice, zero errors.
2. Table of contents shows the intended structure in order.
3. Rendered chapter compared side by side with the corresponding chapter of `RapportPFE3.pdf`.
4. Every factual claim traced to its source before commit.
