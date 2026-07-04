# Chapitre 7 — Implémentation : Gestion opérationnelle

## Introduction

Ce chapitre couvre les équipes projet (phase 8), les charges de travail planifiées et réelles
(phase 9), et le moteur de KPI financiers (phase 10). Ces trois modules interagissent en permanence
et alimentent les tableaux de bord de pilotage.

---

## 1. Gestion des équipes

### 1.1 Modèle d'affectation

`TeamAssignment` : lie une `Resource` à un `Project` pour une période `startDate` / `endDate`.
Suppression douce : retrait = `deleted = true`, historique préservé.

**Index partiel unique** (prévient les doublons actifs, autorise les ré-affectations) :
```sql
UNIQUE (project_id, resource_id) WHERE deleted = FALSE
```

### 1.2 Endpoints

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects/{id}/team` | `VIEW_TEAM` | 200 |
| GET | `/api/projects/{id}/team/history` | `VIEW_TEAM` | 200 |
| POST | `/api/projects/{id}/team` | `ASSIGN_DEVELOPER` | 201 |
| DELETE | `/api/projects/{id}/team/{aid}` | `ASSIGN_DEVELOPER` | 204 |

---

## 2. Charges de travail

### 2.1 Deux flux

- **Plan de charge** (`plan_charges`) : prévisionnel mensuel (jours prévus/ressource/mois)
- **Charge réelle** (`charges_reelles`) : jours travaillés (`source = MANUAL | KIMAI`)

### 2.2 Normalisation de la période

```java
LocalDate period = LocalDate.of(year, month, 1);
```

Simplifie les agrégations mensuelles et les jointures inter-tables.

### 2.3 Index partiels uniques

```sql
-- Plan de charge
UNIQUE (project_id, resource_id, period) WHERE deleted = FALSE

-- Charge réelle
UNIQUE (project_id, resource_id, period, source) WHERE deleted = FALSE
```

### 2.4 Validation des charges réelles

Cycle : `SOUMISE` → `VALIDEE` (permission `VALIDATE_WORKLOAD`).
Une charge validée (`validatedAt` non nul) est immuable → toute modification déclenche 409.

### 2.5 Immutabilité de la période

`period` et `resourceId` ne peuvent pas être modifiés après création → 400 Bad Request.

### 2.6 Endpoints (9 endpoints)

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects/{id}/plan-charges` | `VIEW_WORKLOAD` | 200 |
| POST | `/api/projects/{id}/plan-charges` | `SUBMIT_WORKLOAD` | 201 |
| PUT | `/api/projects/{id}/plan-charges/{pid}` | `SUBMIT_WORKLOAD` | 200 |
| DELETE | `/api/projects/{id}/plan-charges/{pid}` | `SUBMIT_WORKLOAD` | 204 |
| GET | `/api/projects/{id}/charges-reelles` | `VIEW_WORKLOAD` | 200 |
| POST | `/api/projects/{id}/charges-reelles` | `SUBMIT_WORKLOAD` | 201 |
| PUT | `/api/projects/{id}/charges-reelles/{cid}` | `SUBMIT_WORKLOAD` | 200 |
| DELETE | `/api/projects/{id}/charges-reelles/{cid}` | `SUBMIT_WORKLOAD` | 204 |
| PATCH | `/api/projects/{id}/charges-reelles/{cid}/validate` | `VALIDATE_WORKLOAD` | 200 |

---

## 3. Moteur de KPI

### 3.1 Formules financières

| Indicateur | Formule | Description |
|------------|---------|-------------|
| `budgetPlanifie` | Σ (jours planifiés × TCC jour) | Coût prévisionnel ressources |
| `budgetConsome` | Σ (jours réels validés × TCC jour) | Coût charges validées |
| `EAC` | réel + planifié restant | Estimation à achèvement |
| `marge` | budget effectif − EAC | Marge financière prévisionnelle |
| `tauxConsommation` | budgetConsome / budgetPlanifie | Taux d'avancement financier |

```
coutJournalierTcc = tarifJournalier × (1 + tauxTcc)
```

Chargement TCC en **un seul accès batch** par projet (évite le N+1).

### 3.2 Calcul en direct (live)

`GET /api/projects/{id}/kpi` — recalcul à la demande, sans persistance.
`snapshotId` absent de la réponse.

### 3.3 Snapshots historiques

`POST /api/projects/{id}/kpi/snapshots` — matérialise l'état KPI dans `snapshot_kpis`.

```sql
UNIQUE (project_id, snapshot_date) WHERE deleted = FALSE
```

Double snapshot le même jour → 409 Conflict. Retourne 201 + `Location: .../snapshots/{id}`.

### 3.4 Convention API — en-tête Location

Tous les POST retournent : **201 Created** + **`Location`** pointant sur la ressource créée
(via `ServletUriComponentsBuilder`). Vérifié pour les 18 endpoints POST de l'application.

### 3.5 Endpoints

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects/{id}/kpi` | `VIEW_KPI` | 200 live |
| GET | `/api/projects/{id}/kpi/snapshots` | `VIEW_KPI` | 200 liste |
| POST | `/api/projects/{id}/kpi/snapshots` | `VIEW_KPI` | 201 + Location |

---

## Conclusion

Les trois modules forment un pipeline cohérent : affectation → charges planifiées/réelles →
KPI agrégés. La série temporelle mensuelle, les index partiels uniques et le chargement batch TCC
garantissent fiabilité et performance. Le chapitre suivant décrit les modules financier et
gouvernance.
