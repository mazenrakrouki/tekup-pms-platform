-- V10: business trips (missions) and their cost lines (composantes_mission), plus the mission permissions.
-- Two tables because a trip has one set of dates but many cost lines of different kinds (per diem, ticket...).

CREATE TABLE missions (
    id              BIGSERIAL       PRIMARY KEY,
    -- Project charged for the trip; the module URL is always /api/projects/{projectId}/missions.
    project_id      BIGINT          NOT NULL,
    -- Traveller; lets MissionRepository restrict a developer's own trips without MANAGE_MISSION/VIEW_ALL_PROJECTS.
    user_id         BIGINT          NOT NULL,
    -- Trip purpose; 500 chars because it is often a full sentence.
    objet           VARCHAR(500)    NOT NULL,
    -- Destination; nullable, a trip can be recorded before the place is fixed.
    lieu            VARCHAR(255),
    -- Trip start/end; both required since the per diem is counted per day.
    date_debut      DATE            NOT NULL,
    date_fin        DATE            NOT NULL,
    -- The three columns every PMS table carries (see V8 for the detail).
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- FKs: the trip must point at a project and a user that really exist.
    CONSTRAINT fk_mission_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_mission_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    -- End must not be before start (>= allows a one-day trip); a negative span would give a negative per diem.
    CONSTRAINT chk_mission_dates  CHECK (date_fin >= date_debut)
);

-- Live trips of one project, for the mission tab. Partial index: only deleted = FALSE rows are indexed.
CREATE INDEX idx_mission_project ON missions(project_id) WHERE deleted = FALSE;
-- Live trips of one user, the "my trips" list every developer sees; needed since it can't reuse idx_mission_project.
CREATE INDEX idx_mission_user    ON missions(user_id)    WHERE deleted = FALSE;

-- Cost lines of one trip (French: "composantes de cout d'une mission")
CREATE TABLE composantes_mission (
    id                BIGSERIAL       PRIMARY KEY,
    -- Links to the trip (not the project) so a trip's total cost is one query.
    mission_id        BIGINT          NOT NULL,
    -- Cost kind, stored as text matching the Java enum TypeComposante by name (see CHECK below).
    type_composante   VARCHAR(30)     NOT NULL,
    montant           NUMERIC(15,2)   NOT NULL,
    -- Currency of this line; kept per line since one trip can mix currencies. Defaults to TND, the company's own.
    devise            VARCHAR(3)      NOT NULL DEFAULT 'TND',
    -- Free text for the detail ("Tunis-Abidjan return, Tunisair").
    description       VARCHAR(500),
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN         NOT NULL DEFAULT FALSE,
    -- FK to the trip: a cost line with no trip could never be totalled.
    CONSTRAINT fk_comp_mission   FOREIGN KEY (mission_id) REFERENCES missions(id),
    -- Restricts to the five known cost kinds (PERDIEM, BILLET, TIMBRE, TRANSPORT, SEJOUR) so Hibernate's
    -- enum-by-name mapping never throws "No enum constant" on a mistyped or free-text value.
    CONSTRAINT chk_comp_type     CHECK (type_composante IN ('PERDIEM','BILLET','TIMBRE','TRANSPORT','SEJOUR')),
    -- A cost line must be strictly positive; removing an expense goes through the soft delete, not a negative line.
    CONSTRAINT chk_comp_montant  CHECK (montant > 0)
);

-- Live cost lines of one trip, read whenever a trip is opened or its total is needed.
CREATE INDEX idx_comp_mission ON composantes_mission(mission_id) WHERE deleted = FALSE;

-- ── Mission permissions ──────────────────────────────────────────
-- Data-driven permissions (ADR-001): rows here, @PreAuthorize("hasAuthority('MANAGE_MISSION')") in the services.
-- These two codes are new (unlike V9's), so ON CONFLICT here only guards a replay of this file.
INSERT INTO permissions (code, module, created_at, updated_at, deleted) VALUES
  ('MANAGE_MISSION', 'MISSION', NOW(), NOW(), FALSE),
  ('VIEW_MISSION',   'MISSION', NOW(), NOW(), FALSE)
ON CONFLICT (code) DO NOTHING;

-- Grants the write permission to the two roles that organise trips. V12 later rebuilds this whole matrix.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET') AND p.code = 'MANAGE_MISSION'
ON CONFLICT DO NOTHING;

-- Grants the read permission to four roles, developer included: VIEW_MISSION alone doesn't open the whole
-- company's trips (BR-050) - MissionService.canSeeAllMissions() narrows findByProject() to his own rows
-- unless he also holds MANAGE_MISSION or VIEW_ALL_PROJECTS (UC-21).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'CHEF_PROJET', 'DIRECTEUR', 'DEVELOPPEUR') AND p.code = 'VIEW_MISSION'
ON CONFLICT DO NOTHING;
