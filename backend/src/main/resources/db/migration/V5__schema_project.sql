-- =============================================================
-- V5 : Projets
-- =============================================================

CREATE TABLE projects (
    id                  BIGSERIAL       PRIMARY KEY,
    code                VARCHAR(20)     NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    description         TEXT,
    status              VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    start_date          DATE,
    end_date            DATE,
    initial_budget      NUMERIC(15,2),
    revised_budget      NUMERIC(15,2),
    director_id         BIGINT,
    chef_projet_id      BIGINT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_projects_code     UNIQUE (code),
    CONSTRAINT fk_projects_director FOREIGN KEY (director_id)    REFERENCES users(id),
    CONSTRAINT fk_projects_chef     FOREIGN KEY (chef_projet_id) REFERENCES users(id),
    CONSTRAINT chk_project_dates    CHECK (end_date IS NULL OR end_date > start_date),
    CONSTRAINT chk_initial_budget   CHECK (initial_budget IS NULL OR initial_budget >= 0),
    CONSTRAINT chk_status           CHECK (status IN ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED'))
);

CREATE INDEX idx_projects_status       ON projects(status) WHERE deleted = FALSE;
CREATE INDEX idx_projects_chef         ON projects(chef_projet_id) WHERE deleted = FALSE;
CREATE INDEX idx_projects_director     ON projects(director_id) WHERE deleted = FALSE;
