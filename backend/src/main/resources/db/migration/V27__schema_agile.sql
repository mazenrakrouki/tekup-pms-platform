-- V27: agile planning module — sprints and product backlog, delivering the
-- subject's "planification agile" requirement. Self-contained: only references
-- projects(id), so it cannot affect workload, billing, KPI or quote figures.
--
-- Enum values are English (new enums, no persisted rows yet — the migration
-- risk in docs/ENGLISH_MIGRATION_AUDIT.md section 5 does not apply here).

-- ── Sprints ──────────────────────────────────────────────────
-- One row = one sprint of one project.
CREATE TABLE sprints (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    -- Nullable: a sprint can be created before the team agrees on its goal.
    goal        VARCHAR(1000),
    -- DATE not TIMESTAMP: a sprint starts on a day, not an instant.
    start_date  DATE          NOT NULL,
    end_date    DATE          NOT NULL,
    -- Stored as text (mapped via @Enumerated(EnumType.STRING)) so a reordered
    -- Java enum can't silently renumber existing rows.
    status      VARCHAR(10)   NOT NULL DEFAULT 'PLANNED',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    -- Soft delete: backlog items point at the sprint, so a physical delete
    -- would break that history or be blocked by the FK below.
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_sprint_project FOREIGN KEY (project_id) REFERENCES projects(id),
    -- Mirrors the Java enum so a row written outside the app can't poison it.
    CONSTRAINT chk_sprint_status CHECK (status IN ('PLANNED','ACTIVE','CLOSED')),
    CONSTRAINT chk_sprint_dates  CHECK (end_date >= start_date)
);

-- Partial index (live rows only) for "the sprints of project X", the board's
-- main query.
CREATE INDEX idx_sprint_project ON sprints(project_id) WHERE deleted = FALSE;

-- ── Product backlog ──────────────────────────────────────────
-- One row = one item of work. sprint_id nullable: NULL means the item sits in
-- the product backlog, not yet committed to a sprint.
CREATE TABLE backlog_items (
    id            BIGSERIAL      PRIMARY KEY,
    project_id    BIGINT         NOT NULL,
    sprint_id     BIGINT,
    title         VARCHAR(255)   NOT NULL,
    description   VARCHAR(2000),
    priority      VARCHAR(10)    NOT NULL DEFAULT 'MEDIUM',
    -- NUMERIC(6,2): exact decimal so estimates (including half-days) can't
    -- drift like a float sum would. Nullable: unestimated is normal.
    estimate_days NUMERIC(6,2),
    status        VARCHAR(15)    NOT NULL DEFAULT 'TODO',
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    deleted       BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_backlog_project  FOREIGN KEY (project_id) REFERENCES projects(id),
    -- FK on a nullable column: only checked when sprint_id is set.
    CONSTRAINT fk_backlog_sprint   FOREIGN KEY (sprint_id)  REFERENCES sprints(id),
    CONSTRAINT chk_backlog_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_backlog_status   CHECK (status   IN ('TODO','IN_PROGRESS','DONE')),
    CONSTRAINT chk_backlog_estimate CHECK (estimate_days IS NULL OR estimate_days >= 0)
);

-- Partial indexes for the two board views: the product backlog of a project,
-- and the items of one sprint.
CREATE INDEX idx_backlog_project ON backlog_items(project_id) WHERE deleted = FALSE;
CREATE INDEX idx_backlog_sprint  ON backlog_items(sprint_id)  WHERE deleted = FALSE;

-- ── Permissions ──────────────────────────────────────────────
-- Read/write split so a developer can follow the board without re-planning it.
INSERT INTO permissions (code, module, description, created_at, updated_at, deleted) VALUES
    ('VIEW_AGILE',   'AGILE', 'Consulter le backlog produit et les sprints d''un projet.',        NOW(), NOW(), FALSE),
    ('MANAGE_AGILE', 'AGILE', 'Créer et modifier les sprints et les éléments du backlog.',        NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- Grants follow the matrix V12 rebuilt (not the original V11 grants).
--   MANAGE_AGILE: CHEF_PROJET only — planning is the PM's responsibility.
--   VIEW_AGILE: DIRECTEUR, CHEF_PROJET, DEVELOPPEUR — no financial data here,
--   so BR-050 doesn't apply.
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

-- Bump token_version so active sessions pick up the new permissions (ADR-017)
-- without a re-login. ADMIN is absent: V12 removed project modules from it.
UPDATE users SET token_version = token_version + 1
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('DIRECTEUR','CHEF_PROJET','DEVELOPPEUR'));
