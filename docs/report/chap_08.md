# Chapitre 8 — Implémentation : Modules financier et gouvernance

## Introduction

Ce chapitre décrit la facturation (phase 11), les missions et frais de déplacement (phase 12),
et la gouvernance de projet — risques, livrables, parties prenantes, demandes de changement
(phase 13).

---

## 1. Module Facturation

### 1.1 Jalons de facturation

Règle fondamentale (BR-038) :
```
∑ pourcentage des jalons actifs ≤ 100 %
```
Violation → 400 Bad Request.

Montant calculé automatiquement :
```
montant = effectiveBudget × pourcentage / 100
```

### 1.2 Cycle de vie d'un jalon

```
PREVU → FACTURE → PAYE
```

- **FACTURE** : `PATCH /jalons/{id}/facturer` — nécessite `dateFacture` dans le corps
- **PAYE** : transition automatique si `montantRecu ≥ montant_jalon`

### 1.3 Paiements

`Paiement` : enregistre `montantRecu` sur un jalon en état `FACTURE`.
Si couvert intégralement → jalon passe à `PAYE`.

### 1.4 Avenants

Amendement contractuel modifiant le budget :
```
revisedBudget = revisedBudget + avenant.montant
```
Annulation inverse l'opération. Le budget effectif est toujours `revisedBudget ?? initialBudget`.

### 1.5 Endpoints (11 endpoints)

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects/{id}/jalons` | `VIEW_BILLING` | 200 |
| POST | `/api/projects/{id}/jalons` | `MANAGE_BILLING` | 201 |
| PUT | `/api/projects/{id}/jalons/{jid}` | `MANAGE_BILLING` | 200 |
| DELETE | `/api/projects/{id}/jalons/{jid}` | `MANAGE_BILLING` | 204 |
| PATCH | `/api/projects/{id}/jalons/{jid}/facturer` | `MANAGE_BILLING` | 200 |
| GET | `/api/projects/{id}/jalons/{jid}/paiements` | `VIEW_BILLING` | 200 |
| POST | `/api/projects/{id}/jalons/{jid}/paiements` | `MANAGE_BILLING` | 201 |
| GET | `/api/projects/{id}/avenants` | `VIEW_BILLING` | 200 |
| POST | `/api/projects/{id}/avenants` | `MANAGE_BILLING` | 201 |
| PUT | `/api/projects/{id}/avenants/{aid}` | `MANAGE_BILLING` | 200 |
| DELETE | `/api/projects/{id}/avenants/{aid}` | `MANAGE_BILLING` | 204 |

---

## 2. Module Missions

### 2.1 Structure

`Mission` : déplacement professionnel (objet, lieu, `dateDebut`, `dateFin`).
Règle : `dateFin ≥ dateDebut`.

### 2.2 Composantes de mission

| Type | Description |
|------|-------------|
| `PERDIEM` | Frais de séjour journaliers |
| `BILLET` | Billet de transport longue distance |
| `TIMBRE` | Frais postaux |
| `TRANSPORT` | Transport local |
| `SEJOUR` | Hébergement hôtelier |

### 2.3 Multi-devises (ADR-007)

Chaque composante : `montant` + `devise` ISO 4217 (normalisée en majuscules, défaut `TND`).
Conversion vers devise de référence différée (évolution future).

### 2.4 Endpoints (8 endpoints)

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects/{id}/missions` | `VIEW_MISSION` | 200 |
| POST | `/api/projects/{id}/missions` | `MANAGE_MISSION` | 201 |
| GET | `/api/projects/{id}/missions/{mid}` | `VIEW_MISSION` | 200 |
| PUT | `/api/projects/{id}/missions/{mid}` | `MANAGE_MISSION` | 200 |
| DELETE | `/api/projects/{id}/missions/{mid}` | `MANAGE_MISSION` | 204 |
| GET | `.../missions/{mid}/composantes` | `VIEW_MISSION` | 200 |
| POST | `.../missions/{mid}/composantes` | `MANAGE_MISSION` | 201 |
| DELETE | `.../missions/{mid}/composantes/{cid}` | `MANAGE_MISSION` | 204 |

---

## 3. Module Gouvernance

### 3.1 Registre des risques

`Risk` : probabilité (`FAIBLE / MOYEN / ELEVE`) × impact (`FAIBLE / MOYEN / ELEVE`) + description + plan de traitement.
Statut : `OUVERT / MITIGE / FERME`. Un risque fermé ne peut plus être modifié.

### 3.2 Livrables — machine à états

```
EN_ATTENTE → EN_COURS → LIVRE → VALIDE
```

Transitions via endpoints PATCH dédiés. Transitions inverses interdites.
Livrable `VALIDE` immuable → toute modification déclenche 409.

### 3.3 Parties prenantes

`PartiePrenante` : nom, fonction, coordonnées, matrice influence / intérêt.

### 3.4 Demandes de changement

```
EN_ATTENTE → APPROUVE
           → REJETE
```

`PATCH /demandes-changement/{id}/approuver` ou `/rejeter` enregistre date de décision et décideur.
Demande déjà traitée → 409 (idempotence en lecture, rejet en écriture).

### 3.5 Endpoints (19 endpoints)

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET/POST | `/api/projects/{id}/risks` | `VIEW/MANAGE_GOVERNANCE` | 200/201 |
| PUT/DELETE | `.../risks/{rid}` | `MANAGE_GOVERNANCE` | 200/204 |
| GET/POST | `.../livrables` | `VIEW/MANAGE_GOVERNANCE` | 200/201 |
| PATCH | `.../livrables/{lid}/demarrer` | `MANAGE_GOVERNANCE` | 200 |
| PATCH | `.../livrables/{lid}/livrer` | `MANAGE_GOVERNANCE` | 200 |
| PATCH | `.../livrables/{lid}/valider` | `MANAGE_GOVERNANCE` | 200 |
| DELETE | `.../livrables/{lid}` | `MANAGE_GOVERNANCE` | 204 |
| GET/POST | `.../parties-prenantes` | `VIEW/MANAGE_GOVERNANCE` | 200/201 |
| PUT/DELETE | `.../parties-prenantes/{ppid}` | `MANAGE_GOVERNANCE` | 200/204 |
| GET/POST | `.../demandes-changement` | `VIEW/MANAGE_GOVERNANCE` | 200/201 |
| PATCH | `.../demandes-changement/{did}/approuver` | `MANAGE_GOVERNANCE` | 200 |
| PATCH | `.../demandes-changement/{did}/rejeter` | `MANAGE_GOVERNANCE` | 200 |
| DELETE | `.../demandes-changement/{did}` | `MANAGE_GOVERNANCE` | 204 |

---

## 4. Bilan de l'API REST backend

**75+ endpoints REST** sur 10 modules. Conventions respectées partout :
- Toute création → `201 Created` + `Location`
- Permissions vérifiées par `@PreAuthorize` (ADR-001)
- Entités supprimées filtrées (`deleted = false`, ADR-009)
- Transitions invalides / doublons → `409 Conflict`
- Données invalides → `400 Bad Request`

**51 tests d'intégration** sur H2 (profil `test`) — BUILD SUCCESS.

---

## Conclusion

Les modules de facturation, missions et gouvernance complètent l'implémentation backend.
Contrainte Σ%≤100 pour les jalons, multi-devises pour les missions, machines à états pour livrables
et demandes de changement : chaque module apporte ses règles métier spécifiques tout en respectant
les conventions transversales de l'application. Le chapitre suivant présente le frontend Angular.
