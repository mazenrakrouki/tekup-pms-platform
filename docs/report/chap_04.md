# Chapitre 4 — Conception de la base de données

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/chap_04.tex` (lecture rapide ; source
> officielle = `.tex`). Détail complet dans [docs/DATABASE_DESIGN.md](../DATABASE_DESIGN.md) et le
> schéma SQL dans [docs/schema.sql](../schema.sql).

## Introduction
Ce chapitre traduit le modèle métier et l'architecture en un schéma relationnel concret pour
PostgreSQL 17 : conventions, modèle logique (entités et relations), catalogue des entités, modèle
temporel mensuel et instantanés de KPI, multi-devises, paramètres, audit, données de référence et
stratégie de migration. Le schéma compte environ **26 tables** et fait office de source de vérité.

## 1. Conventions de modélisation
- Tables en `snake_case` au pluriel ; clé primaire `id` en identité.
- Clés étrangères `<entité>_id` ; horodatages en `timestamptz`.
- Montants en `numeric(18,3)` + devise (`*_currency`), jamais en flottant.
- Mois stockés comme **premier jour du mois** (série temporelle).
- Énumérations via `varchar` + `CHECK`.
- Audit (`created_by/at`, `modified_by/at`) et suppression logique (`active`, `deleted_at`) sur
  chaque table métier.

## 2. Modèle logique
Cœur = contrôle d'accès dynamique : `users` → `roles`, et `roles` ⇄ `permissions` via
`role_permissions` (M:N). Un `projects` référence un directeur (utilisateur) et regroupe ses entités
filles : affectation du chef de projet (**une seule active**), affectations d'équipe, plan de
charge, charges réelles, jalons de facturation, missions, gouvernance (risques, livrables, parties
prenantes, changements, actions, avenants) et instantanés de KPI. Une ressource a un TCC par année
et peut être rattachée à un utilisateur.

## 3. Catalogue des entités

| Domaine | Tables principales | Module |
|---|---|---|
| Sécurité / RBAC | `users`, `roles`, `permissions`, `role_permissions` | Authentification & RBAC |
| Référentiel | `currencies`, `exchange_rates`, `parameters`, échelles de risque | Configuration |
| Ressources | `resources`, `tcc` | Gestion du TCC |
| Projets | `projects`, `project_manager_assignments` | Gestion de projet |
| Équipes | `team_assignments`, `stakeholders` | Équipes / Parties prenantes |
| Charges | `workload_plan`, `actual_workload` | Plan de charge / Charges réelles |
| Facturation | `billing_milestones`, `payments`, `avenants` | Facturation / Avenants |
| Missions | `missions` | Gestion des missions |
| Gouvernance | `risks`, `deliverables`, `change_requests`, `actions` | Risques / Livrables / Changements |
| KPI | `kpi_snapshots` | KPI & Reporting |

*Tableau 4.1 — Catalogue des entités par domaine.*

## 4. Modèle temporel mensuel et instantanés de KPI
Le classeur est une grille **ressource × mois**. Plutôt que 25 colonnes de mois, le modèle est
**normalisé** : une ligne par `(projet, ressource, mois)` dans `workload_plan` et `actual_workload`.

`kpi_snapshots` stocke un instantané par `(projet, mois)` + un cumul courant → historique des KPI
(courbes d'évolution) et lectures rapides. Conformément à la correction de la phase 2, il distingue
le **coût de main-d'œuvre** (`actual_labor_cost`, Σ JH × TCC) des **autres coûts**
(`actual_other_cost`, missions/frais) ; `actual_total_cost` agrège les deux.

Les charges réelles sont **bi-sources** : `actual_workload` garde une ligne par source (manuelle +
KIMAI) via `UNIQUE(projet, ressource, mois, source)`, plus un drapeau `accepted` et un index unique
partiel garantissant **une seule valeur retenue** par cellule — c'est elle qui alimente le moteur de
KPI (D2).

## 5. Multi-devises, paramètres et audit
Chaque montant porte une devise ; conversions via taux datés (`exchange_rates`), devise de
restitution (TND) paramétrable. **Règle de date** : chaque conversion utilise le taux à la *date
métier* du montant (facture pour la facturation, mois pour les coûts mensuels, mission pour les
déplacements) ; les instantanés KPI portent une colonne de devise. Le **budget effectif** (budget
initial + avenants validés) est calculé dans l'instantané, d'où `budget_remaining = budget effectif −
EAC`. Le **coût de mission** a une `cost_currency` ; le perdiem est converti dedans avant sommation
(pas de mélange de devises). `parameters` + échelles de risque portent toutes les constantes — jamais
codées en dur. Audit + suppression logique préservent l'historique.

## 6. Données de référence
Un jeu de *seed* crée : les 4 rôles, le catalogue complet des permissions, la **matrice
rôle–permission par défaut** (`ASSIGN_DEVELOPER` au Directeur **et** au Chef de Projet — D4), les
devises, les paramètres, les échelles de risque et un administrateur d'amorçage.

## 7. Stratégie de migration
**Flyway**, migrations SQL versionnées ; le schéma fait foi, JPA en mode `validate` (les entités
correspondent au schéma, sans le générer). `docs/schema.sql` deviendra la migration `V1` lors de la
réalisation (Phase 5).

## Conclusion
Le modèle normalise le classeur Excel en ≈ 26 tables, RBAC dynamique au centre, série temporelle
mensuelle pour les charges, instantanés de KPI historisés, multi-devises et paramètres, audit et
suppression logique. La suite : la modélisation UML, puis la réalisation des modules.
