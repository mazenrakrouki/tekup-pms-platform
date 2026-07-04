-- =============================================================
-- Exécuter une seule fois en tant que superutilisateur PostgreSQL
-- Commande : psql -U postgres -f scripts/create_database.sql
-- =============================================================

-- Créer l'utilisateur applicatif
CREATE USER pms_dev WITH PASSWORD 'pms_dev';

-- Créer la base de développement
CREATE DATABASE pms_dev OWNER pms_dev ENCODING 'UTF8' LC_COLLATE 'en_US.UTF-8' LC_CTYPE 'en_US.UTF-8';

-- Accorder les droits
GRANT ALL PRIVILEGES ON DATABASE pms_dev TO pms_dev;

-- Se connecter à pms_dev et accorder les droits sur le schéma public
\c pms_dev
GRANT ALL ON SCHEMA public TO pms_dev;
