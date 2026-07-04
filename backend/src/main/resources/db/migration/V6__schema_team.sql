-- =============================================================
-- V6 : Affectations d'équipe (membres ↔ projets)
-- =============================================================

CREATE TABLE team_assignments (
    id              BIGSERIAL   PRIMARY KEY,
    project_id      BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    role_in_team    VARCHAR(50) NOT NULL,
    start_date      DATE        NOT NULL,
    end_date        DATE,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_ta_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_ta_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT chk_ta_dates  CHECK (end_date IS NULL OR end_date >= start_date)
);

-- Partial index : une seule affectation ACTIVE par utilisateur par projet
-- Permet la ré-affectation après suppression logique
CREATE UNIQUE INDEX uk_ta_project_user_active ON team_assignments(project_id, user_id) WHERE deleted = FALSE;
CREATE INDEX idx_ta_project_id ON team_assignments(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_ta_user_id    ON team_assignments(user_id)    WHERE deleted = FALSE;
