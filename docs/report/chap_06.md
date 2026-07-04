# Chapitre 6 — Implémentation : Socle technique

## Introduction

Ce chapitre décrit la mise en œuvre des trois premiers modules backend de la plateforme PMS :
l'authentification et le contrôle d'accès basé sur les rôles dynamiques (phases 5 et 6), la gestion
des utilisateurs et des ressources humaines (phase 6), et le cycle de vie des projets (phase 7).

Stack : Java 21 · Spring Boot 3.3.6 · Spring Security · JPA/Hibernate · Flyway · MapStruct 1.5.5 · PostgreSQL 17.

---

## 1. Architecture de l'application Spring Boot

### 1.1 Structure des modules

Architecture **monolithe modulaire** (ADR-016) — paquetages fonctionnels indépendants :
`auth`, `user`, `project`, `team`, `workload`, `kpi`, `billing`, `mission`, `governance`, `shared`.

Chaque module expose sa propre couche Controller → Service → Repository → Domain.

### 1.2 Entité de base et audit automatique

Toutes les entités héritent de `BaseEntity` :
- clé primaire auto-générée
- `createdAt` / `updatedAt` via `@PrePersist` / `@PreUpdate`
- marqueur `deleted` (suppression douce, ADR-009)

### 1.3 Migrations Flyway

`spring.jpa.hibernate.ddl-auto=validate` — DDL exclusivement géré par Flyway (ADR-019).
11 migrations V1–V11, idempotentes, constituant la documentation vivante du schéma.

---

## 2. Authentification et sécurité JWT

### 2.1 Émission des jetons

À la connexion (`POST /api/auth/login`) :
1. Vérification e-mail + mot de passe BCrypt (force 12)
2. Vérification `active = true`
3. Émission **jeton d'accès JWT** (HMAC-SHA512) : email + tokenVersion + permissions
4. **Jeton de rafraîchissement** dans cookie `HttpOnly / Secure / SameSite=Strict`

### 2.2 Révocation instantanée par version de jeton (ADR-017)

Champ `token_version` persisté dans `users`. À chaque requête, le filtre compare la version JWT
avec la valeur du cache Caffeine (`email:tokenVersion`). Mismatch → 401.

- Déconnexion : incrémente `token_version` → cache invalidé immédiatement
- Changement de mot de passe : même mécanique, invalide toutes les sessions actives

### 2.3 Changement de mot de passe obligatoire (ADR-010)

`first_login = true` à la création. Le jeton initial est restreint à `POST /api/auth/change-password` uniquement.
Après changement : `first_login = false`, nouveau jeton sans restriction.

---

## 3. RBAC dynamique (ADR-001)

### 3.1 Modèle

La table `role_permissions` associe permissions à rôles. Un utilisateur = un rôle, reconfigurable
sans redéploiement.

V2 seed : 4 rôles par défaut · 18 codes de permission · 7 modules (`ADMIN`, `PROJET`, `EQUIPE`,
`CHARGE`, `FACTURATION`, `KPI`, `GOV`).

### 3.2 Propagation Spring Security

```java
@PreAuthorize("hasAuthority('CREATE_PROJECT')")
```

**Règle absolue** : jamais de vérification de nom de rôle dans le code applicatif.

### 3.3 Menu dynamique

`GET /api/me/context` → liste des permissions + profil. Le frontend construit le menu
dynamiquement selon les permissions reçues.

| Champ | Description |
|-------|-------------|
| `userId` | Identifiant |
| `email` | Adresse e-mail |
| `firstName`, `lastName` | Prénom et nom |
| `roleName` | Nom du rôle actif |
| `permissions` | Codes de permission (`String[]`) |
| `firstLogin` | Changement de mot de passe requis |

---

## 4. Gestion des utilisateurs et ressources

### 4.1 Utilisateurs

- **Création** : hash BCrypt + `first_login = true`
- **Activation/désactivation** : bascule `active`
- **Attribution de rôle** : lie l'entité `Role` à l'utilisateur

### 4.2 Ressources humaines (ADR-022)

`Resource` ≠ `User` : une ressource peut exister sans compte (consultants, sous-traitants).
Lien `user_id` optionnel.

Attributs : `tarifJournalier` (TND) · `tauxTcc` (défaut 70 %) · entrées TCC annuelles.

### 4.3 MapStruct (ADR-018)

`UserMapper` et `ResourceMapper` — généré à la compilation, pas de réflexion à l'exécution.

---

## 5. Cycle de vie des projets

### 5.1 États

```
PROSPECT → EN_COURS → EN_PAUSE → TERMINE → ANNULE
                                         → ARCHIVE (depuis TERMINE)
```

Transitions invalides → `IllegalStateException` → 400 Bad Request.

### 5.2 Budget effectif

```
effectiveBudget = revisedBudget ?? initialBudget
```

### 5.3 Affectation chef de projet

`PUT /api/projects/{id}/assign-chef` (permission `ASSIGN_CHEF_PROJET`) :
- crée une ligne `project_manager_assignments` avec `startDate = aujourd'hui`
- clôture l'affectation précédente (`endDate = hier`)
- historique intégralement préservé

### 5.4 Endpoints

| Méthode | URL | Permission | Code |
|---------|-----|------------|------|
| GET | `/api/projects` | `VIEW_PROJECT` | 200 |
| GET | `/api/projects/{id}` | `VIEW_PROJECT` | 200 / 404 |
| POST | `/api/projects` | `CREATE_PROJECT` | 201 + Location |
| PUT | `/api/projects/{id}` | `EDIT_PROJECT` | 200 |
| DELETE | `/api/projects/{id}` | `DELETE_PROJECT` | 204 |
| PUT | `/api/projects/{id}/assign-chef` | `ASSIGN_CHEF_PROJET` | 200 |

---

## Conclusion

Le socle technique installe les invariants valables pour toute l'application : révocation
instantanée des jetons, RBAC strictement basé sur les codes de permission, suppression douce
systématique et budget effectif dynamique. Le chapitre suivant décrit les modules opérationnels.
