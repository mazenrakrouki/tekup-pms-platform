# UML Audit — Engineering Diagrams (`docs/uml/engineering/`)

Scope: the 13 diagrams under `docs/uml/engineering/*.puml` (Level 2, per ADR-024 — detailed, engineering-facing, not embedded in the report). Direction confirmed with the user: unlike the report-level set (which prioritizes conceptual clarity in English), engineering diagrams keep **exact technical terminology from the real implementation** — including French class/field names where the actual Java code uses French (e.g. `JalonFacturation`, `Avenant`, `Livrable`, `PartiePrenante`), and English where the code uses English (e.g. `User`, `Resource`, `TeamAssignment`).

## Corrections applied (2026-08)

Every naming/content problem identified in the initial audit was fixed and verified against the real backend (`backend/src/main/java/com/pms/**`), not guessed:

| Problem | Fix | Verified against |
|---|---|---|
| `Utilisateur`, `Ressource`, `Risque` used instead of the real class names | Renamed to `User`, `Resource`, `Risk` everywhere | entity file listing |
| `AffectationEquipe` used instead of the real class name | Renamed to `TeamAssignment` | `TeamAssignment.java` |
| `Intervenant` (no real backend match) | Renamed to `PartiePrenante` — the closest real entity (name/role/influence fields match) | `PartiePrenante.java` |
| `WorkloadPlan`, `ActualWorkload`, `BillingMilestone` (`02-class.puml`) — don't exist | Renamed to `PlanCharge`, `ChargeReelle`, `JalonFacturation` | entity file listing |
| `StatutProjet` enum with fabricated values (`BROUILLON/ACTIF/EN_ATTENTE/TERMINE/ANNULE`) | Replaced with the real `ProjectStatus` (`DRAFT/ACTIVE/ON_HOLD/COMPLETED/CANCELLED`) | `ProjectStatus.java` |
| `StatutJalon` — wrong enum name and wrong first value (`EN_ATTENTE` instead of `PREVU`) | Fixed to `JalonStatut {PREVU, FACTURE, PAYE}` | `JalonStatut.java` |
| `Resource` shown with invented `nomComplet`/`profil` fields that don't exist on the entity | Replaced with the real fields (`dailyRate`, `tccRate`, `staffingStart`, `staffingEnd`); identity comes from the linked `User`, not stored on `Resource` | `Resource.java` |
| `AffectationEquipe`/`PlanCharge`/`ChargeReelle` shown relating to `Resource` | Corrected to relate to `User` — the real entities reference `User`, not `Resource`, directly | `TeamAssignment.java`, `PlanCharge.java`, `ChargeReelle.java` |
| `TCC` class name inconsistent with `TCC`/`TccAnnuel` used elsewhere | Standardized on `TccAnnuel` (the real class name) everywhere | `TccAnnuel.java` |
| `User` missing `tokenVersion` in `01-auth-rbac.puml` while it's referenced by a note about revocation | Added the field | `User.java` |
| `ChargeReelle.source` / `SourceCharge` enum — KIMAI dual-source is real and documented in-scope (`BUSINESS_ANALYSIS.md`, ADR-003), but no `source` column exists on the entity yet | Kept the field (it's the designed target state) but added an explicit note flagging it as designed-but-not-yet-persisted, so the diagram doesn't misrepresent the current implementation | `ChargeReelle.java` (field absent), `BUSINESS_ANALYSIS.md:56,76,96,140,176,340,356` (feature confirmed real) |
| `JetonAcces` — no persisted token entity exists | Kept as a conceptual (non-persisted) class representing the JWT claims, renamed `AccessToken` for consistency, note added clarifying it isn't a JPA entity | confirmed no `Token`/`AccessToken` entity exists |
| Package labels and all prose (actors, sequence messages, activity steps, notes) were French | Translated to English for consistency with the rest of the now-English `docs/uml/` tree — this is a presentation-language choice, not a technical-accuracy one; French class/field names that are genuinely real were left untouched |
| One `ADR-003` reference introduced during this pass in a note | Caught in self-review and removed — no diagram in either folder should cite internal reference codes |

## Per-diagram final status

| # | Diagram | Status |
|---|---|---|
| 00 | class-overview | Renamed to real entities, added visibility markers (were missing), `ProjectStatus` typed correctly, package labels translated |
| 01 | use-case | Translated; KIMAI actor/use-case kept (verified real, in-scope) |
| 01 | auth-rbac | `Utilisateur→User`, `JetonAcces→AccessToken` (kept as non-persisted concept, noted), `tokenVersion` added |
| 02 | class (domain overview) | Renamed 3 nonexistent classes to real ones; fixed `Resource`'s invented fields; fixed `PlanCharge`/`ChargeReelle`→`User` relationships; package labels translated |
| 02 | user-resource | Renamed to real entities; `Resource`'s fields corrected to the real ones; `User` fields corrected to `firstName/lastName` |
| 03 | project-team | Renamed to real entities (`User`, `TeamAssignment`, `PartiePrenante`); `ProjectStatus` enum values corrected; `TeamAssignment→Resource` relationship corrected to `TeamAssignment→User` |
| 03 | seq-login | Translated only — no entity-name issues existed |
| 04 | financial | `Risque→Risk`; `JalonStatut` enum name and values corrected |
| 04 | seq-assign-developer | Translated only |
| 05 | kpi-reporting | `Ressource→Resource`; `SnapshotKpi` fields corrected to the real ones (was inventing some); `source` field flagged as designed-not-yet-implemented; ADR reference removed after self-review |
| 05 | seq-submit-workload-kpi | Translated only |
| 06 | act-project-lifecycle | Translated only |
| 07 | act-kpi-recompute | Translated only |

All 13 render cleanly via Kroki (HTTP 200) and were visually inspected — correct layout, no overlapping text, no truncated labels.

See [`UML_VALIDATION_REPORT.md`](UML_VALIDATION_REPORT.md) for the final scored table.
