# UML Audit — PMS Report Diagrams

Scope: the 15 diagrams under `docs/uml/*.puml` (Level 1 — report, per ADR-024). `docs/uml/engineering/*.puml` (Level 2 — implementation detail) has its own separate audit at `docs/uml/engineering/UML_AUDIT.md`.

## 2026-08 update — full English pass

The report will be written entirely in English. All report-level diagrams were translated from French to English: class names, enum values, relationship labels, actor names, use-case labels, sequence messages, activity steps, and notes. This superseded the earlier note-trimming/ADR-removal pass (which is still in effect — every note stayed one line, no internal reference codes were reintroduced during translation).

Naming choices, since the real backend mixes English and French class names and a couple of report diagrams used placeholder names that don't exist anywhere in the code:

| Report term | Real backend entity | Note |
|---|---|---|
| `User`, `Resource`, `Project`, `TeamAssignment`, `Risk` | same | already English in code |
| `BillingMilestone` | `JalonFacturation` | conceptual English label for the report; engineering diagrams keep the real name |
| `Payment` | `Paiement` | " |
| `Amendment` | `Avenant` | " |
| `QuoteLine` / `QuoteSection` | `LigneDi` / `SectionDi` | "Internal Quote" is the report's English name for "Devis Interne" |
| `Deliverable`, `Stakeholder`, `ChangeRequest` | `Livrable`, `PartiePrenante`, `DemandeChangement` | " |
| `WorkloadPlan` / `ActualWorkload` | `PlanCharge` / `ChargeReelle` | " |
| `KpiSnapshot` / `AnnualTcc` | `SnapshotKpi` / `TccAnnuel` | reordered to natural English, same entity |
| `ProjectStatus` values `DRAFT/ACTIVE/ON_HOLD/COMPLETED/CANCELLED` | same | already matches the real Java enum exactly — no change needed |

This is a conceptual/report-level vocabulary, not a rename of the actual codebase. It's documented here once so the mapping stays traceable, and should be introduced consistently in the report text (a short terminology note in chapter 5's introduction, or a glossary entry, is recommended so a reader who cross-references the code isn't confused by e.g. `BillingMilestone` vs. `JalonFacturation`).

A missing source was found and fixed while doing this pass: the report embeds `act_kpi_recompute.png` in `chap_05.md`, but no report-level `.puml` source existed for it (only an engineering-level one did). Created `15-act-kpi-recompute.puml` as the report-level source, English, one-line note, no internal references.

## Per-diagram final status

| # | Diagram | Status |
|---|---|---|
| 01 | Use case | Translated, re-rendered, verified. **2026-08 refinement:** introduced actor generalization (*Platform User* for the shared Authenticate use case, *Portfolio User* for the shared KPI-viewing use case) — replaces redundant per-actor associations with proper UML generalization; re-rendered and pushed to the report image folder |
| 02 | Package | Translated, re-rendered, verified |
| 03 | Class — Security (RBAC) | Attributes were already English; title/labels/notes translated |
| 04 | Class — Project & Team | Attributes were already English; title/labels/notes translated; redundant edge still removed (kept from the earlier pass) |
| 05 | Class — Financial | Translated (`BillingMilestone`/`Payment`/`Amendment`/`QuoteLine`), re-rendered, verified |
| 06 | Class — Workload & KPI | Translated (`WorkloadPlan`/`ActualWorkload`/`KpiSnapshot`/`AnnualTcc`), re-rendered, verified |
| 07 | Class — Governance | Translated (`Deliverable`/`Stakeholder`/`ChangeRequest`), re-rendered, verified — not embedded in report |
| 08 | Sequence — Login | Translated, re-rendered, verified |
| 09 | Sequence — Assign developer | Translated, re-rendered, verified |
| 10 | Sequence — Workload → KPI | Translated; also removed a `BR-033` reference missed in the first pass; re-rendered, verified |
| 11 | Activity — Project lifecycle | Translated, re-rendered, verified |
| 12 | State — Project lifecycle | Translated, re-rendered, verified — not embedded in report |
| 13 | Deployment | Translated, re-rendered, verified — not embedded in report |
| 14 | Class — Domain overview | Translated; layout hints re-added after the first render came out too wide (single row of 7 packages) — fixed to a balanced 2-row layout; re-rendered, verified — not embedded in report |
| 15 | Activity — KPI recompute | **New** — created to fill the missing source for the already-embedded `act_kpi_recompute.png` |

