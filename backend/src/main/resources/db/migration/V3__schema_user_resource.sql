-- =============================================================
-- V3 : Ressources humaines, TCC et paramètres applicatifs
-- =============================================================

-- ── Ressources (collaborateurs facturables) ───────────────────
CREATE TABLE resources (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    daily_rate      NUMERIC(10,2) NOT NULL,
    tcc_rate        NUMERIC(5,4)  NOT NULL,          -- ex. 0.4200 = 42%
    staffing_start  DATE          NOT NULL,
    staffing_end    DATE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_resources_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_resource_dates CHECK (staffing_end IS NULL OR staffing_end > staffing_start),
    CONSTRAINT chk_daily_rate     CHECK (daily_rate > 0),
    CONSTRAINT chk_tcc_rate       CHECK (tcc_rate >= 0 AND tcc_rate < 10)
);

CREATE INDEX idx_resources_user_id ON resources(user_id);
CREATE INDEX idx_resources_active  ON resources(user_id) WHERE deleted = FALSE;

-- ── Paramètres applicatifs ────────────────────────────────────
CREATE TABLE parameters (
    id          BIGSERIAL    PRIMARY KEY,
    param_key   VARCHAR(100) NOT NULL,
    param_value VARCHAR(500) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_parameters_key UNIQUE (param_key)
);

-- Valeurs par défaut
INSERT INTO parameters (param_key, param_value, description, created_at, updated_at, deleted) VALUES
    ('TCC_DEFAULT_RATE',   '0.42',   'Taux de charges commerciales par défaut (42%)', NOW(), NOW(), FALSE),
    ('CURRENCY',           'EUR',    'Devise utilisée dans la plateforme',             NOW(), NOW(), FALSE),
    ('FISCAL_YEAR_START',  '01-01',  'Début de l''exercice fiscal (MM-DD)',             NOW(), NOW(), FALSE);
