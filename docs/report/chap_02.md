# Chapitre 2 — Analyse et spécification des besoins

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/chap_02.tex` (pour lecture rapide ;
> la source officielle reste le fichier `.tex`).

## Introduction
Ce chapitre formalise l'analyse métier du système PMS. Il identifie les acteurs, recense les besoins
fonctionnels et non fonctionnels, présente les règles de gestion ainsi que le modèle d'autorisation
dynamique qui structure toute la plateforme, et détaille le modèle financier et les indicateurs de
performance (KPI) extraits de la réalité métier. Cette spécification constitue le socle des phases de
conception ultérieures.

## 1. Identification des acteurs
Le système repose sur quatre rôles principaux, complétés par des acteurs secondaires (le système
lui-même et l'outil externe de suivi des temps KIMAI).

| Acteur | Mission | Limite (ne peut pas) |
|---|---|---|
| Administrateur | Administration technique et fonctionnelle : utilisateurs, rôles, permissions, TCC, paramétrage. | Piloter les projets ; accéder aux données financières des projets. |
| Directeur | Gouvernance stratégique : création des projets, budgets, affectation des chefs de projet, supervision du portefeuille, KPI exécutifs. | Gérer les opérations quotidiennes (délégué, non interdit). |
| Chef de Projet | Utilisateur opérationnel principal : équipes, plan de charge, facturation, missions, suivi des coûts et de la rentabilité. | Gérer les utilisateurs, les rôles, le TCC et le paramétrage. |
| Développeur | Saisie des charges réelles ; consultation de ses affectations et missions. | Accéder à toute donnée financière (budget, marge, EAC, facturation). |

*Tableau 2.1 — Acteurs du système PMS et leurs limites.*

## 2. Besoins fonctionnels
Les besoins fonctionnels sont organisés par module. Ils synthétisent les exigences du document SRS
(FR-001 à FR-053) et les cas d'utilisation (UC-001 à UC-020).

| Module | Principaux besoins fonctionnels |
|---|---|
| Authentification | Connexion par e-mail / mot de passe, génération de jetons JWT et de rafraîchissement, changement obligatoire du mot de passe à la première connexion, déconnexion. |
| Gestion des utilisateurs | Création par l'administrateur, génération automatique d'un mot de passe temporaire, modification, activation / désactivation, attribution d'un rôle. |
| Rôles et permissions | Gestion des rôles, des permissions et des associations rôle–permission. |
| Gestion des projets | Création, modification, changement de statut, définition du budget, affectation d'un chef de projet, consultation du portefeuille. |
| Gestion des équipes | Affectation et retrait de développeurs, consultation des disponibilités, historisation des affectations. |
| Gestion du TCC | Saisie du coût chargé par ressource et par année, historisation, calcul du coût journalier. |
| Plan de charge | Planification mensuelle en jours-homme, totaux planifiés, calcul de l'ETC et des prévisions. |
| Charges réelles | Saisie des jours-homme réalisés (projets affectés uniquement), validation, historisation, import depuis KIMAI. |
| Facturation | Création des jalons (pourcentage, montant, dates), suivi des statuts, calcul de l'avancement. |
| Missions | Création des missions, saisie des frais, calcul automatique du coût total. |
| KPI et reporting | Calcul des indicateurs financiers, tableaux de bord projet et portefeuille, génération et export de rapports. |
| Gouvernance (avenants, risques, livrables, parties prenantes, changements) | Registres de suivi de la vie du projet, conformément au classeur Excel. |

*Tableau 2.2 — Besoins fonctionnels par module.*

## 3. Besoins non fonctionnels
- **Performance** : réponse en moins de deux secondes pour les opérations courantes (NFR-001).
- **Sécurité** : authentification JWT obligatoire, mots de passe chiffrés (NFR-002).
- **Autorisation** : contrôle d'accès basé sur les permissions (RBAC dynamique, NFR-003).
- **Maintenabilité** : architecture propre, modulaire et orientée fonctionnalités (NFR-006).
- **Évolutivité** : support de la croissance et intégration future (ERP, RH, comptabilité) sans
  refonte du cœur (NFR-005, BR-065).
- **Auditabilité** : conservation de l'historique et suppression logique (NFR-008).
- **Ergonomie** : interface intuitive, responsive et en langue française (NFR-007).

## 4. Règles de gestion
Les règles de gestion proviennent du document BRS (65 règles, BR-001 à BR-065), enrichies par les
règles extraites du fichier Excel. Les plus structurantes sont synthétisées ci-dessous.

| Domaine | Règles principales |
|---|---|
| Utilisateurs | Création réservée à l'administrateur ; pas d'auto-inscription ; un seul rôle par utilisateur ; e-mail unique ; mot de passe temporaire puis changement à la première connexion. |
| Projets | Création réservée au directeur ; exactement un directeur et un chef de projet actif par projet ; statut parmi {DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED} ; code projet unique ; budget strictement positif ; date de début antérieure à la date de fin. |
| Équipes | Un développeur peut appartenir à plusieurs projets ; le retrait conserve l'historique ; l'affectation enregistre son auteur et sa date ; les développeurs inactifs ne peuvent être affectés. |
| TCC | Géré par l'administrateur ; une valeur par ressource et par année ; valeur historique immuable après validation financière. |
| Charges | Charges exprimées en jours-homme, jamais négatives ; saisie réservée aux projets affectés ; historique préservé (aucune suppression). |
| Facturation | Jalon rattaché à un projet ; statut parmi {PENDING, INVOICED, PAID} ; montant positif ; somme des pourcentages des jalons ≤ 100 %. |
| Missions | Mission rattachée à un projet et à un employé ; date de début antérieure à la date de fin ; coût total calculé automatiquement. |
| KPI et sécurité | KPI financiers visibles uniquement par le directeur et le chef de projet ; calculs automatiques ; RBAC appliqué par permissions ; mots de passe chiffrés. |

*Tableau 2.3 — Synthèse des règles de gestion.*

## 5. Le modèle d'autorisation dynamique
L'exigence architecturale centrale du projet est de **ne jamais coupler directement un rôle à un
module**. Un tel couplage (« ROLE → MODULE ») imposerait de modifier le code source à chaque
évolution des responsabilités. Le système adopte donc un modèle dynamique à trois niveaux :

**ROLE → PERMISSION → MODULE**

Les rôles sont des ensembles de permissions. L'autorisation côté serveur, la visibilité côté client
et la **génération dynamique des menus** reposent toutes sur les permissions, jamais sur le nom du
rôle.

| Permission (extrait) | Rôles porteurs par défaut |
|---|---|
| `MANAGE_USERS`, `MANAGE_ROLES`, `MANAGE_PERMISSIONS` | Administrateur |
| `MANAGE_TCC`, `MANAGE_PARAMETERS` | Administrateur |
| `CREATE_PROJECT`, `EDIT_PROJECT`, `CHANGE_PROJECT_STATUS`, `MANAGE_PROJECT_BUDGET`, `ASSIGN_PROJECT_MANAGER` | Directeur |
| `ASSIGN_DEVELOPER`, `REMOVE_DEVELOPER` | Directeur *et* Chef de Projet |
| `MANAGE_WORKLOAD_PLAN`, `MANAGE_BILLING`, `MANAGE_MISSIONS` | Chef de Projet |
| `VIEW_FINANCIALS`, `VIEW_KPI` | Directeur (tous projets), Chef de Projet (projets gérés) |
| `VIEW_EXECUTIVE_DASHBOARD` | Directeur |
| `SUBMIT_ACTUAL_WORKLOAD` | Développeur |

*Tableau 2.4 — Extrait de la matrice Rôle–Permission par défaut.*

### 5.1 Illustration : l'affectation des développeurs
Les documents de spécification (plan de projet, SRS, cas d'utilisation) indiquent que seul le chef de
projet peut affecter des développeurs. Le besoin métier validé exige cependant que le *directeur*
puisse également le faire. Plutôt que de modifier le code, il suffit d'attribuer la permission
`ASSIGN_DEVELOPER` au rôle `DIRECTEUR` :

- Aujourd'hui : `CHEF_DE_PROJET → ASSIGN_DEVELOPER`
- Demain : on ajoute `DIRECTEUR → ASSIGN_DEVELOPER`

Ce changement s'opère par une simple mise à jour de configuration en base de données, sans aucune
modification du *backend*, du *frontend* ni de la logique métier. Cet exemple illustre concrètement
la valeur du modèle d'autorisation dynamique.

## 6. Le modèle financier et les indicateurs de performance
Le cœur métier de la plateforme réside dans son modèle financier, fidèle à la réalité extraite du
classeur Excel (onglets *TCC*, *Coût Prévisionnel*, *Coût réel Actualisé*, *Avancement de
facturation*, *Missions* et *Glossaire*). Les constantes métier (taux de frais généraux, jours
ouvrés, provisions) sont paramétrables et jamais codées en dur.

| Indicateur | Définition / Calcul |
|---|---|
| TCC journalier | (coût annuel chargé × (1 + taux de frais généraux)) / jours ouvrés de l'année. Dans l'Excel : facteur ×1,7 (frais généraux ≈ 70 %) et 22 jours (2024) ou 20 jours (2025). |
| Coût Prévisionnel | somme, par mois, des jours-homme planifiés × TCC. |
| ETC (reste à faire) | somme des jours-homme restants × TCC. |
| Coût Actuel | somme des jours-homme consommés × TCC. |
| EAC | Coût Actuel + ETC. |
| Earned Value (EV %) | taux d'avancement réel du projet. |
| Cumul CA Production | total du contrat × EV %. |
| FAE / Stock | Cumul CA Production − total facturé. |
| RAF (jours-homme) | charge planifiée restante − charge consommée. |
| Dérive (jours-homme) | charges vendues − charges consommées − RAF. |
| Marge nette vendue % | (prix de vente − coût total) / prix de vente. |
| Marge nette actuelle | Cumul CA Production − Coût Actuel. |
| Marge nette EAC | Budget total − (Coût Actuel + Coût Prévisionnel), soit Budget − EAC. |
| Avancement de facturation % | cumul facturé / total du contrat. |
| Coût de mission | perdiem (séjour × indemnité × taux de change) + billet + timbre (+ transport, séjour). |

*Tableau 2.5 — Modèle financier et indicateurs de performance (modèle Excel complet).*

### 6.1 KPI par rôle
Le développeur ne dispose d'aucun indicateur financier : il ne consulte que ses propres charges. Il
en fournit néanmoins la mesure brute, car sa saisie des jours-homme réels alimente la chaîne Coût
Actuel → EAC → Marge. Le chef de projet exploite l'ensemble des KPI des projets qu'il gère afin de
piloter leur rentabilité, tandis que le directeur dispose d'une vue consolidée du portefeuille via un
tableau de bord exécutif. L'administrateur, enfin, n'accède à aucun KPI projet mais administre les
intrants (TCC, paramètres) dont ils dépendent.

## 7. Analyse du classeur Excel existant
La source métier principale est le classeur de revue de projet (*DEV_Fiche_revue_projet*), composé de
**21 onglets** et de **2 088 formules**. Chaque onglet a été analysé (objet, propriétaire métier,
règles, calculs) et rattaché à un module de la future plateforme.

| Onglet | Propriétaire métier | Module PMS |
|---|---|---|
| Fiche Doc | Qualité / Direction | Gestion de projet (métadonnées) |
| Fiche identification | Directeur | Gestion de projet |
| TCC | Administrateur | Gestion du TCC |
| Missions | Chef de projet | Gestion des missions |
| Avancement_Facturation | Chef de projet | Facturation |
| Recap Facturation (2) | Chef de projet | Facturation |
| Coût Prévisionnel | Chef de projet | Plan de charge + KPI |
| Coût réel Actualisé | Développeur / Chef de projet | Charges réelles + KPI |
| Recap Facturation | Chef de projet | Facturation |
| S. Contractuelle | Directeur / Chef de projet | Gestion de projet + Facturation |
| Situation actuelle | Chef de projet | KPI / Livrables |
| Parties Prenantes | Chef de projet | Parties prenantes + Équipe |
| Livrables projet | Chef de projet | Livrables |
| Base des Risques | Chef de projet | Registre des risques |
| Liste des actions | Chef de projet | Actions / Risques |
| Registre Changement | Chef de projet | Registre des changements |
| Avenants | Directeur / Chef de projet | Avenants |
| Statistiques | Système (calcul) | Moteur de KPI |
| Dashboard | Système / Directeur, CP | Tableaux de bord |
| Glossaire | Référence | KPI (définitions) |
| Paramètres | Administrateur | Configuration / Échelles de risque |

*Tableau 2.6 — Analyse des 21 onglets du classeur Excel et rattachement aux modules PMS.*

### 7.1 Couverture des formules
Les 2 088 formules du classeur reposent sur un vocabulaire *fini*, ce qui permet de garantir que
chacune est rattachée à un calcul futur du système. Les catégories ci-dessous sont exhaustives.

| Catégorie de formule | Nombre | Calcul PMS associé |
|---|---:|---|
| Fonctions de date (DATE, YEAR, MONTH, DAY, EDATE, TODAY) | 460 | Calendrier projet/sprint, sélection du mois courant, durées |
| Agrégation SUM | 92 | Totaux mensuels et cumuls (coûts, facturation, charges) |
| HLOOKUP (sélection du mois) | 31 | Instantané KPI du mois sélectionné |
| IF / ISBLANK / IFERROR | 36 | Statuts conditionnels, valeurs sûres |
| AVERAGE / MAX / COUNTA | 7 | Moyennes, maximum, comptage de livrables |
| Arithmétique de coûts (JH × TCC, sommes, écarts) | 1 421 | Coût prévisionnel, coût actuel, ETC, EAC, coût mission |
| Pourcentages | 13 | Marges, avancement de facturation, PPR, Delivery % |
| Références inter-onglets | 1 426 | Jointures relationnelles (charges ↔ TCC ↔ projet) |
| Références cassées (#REF!, #VALUE!) | 169 | Non reproduites : recalcul propre à partir de données normalisées |

*Tableau 2.7 — Cartographie des formules Excel vers les calculs du futur système.*

**Résultat de la vérification :** chacune des 2 088 formules relève d'une catégorie de logique métier
vivante (rattachée à un calcul PMS) ou d'un artefact cassé que la plateforme remplace par un recalcul
propre. *Aucune formule n'est laissée sans correspondance.*

## 8. Processus métier global
Le déroulement nominal de la plateforme enchaîne : création des comptes par l'administrateur ;
première connexion et changement de mot de passe ; création d'un projet et affectation d'un chef de
projet par le directeur ; constitution de l'équipe ; planification de la charge ; définition des
jalons de facturation et des missions ; saisie des charges réelles par les développeurs (ou import
KIMAI) ; calcul automatique des KPI ; supervision du portefeuille par le directeur.

## Conclusion
Ce chapitre a spécifié les acteurs, les besoins, les règles de gestion, le modèle d'autorisation
dynamique, le modèle financier et l'analyse complète du classeur Excel existant. Ces éléments
définissent sans ambiguïté le *quoi* du système, strictement sur le plan métier. Les chapitres
suivants aborderont le *comment* : l'architecture technique, la conception de la base de données, la
modélisation UML puis la réalisation des modules.
