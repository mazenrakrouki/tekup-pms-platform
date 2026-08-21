# UML Validation Report — Engineering Diagrams

Final validation of the 13 diagrams in `docs/uml/engineering/*.puml` (Level 2 — implementation-aligned, not embedded in the report), after the corrections in [`UML_AUDIT.md`](UML_AUDIT.md). Each row was checked against: PlantUML syntax (Kroki), UML semantics, notation correctness, **exact match to the real backend** (class names, field names, enum values, relationship targets — verified by reading the actual `.java` entity files, not assumed), readability, and complexity.

| Diagram | UML Valid | Backend-Accurate | Readable | Complexity | Status |
|---|---|---|---|---|---|
| 00 — Class overview | ✓ | ✓ | ✓ | Medium (17 classes, 5 packages) | **APPROVED** |
| 01 — Use case | ✓ | ✓ | ✓ | Low (5 actors, 11 use cases) | **APPROVED** |
| 01 — Auth / RBAC | ✓ | ✓ (`AccessToken` explicitly noted as non-persisted) | ✓ | Low (4 classes) | **APPROVED** |
| 02 — Class (domain) | ✓ | ✓ | ✓ | Low-Medium (11 classes, 5 packages) | **APPROVED** |
| 02 — User / Resource | ✓ | ✓ | ✓ | Low (4 classes) | **APPROVED** |
| 03 — Project / Team | ✓ | ✓ | ✓ | Low-Medium (6 classes, 2 enums) | **APPROVED** |
| 03 — Sequence: Login | ✓ | ✓ | ✓ | Low (4 lifelines) | **APPROVED** |
| 04 — Financial | ✓ | ✓ | ✓ | Low-Medium (6 classes, 1 enum) | **APPROVED** |
| 04 — Sequence: Assign developer | ✓ | ✓ | ✓ | Low (5 lifelines) | **APPROVED** |
| 05 — KPI reporting | ✓ | ✓ (source field flagged as designed, not yet built) | ✓ | Medium (5 classes, one attribute-heavy) | **APPROVED** |
| 05 — Sequence: Workload → KPI | ✓ | ✓ | ✓ | Medium (5 lifelines, async phase) | **APPROVED** |
| 06 — Activity: Project lifecycle | ✓ | ✓ | ✓ | Low (4 swimlanes) | **APPROVED** |
| 07 — Activity: KPI recompute | ✓ | ✓ | ✓ | Low (single flow, one decision) | **APPROVED** |

**13/13 approved.**

## What makes this set different from the report-level one

This is where implementation exactness matters: every class name, field name, enum value, and relationship target was cross-checked against the actual JPA entity source (`backend/src/main/java/com/pms/**`). Three real defects were caught this way that a text-only review would have missed:

1. `02-class.puml` presented three entities (`WorkloadPlan`, `ActualWorkload`, `BillingMilestone`) that don't exist anywhere in the codebase.
2. `03-project-team.puml`'s `StatutProjet` enum had different values than the real `ProjectStatus` — not a translation, a different 5-state set.
3. Two diagrams modeled `TeamAssignment`/`PlanCharge`/`ChargeReelle` as relating to `Resource`, when the real entities relate directly to `User`.

One honest gap remains flagged rather than silently resolved: `ChargeReelle`'s KIMAI-import `source` field is a real, documented, in-scope business rule (confirmed in `BUSINESS_ANALYSIS.md`) but isn't yet a column on the actual entity. The diagram keeps it and says so, rather than either inventing a false "it's implemented" claim or dropping a real requirement.

## Traceability

Every class name in the approved set was found in `backend/src/main/java/com/pms/**` by direct file search, except `AccessToken` (explicitly documented as a non-persisted conceptual class representing JWT claims). Every enum's values were read from the actual Java enum source, not inferred.
