-- =============================================================
-- V15 : Workload d'avenant (fidélité Excel "Workload avenants en JH")
-- -------------------------------------------------------------
-- Un avenant peut modifier non seulement le budget (déjà géré via revised_budget)
-- mais aussi la charge vendue. On stocke l'impact en jours-homme sur l'avenant.
-- =============================================================

ALTER TABLE avenants
    ADD COLUMN IF NOT EXISTS workload_days NUMERIC(10,2);