All 15 render cleanly via Kroki (HTTP 200) and were visually inspected. The 11 embedded in `docs/report/chap_05.md` have had their PNGs regenerated in `report/Rapport PFE TEKUP LATEX/img/` — the report now shows the English versions. No LaTeX changes needed (filenames unchanged).

## Final quality pass (2026-08) — notation consistency & presentation

A last review pass over the whole report-level set, checking the things a compile can't catch.
Three classes of issue were found and fixed; everything else was verified and left alone.

### 1. Inconsistent notation for the same modeling concept (real UML issue)

Enum-typed attributes were drawn two different ways across the set: `04` used a dependency
(`..>`), while `05` and `07` used an association (`-->`). An enum used as an attribute type is a
**usage dependency**, not a structural association between instances — an association would imply
`Risk` holds a link to a `RiskStatus` *object*, which is not what an enum type means. Unified all
9 enum-typing relationships on `..>`.

| File | Was | Now |
|---|---|---|
| `05-class-financial` | `BillingMilestone --> MilestoneStatus`, `QuoteLine --> QuoteSection` | `..>` |
| `07-class-governance` | `Risk --> RiskLevel`, `Risk --> RiskStatus`, `Deliverable --> DeliverableStatus` | `..>` |
| `04-class-project-team` | already `..>` | unchanged |

### 2. Types referenced but never defined (real UML issue)

`07-class-governance` typed `ChangeRequest.priority : ChangePriority` and
`ChangeRequest.status : ChangeStatus`, but neither enum was declared — while *every other* status
enum in the same diagram (`RiskLevel`, `RiskStatus`, `DeliverableStatus`) was declared and drawn.
Internally inconsistent, and the kind of gap a supervisor reasonably asks about. Declared both
enums (values checked against the real `PrioriteChangement` / `StatutChangement` Java enums) and
added the missing `Stakeholder ..> RiskLevel` dependency, which was also implicit. Every type
named in the governance diagram is now defined within it.

### 3. Notes long enough to distort layout (presentation issue)

Three notes were long single-line paragraphs that stretched the canvas far wider than the diagram
itself, leaving large dead space — and two of them restated what labeled arrows already showed.

| File | Action | Canvas width |
|---|---|---|
| `02-package` | Removed — the four inbound arrows are already labeled `feeds` / `yearly TCC` / `Internal Quote, budget`; the note just restated them | 1453 → 875 px |
| `12-state-project` | Both notes cut to their actual point ("Archiving is a flag, not a state." / "Any other transition is rejected (HTTP 422).") | 1677 → 995 px |
| `13-deployment` | Cut the nginx/JAR/secrets implementation trivia; kept "Single deployable: modular monolith." | 1134 → 503 px |

### Verified and deliberately left unchanged

- **`14-class-global` relationship semantics.** Re-checked composition vs. association: compositions
  are used only where the child is genuinely lifecycle-owned by `Project`; `User`↔`Role` and
  `Resource`↔`User` are plain associations because no ownership exists. Multiplicities correctly
  encode "one active PM per project, one user may manage many projects". Correct as-is.
- **`06-class-kpi`'s 9-attribute `KpiSnapshot`.** Heaviest box in the set, but the diagram *is*
  about the EVM indicators — those attributes are the subject, not clutter.
- **No `<<include>>` / `<<extend>>` added.** Considered and rejected: no use case in PMS is
  genuinely a mandatory sub-flow of another, and inventing one to demonstrate the notation would
  be exactly the "look richer" failure mode this audit exists to prevent.

### Verification performed

| Check | Result |
|---|---|
| All 15 sources compile (Kroki) | 15/15 HTTP 200, all valid PNG |
| All 15 rendered images visually inspected | No overlap, no truncation, no unreadable labels |
| Internal reference codes (`ADR-`, `BR-`, `§`, migration ids) | 0 occurrences |
| French terminology leaks | 0 occurrences |
| Enum-typing notation consistency | 9/9 use `..>` |
| Domain vocabulary consistency | Single spelling per concept, no variants |
| LaTeX chapter builds with updated images | exit 0, 10 pages, all figures resolve |

## What's not done

`docs/report/chap_05.md` itself (the French prose describing the diagrams) was not touched — translating the report body text is a separate, larger task than the diagrams and wasn't asked for here. Say the word if you want that pass too.
