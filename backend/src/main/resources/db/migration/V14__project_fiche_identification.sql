-- =============================================================
-- V14 : Fiche d'identification projet (fidélité au modèle Excel)
-- -------------------------------------------------------------
-- Le modèle Projet initial était un sous-ensemble. On complète avec les
-- champs de la feuille Excel "Fiche identification" (BUSINESS_ANALYSIS §9,
-- feuille 2 ; ADR-002 full Excel model). Devise pragmatique : code + taux
-- de conversion vers TND stockés directement sur le projet (comme l'Excel).
-- =============================================================

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS contract_id               VARCHAR(100),
    ADD COLUMN IF NOT EXISTS client                    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS funder                    VARCHAR(255),       -- Bailleur de fonds
    ADD COLUMN IF NOT EXISTS business_model            VARCHAR(20),        -- SEUL | GROUPEMENT
    ADD COLUMN IF NOT EXISTS engagement_type           VARCHAR(20),        -- FORFAIT | REGIE
    ADD COLUMN IF NOT EXISTS currency                  VARCHAR(10) NOT NULL DEFAULT 'TND',
    ADD COLUMN IF NOT EXISTS exchange_rate_to_tnd      NUMERIC(15,6) NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS license_subcontract_budget NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS sold_workload_days        NUMERIC(10,2),      -- Workload vendu (JH)
    ADD COLUMN IF NOT EXISTS warranty_workload_days    NUMERIC(10,2),      -- Workload garantie (JH)
    ADD COLUMN IF NOT EXISTS penalty_provision         NUMERIC(15,2);      -- PPP
