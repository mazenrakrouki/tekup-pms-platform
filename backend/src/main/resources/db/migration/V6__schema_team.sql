-- V6: creates "team_assignments" — who works on which project, with what
-- label and between which dates. Join table between users (V1) and projects
-- (V5).
--
-- Security role: this table is one of the two sources of the ADR-021
-- perimeter (the other being projects.chef_projet_id) — a user may touch
-- project 7 as its chef de projet OR via an active row here. role_in_team is
-- NOT a security role: it's a free-text label for the team screen; access
-- comes only from users.role_id / role_permissions.

CREATE TABLE team_assignments (
    id              BIGSERIAL   PRIMARY KEY,
    project_id      BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    -- Free text on purpose: every project names its jobs differently.
    role_in_team    VARCHAR(50) NOT NULL,
    start_date      DATE        NOT NULL,
    -- NULL while still on the project (the normal case).
    end_date        DATE,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Safety net only: ProjectService always soft-deletes, so this rarely fires.
    CONSTRAINT fk_ta_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    -- No cascade here: an assignment is history of the PROJECT, not the user.
    CONSTRAINT fk_ta_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT chk_ta_dates  CHECK (end_date IS NULL OR end_date >= start_date)
);

-- Partial unique index: only one ACTIVE assignment per (project, user) — a
-- plain UNIQUE would count soft-deleted rows and block re-assigning someone.
CREATE UNIQUE INDEX uk_ta_project_user_active ON team_assignments(project_id, user_id) WHERE deleted = FALSE;
CREATE INDEX idx_ta_project_id ON team_assignments(project_id) WHERE deleted = FALSE;
-- Backs half of the ADR-021 perimeter check, run on every project request.
CREATE INDEX idx_ta_user_id    ON team_assignments(user_id)    WHERE deleted = FALSE;
