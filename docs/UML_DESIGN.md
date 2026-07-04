# CONCEPTION UML — PMS

**Projet :** Plateforme centralisée de gestion et de pilotage financier des projets informatiques (PMS)
**Phase :** 4 — Conception UML · **Statut :** En attente de validation · **Date :** 2026-06-25
**Outillage (ADR-023) :** PlantUML = source de vérité ; Mermaid = miroir lisible.
**Politique deux-niveaux (ADR-024) :**
- **Niveau 1 — UML du rapport** (`docs/uml/`) : simple, pédagogique, lisible en 30 secondes,
  une page A4 maximum. **Seul ce niveau apparaît dans le rapport.**
- **Niveau 2 — UML d'ingénierie** (`docs/uml/engineering/`) : modèle complet et détaillé,
  pour référence/maintenance uniquement.

> **Règle de décision :** en cas de conflit entre exhaustivité et lisibilité, la lisibilité gagne.

---

## 1. Diagramme de cas d'utilisation (Niveau 1)
**Source :** [`docs/uml/01-use-case.puml`](uml/01-use-case.puml) · **Rendu :** `img/use_case.png`

4 acteurs (Administrateur, Directeur, Chef de Projet, Développeur) et 12 cas d'utilisation métier.

```mermaid
graph LR
  Admin([Administrateur]):::a --> U1[S'authentifier]
  Dir([Directeur]):::a --> U1
  PM([Chef de Projet]):::a --> U1
  Dev([Développeur]):::a --> U1
  Admin --> Uu[Gérer utilisateurs et rôles]
  Admin --> Ut[Gérer le TCC]
  Dir --> Uproj[Créer et superviser les projets]
  Dir --> Uapm[Affecter un chef de projet]
  Dir --> Uteam[Constituer l'équipe]
  Dir --> Uexec[Portefeuille et KPI exécutifs]
  PM --> Uteam
  PM --> Uplan[Planifier la charge]
  PM --> Ubill[Facturation et missions]
  PM --> Upmkpi[KPI du projet]
  Dev --> Usub[Saisir les charges réelles]
  Dev --> Uhist[Mes affectations]
  classDef a fill:#1f4e79,color:#fff;
```

---

## 2. Diagramme de packages (Niveau 1)
**Source :** [`docs/uml/02-package.puml`](uml/02-package.puml) · **Rendu :** `img/package_diagram.png`

Les **10 modules fonctionnels** et leurs dépendances principales (les flèches du bas convergent
vers le moteur de KPI, qui agrège tout le reste).

```mermaid
flowchart TB
  AUTH[Authentification et RBAC]
  USER[Gestion des utilisateurs]
  PROJ[Gestion des projets]
  TEAM[Gestion des équipes]
  TCC[Gestion du TCC]
  PLAN[Plan de charge]
  ACTUAL[Charges réelles]
  BILL[Facturation]
  MISS[Gestion des missions]
  KPI[KPI et Reporting]
  USER -.-> AUTH
  PROJ -.-> AUTH
  TEAM -.-> PROJ
  TEAM -.-> USER
  PLAN -.-> PROJ
  PLAN -.-> TEAM
  ACTUAL -.-> PLAN
  BILL -.-> PROJ
  MISS -.-> PROJ
  TCC -.-> USER
  PLAN --> KPI
  ACTUAL --> KPI
  BILL --> KPI
  MISS --> KPI
  TCC --> KPI
```

---

## 3. Diagrammes de classes (Niveau 1, 4 domaines)
Pour la lisibilité, le modèle est **éclaté en 4 diagrammes de domaine** (ADR-024). Tous omettent les
champs d'audit et les classes techniques (Controller/Service/Repository/DTO/Mapper).

### 3.1 Domaine Sécurité
**Source :** [`03-class-security.puml`](uml/03-class-security.puml) · **Rendu :** `img/class_security.png`

```mermaid
classDiagram
  class User { +email; +active }
  class Role { +name }
  class Permission { +code; +module }
  User "*" --> "1" Role : possède
  Role "*" -- "*" Permission : accorde
```

### 3.2 Domaine Projet & Équipe
**Source :** [`04-class-project-team.puml`](uml/04-class-project-team.puml) · **Rendu :** `img/class_project_team.png`

