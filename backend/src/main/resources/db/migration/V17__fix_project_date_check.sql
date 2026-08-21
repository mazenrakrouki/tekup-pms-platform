-- =============================================================
-- V17 : Correction de la contrainte chk_project_dates
-- =============================================================
-- La règle métier (modèle Excel, bornes incluses) autorise un
-- projet d'un seul jour (start_date = end_date, durée = 1 j).
-- Ancienne contrainte : end_date > start_date  (strict — 500 sur projet 1 jour)
-- Nouvelle contrainte  : end_date >= start_date (inclus)

ALTER TABLE projects DROP CONSTRAINT chk_project_dates;
ALTER TABLE projects ADD CONSTRAINT chk_project_dates
    CHECK (end_date IS NULL OR end_date >= start_date);
