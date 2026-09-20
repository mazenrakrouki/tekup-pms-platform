-- V3: human resources cost sheet (resources) and application settings
-- (parameters). Resources holds what a person's working day costs the
-- company (daily_rate + tcc_rate); parameters is a small key/value table.
--
-- Kept separate from users (ADR-022): users is the account, resources is the
-- cost. Not every user has a billable rate, and a cost sheet must keep
-- existing for past months after the account is switched off.
--
-- TCC is the overhead coefficient added to the raw daily rate (social
-- charges, office, tooling), stored as a fraction: loaded cost =
-- daily_rate * (1 + tcc_rate).

-- -- Resources (billable staff) ----------------------------------
CREATE TABLE resources (
    id              BIGSERIAL    PRIMARY KEY,
    -- NOT NULL: an ownerless cost sheet could never be matched to declared days.
    user_id         BIGINT       NOT NULL,
    -- NUMERIC not float: exact decimals, so summed day costs never drift by
    -- a few cents against what the screen shows.
    daily_rate      NUMERIC(10,2) NOT NULL,
    -- NUMERIC(5,4) caps at 9.9999 — stops e.g. "42" being typed for "0.42",
    -- which would multiply every cost of that person by 43.
    tcc_rate        NUMERIC(5,4)  NOT NULL,          -- for example 0.4200 means 42 percent
    staffing_start  DATE          NOT NULL,
    -- NULL = no planned end (permanent staff). A far-future default date would
    -- read as a real end date to anyone reporting on it later.
    staffing_end    DATE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Soft delete: a cost computed last year must keep the rate used then.
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_resources_user FOREIGN KEY (user_id) REFERENCES users(id),
    -- "IS NULL OR" so an open-ended window is accepted (a CHECK only refuses
    -- what's clearly false; NULL is "unknown", not false).
    CONSTRAINT chk_resource_dates CHECK (staffing_end IS NULL OR staffing_end > staffing_start),
    -- A day can't cost zero or less, or the project would look free on the KPI screen.
    CONSTRAINT chk_daily_rate     CHECK (daily_rate > 0),
    CONSTRAINT chk_tcc_rate       CHECK (tcc_rate >= 0 AND tcc_rate < 10)
);

-- Supports "the cost sheet of user X", run by KpiService for every team member.
CREATE INDEX idx_resources_user_id ON resources(user_id);
-- Partial index matching ResourceRepository.findActiveByUserId's "deleted = false".
CREATE INDEX idx_resources_active  ON resources(user_id) WHERE deleted = FALSE;

-- -- Application settings ----------------------------------------
-- Key/value table; adding a setting is an INSERT, not a migration + redeploy.
-- Values are always text and unvalidated — no Java code reads this table back
-- today; the three rows below are documented defaults, not live config.
CREATE TABLE parameters (
    id          BIGSERIAL    PRIMARY KEY,
    -- "param_key" not "key": KEY is a reserved SQL word.
    param_key   VARCHAR(100) NOT NULL,
    param_value VARCHAR(500) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Absolute UNIQUE (not partial): unlike users.email/projects.code (V18),
    -- a soft-deleted key can't be reinserted here.
    CONSTRAINT uk_parameters_key UNIQUE (param_key)
);

-- Defaults shipped with the platform; descriptions stay French (data, not comments).
INSERT INTO parameters (param_key, param_value, description, created_at, updated_at, deleted) VALUES
    ('TCC_DEFAULT_RATE',   '0.42',   'Taux de charges commerciales par défaut (42%)', NOW(), NOW(), FALSE),
    ('CURRENCY',           'EUR',    'Devise utilisée dans la plateforme',             NOW(), NOW(), FALSE),
    ('FISCAL_YEAR_START',  '01-01',  'Début de l''exercice fiscal (MM-DD)',             NOW(), NOW(), FALSE);
