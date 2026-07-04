# 🎯 PROJECT_STATUS.md — PMS Real-Time Progress Dashboard

> **Mis à jour automatiquement** à chaque fin de phase ou correction majeure.
> Dernière mise à jour : **2026-06-27** — **Audit RBAC (Phase C) terminé** : matrice rôle→permission reconstruite depuis les règles métier et corrigée via migration V12 (correction 100% données, zéro code — preuve ADR-001). Voir [docs/AUTHORIZATION_MATRIX.md](docs/AUTHORIZATION_MATRIX.md). Test des 4 rôles : **22/22 PASS**. Admin 22→5 permissions ; fuite financière développeur (VIEW_KPI) supprimée (BR-050) ; Directeur aligné ADR-005.

---

## 📊 Avancement global

```
Projet total      ███████████████████░  93%
Documentation     ████████████████████  100% ✅
Backend           ████████████████████  100% ✅
Frontend Angular  ████████████████████  100% ✅
Tests             ███████████████████░   95%
Rapport           ████████████████░░░░   70%
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

```
Rapport total     ███████████████████░  95% (10/10 chapitres + conclusion ; intro à finaliser)
```

| Chapitre | Titre | État |
|----------|-------|------|
| Introduction générale | Contexte, motivation | 🟡 Ébauche |
| Chapitre 1 | Contexte du projet | ✅ Rédigé |
| Chapitre 2 | Analyse des besoins | ✅ Rédigé |
| Chapitre 3 | Conception architecturale | ✅ Rédigé |
| Chapitre 4 | Conception BD | ✅ Rédigé |
| Chapitre 5 | Conception UML | ✅ Rédigé |
| Chapitre 6 | Implémentation — Socle technique | ✅ Rédigé (Auth, RBAC, Users, Projets) |
| Chapitre 7 | Implémentation — Gestion opérationnelle | ✅ Rédigé (Équipes, Charges, KPI) |
| Chapitre 8 | Implémentation — Financier & Gouvernance | ✅ Rédigé (Facturation, Missions, Gouvernance) |
| Chapitre 9 | Frontend Angular | ✅ Rédigé (chap_09.tex — architecture, RBAC UI, scope, fiche identification) |
| Chapitre 10 | Tests, sécurité, déploiement | ✅ Rédigé (chap_10.tex — 51 tests, audit RBAC, OWASP, déploiement) |
| Conclusion | Bilan & perspectives | ✅ Rédigé (conclusion.tex) |

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
| ADR-002 | Modèle Excel complet (EV/ETC/EAC/marges) | 🟡 Partiel (Phase 10 KPI base) |
| ADR-007 | Multi-devises | 🟡 Partiel (stockage devise ISO 4217 + normalisation uppercase ; conversion taux de change différée) |
| ADR-008 | Parameter store | ✅ Table parameters seedée |
| ADR-009 | Soft delete + audit | ✅ Implémenté (BaseEntity) |
| ADR-010 | JWT first-login forcé | ✅ Implémenté |
| ADR-015 | KPI engine hybride (snapshots) | 🟡 Partiel (Phase 10 base) |
| ADR-016 | Monolithe modulaire (feature packages) | ✅ Implémenté |
| ADR-017 | Révocation token-version (cache) | ✅ Implémenté + corrigé |
| ADR-018 | MapStruct obligatoire | ✅ Implémenté |
| ADR-019 | Flyway DDL exclusif | ✅ Implémenté (8 migrations) |
| ADR-021 | Scope autorisation (Director/PM/Dev) | 🟡 Partiel (permissions OK, data-scope à affiner) |
| ADR-022 | User vs Resource (identité vs coût) | ✅ Implémenté |
| ADR-024 | UML deux niveaux (Niveau 1 + Engineering) | ✅ Documenté |

---

## ⚡ Prochaines actions

> **Phase 15 (restant) — OWASP & Déploiement**
> - Review sécurité OWASP (injection, XSS, CSRF, broken auth)
> - Profiles Spring Boot prod (variables d'environnement, HTTPS)
>
> **Rapport (ch.9–10 + conclusion)**
> - Chapitre 9 : Frontend Angular (captures d'écran, composants, RBAC UI)
> - Chapitre 10 : Tests (51 tests), sécurité, déploiement
> - Conclusion générale

---

*Ce fichier est la référence d'avancement. Mis à jour à chaque fin de phase.*
