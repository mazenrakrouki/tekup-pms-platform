-- =============================================================
-- V21 : TCC par année (spec F-AFF-13 §6.3 règle 4)
-- -------------------------------------------------------------
-- Le coût d'un JH dépend de l'année d'imputation (TCC 2024 ≠ TCC 2025).
-- Chaque ressource peut avoir un tarif spécifique par année ; à défaut,
-- le tarif de base de la ressource (resources.daily_rate/tcc_rate) s'applique.
-- =============================================================

CREATE TABLE tcc_annuels (
    id          BIGSERIAL     PRIMARY KEY,
    resource_id BIGINT        NOT NULL,
    annee       INT           NOT NULL,
    daily_rate  NUMERIC(10,2) NOT NULL,
    tcc_rate    NUMERIC(5,4)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_tcc_annuel_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    CONSTRAINT chk_tcc_annee CHECK (annee BETWEEN 2000 AND 2100)
);

CREATE UNIQUE INDEX uk_tcc_annuel_resource_annee ON tcc_annuels(resource_id, annee) WHERE deleted = FALSE;
CREATE INDEX        idx_tcc_annuel_resource      ON tcc_annuels(resource_id)        WHERE deleted = FALSE;
