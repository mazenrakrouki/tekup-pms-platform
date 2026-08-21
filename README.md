# PMS — Project Management System

Plateforme centralisée de gestion et de pilotage financier des projets informatiques.
Elle automatise la logique métier auparavant portée par un classeur Excel : plan de charge,
charges réelles, coût chargé par ressource et par année, facturation, missions, gouvernance
et indicateurs EVM.

**Stack :** Spring Boot 3.3 (Java 21) · Angular 21 · PostgreSQL 17 · Flyway · Docker Compose

---

## Démarrage rapide

### Mode conteneurisé (recommandé pour une démonstration)

```bash
cp .env.example .env      # puis renseigner DB_PASSWORD et JWT_SECRET
docker compose up -d --build
```

Application : **http://localhost:8081** — le premier démarrage applique 26 migrations
(60 à 90 secondes).

### Mode natif (développement)

```bash
# backend  → http://localhost:8090
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# frontend → http://localhost:4200
cd frontend/pms-frontend && npm start
```

Les deux modes utilisent des ports distincts et peuvent tourner simultanément.

> ⚠️ Les comptes de démonstration ont des mots de passe connus et sont créés sur **tous** les
> profils. Voir [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) §5 avant tout usage réel.

---

## Documentation

| Document | Contenu |
|---|---|
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Déploiement, variables d'environnement, sauvegarde, dépannage |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture applicative et décisions transverses |
| [`docs/BUSINESS_ANALYSIS.md`](docs/BUSINESS_ANALYSIS.md) | Analyse métier, règles de gestion, modèle financier |
| [`docs/DATABASE_DESIGN.md`](docs/DATABASE_DESIGN.md) | Modèle relationnel |
| [`docs/UML_DESIGN.md`](docs/UML_DESIGN.md) | Modélisation UML |
| [`DECISIONS.md`](DECISIONS.md) | Journal des décisions d'architecture (ADR) |

## Intégration continue

`.github/workflows/ci.yml` — build et tests backend, build production frontend, construction des
images Docker, test de bout en bout `docker compose` sur les branches d'intégration, publication
des images sur GHCR depuis `main`.
