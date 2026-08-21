# syntax=docker/dockerfile:1
#
# Image du frontend PMS (Angular 21) — ADR-026.
# Le build Angular est un artefact statique : l'image finale est un nginx qui le
# sert et relaie /api vers le backend. Aucun Node à l'exécution.

# ── Étape 1 : build Angular ───────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /build

# Couche de dépendances : invalidée uniquement si le lockfile change.
# `npm ci` (et non `npm install`) : package-lock.json fait foi.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# La configuration production applique fileReplacements → environment.prod.ts,
# donc apiUrl = "/api" (même origine que nginx), et non localhost:8090.
RUN npm run build -- --configuration production

# ── Étape 2 : service statique ────────────────────────────────────
FROM nginx:1.27-alpine AS runtime

RUN rm /etc/nginx/conf.d/default.conf
COPY docker/nginx.conf /etc/nginx/conf.d/pms.conf

# Chemin de sortie réel du builder Angular (application builder) :
# dist/<project>/browser
COPY --from=build /build/dist/pms-frontend/browser /usr/share/nginx/html

EXPOSE 80
