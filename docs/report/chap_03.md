# Chapitre 3 — Conception architecturale

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/chap_03.tex` (pour lecture rapide ;
> la source officielle reste le fichier `.tex`). Détail technique complet dans
> [docs/ARCHITECTURE.md](../ARCHITECTURE.md).

## Introduction
Après avoir spécifié les besoins métier au chapitre précédent, ce chapitre présente l'architecture
technique retenue pour y répondre de manière maintenable, sécurisée et évolutive : style
architectural, architecture globale en trois niveaux, sécurité, contrôle d'accès dynamique,
architectures *backend* et *frontend*, moteur de KPI et stratégie de déploiement. Aucune ligne de
code n'est produite à ce stade ; la phase se conclut par une validation.

## 1. Style architectural
La plateforme adopte un **monolithe modulaire en couches** : une application Spring Boot unique,
partitionnée en **modules fonctionnels**, chacun organisé selon les couches
*Contrôleur → Service → Référentiel → Domaine*. Ce choix privilégie la *simplicité* (une seule base,
cohérence forte des données financières, application simple à construire/tester/défendre), tout en
gardant des frontières nettes. L'approche microservices a été écartée comme sur-ingénierie.

## 2. Architecture globale
Trois niveaux :
- **Client** : SPA Angular 21 (Bootstrap 5, Chart.js, français) — authentification, menu dynamique
  construit à partir des permissions, tableaux de bord.
- **Serveur** : API REST Spring Boot 3.3 (Java 21), monolithe modulaire — couche web (contrôleurs,
  filtre JWT, gardes), service (logique métier, moteur KPI, devises, paramètres), accès aux données
  (Spring Data JPA).
- **Données** : PostgreSQL 17 (service Windows installé).

La communication est en HTTPS/REST avec jeton JWT. KIMAI alimente les charges réelles par import.

| Couche | Responsabilité |
|---|---|
| Contrôleur | Points d'entrée REST, DTO, validation ; aucune logique métier. |
| Service | Logique métier, transactions, orchestration, moteur de KPI. |
| Référentiel | Accès aux données via Spring Data JPA. |
| Domaine | Entités, énumérations, règles de domaine. |
| Transversal | Audit, suppression logique, validation, exceptions, journalisation asynchrone. |

*Tableau 3.1 — Couches de l'architecture backend.*

## 3. Architecture de sécurité
Authentification par **JWT**. À la connexion : vérification du mot de passe (BCrypt), émission d'un
**jeton d'accès** (durée de vie courte) et d'un **jeton de rafraîchissement**. Si `firstLogin` est
vrai, changement de mot de passe obligatoire avant tout accès. Requêtes via `Authorization: Bearer` ;
rafraîchissement à l'expiration.

**Modèle de jeton (révocation par version).** Le jeton d'accès ne porte que l'**identité**
(`userId`, rôle, `token_version`), pas les permissions. À chaque requête, le serveur vérifie la
signature puis charge le **contexte de sécurité** de l'utilisateur depuis un petit cache en mémoire
(`active`, `token_version`, permissions), rejette le jeton si l'utilisateur est désactivé ou si la
version est obsolète, et autorise via les permissions du contexte. Ce cache étant invalidé lors des
événements de sécurité, **toute modification de permission comme toute révocation est immédiate** —
sans attendre l'expiration. L'authentification reste sans état ; seule cette vérification
d'autorisation (légère, adossée au cache) est avec état : le système n'est donc **pas** entièrement
sans état, par choix délibéré (BR-007). La `token_version` est incrémentée lors d'une désactivation,
déconnexion forcée, changement de mot de passe, de rôle ou de permissions. Jeton d'accès en mémoire
côté client ; jeton de rafraîchissement (renouvelé) en cookie `HttpOnly` sécurisé. Connexion protégée
contre la force brute (BR-058).

## 4. Le contrôle d'accès dynamique (RBAC)
Modèle **Rôle → Permission → Module**, jamais de couplage direct rôle/module. Trois niveaux :
- **Backend — sécurité de méthode** : chaque opération déclare la permission requise, ex.
  `@PreAuthorize("hasAuthority('ASSIGN_DEVELOPER')")`. Aucun code ne teste un nom de rôle.
- **Menu dynamique** : `GET /api/me/context` renvoie permissions et menu autorisé ; le frontend
  construit la navigation à partir de cette réponse.
- **Portée des données** : le chef de projet ne voit que ses projets gérés (BR-063), le développeur
  que ses propres charges (BR-064) — filtre d'appartenance appliqué *après* la permission.

**Illustration (D4).** `ASSIGN_DEVELOPER` est attribuée par défaut au **Directeur** *et* au **Chef de
Projet**. Pour l'accorder demain à un autre rôle, l'administrateur ajoute une seule ligne
rôle–permission : garde, menu et directive en tiennent compte aussitôt, **sans modification de
code**.

## 5. Architecture backend
Packaging **orienté fonctionnalités** (`com.pms.<module>`). Les contrôleurs n'accèdent jamais
directement aux référentiels ; les entités ne franchissent pas la frontière de l'API (DTO). La
conversion entité ↔ DTO est assurée **exclusivement par MapStruct** (mappeurs générés à la
compilation, typés, sans réflexion) ; le mappage manuel est proscrit. Préoccu­
pations transversales mutualisées : audit + suppression logique (entité de base), validation (Bean
Validation + invariants métier), gestion centralisée des exceptions, journalisation **asynchrone et
structurée** (non bloquante).

## 6. Le moteur de calcul des indicateurs (KPI)
Approche **hybride** : recalcul à la modification + instantanés (*snapshots*).
- Un `KpiSnapshot` est stocké **par projet et par mois** (EV %, ETC, EAC, coûts, cumul CA production,
  FAE, RAF, dérive, 3 marges, budget restant, avancement de facturation, Delivery %).
- Recalcul dès qu'évoluent plan de charge, charges réelles, facturation, coûts de mission, taux TCC
  ou budget/avenants.
- Les tableaux de bord **lisent les instantanés** → temps de réponse courts (NFR-001).
- La conservation mensuelle permet les **courbes d'évolution des KPI** (cf. feuille « Evolution KPIs »
  de l'Excel).

Toutes les valeurs monétaires passent par le service de conversion de devises ; toutes les constantes
proviennent du magasin de paramètres. Le coût de main-d'œuvre (Σ JH × TCC) et les autres coûts
(missions, frais) sont **deux flux distincts** : le coût total réel et les marges agrègent les deux,
sans confondre les missions avec le coût de main-d'œuvre.

**Précision sur la concurrence :** le recalcul est **asynchrone, après validation de la transaction**
déclencheuse, et **sérialisé par projet** (un seul recalcul en vol, les changements rapprochés se
regroupent) — les tableaux de bord sont cohérents à terme, en une à deux secondes.

## 7. Multi-devises et paramètres configurables
Plusieurs devises (FCFA, TND, EUR) ; service de conversion centralisé à taux **datés** ; devise de
restitution (TND) paramétrable ; aucun facteur codé en dur. Magasin de **paramètres** (clé → valeur
typée) : frais généraux (0,70), jours ouvrés (22/20), heures/jour (8), PPR (5 %), plafond de
facturation (100 %), échelles de risque — administrés via `MANAGE_PARAMETERS`.

## 8. Architecture frontend
Angular 21, structuré par fonctionnalités, composants autonomes. Intercepteur HTTP (jeton +
rafraîchissement) ; gardes de routes (authentification, première connexion, permission) ; **directive
de permission** masquant les éléments non autorisés (`*hasPermission`) ; menu latéral construit
dynamiquement à partir du contexte serveur. Tableaux de bord Chart.js adaptés par profil (projet pour
le CP, exécutif pour le directeur, personnel sans finances pour le développeur). Interface française,
responsive (Bootstrap 5).

## 9. Déploiement
Déploiement **natif**, sans conteneur : base sur le service PostgreSQL 17 existant, backend via
`mvnw` (Java 21, Maven 3.9.16), frontend via `ng serve` (Angular 21, Node 22). Deux profils Spring
(`dev`, `prod`) ; paramètres sensibles externalisés via variables d'environnement. Le schéma de base
de données est traité à la phase suivante.

## Conclusion
Cette architecture concrétise le contrôle d'accès dynamique **Rôle → Permission → Module**, le modèle
financier complet via un moteur de KPI à instantanés performant, la gestion multi-devises et les
paramètres configurables, dans un monolithe modulaire exécutable nativement. Le chapitre suivant
traduira ce domaine en un schéma PostgreSQL concret.
