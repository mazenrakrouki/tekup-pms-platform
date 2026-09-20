-- V5: creates "projects", the central table every module (team, workload,
-- KPI, billing, missions, governance, quote, agile board) hangs off via
-- project_id.
--
-- ADR-021: holding EDIT_PROJECT is not the same as being allowed to edit
-- project 9. ProjectScopeInterceptor also checks the perimeter for every
-- /api/projects/{id}/** call, computed from real relations (chef_projet_id
-- below, or team_assignments in V6) — never from a role name. VIEW_ALL_PROJECTS
-- (V13) is what lifts that filter for directors, not the director_id column.
-- Without this check, editing the id in a URL would open someone else's
-- project (IDOR — Insecure Direct Object Reference).

CREATE TABLE projects (
    id                  BIGSERIAL       PRIMARY KEY,
    -- The paper reference, e.g. PRJ-2026-014 — what people say out loud.
    code                VARCHAR(20)     NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    description         TEXT,
    -- Text not int: a stored 2 means nothing on its own, and reordering the
    -- Java enum would silently change every existing row's meaning.
    status              VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    start_date          DATE,
    end_date            DATE,
    -- initial_budget is never rewritten, so drift against revised_budget stays visible.
    initial_budget      NUMERIC(15,2),
    revised_budget      NUMERIC(15,2),
    director_id         BIGINT,
    -- One of the two columns ADR-021's perimeter is built from — see header.
    chef_projet_id      BIGINT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Absolute unique (known limit): a deleted project's code can't be reused
    -- until V18 replaces this with a partial index (WHERE deleted = FALSE).
    CONSTRAINT uk_projects_code     UNIQUE (code),
    CONSTRAINT fk_projects_director FOREIGN KEY (director_id)    REFERENCES users(id),
    CONSTRAINT fk_projects_chef     FOREIGN KEY (chef_projet_id) REFERENCES users(id),
    -- Corrected by V17: this strict ">" wrongly refuses a one-day project;
    -- V17 replaces it with ">=".
    CONSTRAINT chk_project_dates    CHECK (end_date IS NULL OR end_date > start_date),
    CONSTRAINT chk_initial_budget   CHECK (initial_budget IS NULL OR initial_budget >= 0),
    -- Kept in step with the Java enum ProjectStatus as a second guard: refuses
    -- an impossible value from any source, not just ProjectService.changeStatus.
    CONSTRAINT chk_status           CHECK (status IN ('DRAFT','ACTIVE','ON_HOLD','COMPLETED','CANCELLED'))
);

-- Partial indexes (live rows only) matching this project's "AND deleted = false" queries.
CREATE INDEX idx_projects_status       ON projects(status) WHERE deleted = FALSE;
-- Backs the ADR-021 perimeter check, which runs on every project request.
CREATE INDEX idx_projects_chef         ON projects(chef_projet_id) WHERE deleted = FALSE;
CREATE INDEX idx_projects_director     ON projects(director_id) WHERE deleted = FALSE;
