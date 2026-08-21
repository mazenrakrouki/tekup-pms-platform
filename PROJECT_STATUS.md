# 🎯 PROJECT_STATUS.md — PMS Real-Time Progress Dashboard

> **Mis à jour automatiquement** à chaque fin de phase ou correction majeure.
> Dernière mise à jour : **2026-08-19** — **Phase F — DevOps : conteneurisation et intégration continue.** Pile Docker Compose à 3 services (postgres:17-alpine · backend temurin-21 non-root · nginx servant le build Angular, point d'entrée unique `:8081` → plus aucun CORS) ; pipeline **GitHub Actions** (backend / frontend / images / smoke / publish GHCR) ; `docs/DEPLOYMENT.md` (clôture DOC-2) ; **ADR-026**, ADR-014 passé à *Superseded*. Trois défauts latents corrigés au passage : (1) **`angular.json` n'avait aucun `fileReplacements`** — `http://localhost:8090/api` était compilé dans le bundle de production ; (2) `/actuator/health` répondait 401, ce qui aurait bloqué `depends_on: service_healthy` ; (3) `application.yml` embarquait un mot de passe et un secret JWT de repli. **89/89 tests backend.** Rapport reconstruit : **78 pages, 0 erreur**.
> ✅ **Pile vérifiée en exécution le 2026-08-20** : 3 conteneurs `(healthy)`, backend uid=1001, 26 migrations, 127 utilisateurs / 95 projets seedés, **login réel via nginx → 200 + cookie `HttpOnly; SameSite=Strict`**, RBAC conforme à la matrice (Directeur/Chef/Dév 200, Admin 403), données conservées après `restart`.
>
> Précédent : **2026-07-05 (soir)** — **Gouvernance d'ingénierie (Phase E)** : nomination Chief Software Architect ; création de [docs/ENHANCEMENTS.md](docs/ENHANCEMENTS.md) (backlog d'ingénierie vivant, revue complète du projet, roadmap 4 phases) ; **refonte UML v2.0** — 13 diagrammes alignés sur le code (5 diagrammes de classes par domaine dont Gouvernance, séquence KPI hybride réelle remplaçant le pipeline asynchrone fictif, nouveaux diagrammes d'états et de déploiement, acteur KIMAI retiré) ; correctif découvert par l'audit : **machine à états projet désormais gardée** (`ProjectStatus.canTransitionTo`, transition illégale → 422). **89/89 tests backend.**
>
> Précédent : **2026-07-05** — **Méthode F-AFF-13 complète (Phase D)** : (1) backlog corrections clôturé — score ingénierie **88/100** (H-3 bannière KPI, N-2…N-5, headers sécurité, V20) ; (2) spec F-AFF-13 implémentée : **Devis Interne** structure vide (V23, capacité MANAGE_DI Directeur, moteur de calcul 2 passes — aucune valeur société seedée, décision BUSINESS_ANALYSIS §16), **indicateurs EVM** (V22 : EV %, Delivery %, dérive JH, CA production, FAE, marge actuelle vs vendue, revue mensuelle avec saisie EV), **TCC par année** (V21 : tarif de l'année d'imputation, fallback tarif de base). **87/87 tests backend** ; vérifié en UI de bout en bout (calcul DI 10 000 FCFA → 51 TND ✓, snapshot EV 50 % → CA prod 459 000 TND ✓).

---

## 📊 Avancement global

```
Projet total      ███████████████████░  95%
Documentation     ████████████████████  100% ✅
Backend           ████████████████████  100% ✅
Frontend Angular  ████████████████████  100% ✅
Tests             ███████████████████░   95%
DevOps (Phase F)  ███████████████████░   95%  — pile vérifiée en exécution ; reste la ligne de base à pousser
Rapport           █████████████████░░░   85%  — 78 pages, 0 erreur ; captures d'écran manquantes
```

---

## 🟢 DONE — Ce qui est terminé et validé

### 📋 Phase 0 — Setup & Analyse initiale `100%` ✅
- Analyse de tous les documents fournisseur (Cahier, BRS, SRS, UC, Plan)
- Analyse du classeur Excel (21 feuilles, 2088+ formules)
- Création de `DECISIONS.md` (ADR-001 → ADR-024)
- `PROJECT_MAP.md`, `PROJECT_TODO.md`, `PROJECT_STATUS.md`

### 📋 Phase 1 — Analyse Métier `100%` ✅ APPROUVÉ
- Acteurs, responsabilités, périmètre (4 rôles)
- Catalogue permissions dynamiques (~50 permissions)
- Matrice Rôle → Permission (ADR-001)
- Règles métier BR-001…BR-065 tracées
- Catalogue formules KPI complet (EV/ETC/EAC/CA/FAE/marges/billing)
- `docs/BUSINESS_ANALYSIS.md` — livrable principal
- `chap_01.md`, `chap_02.md` rapport (LaTeX + miroir Markdown)

### 🏗️ Phase 2 — Architecture `100%` ✅ APPROUVÉ
- Architecture 3-tiers, monolithe modulaire (ADR-016)
- Sécurité JWT access+refresh + token-version revocation (ADR-010/017)
- RBAC dynamique par permission (ADR-001)
- Moteur KPI hybride snapshots (ADR-002/015)
- Multi-devises, parameters, soft-delete/audit (ADR-007/008/009)
- `docs/ARCHITECTURE.md` — 15 sections
- `chap_03.md` rapport

### 🗄️ Phase 3 — Conception Base de Données `100%` ✅ APPROUVÉ
- ERD conceptuel (26+ entités)
- Conventions PostgreSQL, soft delete, partial unique indexes
- DDL référentiel `docs/schema.sql` (518 lignes)
- Flyway décidé (ADR-019) — `ddl-auto=validate`
- `docs/DATABASE_DESIGN.md`
- `chap_04.md` rapport

### 📐 Phase 4 — Conception UML `100%` ✅ (gate en attente)
- 11 diagrammes Niveau 1 PlantUML (UC, Package, 4×Class, 3×Séquence, 2×Activité)
- Niveau 2 engineering archivé dans `docs/uml/engineering/`
- `docs/UML_DESIGN.md` + 11 PNG rendus
- ADR-023 (PlantUML/Mermaid), ADR-024 (deux niveaux UML)
- `chap_05.md` rapport

---

### ⚙️ Backend Spring Boot — Implémentation

#### 🔐 Backend Phase 5 — Auth & RBAC Dynamique `100%` ✅
| Livrable | État |
|----------|------|
| `V1__schema_auth.sql` — users, roles, permissions, role_permissions | ✅ |
| `V2__seed_rbac.sql` — 4 rôles, 18 permissions | ✅ |
| Entités `User`, `Role`, `Permission` + `BaseEntity` | ✅ |
| `JwtService` (access token, tokenVersion) | ✅ |
| `JwtAuthenticationFilter` (cache `email:tokenVersion`) | ✅ |
| `AuthService` — login, logout (révocation + cache eviction), changePassword | ✅ |
| `AuthController` — POST /login /refresh /logout /change-password /me/context | ✅ |
| BCrypt strength 12, SecurityConfig, DataInitializer (`admin@pms.local`) | ✅ |
| 7 tests AuthControllerTest passés ✅ | ✅ |

#### 👥 Backend Phase 6 — Gestion Utilisateurs & Ressources `100%` ✅
| Livrable | État |
|----------|------|
| `V3__schema_user_resource.sql` — resources, parameters | ✅ |
| `V4__add_unique_resource_user.sql` | ✅ |
| `UserCrudService` / `UserController` — CRUD + activate/deactivate | ✅ |
| `ResourceService` / `ResourceController` — CRUD + tarif journalier + TCC | ✅ |
| MapStruct `UserMapper`, `ResourceMapper` | ✅ |

#### 🗂️ Backend Phase 7 — Gestion Projets `100%` ✅
| Livrable | État |
|----------|------|
| `V5__schema_project.sql` — projects (statut, budget effectif) | ✅ |
| `ProjectService` — CRUD, cycle de vie (PROSPECT→TERMINE), assignChefProjet | ✅ |
| `ProjectController` — 6 endpoints | ✅ |
| `existsByCode` normalisé uppercase, `findActiveByStatus` avec deleted filter | ✅ |

#### 👨‍💼 Backend Phase 8 — Gestion Équipes `100%` ✅
| Livrable | État |
|----------|------|
| `V6__schema_team.sql` — team_assignments, partial unique index | ✅ |
| `TeamAssignmentService` — affecter/retirer, historique, soft-delete | ✅ |
| `TeamController` — 5 endpoints | ✅ |
| Garde re-affectation après soft-delete (`WHERE deleted = FALSE`) | ✅ |

#### 📅 Backend Phase 9 — Charges de Travail `100%` ✅
| Livrable | État |
|----------|------|
| `V7__schema_workload.sql` — plan_charges + charges_reelles (partial unique indexes) | ✅ |
| `PlanChargeService` — CRUD, validation période immuable | ✅ |
| `ChargeReelleService` — submit, update, validate (VALIDATE_WORKLOAD), delete | ✅ |
| `WorkloadController` — 9 endpoints | ✅ |
| Période normalisée `LocalDate(year, month, 1)` | ✅ |

#### 📈 Backend Phase 10 — Moteur KPI `100%` ✅
| Livrable | État |
|----------|------|
| `V8__schema_kpi.sql` — snapshot_kpis, partial unique index | ✅ |
| `KpiService` — `budgetPlanifie`, `budgetConsome`, `EAC`, `marge`, `tauxConsommation` | ✅ |
| `KpiController` — GET live / GET snapshots / POST snapshot (201) | ✅ |
| Guard empty collection (`IN ()` vide évité) | ✅ |
| Chargement ressources batch en 1 requête | ✅ |

---

#### 💳 Backend Phase 11 — Facturation `100%` ✅
| Livrable | État |
|----------|------|
| `V9__schema_billing.sql` — jalons_facturation, paiements, avenants + permissions | ✅ |
| `JalonFacturation` (%, montant, dates, statut PREVU/FACTURE/PAYE) | ✅ |
| Règle Σ% ≤ 100 par projet — validée en service | ✅ |
| `montant` calculé : `effectiveBudget × pourcentage / 100` | ✅ |
| `PATCH /jalons/{id}/facturer` — transition PREVU → FACTURE | ✅ |
| `Paiement` — enregistrement paiements, auto-PAYE si couvert | ✅ |
| `Avenant` — modifie `project.revisedBudget` (création + annulation) | ✅ |
| `BillingController` — 11 endpoints (jalons + paiements + avenants) | ✅ |
| Permissions `MANAGE_BILLING` (Admin, Chef) + `VIEW_BILLING` (+ Directeur) seedées | ✅ |

#### ✈️ Backend Phase 12 — Missions `100%` ✅
| Livrable | État |
|----------|------|
| `V10__schema_missions.sql` — missions + composantes_mission + permissions | ✅ |
| `Mission` (projet, user, objet, lieu, dateDebut, dateFin) | ✅ |
| `ComposanteMission` (PERDIEM/BILLET/TIMBRE/TRANSPORT/SEJOUR, montant, devise) | ✅ |
| Validation `dateFin >= dateDebut` en service | ✅ |
| `devise` normalisé uppercase, défaut "TND" | ✅ |
| `MissionController` — 8 endpoints (missions + composantes) | ✅ |
| Permissions `MANAGE_MISSION` (Admin, Chef) + `VIEW_MISSION` (tous les rôles) seedées | ✅ |

#### 🏛️ Backend Phase 13 — Gouvernance & Risques `100%` ✅
| Livrable | État |
|----------|------|
| `V11__schema_governance.sql` — 4 tables (risks, livrables, parties_prenantes, demandes_changement) + permissions | ✅ |
| `Risk` — probabilite/impact/statut (OUVERT/MITIGE/FERME) | ✅ |
| `Livrable` — titre, dateEcheance, statut (EN_ATTENTE→EN_COURS→LIVRE→VALIDE) | ✅ |
| `PartiePrenante` — nom, fonction, email, téléphone, influence/intérêt | ✅ |
| `DemandeChangement` — demandeur, priorité, statut (EN_ATTENTE/APPROUVE/REJETE) + dateDecision | ✅ |
| Guards : Livrable VALIDE non modifiable/supprimable ; DC traitée non modifiable | ✅ |
| Transitions PATCH : demarrer, livrer, valider (Livrable) ; approuver, rejeter (DemandeChangement) | ✅ |
| 4 Controllers — 19 endpoints au total | ✅ |
| Permissions `MANAGE_GOVERNANCE` (Admin, Chef) + `VIEW_GOVERNANCE` (tous) seedées | ✅ |

### 🐳 Phase F — DevOps : conteneurisation & CI/CD `95%` 🟢
```
███████████████████░  95%
```

**Topologie** — point d'entrée unique `http://localhost:8081` ; l'application et `/api` partagent
la même origine, donc le cookie `pms_refresh` fonctionne **sans CORS**, contrairement au mode dev.

| Élément | État |
|---------|------|
| `docker-compose.yml` — `db` (postgres:17-alpine) · `backend` (temurin-21-jre, uid 1001) · `frontend` (nginx:1.27-alpine) | ✅ écrit, `docker compose config` valide |
| Ports hôte 5433 / 8091 / 8081 — choisis car 5432 (PostgreSQL natif), 8090 (`spring-boot:run`), 4200 (`ng serve`) et 8080 (Apache) sont occupés | ✅ |
| `backend/Dockerfile` multi-stage, non-root, healthcheck `curl /actuator/health`, `start_period: 90s` (26 migrations) | ✅ |
| `frontend/.../Dockerfile` + `docker/nginx.conf` — `^~ /api/` → `backend:8080`, `= /healthz`, `^~ /i18n/` sans cache, assets hachés `try_files $uri =404` | ✅ |
| `.github/workflows/ci.yml` — `backend` / `frontend` / `images` / `smoke` / `publish` (GHCR) | ✅ YAML valide, graphe de jobs conforme |
| `docs/DEPLOYMENT.md` (clôture **DOC-2**) + **ADR-026** ; ADR-014 → *Superseded* | ✅ |
| Rapport — Sprint 9 dans `chap_07.tex` | ✅ |
| **Exécution réelle de la pile (V3–V8)** | ✅ **Vérifiée le 2026-08-20** — 3 conteneurs sains, 26 migrations, login réel via nginx, RBAC conforme, données persistantes après `restart` |

**Défauts latents corrigés** (préexistants, révélés par la mise en pipeline) :

| # | Défaut | Preuve |
|---|--------|--------|
| D1 | `angular.json` sans `fileReplacements` → `http://localhost:8090/api` compilé **dans le bundle de production** | présent dans `chunk-5JAN45E7.js` avant, absent après ; `/api` relatif présent |
| D2 | `/actuator/health` → 401, le healthcheck n'aurait jamais été vert | après : 200 `{"status":"UP"}` sans détails ; `/api/projects` → toujours 401 |
| D3 | `mvnw`/`mvnw.cmd` **absents** alors que la doc les documentait ; `.gitignore` ignorait `.mvn/` | wrapper généré, ligne retirée |
| D4 | `.gitignore` `.env.*` avalait aussi `.env.example` | `git check-ignore` : `.env.example` → suivi, `.env` → ignoré |
| D5 | `DataInitializer` non conditionné → comptes de démo seedés **aussi en `prod`** | `@ConditionalOnProperty(pms.demo.seed-users)` + avertissement dans DEPLOYMENT.md |
| D6 | `application.yml` embarquait `${DB_PASSWORD:pms_password}` et un secret JWT littéral | replis supprimés ; **89/89 tests** toujours verts |

**Non régression (V9)** — la boucle de développement native reste intacte : profil dev démarré sur
le port 8090, `Started PmsApplication in 6.309 seconds`.

---

## 🟡 À VENIR — Prochaines phases

---

## 🟡 EN COURS — Rapport & Tests

### 🌐 Frontend Angular Phase 14 `100%` ✅
```
████████████████████  100%
```
- ✅ Angular 21 skeleton (standalone components, Bootstrap 5, French UI)
- ✅ Écran login + changement mot de passe forcé (firstLogin)
- ✅ Intercepteur HTTP (JWT), token refresh, route guards (`authGuard`, `permissionGuard`)
- ✅ Menu dynamique par permissions (codes réels du backend)
- ✅ AuthResponse enrichi (email, fullName, role, permissions dans la réponse login)
- ✅ CORS configuré (Spring Security — origins 127.0.0.1:4200 + localhost:4200)
- ✅ Tableau de bord (ProjectDashboard avec signal API)
- ✅ Formulaires Projet (status, startDate, endDate, champs corrects)
- ✅ Composant Workload (plan de charge + charges réelles avec badges)
- ✅ Composant KPI (cards par projet, taux consommation, marge)
- ✅ Composant Billing (jalons + avenants par projet)
- ✅ Composant Missions (tableau avec durée calculée)
- ✅ Composant Gouvernance (risques / livrables / demandes changement — 3 onglets)
- ✅ Composant Ressources (tarifs journaliers)
- ✅ Admin Utilisateurs (liste avec rôle et statut)
- ✅ Project detail : 6 onglets lazy-loading (info, équipe, charges, facturation, missions, gouvernance)
- ✅ Corrections : `nom`→`name`, `PrioriteChangement` enum aligné backend, User model aligné backend
- ✅ **Formulaires d'écriture complets (Phase B)** : CRUD utilisateurs, saisie + planification charges, jalons/avenants + facturer + paiements, missions + composantes, risques/livrables/changements/parties prenantes, assignation chef de projet + gestion d'équipe (UI)
- ✅ Endpoint `GET /api/roles` protégé `MANAGE_USERS` ; modals corrigés (syntaxe Angular `(click)` + `stopPropagation`)
- ❌ Tests E2E Angular (Cypress/Playwright) — non requis pour la soutenance

### 🧪 Tests d'intégration Phase 15 `95%` 🟢
```
███████████████████░  95%
```
- ✅ 7 tests `AuthControllerTest` (login, logout, refresh, changePassword)
- ✅ 9 tests `ProjectControllerTest` (CRUD + 401 + **403 RBAC dynamique** + 404 + 409)
- ✅ 8 tests `GovernanceControllerTest` (Risk + machine à états Livrable + DC)
- ✅ 8 tests `BillingControllerTest` (Jalons + Paiements + Avenants)
- ✅ 6 tests `TeamControllerTest` (assign, doublon 409, retrait 204, historique)
- ✅ 7 tests `WorkloadControllerTest` (planCharge + doublon + delete + chargeReelle + validate + idempotence)
- ✅ 6 tests `KpiControllerTest` (live 200, snapshots, POST 201+Location, doublon 409, 404)
- ✅ Helper `TestFixtures.java` (toutes permissions, set mutable, admin + viewer)
- ✅ Vérifie ADR-001 : viewer bloqué à 403 sur création de projet
- ✅ KpiController corrigé : POST /snapshots retourne maintenant Location header (cohérence convention API)
- **51/51 tests passent — BUILD SUCCESS**
- ❌ Review sécurité (OWASP)
- ❌ Configuration déploiement (profiles prod)

---

## 📝 Rapport PFE TEK-UP

> **Restructuré** : le rapport est désormais en **anglais**, en **7 chapitres**, suivant l'architecture
> Release/Sprint de l'école. Les 10 anciens chapitres français sont dans `_archive_old_chapters/`.
> Le développement est présenté en sprints **déduits des fonctionnalités réellement livrées** —
> aucune date, cérémonie, vélocité ni rétrospective inventée.

```
Rapport total     █████████████████░░░  85% — compile en 78 pages, 0 erreur (2 passes)
```

| Chapitre | Titre | État |
|----------|-------|------|
| Introduction | Contexte, motivation, plan | ✅ Réécrit en anglais |
| Chapitre 1 | *Project Context and Methodology* | ✅ Rédigé — inclut l'analyse des 8 feuilles Excel (phase de préparation) |
| Chapitre 2 | *Requirements Analysis and Specification* | ✅ Rédigé — acteurs, RBAC, backlog produit US-01…US-26, planification des releases |
| Chapitre 3 | *System Architecture and Design* | ✅ Rédigé — fusion architecture + conception BD |
| Chapitre 4 | *Release 1 — Foundation and Security* | ✅ Rédigé — Sprints 1–2 |
| Chapitre 5 | *Release 2 — Project and Operational Management* | ✅ Rédigé — Sprints 3–4 |
| Chapitre 6 | *Release 3 — Financial Management and Governance* | ✅ Rédigé — Sprints 5–7 |
| Chapitre 7 | *Release 4 — Consolidation, Delivery and Deployment* | ✅ Rédigé — Sprints 8–9 (Sprint 9 = conteneurisation & CI/CD) |
| Conclusion | Bilan & perspectives | ✅ Réécrit en anglais |

**Correctif de compilation (majeur).** Le rapport **ne compilait pas du tout** : `babel-french` redéfinit
les listes de manière incompatible avec `enumitem` v3.11, ce qui cassait **chaque** `egin{itemize}`.
Résolu dans `tpl/isipfe.cls` par `renchbsetup{StandardLists=true}` (prouvé par bissection).

**Reste à faire :** 8 captures Excel anonymisées + 5 captures d'interface — les blocs `% TODO:`
sont déjà en place, il suffit de les décommenter. Question ouverte : basculer la langue principale
de `babel` du français vers l'anglais pour que « Figure »/« Table » soient générés en anglais.

---

## 🗃️ État des migrations Flyway

| Migration | Contenu | État |
|-----------|---------|------|
| `V1__schema_auth.sql` | users, roles, permissions, role_permissions | ✅ |
| `V2__seed_rbac.sql` | 4 rôles, 18 permissions, admin user | ✅ |
| `V3__schema_user_resource.sql` | resources, parameters | ✅ |
| `V4__add_unique_resource_user.sql` | UNIQUE(user_id) sur resources | ✅ |
| `V5__schema_project.sql` | projects + statuts | ✅ |
| `V6__schema_team.sql` | team_assignments + partial index | ✅ |
| `V7__schema_workload.sql` | plan_charges + charges_reelles + partial indexes | ✅ |
| `V8__schema_kpi.sql` | snapshot_kpis + partial index | ✅ |
| `V9__schema_billing.sql` | jalons_facturation, paiements, avenants + permissions | ✅ |
| `V10__schema_missions.sql` | missions + composantes_mission + permissions | ✅ |
| `V11__schema_governance.sql` | risks, livrables, parties_prenantes, demandes_changement + permissions | ✅ |

---

## 🔑 Décisions Architecture (ADR)

| ADR | Sujet | Statut |
|-----|-------|--------|
| ADR-001 | RBAC dynamique (Permission, jamais Role) | ✅ Implémenté |
| ADR-002 | Modèle Excel complet (EV/ETC/EAC/marges) | ✅ Implémenté (EVM V22 : EV/Delivery/dérive/CA prod/FAE/marges + DI structure V23 + TCC/année V21) |
| ADR-007 | Multi-devises | 🟡 Partiel (devise ISO 4217 + taux projet exchangeRateToTnd utilisé par le DI ; historisation des taux différée) |
| ADR-008 | Parameter store | ✅ Table parameters seedée |
| ADR-009 | Soft delete + audit | ✅ Implémenté (BaseEntity) |
| ADR-010 | JWT first-login forcé | ✅ Implémenté |
| ADR-015 | KPI engine hybride (snapshots) | ✅ Implémenté (snapshots EVM : revue mensuelle figée avec EV %, faits marquants, date fin estimée) |
| ADR-016 | Monolithe modulaire (feature packages) | ✅ Implémenté |
| ADR-017 | Révocation token-version (cache) | ✅ Implémenté + corrigé |
| ADR-018 | MapStruct obligatoire | ✅ Implémenté |
| ADR-019 | Flyway DDL exclusif | ✅ Implémenté (23 migrations V1→V23) |
| ADR-021 | Scope autorisation (Director/PM/Dev) | 🟡 Partiel (permissions OK, data-scope à affiner) |
| ADR-022 | User vs Resource (identité vs coût) | ✅ Implémenté |
| ADR-024 | UML deux niveaux (Niveau 1 + Engineering) | ✅ Documenté |

---

## ⚡ Prochaines actions

> **P0 — Débloquer Docker (PROJECT_TODO F.10)**
> - Le démon Docker Desktop ne démarre pas : socket `dockerInference` obsolète (2025-12-09)
>   verrouillée par `wslservice`. `Remove-Item`, `del`, `fsutil reparsepoint delete` et un miroir
>   robocopy échouent tous. Le correctif fiable est `wsl --shutdown`, qui arrête **toutes** les
>   distributions WSL → décision utilisateur.
> - ℹ️ `EnableDockerAI` a été mis à `false` dans `%APPDATA%\Docker\settings-store.json`
>   (sauvegarde : `settings-store.json.bak-pms`) — sans effet, à restaurer si souhaité.
> - Tant que la pile n'a pas tourné : **V3–V8 non vérifiés** (images, migrations, login de bout en
>   bout à travers nginx, persistance du volume).
>
> **P0 — Pousser la ligne de base (F.11)**
> - 2 commits pour 277 fichiers non commités : un pipeline vert ne prouverait rien. À faire **avant**
>   d'activer les checks obligatoires sur `develop`/`main`.
> - ⚠️ `.env` a été créé à partir du gabarit pour valider `docker compose` — il contient encore le
>   `JWT_SECRET` de substitution. À remplacer avant toute exécution réelle (DEPLOYMENT.md §3).
>
> **P1 — Rapport**
> - 8 captures Excel anonymisées + 5 captures d'interface (blocs `% TODO:` déjà prêts).
> - Question ouverte : langue principale `babel` (français → anglais) pour les libellés générés.
>
> **P1/P2 — Backlog qualité** (voir `docs/ENHANCEMENTS.md`)
> - ADRs (amendement C-1 writer unique, recalcul jalons H-4) · seed de tests frontend (H-7)
> - Aucune cible `test` Angular déclarée — le CI ne peut pas exécuter de tests front (FE-1/T-3)
> - Review sécurité OWASP · performance NFR-001 (≤2 s)
> - Resynchroniser `docs/report/*.md` sur la nouvelle structure en 7 chapitres (F.12)

---

*Ce fichier est la référence d'avancement. Mis à jour à chaque fin de phase.*