```mermaid
classDiagram
  class Project { +code; +status; +budget }
  class User { +email }
  class Resource { +fullName }
  class ProjectManagerAssignment { +active }
  class TeamAssignment { +staffingPercent; +active }
  Project "*" --> "1" User : directeur
  Project "1" --> "*" ProjectManagerAssignment
  ProjectManagerAssignment "*" --> "1" User : chef de projet
  Project "1" --> "*" TeamAssignment
  TeamAssignment "*" --> "1" Resource
  Resource "0..1" --> "1" User : peut être (ADR-022)
```

### 3.3 Domaine Financier
**Source :** [`05-class-financial.puml`](uml/05-class-financial.puml) · **Rendu :** `img/class_financial.png`

```mermaid
classDiagram
  class Project { +code; +budget }
  class BillingMilestone { +percent; +amount; +status }
  class Payment { +amount; +paidDate }
  class Mission { +totalCost }
  class Avenant { +amount }
  Project "1" --> "*" BillingMilestone
  BillingMilestone "1" --> "*" Payment
  Project "1" --> "*" Mission
  Project "1" --> "*" Avenant
```

### 3.4 Domaine Charges & KPI
**Source :** [`06-class-kpi.puml`](uml/06-class-kpi.puml) · **Rendu :** `img/class_kpi.png`

```mermaid
classDiagram
  class Project { +code }
  class Resource { +fullName }
  class Tcc { +year; +annualCost }
  class WorkloadPlan { +month; +plannedMd }
  class ActualWorkload { +month; +manDays; +source }
  class KpiSnapshot { +snapshotMonth; +eac; +marginEac }
  Resource "1" --> "*" Tcc
  Project "1" --> "*" WorkloadPlan
  WorkloadPlan "*" --> "1" Resource
  Project "1" --> "*" ActualWorkload
  ActualWorkload "*" --> "1" Resource
  Project "1" --> "*" KpiSnapshot
```

---

## 4. Diagrammes de séquence (Niveau 1)

### 4.1 Connexion
**Source :** [`07-seq-login.puml`](uml/07-seq-login.puml) · **Rendu :** `img/seq_login.png`

```mermaid
sequenceDiagram
  autonumber
  actor User as Utilisateur
  participant SPA as Frontend
  participant AC as AuthController
  participant AS as AuthService
  participant DB as Base de données
  User->>SPA: e-mail + mot de passe
  SPA->>AC: POST /api/auth/login
  AC->>AS: authentifier
  AS->>DB: vérifier l'utilisateur
  DB-->>AS: utilisateur
  AS-->>AC: jeton JWT + premier login ?
  AC-->>SPA: jeton + redirection
  alt première connexion
    SPA-->>User: changer le mot de passe
  else connexion normale
    SPA-->>User: accès au tableau de bord
  end
```

### 4.2 Affecter un développeur (permission ET portée)
**Source :** [`08-seq-assign-developer.puml`](uml/08-seq-assign-developer.puml) · **Rendu :** `img/seq_assign_developer.png`

```mermaid
sequenceDiagram
  autonumber
  actor U as Directeur / CP
  participant SPA as Frontend
  participant TC as TeamController
  participant TS as TeamService
  participant DB as Base de données
  U->>SPA: choisir un développeur
  SPA->>TC: POST /api/projects/{id}/team
  TC->>TC: vérifier permission ASSIGN_DEVELOPER
  TC->>TC: vérifier portée (projet accessible)
  alt refusé
    TC-->>SPA: 403 Interdit
  else autorisé
    TC->>TS: affecter
    TS->>DB: enregistrer
    TC-->>SPA: 201 Créé
  end
```

### 4.3 Saisir les charges → recalcul KPI
**Source :** [`09-seq-submit-workload-kpi.puml`](uml/09-seq-submit-workload-kpi.puml) · **Rendu :** `img/seq_submit_workload.png`

```mermaid
sequenceDiagram
  autonumber
  actor D as Développeur
  participant SPA as Frontend
  participant AC as WorkloadController
  participant WS as WorkloadService
  participant KPI as KpiService
  participant DB as Base de données
  D->>SPA: saisir charges (mois, JH)
  SPA->>AC: POST /api/workload/actual
  AC->>WS: enregistrer
  WS->>DB: sauvegarder
  AC-->>SPA: 200 OK
  Note over WS,KPI: Recalcul asynchrone
  WS->>KPI: déclencher recalcul (projet)
  KPI->>DB: lire TCC, plan, réels, facturation
  KPI->>DB: écrire KpiSnapshot
```

