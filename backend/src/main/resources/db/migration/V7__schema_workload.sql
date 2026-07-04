-- =============================================================
-- V7 : Charges de travail — planifiées et réelles
-- =============================================================

-- ── Charges planifiées ────────────────────────────────────────
CREATE TABLE plan_charges (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    period       DATE         NOT NULL,   -- toujours le 1er du mois
    planned_days NUMERIC(5,2) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pc_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_pc_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT chk_pc_days   CHECK (planned_days > 0 AND planned_days <= 31)
);

CREATE UNIQUE INDEX uk_pc_active     ON plan_charges(project_id, user_id, period) WHERE deleted = FALSE;
CREATE INDEX        idx_pc_project   ON plan_charges(project_id) WHERE deleted = FALSE;
CREATE INDEX        idx_pc_user      ON plan_charges(user_id)    WHERE deleted = FALSE;

-- ── Charges réelles ───────────────────────────────────────────
CREATE TABLE charges_reelles (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    period        DATE         NOT NULL,   -- toujours le 1er du mois
    actual_days   NUMERIC(5,2) NOT NULL,
    submitted_at  TIMESTAMP,
    validated_at  TIMESTAMP,
    validated_by  BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_cr_project      FOREIGN KEY (project_id)  REFERENCES projects(id),
    CONSTRAINT fk_cr_user         FOREIGN KEY (user_id)     REFERENCES users(id),
    CONSTRAINT fk_cr_validated_by FOREIGN KEY (validated_by) REFERENCES users(id),
    CONSTRAINT chk_cr_days        CHECK (actual_days >= 0 AND actual_days <= 31)
);

CREATE UNIQUE INDEX uk_cr_active   ON charges_reelles(project_id, user_id, period) WHERE deleted = FALSE;
CREATE INDEX        idx_cr_project ON charges_reelles(project_id) WHERE deleted = FALSE;
CREATE INDEX        idx_cr_user    ON charges_reelles(user_id)    WHERE deleted = FALSE;
