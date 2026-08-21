# syntax=docker/dockerfile:1
#
# Image du backend PMS (Spring Boot 3.3 / Java 21) — ADR-026.
# Construction multi-étapes : l'image finale ne contient qu'un JRE et le JAR,
# ni Maven, ni sources, ni cache de dépendances.

# ── Étape 1 : construction ────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Couche de dépendances : invalidée uniquement si pom.xml change.
# Si cette étape échoue (annotationProcessorPaths Lombok/MapStruct), on peut
# supprimer ces deux lignes : on perd le cache, rien d'autre.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# Couche applicative
COPY src ./src
# Les tests sont exécutés par la CI, pas ici : construire l'image est une étape
# d'empaquetage, pas la barrière qualité. Les rejouer doublerait le temps de build
# sans apporter de signal supplémentaire.
RUN mvn -B -ntp clean package -DskipTests

# ── Étape 2 : exécution ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl est requis par le HEALTHCHECK de docker-compose : l'image temurin ne le fournit pas.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 pms \
 && useradd  --system --uid 1001 --gid pms --no-create-home --shell /usr/sbin/nologin pms

WORKDIR /app
COPY --from=build --chown=pms:pms /build/target/pms-backend-*.jar /app/app.jar

USER pms
EXPOSE 8080

# MaxRAMPercentage laisse la JVM s'adapter à la limite mémoire du conteneur
# au lieu de la mémoire de l'hôte.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Forme « sh -c » plutôt qu'un script .sh : JAVA_OPTS reste substituable, et
# aucun fichier script n'existe qui pourrait être corrompu en CRLF par un
# checkout Windows (« bad interpreter: /bin/sh^M »).
# exec → la JVM devient PID 1 et reçoit correctement SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
