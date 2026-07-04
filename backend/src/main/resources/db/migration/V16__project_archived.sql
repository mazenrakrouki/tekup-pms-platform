-- =============================================================
-- V16 : Archivage des projets terminés
-- -------------------------------------------------------------
-- Un projet TERMINE peut être archivé : il disparaît des listes actives
-- mais reste consultable dans une section "Projets archivés". Distinct de
-- la suppression douce (deleted) qui retire définitivement le projet.
-- =============================================================

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
