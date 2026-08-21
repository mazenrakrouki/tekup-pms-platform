# PROJECT_TODO.md — PMS Master Roadmap

> Status legend: **TODO · IN_PROGRESS · BLOCKED · DONE**. Priority: **P0** (critical path) ·
> **P1** (important) · **P2** (later). A task never starts before its dependencies are DONE.
> Each phase ends with a report chapter and a validation gate.

**Progress:** Backend phases 5–15 DONE ✅ — Frontend Phase 14 DONE ✅ — Tests Phase 15 95% 🟢 (89/89 backend) — **RBAC audit + scope (Phase C/C.8) DONE ✅** — **Phase F DevOps DONE ✅ (vérification runtime bloquée, F.10)** — Rapport restructuré en 7 chapitres anglais, 78 pages, 0 erreur ✅ — Phases 1,2,3 APPROVED ✅ — Phase 4 gate pending
**Current phase:** Phase F — DevOps. **Pile conteneurisée vérifiée en exécution (F.10 DONE, 2026-08-20).** Restent **F.11** (pousser la ligne de base avant d'activer les checks obligatoires) et les captures d'écran du rapport.
**Dernière correction majeure (2026-06-27) :** audit + correction de la matrice RBAC (V12) — voir [docs/AUTHORIZATION_MATRIX.md](docs/AUTHORIZATION_MATRIX.md). Admin 22→5 permissions, fuite financière développeur corrigée (BR-050), Directeur aligné ADR-005. **Correction 100% données, zéro code (preuve ADR-001).**
**See [PROJECT_STATUS.md](PROJECT_STATUS.md) for detailed progress dashboard with percentages.**

---

## Phase 0 — Project setup & analysis groundwork
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| 0.1 | Analyze all Provider documents (Cahier, BRS, SRS, UC, Plan) | DONE | P0 | — | Done in Phase 1 prep |
| 0.2 | Analyze the Excel file (21 sheets, formulas, KPIs) | DONE | P0 | — | Primary business source |
| 0.3 | Create DECISIONS.md (ADR-001…011) | DONE | P0 | 0.1,0.2 | Source of truth |
| 0.4 | Create PROJECT_MAP.md | DONE | P0 | 0.3 | Living map |
| 0.5 | Create PROJECT_TODO.md | DONE | P0 | 0.3 | This file |
| 0.6 | Copy Cahier des Charges into docs/ | DONE | P2 | — | Done |

## Phase 1 — Business Analysis  *(report ch.1–2)*
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| 1.1 | Actors & responsibilities + boundaries | DONE | P0 | 0.1 | 4 roles + System/KIMAI |
| 1.2 | Permission catalog (dynamic RBAC vocabulary) | DONE | P0 | 0.3 | ~50 permissions |
| 1.3 | Default Role→Permission matrix | DONE | P0 | 1.2 | D4 applied |
| 1.4 | End-to-end + per-module workflows | DONE | P0 | 1.1 | |
| 1.5 | Reconcile business rules (BRS+Excel+FR/UC) | DONE | P0 | 0.2 | BR-001…065 traced |
| 1.6 | Full financial/KPI formula catalog | DONE | P0 | 0.2 | EV/ETC/EAC/CA/FAE/margins |
| 1.7 | KPI-per-role + answers to user questions | DONE | P0 | 1.6 | JH/KPI/TCC explained |
| 1.8 | Conceptual entities + parameters inventory | DONE | P1 | 1.5 | Schema deferred to P3 |
| 1.9 | Write docs/BUSINESS_ANALYSIS.md | DONE | P0 | 1.1–1.8 | Main deliverable |
| 1.10 | Report ch.1 (Contexte) + ch.2 (Analyse besoins) | DONE | P0 | 1.9 | LaTeX, French, wired into main.tex |
| 1.12 | Revise BA to strictly business (defer technical) | DONE | P0 | 1.9 | ADR-013 |
| 1.13 | Complete 21-sheet Excel workbook analysis | DONE | P0 | 0.2 | §9 of BA + report §Excel |
| 1.14 | Formula→calculation coverage map (2088 formulas) | DONE | P0 | 1.13 | §10 of BA + report table |
| 1.11 | **Validation gate — Phase 1** | DONE | P0 | 1.9,1.10,1.12–1.14 | ✅ Approved by user |

## Phase 2 — Architecture Design  *(report ch.3)*
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| 2.0 | Confirm key architecture decisions (deployment, KPI strategy) | DONE | P0 | 1.11 | Native + Hybrid KPI |
| 2.1 | High-level / global architecture (3-tier, modular monolith, component view) | DONE | P0 | 2.0 | ARCHITECTURE.md §3 |
| 2.2 | Security architecture (JWT access+refresh, first-login, BCrypt) | DONE | P0 | 2.0 | §5, ADR-010/017 |
| 2.3 | Dynamic RBAC enforcement architecture (authorities, @PreAuthorize, dynamic menu, data-scope) | DONE | P0 | 2.0 | §6, ADR-001 ⭐ |
| 2.4 | Backend architecture (feature modules, layering, DTO/mapping, validation, exceptions, logging, cross-cutting) | DONE | P0 | 2.1 | §4 |
| 2.5 | Frontend architecture (Angular 21 standalone, interceptor, guards, permission directive, dynamic menu, charts) | DONE | P0 | 2.1 | §9 |
| 2.6 | KPI engine + multi-currency + parameters architecture | DONE | P0 | 2.1 | §7–§8, ADR-015 |
| 2.7 | Deployment architecture (native, profiles, config) | DONE | P1 | 2.1 | §11, ADR-014 |
| 2.8 | New ADRs (014 native, 015 KPI hybrid, 016 modular monolith, 017 JWT) | DONE | P0 | 2.1–2.7 | DECISIONS.md |
| 2.9 | Write docs/ARCHITECTURE.md | DONE | P0 | 2.1–2.8 | 15 sections |
| 2.10 | Report ch.3 (Conception architecturale) + chap_03.md mirror | DONE | P0 | 2.9 | wired + compiles |
| 2.11 | **Validation gate — Phase 2** | DONE | P0 | 2.9,2.10 | ✅ Approved (with corrections + MapStruct) |

## Phase 3 — Database Design  *(report ch.4)*
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| 3.0 | Migration tool decision (Flyway) | DONE | P0 | 2.11 | ADR-019 |
| 3.1 | Conventions + entity catalog + relationships (ERD) | DONE | P0 | 2.11 | DATABASE_DESIGN.md §1–3 |
| 3.2 | Time-series + KpiSnapshot model | DONE | P0 | 3.1 | §4, ADR-002/015 |
| 3.3 | Multi-currency + parameters + audit + seed data | DONE | P0 | 3.1 | §5–6, ADR-007/008/009 |
| 3.4 | PostgreSQL DDL (docs/schema.sql) | DONE | P0 | 3.1–3.3 | ~26 tables + seed, 518 lines |
| 3.5 | Write docs/DATABASE_DESIGN.md | DONE | P0 | 3.1–3.4 | design narrative |
| 3.6 | Report ch.4 (Conception BD) + chap_04.md mirror | DONE | P0 | 3.5 | wired + compiles |
| 3.7 | **Validation gate — Phase 3** | DONE | P0 | 3.5,3.6 | ✅ Approved (with fixes + ADR review) |

## Phase 4 — UML Design  *(report ch.5)*
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| 4.0 | Diagram tooling decision (PlantUML / Mermaid) | DONE | P0 | 3.7 | ADR-023 |
| 4.1 | Use case (Niveau 1 simplifié) | DONE | P0 | 4.0 | 12 cas, 4 acteurs |
| 4.2 | Package diagram (10 modules) | DONE | P0 | 4.0 | nouveau Niveau 1 |
| 4.3 | Class diagrams Niveau 1 (4 domaines : Sécurité, Projet&Équipe, Financier, KPI) | DONE | P0 | 4.0 | ADR-024 |
| 4.4 | Sequence Niveau 1 (3 simplifiés, ≤10 interactions) | DONE | P0 | 4.3 | 3 PNG |
| 4.5 | Activity Niveau 1 (2 simplifiés) | DONE | P1 | 4.3 | 2 PNG |
| 4.6 | Engineering UML (Niveau 2) archivé dans docs/uml/engineering/ | DONE | P1 | 4.5 | ADR-024 |
| 4.7 | Write docs/UML_DESIGN.md (Niveau 1 + annexe Niveau 2) | DONE | P0 | 4.1–4.6 | miroirs Mermaid |
| 4.8 | Report ch.5 + chap_05.md mirror (11 figures Niveau 1) | DONE | P0 | 4.7 | compile OK |
| 4.9 | **Validation gate — Phase 4** | TODO | P0 | 4.7,4.8 | en attente d'approbation |

## Phase 5 — Authentication & Dynamic RBAC (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 5.1 | Backend project skeleton (Spring Boot 3.3.6, Maven) | DONE | feature-based `auth/user/project/team/workload/kpi/shared` |
| 5.2 | User/Role/Permission/RolePermission entities + BaseEntity | DONE | V1 migration |
| 5.3 | JWT access token + tokenVersion + Caffeine cache | DONE | ADR-017 — `email:tokenVersion` key |
| 5.4 | Login / logout (révocation cache) / first-login changePassword | DONE | AuthController |
| 5.5 | `@PreAuthorize("hasAuthority('...')")` sur tous les services | DONE | ADR-001 — jamais de role check |
| 5.6 | `GET /api/me/context` — menu dynamique par permissions | DONE | UserContextResponse |
| 5.7 | V2 seed : 4 rôles, 18 permissions, admin@pms.local | DONE | DataInitializer |
| 5.8 | 7 tests AuthControllerTest (H2, profile=test) | DONE | mvn test ✅ |

## Phase 6 — Gestion Utilisateurs & Ressources (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 6.1 | CRUD users (activate/deactivate, assign role) | DONE | V3/V4 migrations |
| 6.2 | Resource CRUD (tarif JH, taux TCC) | DONE | ADR-022 user≠resource |
| 6.3 | MapStruct UserMapper + ResourceMapper | DONE | ADR-018 |

## Phase 7 — Gestion Projets (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 7.1 | Project CRUD, cycle de vie (PROSPECT→TERMINE), budget effectif | DONE | V5 migration |
| 7.2 | assignChefProjet, findByStatus (avec deleted filter) | DONE | |
| 7.3 | Code normalisé uppercase, soft-delete filtré | DONE | |
| 7.4 | **Fiche d'identification complète (fidélité Excel, ADR-002)** : contrat, client, bailleur, modèle business, type engagement, devise+taux→TND, budget licences, workload vendu/garantie, PPP + calculés (durée, budget TND, PPR 5%) | DONE | V14 ; validé sur A24001-PFS_AIE (budget 1 269 861 TND, PPR 63 493, durée 731 — exact Excel) |

## Phase 8 — Gestion Équipes (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 8.1 | TeamAssignment : affecter/retirer/historique | DONE | V6 — partial unique index |
| 8.2 | Garde re-affectation après soft-delete | DONE | `WHERE deleted = FALSE` |
| 8.3 | Validation dates (end_date ≥ start_date) | DONE | CHECK constraint |

## Phase 9 — Charges de Travail (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 9.1 | plan_charges (CRUD planification mensuelle) | DONE | V7 — partial unique index |
| 9.2 | charges_reelles (submit / update / validate / delete) | DONE | validatedAt guard |
| 9.3 | Période normalisée `LocalDate(year,month,1)` | DONE | |
| 9.4 | Immutabilité période + user sur update | DONE | correction Phase 9 |

## Phase 10 — Moteur KPI (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 10.1 | snapshot_kpis table (V8 migration) | DONE | partial unique index par date |
| 10.2 | `budgetPlanifie`, `budgetConsome`, `EAC`, `marge`, `tauxConsommation` | DONE | KpiService |
| 10.3 | Calcul EAC = réel + planifié restant (périodes sans validé) | DONE | |
| 10.4 | GET live / GET snapshots / POST snapshot (201) | DONE | KpiController |
| 10.5 | Guard empty collection (`IN ()` vide évité) | DONE | correction review |
| 10.6 | `Function<T,Long> userIdExtractor` (anti-pattern getUserId retiré) | DONE | correction review |

## Phase 11 — Facturation (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 11.1 | V9 migration : jalons_facturation, paiements, avenants | DONE | Σ%≤100 CHECK |
| 11.2 | `JalonFacturation` entity (%, montant, dates, statut PREVU/FACTURE/PAYE) | DONE | BR-038…043 |
| 11.3 | `Paiement` entity + tracking + auto-PAYE | DONE | |
| 11.4 | `Avenant` entity (recalcule effectiveBudget) | DONE | |
| 11.5 | Service + Controller + Mapper (11 endpoints) | DONE | |
| 11.6 | 8 tests BillingControllerTest | DONE | mvn test ✅ |

## Phase 12 — Missions (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 12.1 | V10 migration : missions + composantes_mission + permissions | DONE | |
| 12.2 | `Mission` entity + `ComposanteMission` (PERDIEM/BILLET/TIMBRE/TRANSPORT/SEJOUR) | DONE | BR-044…048 |
| 12.3 | Validation dateFin ≥ dateDebut, devise uppercase défaut TND | DONE | |
| 12.4 | Service + Controller (8 endpoints) + Mapper | DONE | |

## Phase 13 — Gouvernance & Risques (backend)  ✅ DONE
| # | Task | Status | Notes |
|---|------|--------|-------|
| 13.1 | V11 migration : risks, livrables, parties_prenantes, demandes_changement | DONE | EX.1–EX.5 |
| 13.2 | Machines à états : EN_ATTENTE→EN_COURS→LIVRE→VALIDE (Livrable) ; EN_ATTENTE→APPROUVE/REJETE (DC) | DONE | |
| 13.3 | 4 Controllers, 19 endpoints, 8 tests GovernanceControllerTest | DONE | mvn test ✅ |

## Phase 14 — Frontend Angular  🟢 ~90% EN COURS
| # | Task | Status | Notes |
|---|------|--------|-------|
| 14.1 | Angular 21 skeleton (Bootstrap 5, French UI, standalone) | DONE | |
| 14.2 | Login + changement mot de passe forcé (firstLogin guard) | DONE | |
| 14.3 | Intercepteur JWT, refresh, route guards (authGuard, permissionGuard) | DONE | |
| 14.4 | Menu dynamique par permissions (codes réels backend) | DONE | ADR-001 |
| 14.5 | Dashboard + formulaire Projet | DONE | signals API, status corrects |
| 14.6 | Composants Workload, KPI, Billing, Missions, Governance (lecture) | DONE | |
| 14.9 | **Formulaires écriture (Phase B)** : users CRUD, charge réelle, plan de charge, jalon/facturer/paiement, avenant, mission+composantes, risque/livrable/changement, partie prenante, assign chef + équipe | DONE | modals Bootstrap, smoke-tests 6/6 PASS |
| 14.7 | Grille planning JH (projet × user × mois) | TODO | P2 — vue avancée optionnelle |
| 14.8 | Tests E2E Angular | TODO | P2 — Cypress/Playwright (non requis soutenance) |

## Phase 15 — Tests d'intégration backend  🟡 70% EN COURS
| # | Task | Status | Notes |
|---|------|--------|-------|
| 15.1 | Helper `TestFixtures.java` (permissions idempotentes, admin + viewer) | DONE | |
| 15.2 | `AuthControllerTest` (7 tests) | DONE | |
| 15.3 | `ProjectControllerTest` (9 tests — CRUD, 401, **403 RBAC**, 404, 409) | DONE | ADR-001 vérifié |
| 15.4 | `GovernanceControllerTest` (8 tests — Risk, machine à états, DC) | DONE | |
| 15.5 | `BillingControllerTest` (8 tests — jalons, paiements, avenants) | DONE | **32/32 ✅** |
| 15.6 | `TeamControllerTest` (6 tests — assign, doublon, retrait, historique) | DONE | |
| 15.7 | `WorkloadControllerTest` (7 tests — planCharge + doublon + delete + validate + idempotence) | DONE | |
| 15.8 | `KpiControllerTest` (6 tests — live, snapshots, POST, doublon, 404) | DONE | **51/51 ✅** |
| 15.9 | Bug corrigé : KpiController POST /snapshots manquait header Location | DONE | cohérence API |
| 15.10 | Review sécurité OWASP | TODO | |
| 15.11 | Configuration déploiement (profiles prod/dev) | TODO | ADR-014 |
| 15.12 | Report ch.7 Tests + **gate final** | TODO | |

## Phase C — RBAC Authorization Audit & Correction  ✅ DONE  *(feeds report ch.2/ch.6)*
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| C.1 | Reconstruire la matrice rôle→permission depuis les règles métier (UC-001…024) | DONE | P0 | — | BA §3/§4, BR-038/BR-050, ADR-005 |
| C.2 | Détecter les incohérences (current vs target) | DONE | P0 | C.1 | Admin +17 illégitimes, Dev fuite KPI, Directeur MANAGE_BILLING/RESOURCES, manque ASSIGN_DEVELOPER |
| C.3 | Écrire docs/AUTHORIZATION_MATRIX.md (livrable d'audit) | DONE | P0 | C.1,C.2 | matrice corrigée + justifications |
| C.4 | Migration corrective V12__fix_rbac_matrix.sql (data-only, ADR-001) | DONE | P0 | C.3 | Admin 22→5, Dir 13, Chef 14, Dev 7→5 ; bump token_version |
| C.5 | Appliquer V12 + vérifier Flyway + comptes par rôle | DONE | P0 | C.4 | ✅ appliqué, matrice conforme |
| C.6 | Re-seed démo via bons acteurs (Directeur crée/assigne, PM gère, Dev saisit) | DONE | P0 | C.5 | projet DEMO-RBAC |
| C.7 | Test des 4 rôles (grille PASS/FAIL : 2xx autorisé, 403 interdit) | DONE | P0 | C.6 | **22/22 PASS ✅** |
| C.8 | Enforcement du scope données (ADR-021 : PM=projets gérés, Dev=siens) | DONE | P1 | C.7 | ✅ VIEW_ALL_PROJECTS + ProjectScopeService + interceptor ; 13/13 PASS ; piloté par capacité (ADR-001). **Régression rattrapée** : l'interceptor cassait 34 tests → fixture super-admin dotée de VIEW_ALL_PROJECTS → **51/51 verts** |
| C.9 | Correctif fuite RBAC : affectation chef via édition projet | DONE | P0 | 7.4 | PM (EDIT_PROJECT) pouvait réassigner le chef via PUT → garde par capacité ASSIGN_CHEF_PROJET dans create/update + sélecteur masqué côté UI ; vérifié PM=refusé / Directeur=OK |
| C.10 | Jeu de données réaliste + vérif scope multi-acteurs | DONE | P0 | C.8 | 3 chefs (Ahmed/Sonia/Karim) + 5 devs + 5 projets africains réalistes (SIG, FCFA/EUR) ; chaque chef ne voit que ses projets, chaque dev que les siens ; isolation croisée 403 vérifiée |
| C.11 | Revue logique des rôles — correctifs | DONE | P0 | C.10 | (1) chef ne peut plus changer le **directeur** via édition (garde CREATE_PROJECT) ; (2) développeur ne peut soumettre que **ses propres** charges (BR-033, garde auto-soumission) ; séparation déclare(dev)/valide(chef) confirmée ; 51/51 tests verts |
| C.12 | Test frontend (navigateur) — champs + workflow | DONE | P0 | C.11 | Audit statique modèles TS vs DTO : **bug Ressources** (tarifJournalier/devise/dateDebut → dailyRate/tccRate/staffingStart) corrigé ; workflow création projet validé en UI (tous champs + aperçu calculé budget TND/PPR + sélecteur chef + submit → /projects/15) ; tous les autres modèles conformes |
| C.13 | Dashboard adapté à l'admin | DONE | P1 | C.12 | L'admin (sans VIEW_PROJECT) voyait un dashboard projets vide → branche « Espace administration » (utilisateurs + ressources/TCC), plus de stats projet à 0 ; vérifié en UI |
| C.14 | Archivage des projets terminés | DONE | P1 | C.12 | V16 (colonne archived) + endpoints archive/unarchive/archived (statut COMPLETED requis, 409 sinon, scope ADR-021) ; UI : bouton Archiver sur projet terminé + bascule Actifs/Archivés + bouton Restaurer ; 51/51 tests verts ; vérifié en UI (A25004 archivé → section Archivés) |
| C.15 | Audit UX/UI — gating des actions par permission | DONE | P0 | C.14 | **Défaut** : billing/governance/workload affichaient TOUS les boutons d'écriture (Ajouter/Supprimer/Facturer/Valider/transitions) même au Directeur (lecture seule) → clic = 403. **Fix** : `canManage()`/`canPlan()`/`canSubmit()` (MANAGE_BILLING/MANAGE_GOVERNANCE/VALIDATE_WORKLOAD/SUBMIT_WORKLOAD) masquent les actions interdites. Vérifié en UI (Directeur ne voit plus les boutons). KPI confirmé auto-chargé + badges FR (fausse alerte = bundle périmé) |

## Extra modules (ADR-004, full Excel scope) — after core
| EX.1 | Avenants (contract amendments → budget/billing impact) | DONE | P1 | 9.2,14.3 | V15 ; writer unique du budget révisé (C-1) + recalcul jalons PREVU (H-4) |
| EX.2 | Risk Register (probability/severity/treatment scales) | DONE | P1 | 9.2 | module governance (Risk, NiveauRisque, StatutRisque) |
| EX.3 | Deliverables (Delivery% KPI) | DONE | P1 | 9.2 | Livrable + Delivery % dans le KPI EVM (D.2) |
| EX.4 | Stakeholders (Parties Prenantes) | DONE | P2 | 9.2 | PartiePrenante (governance) |
| EX.5 | Change Register | DONE | P2 | 9.2 | DemandeChangement (governance) |
| EX.6 | Module reports + **gate** | TODO | P1 | EX.1–EX.5 | |

## Phase D — Méthode F-AFF-13 (spec 2026-07-05) *(fiche revue projet ST2i → app)*
| D.1 | Devis Interne — structure vide (décision §16 BUSINESS_ANALYSIS : structure ✅, valeurs société ❌) | DONE | P0 | EX.1 | V23 `lignes_di` + capacité MANAGE_DI (Directeur) ; moteur 2 passes (montants/marges calculés à la lecture, lignes taxes % du total vendu) ; écran `/projects/:id/devis-interne` ; 4 tests service ; vérifié UI (10 000 FCFA → 51 TND @0.0051) |
| D.2 | Indicateurs EVM (Glossaire F-AFF-13 §5) | DONE | P0 | D.1 | V22 : EV % (saisie CdP au snapshot), Delivery % (livrés/planifiés), consommé/RAF/dérive JH (vs workload vendu), CA production (budget × EV), FAE, marge actuelle vs vendue (DI calculé sinon fiche identification) ; revue mensuelle avec faits marquants + date fin estimée ; vérifié UI (EV 50 % → CA prod 459 000 TND) |
| D.3 | TCC par année (règle métier §6.3-4 : TCC 2024 ≠ 2025) | DONE | P1 | D.2 | V21 `tcc_annuels` (resource, année, tarifs) ; valorisation des charges au tarif de l'année d'imputation, fallback tarif de base ; éditeur « Tarifs par année » dans Ressources |
| D.4 | Backlog corrections clôturé (score 88/100) | DONE | P0 | — | H-3 bannière warnings KPI, N-2 descope V20, N-3 erreurs login 403/429, N-4 warning supprimé, N-5 assertOwnership, headers sécurité, contrainte DI §16 ; **87/87 tests** |

## Phase E — Gouvernance d'ingénierie (Chief Software Architect, 2026-07-05)
| E.1 | ENHANCEMENTS.md — backlog d'ingénierie vivant (revue complète + roadmap 4 phases) | DONE | P0 | — | Nouveau document maître ; ENHANCEMENTS_AND_CORRECTIONS.md devient l'archive de vérification |
| E.2 | Refonte UML v2.0 — 14 diagrammes alignés code | DONE | P0 | E.1 | Vue globale du domaine (20 entités, relations seules) + 5 classes/domaine (+ Gouvernance), séquence KPI hybride réelle, états projet + déploiement nouveaux, KIMAI retiré, UML_DESIGN.md v2 avec justifications ; engineering/ archivé |
| E.3 | Machine à états projet gardée (découverte audit E.2) | DONE | P0 | E.2 | `ProjectStatus.canTransitionTo` (state pattern), 422 sur transition illégale, 2 tests ; **89/89** |
| E.4 | Roadmap ENHANCEMENTS Phase 1 (reste) : matrice autorisation + springdoc | DONE | P0 | E.1 | AUTHORIZATION_MATRIX.md mis à jour (V20/V23/VIEW_ALL_PROJECTS) ; springdoc 2.6.0 + @Tag 14 contrôleurs ; /swagger-ui.html disponible |

## Phase F — DevOps : conteneurisation et intégration continue (2026-08-19)
| # | Task | Status | Pri | Depends | Notes |
|---|------|--------|-----|---------|-------|
| F.1 | Hygiène dépôt : `.gitignore`, `.gitattributes` ciblé, wrapper `mvnw`/`mvnw.cmd` | DONE | P0 | — | `.mvn/` n'est plus ignoré ; `!.env.example` ajouté (`git check-ignore` → non ignoré). `.gitattributes` volontairement **ciblé** : un `* text=auto` réécrirait les fins de ligne de 277 fichiers non commités |
| F.2 | Durcissement de la configuration backend + Actuator | DONE | P0 | — | `${DB_PASSWORD}` / `${JWT_SECRET}` **sans valeur de repli** ; secret dev explicite ; prod : `validate-on-migrate: true`, CORS / cookie / niveau de log pilotés par variables ; `spring-boot-starter-actuator` ajouté. **89/89 tests** |
| F.3 | `/actuator/health` accessible sans authentification | DONE | P0 | F.2 | `SecurityConfig` : `permitAll` sur health/info **uniquement**. Vérifié : health → 200 `{"status":"UP"}` sans détails ; `/api/projects` → toujours 401 |
| F.4 | **Correctif du build de production Angular** | DONE | P0 | — | `angular.json` ne déclarait aucun `fileReplacements` : `http://localhost:8090/api` était compilé **dans le bundle de production**. Vérifié avant/après — chaîne présente dans `chunk-5JAN45E7.js`, absente après correctif, `/api` relatif présent |
| F.5 | Images Docker (backend multi-stage non-root, frontend nginx) | DONE | P0 | F.2,F.4 | `eclipse-temurin:21-jre-jammy`, uid 1001. nginx : `^~ /api/` → `backend:8080`, `= /healthz`, `^~ /i18n/` sans cache (JSON Transloco non hachés), assets hachés `try_files $uri =404`, `/` → `index.html` |
| F.6 | Orchestration `docker-compose.yml` (db + backend + frontend) | DONE | P0 | F.5 | Ports hôte 5433/8091/8081 — 5432/8090/4200/8080 sont déjà occupés. `pg_isready -h 127.0.0.1` (force TCP, sinon vert trop tôt) ; `start_period: 90s` pour 26 migrations ; volume `pms-db-data` ; `.env.example` documenté |
| F.7 | Pipeline CI/CD GitHub Actions | DONE | P1 | F.5,F.6 | Jobs `backend` / `frontend` / `images` (PR + push), `smoke` (branches d'intégration), `publish` GHCR (`main`). Garde-fou échouant si `localhost:8090` réapparaît dans le bundle. **Pas de `npm test`** : aucune cible `test` déclarée — cf. ENHANCEMENTS FE-1/T-3 |
| F.8 | `docs/DEPLOYMENT.md` (clôture DOC-2) + ADR-026 | DONE | P1 | F.6 | Deux modes (natif / conteneur), tableau des variables, sauvegarde `pg_dump -Fc`, dépannage, hors-périmètre explicite (Kubernetes, cloud, TLS). ADR-014 passé à *Superseded by ADR-026* ; les ADR réservés par DOC-4 renumérotés 027/028 |
| F.9 | Rapport — Sprint 9 « Containerization and Continuous Delivery » | DONE | P1 | F.8 | Ajouté dans `chap_07.tex` (Release 4 = Sprints 8–9). La section « Deployment » du Sprint 8 décrivait un déploiement natif mono-artefact : **réécrite**, pas complétée. Rapport reconstruit : 78 pages, 0 erreur |
| F.10 | **Vérification runtime de la pile conteneurisée (V3–V8)** | DONE | P0 | F.6 | **Exécuté le 2026-08-20.** 3 conteneurs `(healthy)` ; backend uid=1001 sans Maven dans l'image ; nginx : healthz 200, route profonde 200, `/i18n/` en `no-cache`, `.js` inexistant → 404 ; 26 migrations, 127 utilisateurs / 95 projets seedés ; **login réel via nginx → 200 + cookie `Path=/api/auth/refresh; HttpOnly; SameSite=Strict`** ; RBAC vérifié de bout en bout (Directeur/Chef/Dév 200, Admin 403 — conforme à la matrice) ; données conservées après `restart`. Reset `down -v` non exécuté : détruirait la pile nécessaire aux captures d'écran |
| F.11 | Pousser la ligne de base et vérifier le pipeline (V10) | TODO | P0 | F.7 | Dépôt à 2 commits pour 277 fichiers non commités : un pipeline vert ne prouverait rien tant que la base réelle n'est pas poussée |
| F.12 | Resynchroniser les miroirs Markdown du rapport | TODO | P2 | F.9 | `docs/report/chap_01…08.md` reflètent l'**ancienne** structure en 10 chapitres, en français. Le rapport est passé à 7 chapitres en anglais (Releases/Sprints) |

## Phase 17 — Testing, hardening & deployment  *(report ch.7)*
| 17.1 | Integration/E2E tests, security review | TODO | P0 | 16.6 | |
| 17.2 | Performance (NFR-001 ≤2s), logging strategy | TODO | P1 | 16.6 | async, structured |
| 17.3 | Deployment + docs | DONE | P1 | 17.1 | Livré par la Phase F : `docs/DEPLOYMENT.md`, pile Docker Compose, CI/CD. Vérification runtime encore bloquée (F.10) |
| 17.4 | Report ch.7 (Tests) + **final gate** | TODO | P0 | 17.1–17.3 | |

## Cross-cutting — PFE report (TEK-UP LaTeX)
| R.1 | Introduction générale | TODO | P1 | 1.11 | |
| R.2 | Keep acronymes/biblio updated each phase | TODO | P1 | ongoing | |
| R.3 | Conclusion & perspectives | TODO | P1 | 17.4 | |
| R.4 | Final assembly & build (70–100 pages) | TODO | P0 | all | Overleaf canonical target |
| R.5 | (If local build required) modernize tpl/ for MiKTeX 2025 | TODO | P2 | user approval | ADR-012; needs touching tpl/ |
