# DEPLOYMENT.md — Déploiement de la plateforme PMS

> Référence : [ADR-026](../DECISIONS.md) — déploiement conteneurisé (Docker Compose)
> et intégration continue. Remplace ADR-014 (« déploiement natif, sans Docker »).

## 1. Deux modes d'exécution

PMS se lance de deux manières. Elles coexistent sur le même poste et n'entrent
pas en conflit.

| | Mode natif | Mode conteneurisé |
|---|---|---|
| **Usage** | développement quotidien | démonstration, recette, déploiement |
| **Lancement** | `mvn spring-boot:run` + `ng serve` | `docker compose up -d` |
| **Profil Spring** | `dev` | `prod` |
| **Base** | PostgreSQL 17 natif, port 5432 | conteneur `postgres:17-alpine`, port hôte 5433 |
| **Frontend** | `ng serve`, port 4200 | nginx, port hôte 8081 |
| **Backend** | port 8090 | port hôte 8091 |
| **Rechargement à chaud** | oui | non (reconstruction d'image) |
| **CORS** | requis (origines différentes) | aucun (même origine) |

Le mode natif reste le **mode de développement principal** : il n'est pas remplacé.

---

## 2. Prérequis

- **Docker Desktop 28.x** avec le backend WSL2 démarré
- Environ **3 Go** d'espace disque libre (images + volume)
- Les ports hôte **5433**, **8091** et **8081** libres

Vérifier les ports avant le premier lancement :

```powershell
netstat -ano | findstr LISTENING | findstr ":5433 :8091 :8081"
```

Une sortie vide signifie que les trois ports sont disponibles.

> **Pourquoi ces ports ?** Les ports « évidents » sont déjà pris sur le poste de
> développement : **5432** par le service PostgreSQL natif, **8090** par
> `mvn spring-boot:run`, **4200** par `ng serve`, et **8080** par Apache httpd.
> Les ports hôte sont donc décalés, et pilotés par `.env`.

---

## 3. Variables d'environnement

Toute la configuration passe par `.env` à la racine du dépôt. Le gabarit
`.env.example` est versionné ; `.env` ne l'est pas.

| Variable | Requise | Défaut | Rôle |
|---|---|---|---|
| `DB_NAME` | oui | `pms` | nom de la base |
| `DB_USERNAME` | oui | `pms` | utilisateur de la base |
| `DB_PASSWORD` | **oui** | — | mot de passe ; aucun repli, le démarrage échoue si absent |
| `JWT_SECRET` | **oui** | — | clé de signature des jetons ; aucun repli |
| `DB_HOST_PORT` | non | `5433` | port hôte de PostgreSQL |
| `BACKEND_HOST_PORT` | non | `8091` | port hôte de l'API |
| `FRONTEND_HOST_PORT` | non | `8081` | port hôte de l'application |
| `COOKIE_SECURE` | non | `false` | `true` uniquement derrière HTTPS |
| `CORS_ALLOWED_ORIGINS` | non | `http://localhost:8081` | origines autorisées |
| `LOG_ROOT_LEVEL` | non | `INFO` | niveau de journalisation racine |
| `TZ` | non | `Africa/Tunis` | fuseau horaire des conteneurs |
| `IMAGE_TAG` | non | `local` | étiquette des images construites |

### `JWT_SECRET` — point d'attention

La valeur est utilisée **telle quelle, en octets UTF-8**, pour signer en
HMAC-SHA512 (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`). Elle doit donc faire
**au moins 64 caractères**. Ce n'est **pas** une valeur base64 qui serait
décodée : une chaîne base64 de 44 caractères représentant 32 octets serait
refusée par la bibliothèque.

```powershell
# PowerShell
$b = New-Object byte[] 64
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

```bash
# Git Bash
openssl rand -base64 64 | tr -d '\n'
```

---

## 4. Premier lancement

```powershell
cd "D:\STAGE S2I\Application\PMS"
copy .env.example .env
# éditer .env : renseigner DB_PASSWORD et JWT_SECRET
docker compose up -d --build
```

La construction des deux images prend quelques minutes la première fois.

Suivre l'application des migrations :

```powershell
docker compose logs -f backend
```

Le premier démarrage applique **26 migrations Flyway**, dont un jeu de données
de démonstration d'environ 1100 lignes : compter **60 à 90 secondes** avant que
l'API ne réponde. C'est pour cette raison que le `healthcheck` du service
`backend` déclare `start_period: 90s`.

Vérifier l'état :

```powershell
docker compose ps          # les trois services doivent être (healthy)
```

Puis ouvrir **http://localhost:8081**.

---

## 5. Comptes de démonstration

> ### ⚠️ Avertissement de sécurité
>
> Ces comptes sont créés **sur tous les profils, y compris `prod`**, par
> `DataInitializer`. Leurs mots de passe sont publiés dans cette documentation.
>
> Ils existent pour qu'un `docker compose up` soit immédiatement démontrable.
> **Tout déploiement réel doit les désactiver** en ajoutant à `.env` :
> `PMS_DEMO_SEED_USERS=false` (propriété `pms.demo.seed-users`), puis en créant
> un administrateur avec un mot de passe propre.

| Compte | Mot de passe | Rôle |
|---|---|---|
| `admin@pms.local` | `Admin1234!` | Administrateur |
| `directeur@pms.local` | `Directeur1234!` | Directeur |
| `chef@pms.local` | `Chef1234!` | Chef de projet |
| `dev@pms.local` | `Dev1234!` | Développeur |
| `momo-directeur@pms.local` | `Momo123456` | Directeur (jeu de données étendu) |
| `momo-chef@pms.local` | `Momo123456` | Chef de projet (jeu de données étendu) |
| `momo-dev@pms.local` | `Momo123456` | Développeur (jeu de données étendu) |

---

## 6. Exploitation courante

```powershell
docker compose logs -f backend        # journaux d'un service
docker compose restart backend        # redémarrer un service
docker compose up -d --build backend  # reconstruire après modification du code
docker compose down                   # arrêter (les données SURVIVENT)
docker compose down -v                # arrêter ET supprimer le volume (remise à zéro)
```

`docker compose down` conserve le volume nommé `pms-db-data` : les données sont
retrouvées au `up` suivant. Seul `-v` les supprime, et le démarrage suivant
rejoue alors les 26 migrations sur une base vierge.

---

## 7. Sauvegarde et restauration

```powershell
# Sauvegarde (format personnalisé, compressé)
docker compose exec -T db pg_dump -U pms -d pms -Fc -f /tmp/pms.dump
docker compose cp db:/tmp/pms.dump ./backups/pms-2026-08-19.dump

# Restauration
docker compose cp ./backups/pms-2026-08-19.dump db:/tmp/restore.dump
docker compose exec -T db pg_restore -U pms -d pms --clean --if-exists /tmp/restore.dump
```

> **Ne pas utiliser de redirection PowerShell** (`docker compose exec db pg_dump ... > fichier.sql`).
> PowerShell 5.1 écrit en UTF-16 avec BOM, ce qui corrompt un dump SQL. On écrit
> donc dans le conteneur avec `-f`, puis on récupère le fichier avec
> `docker compose cp`.

Le répertoire `backups/` est ignoré par git.

---

## 8. Dépannage

| Symptôme | Cause | Correction |
|---|---|---|
| `bind: address already in use` | port hôte déjà pris | changer `*_HOST_PORT` dans `.env` |
| `backend` reste `starting` puis `unhealthy` | migrations plus lentes que prévu | `docker compose logs backend` ; augmenter `start_period` |
| `frontend` s'arrête : `host not found in upstream "backend"` | le backend n'était pas démarré | `docker compose up -d backend` puis `docker compose restart frontend` |
| 401 sur `/actuator/health` | règle `permitAll` absente de `SecurityConfig` | vérifier la présence du `requestMatchers("/actuator/health", …)` |
| Le frontend appelle `localhost:8090` | `fileReplacements` absent d'`angular.json` | vérifier la configuration `production` ; l'étape CI « Vérifier la substitution d'environnement » couvre ce cas |
| Échec Flyway `validate` | migration déjà appliquée modifiée | sur un environnement jetable : `docker compose down -v` |
| Migration V26 : `permission denied to create extension` | `CREATE EXTENSION pgcrypto` exige un superutilisateur | fonctionne en Compose (l'utilisateur EST superutilisateur) ; en environnement réel, faire créer l'extension par un DBA au préalable |
| Tri différent des accents entre natif et conteneur | `postgres:17-alpine` utilise musl, le service natif `en_US.UTF-8` | sans effet fonctionnel ici ; basculer sur `postgres:17` (Debian) si le tri accentué devient significatif |

---

## 9. Intégration continue

Le pipeline `.github/workflows/ci.yml` s'exécute sur `develop` et `main`.

| Job | PR | push `develop` | push `main` | Rôle |
|---|---|---|---|---|
| `backend` | ✅ | ✅ | ✅ | compilation + 89 tests |
| `frontend` | ✅ | ✅ | ✅ | build production + garde-fou d'environnement |
| `images` | ✅ | ✅ | ✅ | construction des deux images |
| `smoke` | ❌ | ✅ | ✅ | `docker compose up` + connexion réelle via nginx |
| `publish` | ❌ | ❌ | ✅ | publication sur GHCR |

Pour que le pipeline soit une véritable barrière et pas une simple indication,
il faut **protéger les branches** dans les réglages GitHub et exiger la réussite
de `backend`, `frontend` et `images` avant toute fusion.

---

## 10. Hors périmètre

Volontairement exclus, comme indiqué dans `docs/ENHANCEMENTS.md` :

- **Kubernetes** et toute orchestration multi-nœuds
- **Déploiement cloud** (le périmètre est l'usage interne d'une entreprise)
- **Terminaison TLS** : à assurer par un reverse proxy en amont ; passer alors
  `COOKIE_SECURE=true`
- **Microservices** : ADR-016 (monolithe modulaire) reste en vigueur — trois
  conteneurs ne sont pas trois services, c'est une seule application, sa base et
  son serveur statique
