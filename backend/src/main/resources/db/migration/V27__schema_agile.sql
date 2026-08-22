-- =============================================================
-- V27 : Agile planning — Sprints and product backlog
-- -------------------------------------------------------------
-- Delivers the "planification agile (sprints, backlog)" requirement of the
-- assigned subject. The module is deliberately self-contained: it references
-- projects(id) and nothing else, so the workload, billing, KPI and internal
-- quote engines are unaffected.
--
-- Enum values are in English. These are new enums with no persisted rows
-- anywhere, so the migration risk documented in docs/ENGLISH_MIGRATION_AUDIT.md
-- section 5 does not apply. This follows ProjectStatus, which is already
-- English, and does not reopen the declined rename of the legacy enums.
-- =============================================================

-- ── Sprints ──────────────────────────────────────────────────
CREATE TABLE sprints (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    goal        VARCHAR(1000),
    start_date  DATE          NOT NULL,
    end_date    DATE          NOT NULL,
    status      VARCHAR(10)   NOT NULL DEFAULT 'PLANNED',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_sprint_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT chk_sprint_status CHECK (status IN ('PLANNED','ACTIVE','CLOSED')),
    CONSTRAINT chk_sprint_dates  CHECK (end_date >= start_date)
);

CREATE INDEX idx_sprint_project ON sprints(project_id) WHERE deleted = FALSE;

-- ── Product backlog ──────────────────────────────────────────
-- sprint_id is nullable on purpose: a NULL means the item sits in the product
-- backlog and has not been committed to a sprint yet. Detaching an item is
-- therefore a normal state, not an error.
CREATE TABLE backlog_items (
    id            BIGSERIAL      PRIMARY KEY,
    project_id    BIGINT         NOT NULL,
    sprint_id     BIGINT,
    title         VARCHAR(255)   NOT NULL,
    description   VARCHAR(2000),
    priority      VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM',
    estimate_days NUMERIC(6,2),
    status        VARCHAR(15)    NOT NULL DEFAULT 'TODO',
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    deleted       BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_backlog_project  FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_backlog_sprint   FOREIGN KEY (sprint_id)  REFERENCES sprints(id),
    CONSTRAINT chk_backlog_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_backlog_status   CHECK (status   IN ('TODO','IN_PROGRESS','DONE')),
    CONSTRAINT chk_backlog_estimate CHECK (estimate_days IS NULL OR estimate_days >= 0)
);

CREATE INDEX idx_backlog_project ON backlog_items(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_backlog_sprint  ON backlog_items(sprint_id)  WHERE deleted = FALSE;

-- ── Permissions ──────────────────────────────────────────────
INSERT INTO permissions (code, module, description, created_at, updated_at, deleted) VALUES
    ('VIEW_AGILE',   'AGILE', 'Consulter le backlog produit et les sprints d''un projet.',        NOW(), NOW(), FALSE),
    ('MANAGE_AGILE', 'AGILE', 'Créer et modifier les sprints et les éléments du backlog.',        NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- Grants follow the matrix rebuilt by V12, NOT the original V11 grants: V12
-- deleted every row and cut ADMIN back to platform administration plus TCC.
-- Granting ADMIN here would reintroduce exactly what that audit removed.
--
--   MANAGE_AGILE : CHEF_PROJET only — planning the work of a project is the
--                  project manager's responsibility.
--   VIEW_AGILE   : DIRECTEUR, CHEF_PROJET, DEVELOPPEUR — a board a developer
--                  cannot see would be pointless, and the backlog carries no
--                  financial data, so BR-050 is not engaged.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'MANAGE_AGILE'
WHERE r.name = 'CHEF_PROJET'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code = 'VIEW_AGILE'
WHERE r.name IN ('DIRECTEUR', 'CHEF_PROJET', 'DEVELOPPEUR')
ON CONFLICT DO NOTHING;

-- Bump token_version so active sessions pick up the new permissions without a
-- re-login (ADR-017).
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));