---

## 5. Diagrammes d'activité (Niveau 1)

### 5.1 Cycle de vie d'un projet
**Source :** [`10-act-project-lifecycle.puml`](uml/10-act-project-lifecycle.puml) · **Rendu :** `img/act_project_lifecycle.png`

```mermaid
flowchart TD
  A1[Admin : créer utilisateurs] --> A2[Admin : maintenir TCC]
  A2 --> B1[Directeur : créer un projet]
  B1 --> B2[Directeur : affecter un CP]
  B2 --> C1[CP : constituer l'équipe]
  C1 --> C2[CP : planifier la charge]
  C2 --> C3[CP : facturation et missions]
  C3 --> D1[Dev : saisir charges mensuelles]
  D1 --> S1[Système : recalculer les KPI]
  S1 --> E1[CP : piloter la rentabilité]
  S1 --> E2[Directeur : superviser le portefeuille]
```

### 5.2 Recalcul hybride des KPI
**Source :** [`11-act-kpi-recompute.puml`](uml/11-act-kpi-recompute.puml) · **Rendu :** `img/act_kpi_recompute.png`

```mermaid
flowchart TD
  T[Événement déclencheur] --> P[Publier après commit]
  P --> L[Charger données du projet]
  L --> CC[Calculer coûts main-d'œuvre + autres]
  CC --> KC[Calculer EAC, marges, CA Production, FAE]
  KC --> S[Écrire KpiSnapshot]
  S --> N[Notifier tableaux de bord]
```

---

## 6. Synthèse — diagrammes Niveau 1 (rapport)

| # | Type | Source PlantUML | Rendu PNG |
|---|------|-----------------|-----------|
| 1 | Cas d'utilisation | `01-use-case.puml` | `img/use_case.png` |
| 2 | Packages (10 modules) | `02-package.puml` | `img/package_diagram.png` |
| 3 | Classes — Sécurité | `03-class-security.puml` | `img/class_security.png` |
| 4 | Classes — Projet & Équipe | `04-class-project-team.puml` | `img/class_project_team.png` |
| 5 | Classes — Financier | `05-class-financial.puml` | `img/class_financial.png` |
| 6 | Classes — Charges & KPI | `06-class-kpi.puml` | `img/class_kpi.png` |
| 7 | Séquence — Connexion | `07-seq-login.puml` | `img/seq_login.png` |
| 8 | Séquence — Affecter développeur | `08-seq-assign-developer.puml` | `img/seq_assign_developer.png` |
| 9 | Séquence — Charges → KPI | `09-seq-submit-workload-kpi.puml` | `img/seq_submit_workload.png` |
| 10 | Activité — Cycle de vie projet | `10-act-project-lifecycle.puml` | `img/act_project_lifecycle.png` |
| 11 | Activité — Recalcul KPI | `11-act-kpi-recompute.puml` | `img/act_kpi_recompute.png` |

---

## 7. Annexe — UML d'ingénierie (Niveau 2, hors rapport)

Les diagrammes détaillés (modèle de classes complet aligné sur `schema.sql`, séquences avec toutes
les interactions, contexte de sécurité, ScopeService, etc.) sont conservés sous
[`docs/uml/engineering/`](uml/engineering/) pour référence et maintenance. **Ils ne sont pas inclus
dans le rapport PFE** (ADR-024).

| Source d'ingénierie | Rendu | Objet |
|---|---|---|
| `engineering/01-use-case.puml` | `engineering/img/use_case.png` | 6 acteurs, ~17 cas d'utilisation détaillés |
| `engineering/02-class.puml` | `engineering/img/class_diagram.png` | Modèle complet (~26 classes + énumérations) |
| `engineering/03..05-seq-*.puml` | `engineering/img/seq_*.png` | Séquences détaillées (cache de sécurité, ScopeService, Bus d'événements) |
| `engineering/06..07-act-*.puml` | `engineering/img/act_*.png` | Activités détaillées |

*Fin de la conception UML (Phase 4). En attente de validation avant la Phase 5 — Authentification &
RBAC dynamique (début du code).*
