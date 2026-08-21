-- =============================================================
-- V22 : Indicateurs EVM (spec F-AFF-13 §5 — Glossaire officiel)
-- -------------------------------------------------------------
-- Étend les snapshots KPI avec les indicateurs de la "Situation actuelle" :
-- EV %, Delivery %, JH consommés / RAF / dérive, CA Production, FAE,
-- marge actuelle, date de fin estimée, faits marquants.
-- Ajoute la marge nette vendue (baseline) sur la fiche identification projet.
-- =============================================================

ALTER TABLE snapshot_kpis
    ADD COLUMN IF NOT EXISTS ev_pct             NUMERIC(5,2),   -- avancement Earned Value, saisi par le CdP (0-100)
    ADD COLUMN IF NOT EXISTS delivery_pct       NUMERIC(5,2),   -- livrés / planifiés × 100
    ADD COLUMN IF NOT EXISTS consomme_jh        NUMERIC(10,2),  -- Σ imputations
    ADD COLUMN IF NOT EXISTS raf_jh             NUMERIC(10,2),  -- reste à faire (plan de charge)
    ADD COLUMN IF NOT EXISTS derive_jh          NUMERIC(10,2),  -- workload vendu − consommé − RAF
    ADD COLUMN IF NOT EXISTS ca_production      NUMERIC(15,2),  -- budget TND × EV %
    ADD COLUMN IF NOT EXISTS total_facture      NUMERIC(15,2),  -- Σ jalons facturés/réglés (TND)
    ADD COLUMN IF NOT EXISTS fae                NUMERIC(15,2),  -- CA production − total facturé
    ADD COLUMN IF NOT EXISTS marge_actuelle     NUMERIC(15,2),  -- CA production − coût actuel
    ADD COLUMN IF NOT EXISTS marge_actuelle_pct NUMERIC(7,4),   -- marge actuelle / CA production
    ADD COLUMN IF NOT EXISTS date_fin_estimee   DATE,
    ADD COLUMN IF NOT EXISTS faits_marquants    VARCHAR(2000);

-- Marge nette vendue (%) : baseline commerciale de la fiche identification.
-- Saisie manuelle (agrégat) ; si un Devis Interne existe, sa marge calculée prime.
ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS marge_nette_vendue NUMERIC(7,4);   -- ex. 0.4412 = 44,12 %
