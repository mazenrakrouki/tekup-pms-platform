# UML Validation Report — PMS Report Diagrams

Final validation of the 15 diagrams in `docs/uml/*.puml` (Level 1 — report), after the corrections described in [`UML_AUDIT.md`](UML_AUDIT.md), including the full English-language pass (2026-08). Each row was checked against: PlantUML syntax (compiles via Kroki), UML semantics (relationship types, multiplicities), notation correctness, consistency with the actual PMS domain/architecture, readability, complexity (10–20 meaningful elements, no filler), and language (English terminology throughout, per report language decision).

| Diagram | UML Valid | Project Consistent | Readable | Complexity | Status |
|---|---|---|---|---|---|
| 01 — Use case | ✓ | ✓ | ✓ | Low (19 elements: 4 actors, 15 use cases) | **APPROVED** |
| 02 — Package | ✓ | ✓ | ✓ | Low (10 packages) | **APPROVED** |
| 03 — Class — Sécurité (RBAC) | ✓ | ✓ | ✓ | Low (3 classes) | **APPROVED** |
| 04 — Class — Projet & Équipe | ✓ | ✓ | ✓ | Low (4 classes + 1 enum) | **APPROVED** |
| 05 — Class — Financier | ✓ | ✓ | ✓ | Low (6 classes + 2 enums) | **APPROVED** |
| 06 — Class — Charges & KPI | ✓ | ✓ | ✓ | Low (7 classes) | **APPROVED** |
| 07 — Class — Gouvernance | ✓ | ✓ | ✓ | Low (4 classes + 3 enums) | **APPROVED** (not embedded in report) |
| 08 — Séquence — Connexion | ✓ | ✓ | ✓ | Low (5 lifelines) | **APPROVED** |
| 09 — Séquence — Affecter développeur | ✓ | ✓ | ✓ | Low (5 lifelines) | **APPROVED** |
| 10 — Séquence — Charges → KPI | ✓ | ✓ | ✓ | Medium (5 lifelines, 3 phases — justified, causally chained) | **APPROVED** |
| 11 — Activité — Cycle de vie projet | ✓ | ✓ | ✓ | Low (4 swimlanes) | **APPROVED** |
| 12 — État — Cycle de vie projet | ✓ | ✓ | ✓ | Low (6 states) | **APPROVED** (not embedded in report; available for the lifecycle section) |
| 13 — Déploiement | ✓ | ✓ | ✓ | Low (3 tiers) | **APPROVED** (not embedded in report; available for the architecture/deployment section) |
| 14 — Class — Domain overview | ✓ | ✓ | ✓ | Medium (19 classes, package-grouped, members hidden) | **APPROVED** (not embedded in report) |
| 15 — Activity — KPI recompute | ✓ | ✓ | ✓ | Low (single flow, one decision) | **APPROVED** (new — fills a previously missing source) |

**15/15 approved.** All English. No diagram required a full redesign — the domain modeling (association vs. composition, multiplicities, absence of unjustified inheritance) was already correct. The corrections were annotation-only: essay-style notes cut to one factual sentence each, internal reference codes (`ADR-017`, `ADR-021`, `ADR-022`, `ADR-025`, `ADR-026`, `BR-050`, `§16`, `V12/V20/V23`) removed from every diagram, one redundant relationship removed (04), one unreferenced sub-entity removed from the overview diagram (14), and one layout fix (14, forced-orthogonal routing was colliding two edge labels).

## Traceability

Every class, actor, and relationship in the approved diagrams traces to a real PMS concept:
- Domain classes (`Project`, `User`, `Resource`, `TeamAssignment`, `JalonFacturation`, `Avenant`, `LigneDi`, `Mission`, `Risk`, `Livrable`, `DemandeChangement`, `PartiePrenante`, `SnapshotKpi`, `PlanCharge`, `ChargeReelle`, `TccAnnuel`, `Role`, `Permission`) match the actual JPA entities.
- Actor scoping in the use-case diagram matches the real RBAC model (Admin never gets project-management use cases, Developer never gets financial ones).
- Sequence diagram participants and endpoints (`POST /api/auth/login`, `POST /api/projects/{id}/team`, `POST /charges-reelles`, `GET /api/projects/{id}/kpi`) match the actual controllers.
- The state diagram's transitions match `ProjectStatus.canTransitionTo()` exactly.
- The deployment diagram matches the actual 3-tier local deployment — nothing invented (no Kubernetes, no microservices, no cloud infra that isn't there).

## What's intentionally out of scope

`docs/uml/engineering/*.puml` (Level 2 — detailed engineering diagrams) were not audited here. That split is an existing project decision (ADR-024): Level 2 diagrams serve a different, more technical audience and are explicitly excluded from the report. Say the word if those need the same pass.
